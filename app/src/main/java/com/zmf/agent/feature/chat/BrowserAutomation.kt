package com.zmf.agent.feature.chat

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.capability.port.DomAction
import com.zmf.agent.capability.port.DomResult
import com.zmf.agent.capability.port.WebControl
import com.zmf.agent.capability.port.WebTabInfo
import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置浏览页的助手控制端口。
 *
 * 命令一律通过目标标签 WebView 的 `evaluateJavascript` 执行固定脚本，不使用触摸事件与屏幕坐标。
 * 每个标签一条单消费者队列（互斥锁 + 世代号）：并发调用在队列内串行，终止时世代号加一，
 * 尚未开始的排队命令直接以 `E_USER_ABORTED` 结束，不再执行。
 *
 * 租赁在**本轮首次调用**时建立：被操作的标签进入只读，页面顶部显示提示条；同一轮内复用。
 * 本轮收尾由 [release] 解除，终止只解除对应标签，不终止助手。
 */
internal object BrowserAutomation : WebControl {

    /** 单条脚本的超时：页面无响应时不得无限等待。 */
    private const val SCRIPT_TIMEOUT_MS = 8_000L

    /** 条件等待的轮询间隔。 */
    private const val WAIT_POLL_MS = 120L

    /** 快照节点上限。 */
    private const val MAX_NODES = 300

    /** 抽取文本上限。 */
    private const val MAX_CHARS = 20_000

    private val UNAVAILABLE = DomResult.Failure("E_BROWSER_UNAVAILABLE", "请用户先打开浏览页")

    private val ABORTED = DomResult.Failure("E_USER_ABORTED", "用户手动终止了此次页面操作")

    /** 页面实例按会话隔离：目标会话没有浏览页（或没有可用标签）时的统一失败。 */
    private val NO_SESSION_PAGE = DomResult.Failure(
        "E_BROWSER_NO_PAGE",
        "该会话还没有打开浏览页；请先在这条会话里打开浏览页，或换到已打开浏览页的那条会话"
    )

    /** 端口是否已由浏览页装配。 */
    @Volatile
    private var attached = false

    /** 租赁中的标签，末项为最近被操作的标签；页面据此显示提示条。 */
    private var leased by mutableStateOf<List<String>>(emptyList())

    /** 最近被操作的标签 id；无租赁时为 null。 */
    val leasedTabId: String? get() = leased.lastOrNull()

    /** 单个标签的执行状态。 */
    private class TabState {
        /** 串行闸：同一标签的脚本不得并发执行。 */
        val mutex = Mutex()

        /** 世代号：终止时加一，排队中的旧命令据此丢弃。 */
        @Volatile
        var generation = 0L

        /** 当前命令的协作式终止信号；终止时完成，正在等待的脚本随之结束。 */
        @Volatile
        var abortSignal = CompletableDeferred<Unit>()

        /** 最近一次快照的 ref → 元素定位表达式。 */
        val refs = ConcurrentHashMap<String, String>()

        /** ref 表对应的页面地址；地址变化即视为导航，表必须清空。 */
        @Volatile
        var lastUrl = ""
    }

    private val states = ConcurrentHashMap<String, TabState>()

    /** 由浏览页在组合时装配；未装配时所有命令返回 `E_BROWSER_UNAVAILABLE`。 */
    fun attach(holder: BrowserHolder) {
        attached = true
    }

    /** 由浏览页在离开时卸载：结束全部租赁并丢弃排队中的命令。 */
    fun detach() {
        attached = false
        leased.forEach { BrowserHolder.setAgentReadOnly(it, false) }
        leased = emptyList()
        states.values.forEach {
            it.generation += 1
            it.abortSignal.complete(Unit)
        }
    }

    /** 本轮收尾：解除全部租赁并收起提示条，供发送侧在回合结束时调用。 */
    fun release() {
        if (leased.isEmpty()) return
        leased.forEach { BrowserHolder.setAgentReadOnly(it, false) }
        leased = emptyList()
    }

