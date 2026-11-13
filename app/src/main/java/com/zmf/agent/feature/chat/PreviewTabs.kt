package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 一条预览标签：地址即身份，同一个路径不会开出第二条。 */
internal data class PreviewTab(val path: String)

/**
 * 预览标签的进程级持有者：已打开的文件、当前选中项、各自的版本与已读文本。
 *
 * 与 [BrowserState] 同一套结构：标签按会话挂起与挂回，切换会话不串内容。差异在于
 * **标签清单会落盘**——用户把应用从后台划掉之后，顶栏入口与标签仍在，只是内容需要重读；
 * 已读出的文本只留在内存里，随进程一起消失。
 */
internal object PreviewTabs {

    private const val PREFS_NAME = "preview_tabs"
    private const val KEY_PREFIX = "tabs_"

    /** 同时最多保留几条：与浏览器标签同一取舍，超出时丢最早打开的一条。 */
    private const val MAX_TABS = 8

    val tabs = mutableStateListOf<PreviewTab>()

    /**
     * 从预览页内部打开文件的来路（如 md 里的产物链接）。
     *
     * 与标签清单分开：标签是「打开过什么」，这里只记录「从哪一层进来的」，
     * 系统返回据此逐层退回，直到第一层预览才回到主页。
     */
    private val trail = mutableListOf<String>()

    var activePath by mutableStateOf<String?>(null)
        private set

    /** 标签打开（或上次进入页面）时记录的文件版本，用于判断「文件被改写」。 */
    private val versions = mutableStateMapOf<String, Long>()

    private class Parked(val paths: List<String>, val active: String?)

    private var owner = ""
    private val parked = mutableMapOf<String, Parked>()
    private var appContext: Context? = null

    /** 当前标签；选中项刚被关掉时退回最后一条。 */
    val active: PreviewTab? get() = tabs.firstOrNull { it.path == activePath } ?: tabs.lastOrNull()

    /** 顶栏入口的显隐依据：没有任何标签就不占位。 */
    val count: Int get() = tabs.size

    /** 已记录的版本；没有记录时返回 -1，供界面做 `remember` 的键。 */
    fun versionOf(path: String?): Long = path?.let { versions[it] } ?: -1L

    /**
     * 打开一个文件：已存在则只聚焦，不存在则新建一条并聚焦。
     *
     * 不按「轮次」或「来源」区分：同一个文件在多轮里被写过，仍指向它当前的内容，
     * 各轮的卡片各自指向同一标签，轮次语义由卡片所在的回合承担。
     */
    fun open(path: String) {
        if (tabs.none { it.path == path }) {
            if (tabs.size >= MAX_TABS) {
                val dropped = tabs.removeAt(0)
                versions.remove(dropped.path)
                PreviewTextCache.drop(dropped.path)
            }
            tabs.add(PreviewTab(path))
        }
        activePath = path
        save()
    }

    /** 切到已经打开的标签；不在名单里时什么都不做。 */
    fun activate(path: String) {
        if (tabs.any { it.path == path }) {
            activePath = path
            save()
        }
    }

    /**
     * 在预览页内部打开另一个文件（正文里的文件链接）：先记下来路，再切到目标。
     *
     * 与 [open] 分开是因为来源不同：从会话卡片打开的文件没有上一层，
     * 系统返回直接回主页；而正文链接是「从当前这一页进去的」，返回应退回当前页。
     */
    fun openChild(path: String) {
        val from = activePath
        if (from != null && from != path) trail.add(from)
        open(path)
    }

    /** 系统返回：退回上一层预览；没有来路时返回 false，由调用方回主页。 */
    fun back(): Boolean {
        while (trail.isNotEmpty()) {
            val previous = trail.removeAt(trail.lastIndex)
            // 来路已被关闭时继续往上找，不停在一个不存在的标签上
            if (tabs.any { it.path == previous }) {
                activePath = previous
                save()
                return true
            }
        }
        return false
    }

    /** 关掉一条标签；关掉当前项时落到原位置的下一项，没有则退回最后一条。 */
    fun close(path: String) {
        val index = tabs.indexOfFirst { it.path == path }
        if (index < 0) return
        tabs.removeAt(index)
        versions.remove(path)
        PreviewTextCache.drop(path)
        trail.removeAll { it == path }
        if (activePath == path) {
            activePath = tabs.getOrNull(index)?.path ?: tabs.lastOrNull()?.path
        }
        save()
    }

    /**
     * 进入页面时对账一次文件版本。
     *
     * 返回 true 表示这份文件与上次记录相比已被改写（或替换），界面据此显示一行「文件已更新」；
     * 首次记录返回 false，不会把「刚打开」当成「刚被改」。
     */
    fun observe(path: String, version: Long): Boolean {
        val known = versions[path]
        if (known == version) return false
        versions[path] = version
        return known != null
    }

    /**
     * 会话切换：挂起旧会话的标签，挂回新会话的那一套。
     *
     * 新会话第一次进入时从磁盘读回上次留下的清单——这是「从后台划掉再打开」仍能看见入口的来源。
     * 版本与已读文本属于上一个会话的运行态，切换时整表清空，避免把上一份内容带给另一个会话。
     */
    fun switchTo(context: Context, sessionId: String) {
        appContext = context.applicationContext
        if (sessionId == owner) return
        if (owner.isEmpty()) {
            // 首次挂载：没有旧会话可挂起。内存里若已有标签（打开预览早于这次切换），它们属于本会话，
            // 不能清；只有确实为空时才从磁盘读回上次留下的清单
            owner = sessionId
            if (tabs.isEmpty()) tags(context, sessionId)
            activePath = activePath ?: tabs.firstOrNull()?.path
            return
        }
        parked[owner] = Parked(tabs.map { it.path }, activePath)
        owner = sessionId
        trail.clear()
        val back = parked.remove(sessionId)
        tabs.clear()
        if (back != null) {
            tabs.addAll(back.paths.map { PreviewTab(it) })
            activePath = back.active?.takeIf { path -> tabs.any { it.path == path } }
                ?: tabs.firstOrNull()?.path
        } else {
            tags(context, sessionId)
            activePath = tabs.firstOrNull()?.path
        }
        versions.clear()
        PreviewTextCache.clear()
    }

    /** 从磁盘读回某个会话上次留下的标签清单。 */
    private fun tags(context: Context, sessionId: String) {
        tabs.addAll(load(context, sessionId).map { PreviewTab(it) })
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(context: Context, sessionId: String): List<String> {
        val raw = prefs(context).getString(KEY_PREFIX + sessionId, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    private fun save() {
        val context = appContext ?: return
        if (owner.isEmpty()) return
        // 路径里不含换行，直接用换行分隔即可，不需要 JSON
        prefs(context).edit()
            .putString(KEY_PREFIX + owner, tabs.joinToString("\n") { it.path })
            .apply()
    }
}

/** 一份已经读出的文本：切标签回来时直接显示，不必重读。 */
internal class PreviewText(
    val version: Long,
    var text: String,
    var nextLine: Int,
    var eof: Boolean,
    var binary: Boolean
)

/** 已读文本的内存缓存：按路径保存，版本不一致即作废；关标签或切会话时释放。 */
internal object PreviewTextCache {

    private val entries = HashMap<String, PreviewText>()

    fun get(path: String, version: Long): PreviewText? =
        entries[path]?.takeIf { it.version == version }

    fun put(path: String, entry: PreviewText) {
        entries[path] = entry
    }

    fun drop(path: String) {
        entries.remove(path)
    }

    fun clear() {
        entries.clear()
    }
}
