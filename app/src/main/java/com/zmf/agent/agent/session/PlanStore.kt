package com.zmf.agent.agent.session

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 计划模式的持久化：全局开关，不分会话。
 *
 * 开启后模型只做调研（读文件、列目录、抓网页），不下发写工具、不许执行终端命令，
 * 最后交一份计划等用户确认。落盘位置和权限档同一个 app_prefs，启动即恢复。
 *
 * 计划正文为什么和同一个 object 里的布尔开关放在一起：
 * 两者都是「计划模式」的数据，拆成两个 store 反而容易在清会话时漏掉一边。
 * 但存储介质故意分开：开关是全局 app_prefs（跨会话语义），正文是 appdata/plans.json（按会话，key = sessionId），
 * 混在一起就只能二选一，要么开关被按会话存，要么清一个会话的计划把别的会话也带走。
 *
 * 为什么一个会话存「多份」而不是只留最新一份：用户要能来回切着看、比较不同版本的方案，
 * 再单独删掉不要的那份。只留一份就只剩最后一次输出，前一次的方案连对比的机会都没有。
 */
object PlanStore {

    private const val PREFS_NAME = "app_prefs"
    private const val KEY = "plan_mode"

    /** 计划正文的落盘文件：filesDir/appdata/plans.json（首次读时由 [AppPaths] 顺带处理旧位置迁移）。 */
    private const val PLAN_FILE = "plans.json"
    private const val PLAN_TAG = "plan"

    private const val KEY_ID = "id"
    private const val KEY_TITLE = "title"
    private const val KEY_STEPS = "steps"
    private const val KEY_NOTE = "note"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_STATUS = "status"

    /**
     * 计划的四种状态：
     * - PENDING：刚交上来，两个按钮可点；
     * - APPROVED：用户点了「同意执行」，已经授权过，按钮变灰（不能执行第二次）；
     * - EDITED：用户点了「继续修改」，这份已经被消费，按钮变灰；
     * - ABANDONED：用户既没同意也没修改、直接回主页继续对话：视为废弃，按钮变灰。
     *
     * 为什么要落盘：废弃是「这一轮对话开始」时的判定，进程被杀、切会话再来还得算数；
     * 只放内存的话重启一次旧计划又变成可执行的了。
     */
    const val STATUS_PENDING = "pending"
    const val STATUS_APPROVED = "approved"
    const val STATUS_EDITED = "edited"
    const val STATUS_ABANDONED = "abandoned"

    /**
     * 上一次发出的计划 id，保证「同一毫秒内连续交两份计划」也不会撞号。
     *
     * 为什么 id 用毫秒时间戳而不是随机串：删除靠 id 定位，日志里也一眼能对上是哪一次写入；
     * 撞号用自增兜住，代价只是相邻两条差 1ms，比 UUID 好排查。
     */
    @Volatile
    private var lastIssuedPlanId = 0L