    override fun tabs(sessionId: String): List<WebTabInfo> {
        val list = BrowserState.tabsFor(sessionId)
        val activeId = BrowserState.activeFor(sessionId)?.id
        return list.map {
            WebTabInfo(
                id = it.id.toString(),
                title = it.label,
                url = it.url,
                loading = it.loading,
                active = it.id == activeId
            )
        }
    }

    override suspend fun open(sessionId: String, url: String, newTab: Boolean): DomResult {
        if (!attached) return UNAVAILABLE
        val target = url.trim()
        val scheme = runCatching { Uri.parse(target).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return DomResult.Failure("E_SCHEME_REJECTED", "只允许打开 http 或 https 地址：" + target)
        }
        // 页面实例按会话隔离：该会话没有打开浏览页时明确失败，不回落到显示会话的标签
        val current = BrowserState.activeFor(sessionId) ?: return NO_SESSION_PAGE
        val context = current.web.context

        var result: BrowserTab? = null
        var created = false
        if (newTab) {
            // 新建标签会把 WebView 挂进**当前显示会话**的标签表：非显示会话只能操作它已有的标签，
            // 否则 A 的 browser_open 会在 B 的标签条里凭空多出一页
            if (sessionId.isNotEmpty() && sessionId != BrowserState.ownerSessionId) {
                return DomResult.Failure(
                    "E_BROWSER_NOT_FOREGROUND",
                    "该会话的浏览页不在前台，不能新建标签；请先切到这条会话再打开网页，" +
                        "或直接操作它已经打开的标签（new_tab=false）"
                )
            }
            val before = BrowserState.tabs.map { it.id }.toSet()
            BrowserHolder.open(context, target)
            result = BrowserState.tabs.firstOrNull { it.id !in before }
            created = result != null
            if (result == null) result = BrowserState.active
        } else {
            // 站内导航只落在该会话自己的活动标签上
            result = current
            runCatching { current.web.loadUrl(target) }
        }
        val tab = result ?: return UNAVAILABLE
        val state = stateOf(tab.id.toString())
        // 新地址的 DOM 与旧引用无关：先清表，避免沿用过期的 ref
        state.refs.clear()
        state.lastUrl = ""
        beginLease(tab)
        return DomResult.Ok(
            JSONObject()
                .put("ok", true)
                .put("tab_id", tab.id.toString())
                .put("url", tab.url.ifBlank { target })
                .put("title", tab.label)
                .put("new_tab", created)
                .toString()
        )
    }

    override suspend fun act(sessionId: String, tabId: String?, action: DomAction): DomResult {
        if (!attached) return UNAVAILABLE
        val tab = resolveTab(sessionId, tabId) ?: return NO_SESSION_PAGE
        val state = stateOf(tab.id.toString())
        // 入队时记录世代号：排队期间若被终止，获得锁后直接丢弃
        val generation = state.generation
        beginLease(tab)
        state.mutex.withLock {
            if (!attached) return UNAVAILABLE
            if (generation != state.generation) return ABORTED
            syncRefs(state, tab)
            val signal = CompletableDeferred<Unit>()
            state.abortSignal = signal
            // 终止可能发生在世代号检查与信号登记之间：登记后再核对一次，避免漏掉终止
            if (generation != state.generation) return ABORTED
            val outcome = runAbortable(signal) { execute(tab, state, action) }
            return outcome ?: ABORTED
        }
    }

    override fun abort(sessionId: String, tabId: String?, reason: String) {
        val id = tabId?.takeIf { it.isNotBlank() }
            ?: BrowserState.activeFor(sessionId)?.id?.toString()
            ?: leasedTabId
            ?: return
        endLease(id)
        // 解除租赁后该标签恢复可交互：只读状态必须同步撤回
        val state = states[id]
        if (state != null) {
            state.generation += 1
            state.abortSignal.complete(Unit)
        }
        FileLog.log("browser", "助手页面操作终止：" + id + " / " + reason)
    }

    // ── 租赁 ──

    private fun beginLease(tab: BrowserTab) {
        val id = tab.id.toString()
        if (id in leased) return
        leased = leased + id
        BrowserHolder.setAgentReadOnly(id, true)
    }

    private fun endLease(tabId: String) {
        if (tabId !in leased) return
        leased = leased - tabId
        BrowserHolder.setAgentReadOnly(tabId, false)
    }

