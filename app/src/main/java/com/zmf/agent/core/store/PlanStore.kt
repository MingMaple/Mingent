package com.zmf.agent.core.store

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 计划模式的持久化：全局开关，不分会话。
 *
 * 开启后模型仅做调研（读文件、列目录、抓取网页），不调用写工具，也不执行终端命令，
 * 最终提交一份计划等待用户确认。开关与权限档共用 app_prefs，启动时即恢复。
 *
 * 计划正文与同一对象内的布尔开关放在一起，因为两者均属于计划模式数据；
 * 拆成两个 store 容易在清理会话时遗漏其一。
 * 但存储介质有意区分：开关为全局 app_prefs（跨会话语义），正文为 appdata/plans.json（按会话，key = sessionId）；
 * 合并存储会使二者语义相互牵制：开关被按会话保存，或清理单个会话时波及其他会话。
 *
 * 一个会话保存多份计划而非仅保留最新一份：用户需要对比不同版本并单独删除，
 * 仅保留一份则会失去此前方案。
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
     * - PENDING：刚提交，两个按钮可用；
     * - APPROVED：用户已选择「同意执行」，授权完成，按钮禁用；
     * - EDITED：用户已选择「继续修改」，该份已被消费，按钮禁用；
     * - ABANDONED：用户未同意也未修改，直接返回主页继续对话，视为废弃，按钮禁用。
     *
     * 废弃状态需持久化：该判定发生在每轮对话开始时，进程终止或切换会话后须仍然有效；
     * 仅存于内存时，重启后旧计划会重新变为可执行。
     */
    const val STATUS_PENDING = "pending"
    const val STATUS_APPROVED = "approved"
    const val STATUS_EDITED = "edited"
    const val STATUS_ABANDONED = "abandoned"

    /**
     * 上一次发出的计划 id，确保同一毫秒内连续提交两份计划也不会重复。
     *
     * id 采用毫秒时间戳而非随机串：删除依赖 id 定位，日志中也能直接对应写入批次；
     * 重复时以自增兜底，代价仅为相邻两条相差 1ms，比 UUID 更便于排查。
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

    /** 读取某会话的全部计划，顺序为创建顺序（新增的在末位）。无记录、空文件或损坏 JSON 均返回空列表，不抛出异常。涉及文件 IO，调用方应置于 IO 线程。 */
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
            // 计划仅为展示用说明文字，读取失败按无计划处理，不为此弹出错误或导致页面崩溃
            FileLog.log(PLAN_TAG, "读取计划失败，按「没有计划」处理：" + e)
            emptyList()
        }
    }

    /**
     * 追加一份计划，返回追加后的完整列表。
     *
     * 采用追加而非覆盖：模型每次提交一份方案，多份方案通常都需保留以供用户比较；
     * 覆盖写会删除上一版，用户无法查看之前的方案。
     * 返回完整列表供调用方直接使用（工具需报告序号、页面需重绘，均无需再次读盘）。
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
     * 找不到该 id 不视为错误：页面列表可能落后于磁盘（另一入口刚完成删除），
     * 按目标状态返回当前列表，比抛出异常或静默丢弃更合适。
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
            // 删空时移除 key（同 clearPlans）：文件中保留空数组会被后续读取视为脏数据
            if (rest.isEmpty()) root.remove(sessionId) else root.put(sessionId, plansToJson(rest))
            if (root.length() == 0) planFile(context).delete() else writeRoot(context, root)
            return rest
        } catch (e: Exception) {
            FileLog.log(PLAN_TAG, "删除计划失败：" + e)
            // 写入失败视为未删除：返回删除前的列表，避免界面显示磁盘上仍然存在的计划
            return before
        }
    }

    /** 清除某会话的全部计划；文件为空时一并删除文件，避免保留长期为空的 {}。 */
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

    /**
     * 改一份计划的状态，返回改完的列表。
     *
     * [abandonOtherPending] 用于用户同意某一份计划时：同一时刻仅应有一份计划等待确认，
     * 其余待处理计划一并作废；保留可点击按钮会提供第二次执行机会。
     * 找不到 id 不视为错误（磁盘可能已删除该份），原样返回当前列表。
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
     * 判定仅在此处：用户未选择「同意执行」也未选择「继续修改」，而是直接继续对话，
     * 该计划即作废，页面按钮必须同步禁用，否则可再次点击并直接触发模型执行。
     * 正在进行的流式回合不受影响：调用方仅在用户新发消息时进入此处。
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
     * 缺失（升级前写入的旧计划）与异常取值一律按**废弃**处理：这些计划均来自已被继续对话越过的回合，
     * 界面保留可点击的「同意执行」存在风险：点击已废弃计划会直接触发模型执行。
     * 执行旧计划需由模型重新提交，其代价低于误执行过期计划。
     */
    private fun normalizeStatus(raw: String): String = when (raw) {
        STATUS_PENDING, STATUS_APPROVED, STATUS_EDITED, STATUS_ABANDONED -> raw
        else -> STATUS_ABANDONED
    }

    /** 将某个会话的计划整体写回（空列表时移除 key，与 deletePlan / clearPlans 一致）。 */
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
     * 损坏文件按空对象处理：plans.json 内容均为可再生成的说明文字（丢失后由模型重写），
     * 无需备份或修复；每次写入均为整体覆盖写，损坏文件会在下次写入时自然恢复。
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
            // rename 失败（个别文件系统）时退化为直接覆盖，确保内容写入
            file.writeText(json, Charsets.UTF_8)
            temp.delete()
        }
    }

    /**
     * 从会话值中读取计划列表，兼容两种历史格式：
     * - 数组（当前格式）：逐条解析；
     * - 对象（早期版本仅存一份计划）：视为只含一条的列表，**不丢失用户已有计划**。
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
        // 旧格式无 id：按 createdAt + 下标补齐确定值，重新加载后 id 不变，删除操作方可命中；
        // 随机补齐会使每次读盘更换 id，导致无法删除。
        val stored = obj.optString(KEY_ID, "").trim()
        val id = if (stored.isEmpty() || stored == "null") "legacy-" + createdAt + "-" + index else stored
        val doc = PlanDoc(
            id = id,
            title = obj.optString(KEY_TITLE, ""),
            steps = steps,
            note = obj.optString(KEY_NOTE, ""),
            createdAt = createdAt,
            // 旧数据无此字段：见 normalizeStatus 的说明（按已废弃处理，按钮禁用）
            status = normalizeStatus(obj.optString(KEY_STATUS, ""))
        )
        // 标题与步骤均为空，说明该记录无实际内容（例如写入不完整），按无计划处理
        return if (doc.title.isBlank() && doc.steps.isEmpty()) null else doc
    }

    /** 步骤以 JSON 数组存储，不拼接为字符串：步骤中可能包含逗号、换行与序号，拼接后无法可靠拆分。 */
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
 * id 由 [PlanStore] 生成（时间戳，同毫秒自增），是切换与删除某一份计划的唯一句柄：
 * 不使用列表下标，因为删除中间项后，下标会指向其他计划且无提示。
 *
 * 步骤不存储编号：编号属于排版，由界面按下标生成；写入文本会使模型后续修改时需处理编号错位。
 * createdAt 只作记录与排序依据，不参与任何判断。
 */
data class PlanDoc(
    val id: String,
    val title: String,
    val steps: List<String>,
    val note: String = "",
    val createdAt: Long = 0L,
    /** 见 [PlanStore.STATUS_PENDING] 等常量。仅 PENDING 状态的计划两个按钮可用。 */
    val status: String = PlanStore.STATUS_PENDING
) {
    /** 是否仍可「同意执行」或「继续修改」：仅 PENDING 状态可用。 */
    val actionable: Boolean get() = status == PlanStore.STATUS_PENDING
}
