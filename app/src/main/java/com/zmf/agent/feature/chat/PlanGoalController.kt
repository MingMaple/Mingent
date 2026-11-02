package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.capability.tools.displayName
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.store.Goal
import com.zmf.agent.core.store.GoalPhase
import com.zmf.agent.core.store.GoalStore
import com.zmf.agent.core.store.PlanDoc
import com.zmf.agent.core.store.PlanStore
import com.zmf.agent.core.store.label

/** 批准计划时代替用户发送的语句；正文与日志共用一份，避免两处各写一遍。 */
private const val PLAN_APPROVE_TEXT = "按计划开始执行"

/**
 * 计划模式、计划与目标、以及四条斜杠命令（/plan /goal /compact /permission）。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 消息发送、权限切换、压缩与界面提示均以构造 lambda 注入，不反向持有 ViewModel。
 */
class PlanGoalController(
    private val appContext: Context,
    private val currentSessionId: () -> String,
    private val isStreaming: () -> Boolean,
    private val setActivity: (String?) -> Unit,
    private val reportError: (String?) -> Unit,
    private val applyPermission: (PermissionLevel) -> Unit,
    private val summarizeNow: () -> Unit,
    private val sendNow: (String, Boolean) -> Unit
) {

    /** 计划模式（全局开关）：开启后模型只调研、只给计划，等确认了才动手。 */
    var planMode by mutableStateOf(PlanStore.load(appContext))
        private set

    /** 当前会话的计划列表（模型每次 write_plan 追加一份）；空 = 还没有计划。 */
    var plans by mutableStateOf<List<PlanDoc>>(emptyList())
        private set

    /** 计划预览里选中的那一份；null = 没有计划可选。只存在内存里：这是「看哪份」的选择，不是数据。 */
    var selectedPlanId by mutableStateOf<String?>(null)
        private set

    /** 当前会话的长期目标；null = 没有目标。 */
    var goal by mutableStateOf<Goal?>(null)
        private set

    /**
     * 计划模式开关的唯一写法：状态、落盘、日志三处保持一致（斜杠命令也走它）。
     *
     * 名字必须是 apply* 而不是 set*：`var planMode` 的自动 setter 已占用 setPlanMode 的 JVM 签名，
     * 同名函数会引发 Platform declaration clash（busyMode 曾出现同一问题）。
     */
    private fun applyPlanMode(enabled: Boolean) {
        if (planMode == enabled) return
        planMode = enabled
        PlanStore.save(appContext, enabled)
        FileLog.log("plan", "计划模式 " + (if (enabled) "开启" else "关闭"))
    }

    /** 面板里点「计划」：切换并落盘。 */
    fun togglePlanMode() {
        applyPlanMode(!planMode)
    }

    /**
     * 斜杠命令入口：输入以 / 开头时先在这里拦一道。
     *
     * 返回值表示「该句已被消费」：true = 不作为普通消息发送。
     * 认不出来的 /xxx（比如 /技能名）一律返回 false，让它照常发出去。
     */
    fun handleSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false
        val body = trimmed.removePrefix("/").trim()
        // 单独一个 "/" 当普通输入：它不是命令，也没有可执行的语义
        if (body.isEmpty()) return false
        val name = body.substringBefore(' ').lowercase()
        val arg = body.substringAfter(' ', "").trim()
        return when (name) {
            "plan" -> handlePlanCommand(arg)
            "goal" -> handleGoalCommand(arg)
            "compact" -> handleCompactCommand()
            "permission" -> handlePermissionCommand(arg)
            else -> false
        }
    }

    /**
     * /compact：立刻压一次上下文。
     *
     * 为什么需要该命令：此前仅提供加号面板中的「压缩」入口，无法写入脚本，也无法在对话中直接输入；
     * 压缩做成命令的同时也是自测入口（先发一句话、再 /compact，就能验证压缩与缓存）。
     */
    private fun handleCompactCommand(): Boolean {
        summarizeNow()
        return true
    }

    /**
     * /permission <只读|工作区|完全>（也认 read/workspace/full）：切权限档。
     *
     * 切档入口为 `/permission <preset>`；此前仅提供左下角 chip，
     * 自动化测试得靠点坐标，做成命令后一条 input text 就能切。
     */
    private fun handlePermissionCommand(arg: String): Boolean {
        val level = when (arg.trim().lowercase()) {
            "read", "read-only", "readonly", "只读", "仅可查看" -> PermissionLevel.READ_ONLY
            "workspace", "workspace-write", "工作区", "工作区内修改" -> PermissionLevel.WORKSPACE
            "full", "danger-full-access", "danger", "完全", "完全权限" -> PermissionLevel.FULL
            "" -> null
            else -> null
        }
        if (level == null) {
            setActivity("用法：/permission 只读｜工作区｜完全")
            return true
        }
        applyPermission(level)
        setActivity("权限档已切到「" + level.displayName() + "」")
        return true
    }

    /** /plan [off] [本轮提问]：开 / 关计划模式；带正文时那段文字就是本轮提问。 */
    private fun handlePlanCommand(arg: String): Boolean {
        if (arg.equals("off", ignoreCase = true)) {
            applyPlanMode(false)
            setActivity("计划模式已关闭")
            return true
        }
        applyPlanMode(true)
        if (arg.isEmpty()) {
            setActivity("计划模式已开启")
            return true
        }
        // 带正文：模式先打开，再把这段文字当成一次普通提问发出去（走同一条发送路径）
        sendNow(arg, false)
        return true
    }

    /**
     * /goal ...：目标是「命令流」，不进对话上下文，也不需要预览页。
     *
     * - 无参：只把当前状态写进状态行，不发消息；
     * - pause / resume / clear / edit <文本>：改状态，不发消息；
     * - 其它：整段当成新目标（覆盖旧目标），并立刻开跑一轮把它推进起来。
     */
    private fun handleGoalCommand(arg: String): Boolean {
        if (arg.isEmpty()) {
            reportGoalStatus()
            return true
        }
        val sub = arg.substringBefore(' ').lowercase()
        val rest = arg.substringAfter(' ', "").trim()
        // pause/resume/clear/edit 均为「对已有目标执行操作」：无目标时先明确说明，不可伪装成功
        if ((sub == "pause" || sub == "resume" || sub == "clear" || sub == "edit") && goal == null) {
            setActivity("还没有目标：发 /goal <目标> 先建一个")
            return true
        }
        when (sub) {
            "pause" -> {
                pauseGoal()
                setActivity("目标已暂停")
            }
            "resume" -> {
                resumeGoal()
                setActivity("目标已继续")
            }
            "clear" -> {
                clearGoal()
                setActivity("目标已删除")
            }
            "edit" -> {
                if (rest.isEmpty()) {
                    setActivity("用法：/goal edit <新的目标>")
                } else {
                    editGoal(rest)
                    setActivity("目标已改写")
                }
            }
            // 非子命令：整段内容即为新目标
            else -> createGoal(arg)
        }
        return true
    }

    /** 新建（或覆盖）目标并立刻开跑一轮：目标是「还在做的事」，不能建完还等用户再问一遍。 */
    private fun createGoal(text: String) {
        val objective = text.trim()
        if (objective.isEmpty()) return
        val now = System.currentTimeMillis()
        val next = Goal(
            objective = objective,
            phase = GoalPhase.ACTIVE,
            rounds = 0,
            createdAt = now,
            updatedAt = now
        )
        goal = next
        GoalStore.save(appContext, currentSessionId(), next)
        FileLog.log("goal", "新目标（第 0 轮）：" + objective.take(60))
        // 流式中 send 会直接返回：该轮收尾时 goal 已为 ACTIVE，续跑链会补上本轮
        sendNow(objective, false)
    }

    /** 状态行里说一句当前目标；没有目标就说怎么建。 */
    private fun reportGoalStatus() {
        val current = goal
        val text = if (current == null) {
            "还没有目标：发 /goal <目标> 新建"
        } else {
            "目标" + current.phase.label() + " · 第 " + current.rounds + " 轮" +
                (if (current.note.isNotEmpty()) " · " + current.note else "")
        }
        setActivity(text)
        FileLog.log("goal", text)
    }

    /**
     * 重读当前会话的计划列表：流结束与进入计划页时各调用一次，不可每帧调用（需读盘）。
     *
     * 选中态在这里一起校正：原选中那份被删掉、或第一次读到计划时，落到最新的一份。
     * 用户刚看到的应该就是模型刚交的那份。
     */
    fun reloadPlans() {
        // 记录重读前的计划数量：用于识别「本次确实新增了计划」
        val before = plans.map { it.id }.toSet()
        val list = PlanStore.loadPlans(appContext, currentSessionId())
        plans = list
        val current = selectedPlanId
        val fresh = list.filter { it.id !in before }
        selectedPlanId = when {
            // 模型刚提交新计划：直接将预览切换至最新一份（用户点击右上角按钮时应看到该份，
            // 而非停留在上次查看的旧计划）
            fresh.isNotEmpty() -> fresh.last().id
            // 无新计划则停留在用户上次查看的一份：从计划页返回主页后再次进入，仍为同一页
            current != null && list.any { it.id == current } -> current
            list.isNotEmpty() -> list.last().id
            else -> null
        }
    }

    /**
     * 新一轮对话开始前把「还没处理过」的计划标成废弃。
     *
     * 判据在 [PlanStore.abandonPending] 里，这里只负责同步界面上的两份真值：
     * 列表（按钮要变灰）与选中态（被废弃的那份仍然要能看到详情）。
     */
    fun abandonPendingPlansBeforeNewTurn() {
        if (plans.none { it.status == PlanStore.STATUS_PENDING }) return
        val updated = PlanStore.abandonPending(appContext, currentSessionId())
        if (updated.isEmpty()) return
        plans = updated
        selectedPlanId = updated.lastOrNull()?.id ?: selectedPlanId
    }

    /** 重读当前会话的目标：切会话、以及 goal 工具跑完之后都要对齐。 */
    fun reloadGoal() {
        goal = GoalStore.load(appContext, currentSessionId())
    }

    /** 目标续跑时把轮次推进后的目标写回界面状态（落盘由调用方紧接着做）。 */
    fun applyGoal(next: Goal) {
        goal = next
    }

    /** 换会话路径统一调这一个：两份文档都跟当前会话绑定。 */
    fun reloadSessionDocs() {
        reloadPlans()
        reloadGoal()
    }

    /** 当前目标的一句话：写给请求尾部的快照用，没有目标返回空串（那一行就不出）。 */
    fun goalText(): String {
        val current = goal ?: return ""
        return current.objective.take(200) + "（" + current.phase.label() + "，第 " + current.rounds +
            " 轮 / 上限 " + MAX_GOAL_ROUNDS + " 轮）"
    }

    /** 计划预览切换选中项；id 不在列表里就当没点（界面上的选中态不该跟着乱跳）。 */
    fun selectPlan(id: String) {
        if (plans.none { it.id == id }) return
        if (id == selectedPlanId) return
        selectedPlanId = id
        FileLog.log("plan", "预览切到计划 " + id)
    }

    /** 删除一份计划：删完的列表由 PlanStore 还回来，选中态基于同一份数据校正。 */
    fun deletePlan(id: String) {
        if (plans.none { it.id == id }) return
        // deletePlan 直接返回删除后的列表：省去一次读盘，选中态也基于同一份数据校正
        val remaining = PlanStore.deletePlan(appContext, currentSessionId(), id)
        val wasSelected = selectedPlanId == id
        plans = remaining
        if (wasSelected || remaining.none { it.id == selectedPlanId }) {
            selectedPlanId = remaining.firstOrNull()?.id
        }
        FileLog.log("plan", "删除计划 " + id + "，剩 " + plans.size + " 份")
    }

    /**
     * 「按计划开始执行」：关掉计划模式，再把所选计划连正文一起当成一次普通发送。
     *
     * 选择发送而非直接执行的原因：用户批准的是一句话，它应与其他提问一样生成 user 气泡、
     * 写入日志并落盘，而不是直接修改状态使模型自行开始。流式响应期间直接忽略，否则会与当前轮次争用 messages。
     * [plans] 不清：同意之后计划预览还要能对着逐条执行、回看。
     */
    fun approvePlan(planId: String) {
        if (isStreaming()) return
        val target = plans.firstOrNull { it.id == planId } ?: plans.lastOrNull()
        // 双重校验：页面上按钮已置灰，此处再次拦截；两处状态可能短暂不一致
        //（例如该页仍打开而另一处已将其废弃），过期状态绝不可执行
        if (target != null && !target.actionable) {
            reportError("该计划此前已处理，不可重复执行；如需执行请让模型重新提交一份计划")
            FileLog.log("plan", "拒绝执行已处理的计划 " + target.id + "（状态=" + target.status + "）")
            return
        }
        if (target != null) selectedPlanId = target.id
        // 先写入状态再发送消息：下方 send() 会将「仍待确认」的计划视为废弃，
        // 顺序相反时刚批准的计划会先被废弃、再作为可执行项发送
        if (target != null) {
            plans = PlanStore.markPlan(
                appContext,
                currentSessionId(),
                target.id,
                PlanStore.STATUS_APPROVED,
                // 同一时刻仅应有一份等待批准：其余待确认计划随之作废
                abandonOtherPending = true
            )
        }
        PlanStore.save(appContext, false)
        planMode = false
        FileLog.log(
            "plan",
            "用户批准计划 " + (target?.id ?: "-") + "（" + (target?.title ?: "无标题") + "）：关闭计划模式并开始执行"
        )
        // programmatic = true：该「用户消息」由 App 代替用户发送（按钮点击），并非用户手写。
        // 高危删除的授权码仅认可真实用户消息，代发消息中的授权码一律不识别：否则「批准计划」将成为
        // 可顺带批准删除的漏洞（令牌必须绑定最近一条真实 user 消息）。
        sendNow(if (target != null) approvePlanText(target) else PLAN_APPROVE_TEXT, true)
    }

    /**
     * 同意执行时发出去的那条 user 消息：带上所选计划的正文。
     *
     * 为什么必须带上：一个会话里可能存了好几份计划，只发一句「按计划开始执行」，
     * 模型只能按它最后写的那份做；把标题 / 步骤 / 备注原样带上，选哪份就执行哪份。
     * 为什么不用 system 发：这本来就是用户说的话，进历史后还要能回看、回滚、编辑重发。
     */
    private fun approvePlanText(plan: PlanDoc): String = buildString {
        // 气泡中仅保留该句：可直观确认同意执行的是哪一份计划
        append(PLAN_APPROVE_TEXT).append("：").append(plan.title.ifBlank { "未命名计划" })
        append("\n\n").append(PLAN_DETAIL_MARKER)
        append("\n计划标题：").append(plan.title)
        append("\n步骤：")
        plan.steps.forEachIndexed { index, step ->
            append("\n").append(index + 1).append(". ").append(step)
        }
        if (plan.note.isNotEmpty()) append("\n备注：").append(plan.note)
    }

    /**
     * 「继续修改计划」：只把计划留在页面上。
     *
     * 有意什么都不做：用户点这个是为了回到对话里补充要求，这里替他发消息反而会把「再看看」变成一次发送。
     */
    fun continuePlanEdit(planId: String) {
        // 点击后即视为该计划已被消费：两个按钮置灰，不可再次点击「同意执行」
        if (plans.none { it.id == planId }) return
        plans = PlanStore.markPlan(appContext, currentSessionId(), planId, PlanStore.STATUS_EDITED)
        FileLog.log("plan", "用户选择继续修改计划 " + planId + "，该份标记为已处理")
        // 其余操作均不执行：页面自行负责返回主页，代替用户发送消息会将「再看看」变为一次发送
    }

    /** 暂停目标（/goal pause）：暂停后本轮结束即停止自动续跑。 */
    fun pauseGoal() = applyGoalPhase(GoalPhase.PAUSED)

    /** 继续目标（/goal resume）。 */
    fun resumeGoal() = applyGoalPhase(GoalPhase.ACTIVE)

    /**
     * 改写目标正文（/goal edit <文本>）。
     *
     * 只改 objective：轮次与阶段是进度，改一句话不该把它们清零。
     * 那会让「已经跑到第 5 轮」变成重新开始。
     */
    fun editGoal(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = goal ?: return
        val next = current.copy(objective = trimmed)
        goal = next
        GoalStore.save(appContext, currentSessionId(), next)
        FileLog.log("goal", "目标正文改为：" + trimmed.take(60))
    }

    /** 删除当前会话的目标：内存与盘上一起清，聊天记录不动。 */
    fun clearGoal() {
        if (goal == null) return
        GoalStore.clear(appContext, currentSessionId())
        goal = null
        FileLog.log("goal", "已删除当前会话的目标")
    }

    /** 改阶段并落盘：改完 [goal] 立刻变，输入框那个只读图标靠它判断要不要亮。 */
    private fun applyGoalPhase(phase: GoalPhase) {
        val current = goal ?: return
        if (current.phase == phase) return
        val next = current.copy(phase = phase)
        goal = next
        GoalStore.save(appContext, currentSessionId(), next)
        FileLog.log("goal", "目标阶段改为 " + phase.label())
    }
}