    // ── 队列与取消 ──

    private fun stateOf(tabId: String): TabState = states.getOrPut(tabId) { TabState() }

    private fun resolveTab(sessionId: String, tabId: String?): BrowserTab? {
        val list = BrowserState.tabsFor(sessionId)
        if (tabId.isNullOrBlank()) return BrowserState.activeFor(sessionId)
        return list.firstOrNull { it.id.toString() == tabId }
    }

    /**
     * 执行 [block]，收到终止信号时以取消结束并返回 null。
     *
     * 调用方协程自身被取消时必须向上抛出取消，不得吞掉；
     * 只有终止信号引起的取消折算为 null，由调用方转换为 `E_USER_ABORTED`。
     */
    private suspend fun <T> runAbortable(
        signal: CompletableDeferred<Unit>,
        block: suspend () -> T
    ): T? = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            signal.await()
            work.cancel()
        }
        try {
            val value = work.await()
            if (signal.isCompleted) null else value
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            null
        } finally {
            watcher.cancel()
        }
    }

    /** 页面导航后旧引用失效：ref 表清空，由下一次快照重新建立。 */
    private fun syncRefs(state: TabState, tab: BrowserTab) {
        val url = tab.url
        if (url != state.lastUrl) {
            state.refs.clear()
            state.lastUrl = url
        }
    }

    // ── 动作 ──

    private suspend fun execute(tab: BrowserTab, state: TabState, action: DomAction): DomResult =
        when (action) {
            is DomAction.Snapshot -> snapshot(tab, state, action)
            is DomAction.Click -> click(tab, state, action)
            is DomAction.TypeText -> type(tab, state, action)
            is DomAction.Scroll -> scroll(tab, state, action)
            is DomAction.Extract -> extract(tab, state, action)
            is DomAction.Wait -> waitFor(tab, action)
        }

    private suspend fun snapshot(
        tab: BrowserTab,
        state: TabState,
        action: DomAction.Snapshot
    ): DomResult {
        val mode = action.mode.lowercase()
        if (mode == "text") {
            val obj = runJson(tab, textSnapshotScript())
                ?: return DomResult.Failure("E_SCRIPT", "页面脚本未返回有效结果")
            if (!obj.optBoolean("ok", false)) return failureOf(obj)
            return DomResult.Ok(
                basePayload(tab)
                    .put("mode", "text")
                    .put("text", obj.optString("text"))
                    .put("truncated", obj.optBoolean("truncated"))
                    .toString()
            )
        }

        val maxNodes = action.maxNodes.coerceIn(1, MAX_NODES)
        val obj = runJson(tab, snapshotScript(maxNodes))
            ?: return DomResult.Failure("E_SCRIPT", "页面脚本未返回有效结果")
        if (!obj.optBoolean("ok", false)) return failureOf(obj)
        val nodes = obj.optJSONArray("nodes") ?: JSONArray()
        state.refs.clear()
        for (i in 0 until nodes.length()) {
            val ref = REF_PATTERN.find(nodes.optString(i))?.groupValues?.getOrNull(1) ?: continue
            state.refs[ref] = "[data-agent-ref=\"" + ref + "\"]"
        }
        return DomResult.Ok(
            basePayload(tab)
                .put("mode", "interactive")
                .put("count", nodes.length())
                .put("nodes", nodes)
                .toString()
        )
    }

    private suspend fun click(
        tab: BrowserTab,
        state: TabState,
        action: DomAction.Click
    ): DomResult {
        val element = elementExpr(state, action.ref, action.selector)
            ?: return DomResult.Failure("E_INVALID_ARGUMENT", "必须提供 ref 或 selector")
        val obj = runJson(tab, clickScript(element))
            ?: return DomResult.Failure("E_SCRIPT", "页面脚本未返回有效结果")
        if (!obj.optBoolean("ok", false)) return failureOf(obj)
        if (action.waitMs > 0) delay(action.waitMs.toLong())
        return DomResult.Ok(
            basePayload(tab)
                .put("clicked", true)
                .put("wait_ms", action.waitMs)
                .toString()
        )
    }

    private suspend fun type(
        tab: BrowserTab,
        state: TabState,
        action: DomAction.TypeText
    ): DomResult {
        val element = elementExpr(state, action.ref, null)
            ?: return DomResult.Failure("E_INVALID_ARGUMENT", "必须提供 ref")
        val obj = runJson(tab, typeScript(element, action.text, action.clear, action.submit))
            ?: return DomResult.Failure("E_SCRIPT", "页面脚本未返回有效结果")
        if (!obj.optBoolean("ok", false)) return failureOf(obj)
        return DomResult.Ok(
            basePayload(tab)
                .put("typed", action.text.length)
                .put("submitted", obj.optBoolean("submitted", false))
                .toString()
        )
    }

    private suspend fun scroll(
        tab: BrowserTab,
        state: TabState,
        action: DomAction.Scroll
    ): DomResult {
        val to = action.to?.lowercase().orEmpty()
        val element = if (to == "ref" || (to.isEmpty() && !action.ref.isNullOrBlank())) {
            elementExpr(state, action.ref, null)
                ?: return DomResult.Failure("E_NOT_FOUND", "未找到目标元素")
        } else {
            "null"
        }
        val obj = runJson(tab, scrollScript(element, to, action.deltaY ?: 0))
            ?: return DomResult.Failure("E_SCRIPT", "页面脚本未返回有效结果")
        if (!obj.optBoolean("ok", false)) return failureOf(obj)
        return DomResult.Ok(
            basePayload(tab)
                .put("scroll_y", obj.optInt("y", 0))
                .toString()
        )
    }

    private suspend fun extract(
        tab: BrowserTab,
        state: TabState,
        action: DomAction.Extract
    ): DomResult {
        val element = elementExpr(state, action.ref, action.selector) ?: "document.body"
        val format = action.format.lowercase().let {
            if (it in EXTRACT_FORMATS) it else "text"
        }
        val maxChars = action.maxChars.coerceIn(1, MAX_CHARS)
        val obj = runJson(tab, extractScript(element, format, maxChars))
            ?: return DomResult.Failure("E_SCRIPT", "页面脚本未返回有效结果")
        if (!obj.optBoolean("ok", false)) return failureOf(obj)
        return DomResult.Ok(
            basePayload(tab)
                .put("format", format)
                .put("text", obj.optString("text"))
                .put("truncated", obj.optBoolean("truncated"))
                .toString()
        )
    }

    private suspend fun waitFor(tab: BrowserTab, action: DomAction.Wait): DomResult {
        val ms = action.ms
        if (ms != null) {
            if (ms > 0) delay(ms)
            return DomResult.Ok(
                basePayload(tab)
                    .put("waited_ms", ms)
                    .toString()
            )
        }
        val selector = action.selector
        val text = action.text
        if (selector.isNullOrBlank() && text.isNullOrBlank()) {
            return DomResult.Failure("E_INVALID_ARGUMENT", "必须提供 ms、selector 或 text 之一")
        }
        val deadline = System.currentTimeMillis() + action.timeoutMs.coerceAtLeast(1L)
        while (true) {
            val hit = if (!selector.isNullOrBlank()) {
                BrowserHolder.runScript(tab, pollSelectorScript(selector), SCRIPT_TIMEOUT_MS) == "1"
            } else {
                BrowserHolder.runScript(tab, pollTextScript(text!!), SCRIPT_TIMEOUT_MS) == "1"
            }
            if (hit) {
                return DomResult.Ok(
                    basePayload(tab)
                        .put("matched", if (!selector.isNullOrBlank()) "selector" else "text")
                        .toString()
                )
            }
            if (System.currentTimeMillis() >= deadline) {
                return DomResult.Failure("E_TIMEOUT", "等待超时：目标条件未在限定时间内出现")
            }
            delay(WAIT_POLL_MS)
        }
    }

    // ── 结果与脚本 ──

    private fun basePayload(tab: BrowserTab): JSONObject = JSONObject()
        .put("ok", true)
        .put("tab_id", tab.id.toString())
        .put("url", tab.url)
        .put("title", tab.label)

    private fun failureOf(obj: JSONObject): DomResult = DomResult.Failure(
        obj.optString("code", "E_SCRIPT"),
        obj.optString("message", "页面脚本执行失败")
    )

    private suspend fun runJson(tab: BrowserTab, script: String): JSONObject? {
        val raw = BrowserHolder.runScript(tab, script, SCRIPT_TIMEOUT_MS)
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * 目标元素的定位表达式。
     *
     * 优先使用最近一次快照建立的 ref 表；调用方给出 selector 时按 CSS 选择器定位。
     * 两者皆无时返回 null，由调用方折算为参数错误。
     */
    private fun elementExpr(state: TabState, ref: String?, selector: String?): String? {
        val target = when {
            !ref.isNullOrBlank() -> state.refs[ref] ?: ("[data-agent-ref=\"" + ref + "\"]")
            !selector.isNullOrBlank() -> selector
            else -> return null
        }
        return "document.querySelector(" + JSONObject.quote(target) + ")"
    }

    private fun snapshotScript(maxNodes: Int): String = """
        (function(){try{
          var MAX=$maxNodes;
          var out=[];
          var nodes=document.querySelectorAll('a[href],button,input,textarea,select,summary,label,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=switch],[contenteditable=true]');
          for(var i=0;i<nodes.length&&out.length<MAX;i++){
            var el=nodes[i];
            var r=el.getBoundingClientRect();
            if(!r||r.width<=0||r.height<=0)continue;
            var cs=window.getComputedStyle(el);
            if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')continue;
            var ref=String(out.length+1);
            try{el.setAttribute('data-agent-ref',ref)}catch(e){continue}
            var tag=(el.tagName||'').toLowerCase();
            var pwd=(tag==='input'&&String(el.getAttribute('type')||'').toLowerCase()==='password');
            var name=el.getAttribute('aria-label')||el.getAttribute('title')||el.innerText||'';
            if(!pwd){name=name||el.value||''}
            name=String(name).replace(/\s+/g,' ').trim().slice(0,80);
            var role=el.getAttribute('role')||tag;
            var line='ref='+ref+' role='+role+' name='+name+
              ' bounds='+Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+','+Math.round(r.height)+
              ' disabled='+(el.disabled===true)+' password='+(pwd?'true':'false');
            out.push(line);
          }
          return JSON.stringify({ok:true,count:out.length,nodes:out});
        }catch(e){return JSON.stringify({ok:false,code:'E_SCRIPT',message:String(e&&e.message||e)})}})()
    """.trimIndent()

    private fun textSnapshotScript(): String = """
        (function(){try{
          var t=(document.body&&document.body.innerText)||'';
          var max=$MAX_CHARS;
          var truncated=t.length>max;
          if(truncated){t=t.slice(0,max)}
          return JSON.stringify({ok:true,text:t,truncated:truncated});
        }catch(e){return JSON.stringify({ok:false,code:'E_SCRIPT',message:String(e&&e.message||e)})}})()
    """.trimIndent()

    private fun clickScript(element: String): String = """
        (function(){try{
          var el=$element;
          if(!el){return JSON.stringify({ok:false,code:'E_NOT_FOUND',message:'未找到目标元素'})}
          try{el.scrollIntoView({block:'center',inline:'center'})}catch(e){}
          el.click();
          return JSON.stringify({ok:true});
        }catch(e){return JSON.stringify({ok:false,code:'E_SCRIPT',message:String(e&&e.message||e)})}})()
    """.trimIndent()

    private fun typeScript(
        element: String,
        text: String,
        clear: Boolean,
        submit: Boolean
    ): String {
        val literal = JSONObject.quote(text)
        val next = if (clear) "text" else "(el.value||'')+text"
        val submitFlag = if (submit) "true" else "false"
        return """
            (function(){try{
              var el=$element;
              if(!el){return JSON.stringify({ok:false,code:'E_NOT_FOUND',message:'未找到目标元素'})}
              var tag=(el.tagName||'').toLowerCase();
              var pwd=(tag==='input'&&String(el.getAttribute('type')||'').toLowerCase()==='password');
              if(pwd){return JSON.stringify({ok:false,code:'E_PASSWORD_FIELD',message:'密码框不接受自动输入'})}
              try{el.scrollIntoView({block:'center',inline:'center'})}catch(e){}
              var text=$literal;
              var next=$next;
              if(tag==='input'||tag==='textarea'){
                var proto=(tag==='textarea')?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
                var desc=Object.getOwnPropertyDescriptor(proto,'value');
                if(desc&&desc.set){desc.set.call(el,next)}else{el.value=next}
              }else{
                el.textContent=next;
              }
              el.dispatchEvent(new Event('input',{bubbles:true}));
              el.dispatchEvent(new Event('change',{bubbles:true}));
              var submitted=false;
              if($submitFlag&&el.form){
                try{
                  if(el.form.requestSubmit){el.form.requestSubmit();submitted=true}
                  else{el.form.submit();submitted=true}
                }catch(e){}
              }
              return JSON.stringify({ok:true,submitted:submitted});
            }catch(e){return JSON.stringify({ok:false,code:'E_SCRIPT',message:String(e&&e.message||e)})}})()
        """.trimIndent()
    }

    private fun scrollScript(element: String, to: String, deltaY: Int): String {
        val target = if (element == "null") "null" else element
        return """
            (function(){try{
              var el=$target;
              var to=${JSONObject.quote(to)};
              if(to==='top'){window.scrollTo(0,0)}
              else if(to==='bottom'){
                var doc=document.documentElement;
                var h=Math.max((document.body?document.body.scrollHeight:0),(doc?doc.scrollHeight:0));
                window.scrollTo(0,h);
              }
              else if(el){el.scrollIntoView({block:'start'})}
              else{window.scrollBy(0,$deltaY)}
              return JSON.stringify({ok:true,y:Math.round(window.scrollY||0)});
            }catch(e){return JSON.stringify({ok:false,code:'E_SCRIPT',message:String(e&&e.message||e)})}})()
        """.trimIndent()
    }

    private fun extractScript(element: String, format: String, maxChars: Int): String = """
        (function(){try{
          var el=$element;
          if(!el){return JSON.stringify({ok:false,code:'E_NOT_FOUND',message:'未找到目标元素'})}
          var fmt=${JSONObject.quote(format)};
          var max=$maxChars;
          var out='';
          if(fmt==='html'){
            out=el.innerHTML||'';
          }else if(fmt==='links'){
            var links=(el.querySelectorAll?el.querySelectorAll('a[href]'):[]);
            var arr=[];
            for(var i=0;i<links.length;i++){
              arr.push({
                text:String(links[i].innerText||'').replace(/\s+/g,' ').trim().slice(0,200),
                href:links[i].href||''
              });
            }
            out=JSON.stringify(arr);
          }else if(fmt==='table'){
            var isTable=(el.tagName&&String(el.tagName).toLowerCase()==='table');
            var rows=isTable?el.rows:(el.querySelectorAll?el.querySelectorAll('tr'):[]);
            var lines=[];
            for(var i=0;i<rows.length;i++){
              var cells=rows[i].cells||rows[i].children||[];
              var cs=[];
              for(var j=0;j<cells.length;j++){
                cs.push(String(cells[j].innerText||'').replace(/\s+/g,' ').trim());
              }
              lines.push(cs.join('\t'));
            }
            out=lines.join('\n');
          }else{
            out=el.innerText||el.textContent||'';
          }
          var truncated=out.length>max;
          if(truncated){out=out.slice(0,max)}
          return JSON.stringify({ok:true,format:fmt,text:out,truncated:truncated});
        }catch(e){return JSON.stringify({ok:false,code:'E_SCRIPT',message:String(e&&e.message||e)})}})()
    """.trimIndent()

    private fun pollSelectorScript(selector: String): String =
        "(function(){try{return document.querySelector(" + JSONObject.quote(selector) + ")?'1':'0'}catch(e){return '0'}})()"

    private fun pollTextScript(text: String): String =
        "(function(){try{var t=(document.body&&document.body.innerText)||'';return t.indexOf(" +
            JSONObject.quote(text) + ")>=0?'1':'0'}catch(e){return '0'}})()"

    private val REF_PATTERN = Regex("ref=(\\d+)")

    private val EXTRACT_FORMATS = setOf("text", "html", "links", "table")
}