    /** 当前是否处于计划模式；从未改过时为关。 */
    fun load(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    /** 落盘全局计划模式开关，下一次 [load] 读到它。 */
    fun save(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    // ---- 计划正文（按会话，可存多份）----

    /** 读某会话的全部计划，顺序＝创建顺序（新的在最后）。没有、空文件、坏 JSON 一律空列表，绝不抛。做文件 IO，调用方放到 IO 线程。 */
    @Synchronized
    fun loadPlans(context: Context, sessionId: String): List<PlanDoc> {
        if (sessionId.isBlank()) return emptyList()
        return try {
            val file = planFile(context)
            if (!file.exists() || file.length() == 0L) {
                emptyList()
            } else {
                readPlans(JSONObject(file.readText(Charsets.UTF_8)), sessionId)
            }
        } catch (e: Exception) {
            // 计划只是给人看的说明文字，坏掉就当「还没有计划」，不值得为它弹错或让页面崩
            FileLog.log(PLAN_TAG, "读取计划失败，按「没有计划」处理：" + e)
            emptyList()
        }
    }

    /**
     * 追加一份计划，返回追加后的完整列表。
     *
     * 为什么是追加：模型每次交的是一份「方案」，多个方案常常都值得留着让用户比较；
     * 覆盖写会把上一版直接抹掉，用户连「刚才那版写的是啥」都看不到。
     * 返回列表给调用方直接用（工具要报「第几份」、页面要重绘，都不必再读一次盘）。
     */
    @Synchronized
    fun appendPlan(
        context: Context,
        sessionId: String,
        title: String,
        steps: List<String>,
        note: String = ""
    ): List<PlanDoc> {
        if (sessionId.isBlank()) return emptyList()
        var plans: List<PlanDoc> = emptyList()
        try {
            val now = System.currentTimeMillis()
            val root = readRoot(context)
            val list = readPlans(root, sessionId).toMutableList()
            list.add(
                PlanDoc(
                    id = newPlanId(now),
                    title = title.trim(),
                    steps = steps,
                    note = note.trim(),
                    createdAt = now,
                    status = STATUS_PENDING
                )
            )
            root.put(sessionId, plansToJson(list))
            writeRoot(context, root)
            plans = list
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "追加计划失败：" + e)
        }
        return plans
    }

    /**
     * 删掉某会话里指定 id 的计划，返回剩余列表。
     *
     * 找不到这个 id 不算错：页面上的列表可能比磁盘旧一拍（另一个入口刚删过），
     * 按「已经是想要的状态」把当前列表返回，比抛错或静默丢弃更接近事实。
     */
    @Synchronized
    fun deletePlan(context: Context, sessionId: String, planId: String): List<PlanDoc> {
        if (sessionId.isBlank() || planId.isBlank()) return loadPlans(context, sessionId)
        var before: List<PlanDoc> = emptyList()
        try {
            val root = readRoot(context)
            before = readPlans(root, sessionId)
            val rest = before.filterNot { it.id == planId }
            if (rest.size == before.size) return before
            // 删空就把 key 摘掉（同 clearPlans）：留个空数组在文件里，下次有人读会以为是「有会话但没计划」的脏数据
            if (rest.isEmpty()) root.remove(sessionId) else root.put(sessionId, plansToJson(rest))
            if (root.length() == 0) planFile(context).delete() else writeRoot(context, root)
            return rest
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "删除计划失败：" + e)
            // 没写成功就等于没删：把删之前的列表还给调用方，别让界面显示一份磁盘上还在的计划
            return before
        }
    }

    /** 清掉某会话的全部计划；整个文件空了就连文件一起删，别留个常年躺着的 {}。 */
    @Synchronized
    fun clearPlans(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        try {
            val file = planFile(context)
            if (!file.exists()) return
            val root = readRoot(context)
            if (!root.has(sessionId)) return
            root.remove(sessionId)
            if (root.length() == 0) {
                file.delete()
            } else {
                writeRoot(context, root)
            }
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "清除计划失败：" + e)
        }
    }

    // 老的单条接口（loadPlan / savePlan / clearPlan）已删：ChatViewModel 现在直接调 loadPlans / deletePlan / clearPlans，
    // 留着它们只会让「整体替换成一份」这种语义重新混进来，误调一次就把用户攒的其它计划清掉了。

    /**
     * 改一份计划的状态，返回改完的列表。
     *
     * [abandonOtherPending] 用于「用户同意了某一份」：同一时刻只该有一份计划悬着等确认，
     * 其余还在等他处理的那些跟着变成废弃：留着可点的按钮，等于给了第二次执行的机会。
     * 找不到 id 不算错（磁盘可能已把这份删了），原样返回当前列表。
     */
    @Synchronized
    fun markPlan(
        context: Context,
        sessionId: String,
        planId: String,
        status: String,
        abandonOtherPending: Boolean = false
    ): List<PlanDoc> {
        if (sessionId.isBlank() || planId.isBlank()) return loadPlans(context, sessionId)
        return try {
            val root = readRoot(context)
            val current = readPlans(root, sessionId)
            if (current.none { it.id == planId }) return current
            val next = normalizeStatus(status)
            val updated = current.map { doc ->
                when {
                    doc.id == planId -> doc.copy(status = next)
                    abandonOtherPending && doc.status == STATUS_PENDING -> doc.copy(status = STATUS_ABANDONED)
                    else -> doc
                }
            }
            writePlans(context, root, sessionId, updated)
            updated
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "改计划状态失败（" + status + "）：" + e)
            loadPlans(context, sessionId)
        }
    }

    /**
     * 新一轮对话开始：所有还没处理过的计划一律废弃。
     *
     * 判据只在这一处：用户没点「同意执行」也没点「继续修改」，而是直接回去继续对话，
     * 那份计划就作废了，页面上的两个按钮必须跟着变灰，否则又能被点一次并让模型直接开跑。
     * 已经在跑的流式回合不受影响：调用方只在「用户新发一条消息」时进这里。
     */
    @Synchronized
    fun abandonPending(context: Context, sessionId: String): List<PlanDoc> {
        if (sessionId.isBlank()) return emptyList()
        return try {
            val root = readRoot(context)
            val current = readPlans(root, sessionId)
            if (current.none { it.status == STATUS_PENDING }) return current
            val updated = current.map {
                if (it.status == STATUS_PENDING) it.copy(status = STATUS_ABANDONED) else it
            }
            writePlans(context, root, sessionId, updated)
            FileLog.log(PLAN_TAG, "新一轮对话开始，已废弃 " + updated.count { it.status == STATUS_ABANDONED } + " 份待确认计划")
            updated
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "废弃待确认计划失败：" + e)
            loadPlans(context, sessionId)
        }
    }

    // ---- 内部 ----

    /**
     * 状态字符串收敛到四个已知值。
     *
     * 缺失（升级前写下的旧计划）与怪值一律当**废弃**：那些计划都来自已经被继续对话过去的回合，
     * 界面上还留着可点的「同意执行」会直接出事：点了废弃计划，模型直接开跑。
     * 要执行旧计划，让模型重新交一份新的：这条代价比「误执行一份过期计划」小得多。
     */
    private fun normalizeStatus(raw: String): String = when (raw) {
        STATUS_PENDING, STATUS_APPROVED, STATUS_EDITED, STATUS_ABANDONED -> raw
        else -> STATUS_ABANDONED
    }

    /** 把某个会话的计划整体写回（空列表就把 key 摘掉，同 deletePlan / clearPlans 的口径）。 */
    private fun writePlans(context: Context, root: JSONObject, sessionId: String, plans: List<PlanDoc>) {
        if (plans.isEmpty()) root.remove(sessionId) else root.put(sessionId, plansToJson(plans))
        if (root.length() == 0) planFile(context).delete() else writeRoot(context, root)
    }

    private fun newPlanId(now: Long): String {
        val value = if (now > lastIssuedPlanId) now else lastIssuedPlanId + 1
        lastIssuedPlanId = value
        return value.toString()
    }

    private fun planFile(context: Context): File = AppPaths.dataFile(context, PLAN_FILE)

    /**
     * 整个文件读成 JSONObject；读不出来就给一个空对象。
     *
     * 为什么可以「坏文件当空」：plans.json 里全是可再生成的说明文字（真丢了让模型重写一份就行），
     * 为它做备份/迁移/修复不值得；而每次写入都是整体覆盖写，坏文件会被下一次写入自然修好。
     */
    private fun readRoot(context: Context): JSONObject {
        val file = planFile(context)
        if (!file.exists() || file.length() == 0L) return JSONObject()
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "plans.json 内容坏掉，按空的处理（下次保存会整体重写）：" + e)
            JSONObject()
        }
    }

    /** 先写 .tmp 再 rename（同 SessionStore）：写到一半被杀时旧文件仍然完整。 */
    private fun writeRoot(context: Context, root: JSONObject) {
        val file = planFile(context)
        file.parentFile?.mkdirs()
        val json = root.toString()
        val temp = File(file.parentFile, PLAN_FILE + ".tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            // rename 失败（个别文件系统）时退化成直接覆盖，保证内容落地
            file.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
    }

    /**
     * 从一个会话的值里读出计划列表，兼容两种历史格式：
     * - 数组（当前格式）：逐条解析；
     * - 对象（第一版只有一份计划）：当成只有一条的列表，**不丢用户已有的计划**。
     */
    private fun readPlans(root: JSONObject, sessionId: String): List<PlanDoc> {
        return when (val value = root.opt(sessionId)) {
            is JSONArray -> {
                val out = ArrayList<PlanDoc>(value.length())
                for (i in 0 until value.length()) {
                    val obj = value.optJSONObject(i) ?: continue
                    parsePlan(obj, i)?.let { out.add(it) }
                }
                out
            }
            is JSONObject -> listOfNotNull(parsePlan(value, 0))
            else -> emptyList()
        }
    }

    private fun parsePlan(obj: JSONObject, index: Int): PlanDoc? {
        val steps = ArrayList<String>()
        obj.optJSONArray(KEY_STEPS)?.let { array ->
            for (i in 0 until array.length()) {
                val step = array.optString(i, "").trim()
                if (step.isNotEmpty()) steps.add(step)
            }
        }
        val createdAt = obj.optLong(KEY_CREATED_AT, 0L)
        // 旧格式没有 id：按「createdAt + 下标」补一个确定性的，重新加载后同一条计划 id 不变，删除才删得中，
        // 随机补 id 会在每次读盘后换号，用户点删除就永远删不到。
        val stored = obj.optString(KEY_ID, "").trim()
        val id = if (stored.isEmpty() || stored == "null") "legacy-" + createdAt + "-" + index else stored
        val doc = PlanDoc(
            id = id,
            title = obj.optString(KEY_TITLE, ""),
            steps = steps,
            note = obj.optString(KEY_NOTE, ""),
            createdAt = createdAt,
            // 旧数据没有这个字段：见 normalizeStatus 的注释（当作已废弃，按钮灰掉）
            status = normalizeStatus(obj.optString(KEY_STATUS, ""))
        )
        // 标题和步骤都空，说明这条记录没有实际内容（例如被半截写入），当作没有计划
        return if (doc.title.isBlank() && doc.steps.isEmpty()) null else doc
    }

    /** 步骤用 JSON 数组存，不拼成一个字符串：步骤里允许出现逗号、换行、序号，拼起来就再也拆不回来。 */
    private fun plansToJson(plans: List<PlanDoc>): JSONArray {
        val array = JSONArray()
        for (plan in plans) array.put(planToJson(plan))
        return array
    }

    private fun planToJson(doc: PlanDoc): JSONObject {
        val steps = JSONArray()
        for (step in doc.steps) steps.put(step)
        val obj = JSONObject()
        obj.put(KEY_ID, doc.id)
        obj.put(KEY_TITLE, doc.title)
        obj.put(KEY_STEPS, steps)
        obj.put(KEY_NOTE, doc.note)
        obj.put(KEY_CREATED_AT, doc.createdAt)
        obj.put(KEY_STATUS, normalizeStatus(doc.status))
        return obj
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 一份计划：一句话标题 + 一串可执行的步骤 + 可选补充。
 *
 * id 由 [PlanStore] 生成（时间戳，同毫秒自增），是「切换 / 删除某一份」的唯一句柄：
 * 不用列表下标，是因为删掉中间那份之后，下标会让「用户刚才选的那份」静悄悄变成别人。
 *
 * 为什么步骤连编号都不存：编号是排版，界面按下标生成；存进文本里，模型下次改动就要小心「第 3 条其实已经删了」这种错位。
 * createdAt 只作记录与排序依据，不参与任何判断。
 */
data class PlanDoc(
    val id: String,
    val title: String,
    val steps: List<String>,
    val note: String = "",
    val createdAt: Long = 0L,
    /** 见 [PlanStore.STATUS_PENDING] 等常量。只有 PENDING 的计划两个按钮可点。 */
    val status: String = PlanStore.STATUS_PENDING
) {
    /** 还能不能「同意执行」或「继续修改」：只有刚交上来那份可以。 */
    val actionable: Boolean get() = status == PlanStore.STATUS_PENDING
}
