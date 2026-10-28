package com.zmf.agent.tools

import android.content.Context
import android.os.Environment
import android.os.Process
import com.zmf.agent.core.log.AuditLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.core.terminal.TerminalEnv
import com.zmf.agent.tools.file.TrashStore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 高危操作的确定性闸门。
 *
 * 定位：
 * - **先于档位判定**：完全档只豁免「越界」，不豁免「高危」，后果不因档位而减轻；
 * - **三分级**：`SAFE` 直接放行（工作区内的文件、深层目录），`CONFIRM` 需一次授权码，
 *   `FORBIDDEN` 永久拒绝（存储根 / 工作区根 / App 核心数据 / Ubuntu 根）；
 * - **不进入授权流程的根级退化**：规范化后的目标若等于或包含根级位置，直接
 *   `CRITICAL_PATH_ERROR` 永久拒绝：那是「路径解析错了」，用户授权的对象根本不是它，
 *   给令牌没有意义（拼路径出错会一路删到整盘，只能靠这道防线兜住）；
 * - **一次性授权码**：与 OAuth 2.0 授权码单次使用同构。码只认「会话里最近一条真实 user 消息」
 *   （`programmatic=false`），逐字出现即可；assistant 内容、模型复述、参数里传的码都不参与判定。
 *
 * 为什么令牌只放内存：5 分钟 TTL 已覆盖「用户当场确认」的时间尺度；进程重启后旧码全部失效，
 * 比落盘更安全（落盘反而要处理「重启前签发的码在重启后仍有效」这种不该存在的状态）。
 *
 * 终端部分只是**文本层最佳努力**：变量、eval、脚本内部仍可绕过文本检查，
 * 真正的兜底是 Android 文件权限与 PRoot rootfs 的可重建性。提示词与拒绝文案都必须如实说明这一点。
 */
object HighRiskGate {

    // ---- 分级 ----

    /** 风险分级：直接放行 / 需一次性授权码 / 永久拒绝。 */
    enum class Risk { SAFE, CONFIRM, FORBIDDEN }

    /**
     * 文本层对命令的覆盖度。
     *
     * `PARTIAL` = 命令里出现了变量、脚本、管道喂解释器这类**静态看不见**的构造；
     * 此时任何被判成 `SAFE` 的删除都降一档（`SAFE → CONFIRM`）：「没看出来」不等于「安全」。
     */
    enum class Coverage { FULL, PARTIAL }

    /**
     * 一次判定的结论。
     *
     * [target] 是**指纹用的规范化目标**（文件工具用 canonical 路径，终端用规范化后的 guest 路径），
     * 不是给人看的展示串，展示用 [summary]。
     * [hostFile] 是终端删除操作数落到宿主后的 File（供规模探测用）；通配/虚拟目标为 null。
     */
    data class RiskFinding(
        val risk: Risk,
        val rule: String,
        val summary: String,
        val target: String,
        val hostFile: File? = null
    )

    /** 一次判定的结论：[message] 是可直接交给模型的拒绝文案（放行时为 null），[token] 只在 CONFIRM 档签发。 */
    data class Decision(
        val allowed: Boolean,
        val risk: Risk,
        val message: String? = null,
        val rule: String = "",
        val token: String? = null,
        val coverage: Coverage = Coverage.FULL
    )

    // ---- 固定文案前缀（机器可读，严格可解析）----

    private const val PREFIX_BLOCKED = "[sandbox: high-risk action blocked]"

    private const val PREFIX_FORBIDDEN = "[sandbox: high-risk action blocked (non-overridable)]"

    // ---- 终端删除规模阈值 ----

    /** 条目数阈值：超过就认为「不是常规清理」。 */
    private const val SCALE_ENTRY_LIMIT = 2_000

    /** 体积阈值：200 MB。 */
    private const val SCALE_BYTE_LIMIT = 200L * 1024L * 1024L

    /** 探测预算：超过就按「较大规模」处理，绝不无限扫盘。 */
    private const val SCALE_PROBE_BUDGET_MS = 300L

    private const val SCALE_PROBE_BUDGET_NS = SCALE_PROBE_BUDGET_MS * 1_000_000L

    /**
     * Ubuntu 里「家目录级」的根：`/root` 与 `/home`。
     *
     * 这两个目录下的**一级**目录很可能就是一整份工程（`/root/proj`、`/home/proj`），
     * 删掉它等于删掉整份代码，所以一律升确认档：与「深在这个目录之下」的常规清理区分开。
     */
    private val HOME_LIKE_ROOTS = setOf("root", "home")

    // ---- 授权码 ----

    /** 授权码格式：`AUTH-` + 12 位大写十六进制。不可猜测性由 SecureRandom 保证。 */
    private val TOKEN_REGEX = Regex("AUTH-[0-9A-F]{12}")

    /** 有效期：5 分钟。与 OAuth 授权码的短时效口径一致。 */
    const val TOKEN_TTL_MS = 5L * 60L * 1000L

    private val random = SecureRandom()

    private data class Pending(
        val token: String,
        val sessionId: String,
        val fingerprint: String,
        val tool: String,
        val op: String,
        val summary: String,
        val issuedAt: Long
    )

    /** 未使用的授权码：key = 码本身。 */
    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * 已用掉的码：key = 码本身，value = 用掉的时间。
     *
     * 为什么需要单独记：码用掉后要从 [pending] 里删，否则单次使用就无从谈起；
     * 但删了之后同一个码再来，就只能落到 `pending[code] == null → continue`，
     * 最终报成 [Approval.NO_CODE]「没有授权码」：用户在重发旧码时看到的是
     * 「你没有给码」，而事实是「给了、用过了」。记一份短期「用过的码」既保住单次语义，
     * 又能给出准确原因。
     * 生命周期跟 [pending] 一样按 TTL 清，表不会随会话无限增长。
     */
    private val used = ConcurrentHashMap<String, Long>()

    // ---- 环境快照 ----

    /**
     * 判定所需的宿主路径集合。
     *
     * 单独抽出来的理由：回归用例要用「假根」构造路径（不依赖真实设备目录），
     * 判定逻辑本身必须是纯函数：给什么环境就按什么环境判。
     */
    class SandboxEnv(
        val filesDir: File,
        val appPrivateRoot: File,
        val externalRoot: File,
        val externalFilesDir: File,
        val rootfsDir: File,
        val packageName: String,
        val userId: Int
    ) {
        val canonicalFilesDir: File by lazy { canonical(filesDir) }
        val canonicalAppPrivateRoot: File by lazy { canonical(appPrivateRoot) }
        val canonicalExternalRoot: File by lazy { canonical(externalRoot) }
        val canonicalExternalFilesDir: File by lazy { canonical(externalFilesDir) }
        val canonicalRootfs: File by lazy { canonical(rootfsDir) }

        private fun canonical(file: File): File =
            PathGate.canonicalOrNearestOrNull(file) ?: file.absoluteFile

        companion object {
            fun of(context: Context): SandboxEnv {
                val app = context.applicationContext
                val files = app.filesDir
                val appPrivate = files.parentFile ?: files
                val ext = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
                    ?: File("/storage/emulated/0")
                val extFiles = app.getExternalFilesDir(null)
                    ?: File(ext, "Android/data/" + app.packageName + "/files")
                return SandboxEnv(
                    filesDir = files,
                    appPrivateRoot = appPrivate,
                    externalRoot = ext,
                    externalFilesDir = extFiles,
                    rootfsDir = TerminalEnv.rootfsDir(app),
                    packageName = app.packageName,
                    userId = Process.myUid() / 100000
                )
            }
        }
    }

    @Volatile
    private var boundEnv: SandboxEnv? = null

    /** 绑定环境：由 [ToolContext] 每次构造时刷新。 */
    fun bind(context: Context) {
        boundEnv = SandboxEnv.of(context)
    }

    /** 回归用例注入假环境用。 */
    fun bind(env: SandboxEnv) {
        boundEnv = env
    }

    private fun envOf(ctx: ToolContext): SandboxEnv = boundEnv ?: SandboxEnv.of(ctx.context)

    // ---- 删除能力总开关 ----

    /**
     * 启动自检的 CRITICAL 用例失败时置为非 null：删除能力整体停用。
     *
     * 为什么停用是合理的：判定函数错了，任何一条「放行」都不可信；停用比放行安全，
     * 而且用户能在设置页看见原因，重启（自检通过）即可恢复。
     */
    @Volatile
    private var deletionDisabledReason: String? = null

    /** 供设置页与自检读取；null = 删除能力正常。 */
    fun deletionDisabled(): String? = deletionDisabledReason

    /** 自检失败时调用；只影响进程内，重启重跑自检。 */
    fun disableDeletion(reason: String) {
        deletionDisabledReason = reason
    }

    /** 自检开始前恢复默认（避免上一轮失败影响本轮用例）。 */
    fun enableDeletion() {
        deletionDisabledReason = null
    }

    // =========================================================================
    // 公开入口
    // =========================================================================

    /**
     * 文件工具删除前的判定。SAFE 直接放行；CONFIRM 需要有效授权码；FORBIDDEN 永久拒绝。
     *
     * @param target 已解析出的目标（工作区内可能是 SAF 推不出的 File；SAF 工作区请传 [isDirectory] 并用
     *               [checkWorkspaceDelete]）
     * @param forceConfirm 该位置进不了回收站（不同卷 / rename 失败 / SAF 超预算）时置 true：
     *                     安全区也升为确认档：「不可恢复」必须让用户知道。
     */
    fun checkDelete(
        ctx: ToolContext,
        target: File,
        isDirectory: Boolean,
        forceConfirm: Boolean = false
    ): Decision {
        deletionDisabledReason?.let { reason ->
            audit(ctx, "deny", "delete_file", "delete", "删除能力已停用", target.absolutePath, null, "non-overridable")
            return Decision(false, Risk.FORBIDDEN, disabledText(reason), "删除能力已停用")
        }
        val env = envOf(ctx)
        val finding = classifyDelete(env, ctx.workspaceRoot, target, isDirectory)
        val findings = if (forceConfirm) listOf(fallbackFinding(finding)) else listOf(finding)
        return decide(ctx, "delete_file", "delete", findings)
    }

    /**
     * SAF 工作区内的删除：整棵授权树都是用户自己选的，深度 0（树根）之外直接放行。
     *
     * 为什么不做 canonical：SAF 目录在 File 视角根本不存在，host 路径可能推不出来；
     * 而「用户亲手授权的工作区」本身就是合法的能力路径。
     */
    fun checkWorkspaceDelete(
        ctx: ToolContext,
        relPath: String,
        forceConfirm: Boolean = false
    ): Decision {
        deletionDisabledReason?.let { reason ->
            audit(ctx, "deny", "delete_file", "delete", "删除能力已停用", relPath, null, "non-overridable")
            return Decision(false, Risk.FORBIDDEN, disabledText(reason), "删除能力已停用")
        }
        if (relPath.isBlank()) {
            val finding = RiskFinding(
                Risk.FORBIDDEN,
                "CRITICAL_PATH_ERROR 目标是工作区根，不能删除",
                "工作区根",
                "workspace:"
            )
            return decide(ctx, "delete_file", "delete", listOf(finding))
        }
        if (!forceConfirm) return Decision(true, Risk.SAFE)
        val finding = RiskFinding(
            Risk.CONFIRM,
            "这个位置没法进回收站（系统授权目录或规模超出复制预算），删除将不可恢复",
            relPath,
            "saf:" + relPath
        )
        return decide(ctx, "delete_file", "delete", listOf(finding))
    }

    /**
     * 终端命令里的删除/截断语义判定。
     *
     * 与 [ToolPolicy.checkTerminal] 的顺序：**本函数先跑**（它覆盖完全档），再跑 ToolPolicy
     * （非删除类危险命令与外部挂载点）。两者的拒绝前缀不同，模型能区分「高危」与「越界」。
     */
    fun checkTerminal(ctx: ToolContext, command: String, cwd: String): Decision {
        val env = envOf(ctx)
        val coverage = coverageOf(command)
        val findings = withTrash(env, command, classifyCommand(env, ctx.workspaceRoot, command, cwd))
        if (findings.isEmpty()) return Decision(true, Risk.SAFE, coverage = coverage)
        deletionDisabledReason?.let { reason ->
            audit(ctx, "deny", "terminal", "delete", "删除能力已停用", command.take(160), null, "non-overridable")
            return Decision(false, Risk.FORBIDDEN, disabledText(reason), "删除能力已停用", coverage = coverage)
        }
        val effective = probeScale(downgrade(findings, coverage), command)
        return decide(ctx, "terminal", "delete", effective, coverage)
    }

    /**
     * Shizuku 通道（Android shell，宿主视角）的删除语义判定。
     *
     * 与 [checkTerminal] 的区别：Shizuku 命令里的路径就是**宿主路径**（`/sdcard` 是真正的
     * 共享存储、`/system` 是真正的系统分区），不需要 guest→host 映射；按原样 canonical 即可。
     *
     * 拦截口径：这条通道的 uid 是 shell，比 App 自己权限更大；一旦放行删除，
     * 它就成了「绕过回收站与删除闸门」的后门。所以**任何删除语义一律 FORBIDDEN 且不签发令牌**
     * （`rm/rmdir/unlink/shred/find -delete/xargs rm/pm clear/pm uninstall/mv`）。
     * `mv` 等价于删除：源端消失且不进回收站。
     */
    fun checkHostCommand(ctx: ToolContext, command: String): Decision {
        val env = envOf(ctx)
        val coverage = coverageOf(command)
        val findings = withTrash(
            env,
            command,
            classifyCommand(env, ctx.workspaceRoot, command, "/", hostMode = true)
        )
        if (findings.isEmpty()) return Decision(true, Risk.SAFE, coverage = coverage)
        deletionDisabledReason?.let { reason ->
            audit(ctx, "deny", "shizuku", "delete", "删除能力已停用", command.take(160), null, "non-overridable")
            return Decision(false, Risk.FORBIDDEN, disabledText(reason), "删除能力已停用", coverage = coverage)
        }
        val summary = findings.map { it.summary }.distinct().joinToString("；").take(240)
        audit(ctx, "deny", "shizuku", "delete", findings.first().rule, summary, null, "non-overridable")
        return Decision(
            false,
            Risk.FORBIDDEN,
            hostForbiddenText(summary),
            "宿主通道删除语义不可覆盖",
            coverage = coverage
        )
    }

    /** 没有回收站时把安全判定升成确认档；本来就 FORBIDDEN 的保持不变。 */
    private fun fallbackFinding(finding: RiskFinding): RiskFinding {
        if (finding.risk == Risk.FORBIDDEN) return finding
        return RiskFinding(
            Risk.CONFIRM,
            "该位置没有可用的回收站（不同卷或无法重命名），删除后不可恢复",
            finding.summary,
            finding.target,
            finding.hostFile
        )
    }

    // ---- 覆盖度 ----

    /**
     * 文本层覆盖度：命令里出现**静态看不见**的构造就标 PARTIAL。
     *
     * 一旦 PARTIAL，任何被判成 SAFE 的删除都降为 CONFIRM：「没看出来」不等于「安全」。
     * 这些构造的共同点是「真正的删除对象只有运行时才知道」：变量、命令替换、
     * 脚本内部、上游输出、解码后的载荷。
     */
    fun coverageOf(command: String): Coverage {
        if (command.isBlank()) return Coverage.FULL
        if (PARTIAL_PATTERNS.any { it.containsMatchIn(command) }) return Coverage.PARTIAL
        return Coverage.FULL
    }

    private val PARTIAL_PATTERNS = listOf(
        // $变量 / ${变量}（$HOME、$PWD 虽被展开，但仍按保守口径标 PARTIAL）
        Regex("""\$\{?[A-Za-z_(]"""),
        // 反引号命令替换
        Regex("""`"""),
        // 脚本内联：sh -c / bash -c
        Regex("""\b(?:sh|bash|zsh|dash)\b[^|;&]*\s-c\b"""),
        Regex("""\beval\b"""),
        Regex("""\bxargs\b"""),
        // find -delete：删除范围由 find 的遍历决定
        Regex("""\bfind\b[^|;&]*\s-delete\b"""),
        // 管道喂给解释器
        Regex("""\|\s*(?:sh|bash|zsh|dash|python[0-9.]*|perl|ruby|node)\b"""),
        // base64 解码后的内容交给下游
        Regex("""\bbase64\b[^|;&]*\s(?:-d|--decode)\b""")
    )

    /** PARTIAL 时把 SAFE 降成 CONFIRM（FORBIDDEN 保持）。 */
    private fun downgrade(findings: List<RiskFinding>, coverage: Coverage): List<RiskFinding> {
        if (coverage == Coverage.FULL) return findings
        return findings.map { finding ->
            if (finding.risk != Risk.SAFE) {
                finding
            } else {
                finding.copy(
                    risk = Risk.CONFIRM,
                    rule = "命令含无法静态判定的构造（变量/脚本/命令替换/管道/解码），安全档降为确认：" + finding.rule
                )
            }
        }
    }

    // ---- 终端删除规模阈值 ----

    /**
     * 对「删除动词 + 单一明确目标」做一次有界规模探测。
     *
     * 为什么需要：终端删除没法进回收站（命令整条交给 bash，应用层插不进 rename），
     * 工作区内 `rm -rf` 原本直接 SAFE，而整盘被误删正是这种形态。规模超过阈值就升 CONFIRM。
     *
     * 探测失败/超时**不降档**（按「较大规模」处理）：宁可多问一次，也不放过一次不可恢复的大删除。
     */
    private fun probeScale(findings: List<RiskFinding>, command: String): List<RiskFinding> {
        if (findings.size != 1) return findings
        val finding = findings.first()
        if (finding.risk != Risk.SAFE) return findings
        val target = finding.hostFile ?: return findings
        if (containsWildcard(command) || command.contains('$')) return findings
        val (level, detail) = scaleOf(target)
        val rule = when (level) {
            ScaleLevel.SMALL -> return findings
            ScaleLevel.LARGE -> "本次删除规模较大（" + detail + "），超出常规清理"
            ScaleLevel.UNKNOWN -> "本次删除规模无法在 " + (SCALE_PROBE_BUDGET_MS) + " ms 内探测完，按较大规模处理"
        }
        return findings.map { it.copy(risk = Risk.CONFIRM, rule = rule + "：" + it.rule) }
    }

    private enum class ScaleLevel { SMALL, LARGE, UNKNOWN }

    /**
     * 有界扫描：不跟随软链接，条目数/字节数一到阈值立刻返回，超时按 UNKNOWN。
     *
     * 为什么不调 `du`：那要起进程、解析输出、还得处理 exit code；这里只需要一个数量级的判断，
     * 直接在 Java 层走一遍目录树可控得多（且有硬超时）。
     */
    private fun scaleOf(target: File): Pair<ScaleLevel, String> {
        val deadline = System.nanoTime() + SCALE_PROBE_BUDGET_NS
        var entries = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(target)
        while (stack.isNotEmpty()) {
            if (System.nanoTime() > deadline) return ScaleLevel.UNKNOWN to ""
            val current = stack.removeLast()
            entries++
            if (PathGate.isSymlink(current)) {
                // 软链接不跟随：只算一个条目（与 SafeDelete 的口径一致）
            } else if (current.isDirectory) {
                current.listFiles()?.let { children -> stack.addAll(children) }
            } else {
                bytes += runCatching { current.length() }.getOrDefault(0L)
            }
            if (entries > SCALE_ENTRY_LIMIT) {
                return ScaleLevel.LARGE to ("条目数超过 " + SCALE_ENTRY_LIMIT)
            }
            if (bytes > SCALE_BYTE_LIMIT) {
                return ScaleLevel.LARGE to ("总体积超过 " + (SCALE_BYTE_LIMIT / (1024L * 1024L)) + " MB")
            }
        }
        return ScaleLevel.SMALL to ""
    }

    // ---- 回收站是 Agent 的禁区 ----

    /** 任意卷上的回收站路径判定（按包名匹配，不依赖当前绑定的环境）。 */
    fun isTrashPath(path: String, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        val marker = "/Android/data/" + packageName + "/files/" + TrashStore.DIR_NAME
        val normalized = path.replace('\\', '/')
        return normalized.endsWith(marker) || normalized.contains(marker + "/")
    }

    /** 按当前绑定的环境判回收站路径；还没绑定时一律返回 false。 */
    fun isTrashPath(file: File): Boolean {
        val env = boundEnv ?: return false
        return isTrashPath(file.path, env.packageName)
    }

    /**
     * 回收站判定：**字面路径与 canonical 路径都看**。
     *
     * 只看字面路径会漏掉「工作区里放一个软链接指向 `.trash`」这种绕法：
     * Agent 在终端里 `ln -s` 之后用 `read_file` 走链接，`file.path` 里根本没有回收站字样，
     * 于是读面被绕开。删除与写入两面本来就做了 canonical（`classifyDelete` /
     * `protectedWriteReason`），这里把读面补齐，三面口径才一致。
     */
    fun isTrashPathDeep(file: File, packageName: String): Boolean {
        if (packageName.isEmpty()) return false
        if (isTrashPath(file.path, packageName)) return true
        val canonical = PathGate.canonicalPathOrNull(file) ?: return false
        return isTrashPath(canonical, packageName)
    }

    /**
     * 命令文本里直接出现了回收站路径 → 永久拒绝。
     *
     * 为什么连「读」也拦：回收站里放的是用户刚删掉的内容，Agent 能看见就等于绕过了一次删除；
     * 而清空/恢复必须坚持「只有删除主体本人能做」（主体分离）。
     */
    private fun withTrash(
        env: SandboxEnv,
        command: String,
        findings: List<RiskFinding>
    ): List<RiskFinding> {
        val text = command.replace('\\', '/')
        val markers = listOf(
            "/Android/data/" + env.packageName + "/files/" + TrashStore.DIR_NAME,
            "/data/data/" + env.packageName + "/files/" + TrashStore.DIR_NAME
        )
        val hit = markers.firstOrNull { text.contains(it) } ?: return findings
        val finding = RiskFinding(
            Risk.FORBIDDEN,
            "CRITICAL_PATH_ERROR 命令触碰了回收站（" + hit + "）",
            "回收站",
            "trash:" + env.packageName
        )
        return findings + finding
    }


    /**
     * 写/覆盖前的**绝对禁区**检查（任何档位都拒绝）。
     *
     * 返回 null = 不在禁区；非 null = 直接可以交给模型的拒绝文案。
     */
    fun protectedWriteReason(target: File): String? {
        val env = boundEnv ?: return null
        val canon = PathGate.canonicalOrNearestOrNull(target) ?: return null
        val reason = protectedReason(env, canon) ?: return null
        return forbiddenText(reason, canon.path)
    }

    // =========================================================================
    // 判定核心（纯函数：给环境 + 路径就出结论，回归用例直接调）
    // =========================================================================

    /**
     * 文件工具删除前的判定入口：返回风险级 + 指纹用的规范化目标，不签发令牌、不弹窗。
     */
    fun classifyDelete(
        env: SandboxEnv,
        workspaceRoot: File,
        target: File,
        isDirectory: Boolean
    ): RiskFinding {
        val canon = PathGate.canonicalOrNearestOrNull(target) ?: target.absoluteFile
        return classifyCanonical(env, workspaceRoot, canon, isDirectory, target.absolutePath)
    }

    private fun classifyCanonical(
        env: SandboxEnv,
        workspaceRoot: File,
        canon: File,
        isDirectory: Boolean,
        label: String
    ): RiskFinding {
        val workspace = PathGate.canonicalOrNearestOrNull(workspaceRoot) ?: workspaceRoot.absoluteFile

        // ① 工作区根：删它等于删掉整个工作区，永久拒绝
        if (canon.path == workspace.path) {
            return RiskFinding(
                Risk.FORBIDDEN,
                "CRITICAL_PATH_ERROR 删除目标是工作区根（" + canon.path + "）",
                label,
                canon.path
            )
        }
        // ② 工作区内：这是 Agent 自己的地盘，直接放行（文件/目录都不再问）
        if (PathGate.depthOf(workspace, canon) > 0) {
            return RiskFinding(Risk.SAFE, "工作区内", label, canon.path)
        }
        // ③ 根级退化 / 包含关键根 / 系统根
        degenerateReason(env, workspace, canon)?.let { reason ->
            return RiskFinding(Risk.FORBIDDEN, reason, label, canon.path)
        }
        // ④ App 核心数据绝对禁区
        protectedReason(env, canon)?.let { reason ->
            return RiskFinding(Risk.FORBIDDEN, reason, label, canon.path)
        }
        // ⑤ 按所在区判深度
        return zoneFinding(env, workspace, canon, isDirectory, label)
    }

    private fun zoneFinding(
        env: SandboxEnv,
        workspace: File,
        canon: File,
        isDirectory: Boolean,
        label: String
    ): RiskFinding {
        val safe = { rule: String -> RiskFinding(Risk.SAFE, rule, label, canon.path) }
        val confirm = { rule: String -> RiskFinding(Risk.CONFIRM, rule, label, canon.path) }

        if (!isDirectory) return safe("单个文件")

        // App 外部私有目录（当前工作区之外）：另一个工作区 / 上传缓存，一律要确认
        if (PathGate.depthOf(env.canonicalExternalFilesDir, canon) > 0) {
            return confirm("目标是 App 外部私有目录里当前工作区之外的目录，可能是另一个工作区")
        }

        // Ubuntu 根文件系统
        val rootfsDepth = PathGate.depthOf(env.canonicalRootfs, canon)
        if (rootfsDepth > 0) {
            if (rootfsDepth == 1) return confirm("目标是 Ubuntu 根文件系统下的一级目录，影响整个环境")
            if (rootfsDepth == 2 && canon.parentFile?.name in HOME_LIKE_ROOTS) {
                return confirm(
                    "目标是 Ubuntu 家目录 /" + canon.parentFile?.name + " 下的一级目录，可能装着整份工程"
                )
            }
            return safe("Ubuntu 环境内的深层目录")
        }

        // App 私有目录（filesDir 之外的 cache/databases 等已被禁区覆盖，这里兜结构层级）
        val appDepth = PathGate.depthOf(env.canonicalAppPrivateRoot, canon)
        if (appDepth > 0) {
            if (appDepth <= 2) return confirm("目标是 App 私有目录下的一级目录")
            return safe("App 私有目录内的深层目录")
        }

        // 共享存储
        val extDepth = PathGate.depthOf(env.canonicalExternalRoot, canon)
        if (extDepth > 0) {
            if (extDepth == 1) return confirm("目标是共享存储的一级目录（DCIM/Download 这类），范围过大")
            return safe("共享存储内的深层目录")
        }

        // 其它位置：目录一律确认，文件已在上面的 safe 分支
        return confirm("目标是工作区与已知区之外的目录，范围不可枚举")
    }

    /**
     * 根级退化判定。
     *
     * 两类都算：
     * 1. **等于**根：`/`、`/data`、存储根、工作区根、App 私有目录、Ubuntu 根、`/root`…
     * 2. **包含**根：目标是一个父目录，删下去会连带端掉工作区 / App 数据 / Ubuntu 环境。
     *
     * 这一类**不进入授权流程**：解析结果本身失去意义，用户授权的对象不是它。
     */
    private fun degenerateReason(env: SandboxEnv, workspace: File, canon: File): String? {
        val path = canon.path
        val systemRoots = setOf(
            "/", "/data", "/system", "/vendor", "/product", "/apex",
            "/sdcard", "/storage", "/mnt", "/proc", "/sys", "/dev", "/data/media"
        )
        if (path in systemRoots) {
            return "CRITICAL_PATH_ERROR 删除目标解析后落在系统/存储根级位置（" + path + "）"
        }

        val exact = listOf(
            "共享存储根" to env.canonicalExternalRoot,
            "工作区根" to workspace,
            "App 私有目录" to env.canonicalAppPrivateRoot,
            "App 文件目录" to env.canonicalFilesDir,
            "App 外部私有目录" to env.canonicalExternalFilesDir,
            "Ubuntu 根文件系统" to env.canonicalRootfs,
            "Ubuntu 家目录 /root" to File(env.canonicalRootfs, "root")
        )
        for ((label, root) in exact) {
            if (canon.path == root.path) {
                return "CRITICAL_PATH_ERROR 删除目标解析后等于" + label + "（" + path + "）"
            }
        }

        // 目标是某个关键根的祖先：删它会连带端掉一整个区域
        val critical = listOf(
            "工作区" to workspace,
            "App 数据" to env.canonicalFilesDir,
            "App 私有目录" to env.canonicalAppPrivateRoot,
            "App 外部私有目录" to env.canonicalExternalFilesDir,
            "Ubuntu 环境" to env.canonicalRootfs
        )
        for ((label, root) in critical) {
            if (PathGate.isSameOrAncestor(canon, root) && canon.path != root.path) {
                return "CRITICAL_PATH_ERROR 删除目标包含了" + label + "（" + root.path + "），影响面不可估"
            }
        }

        // Ubuntu 根文件系统顶层系统目录
        val rootfsTop = setOf(
            "bin", "sbin", "lib", "lib64", "usr", "etc", "var", "opt",
            "boot", "dev", "proc", "sys", "run", "srv", "media", "mnt"
        )
        if (PathGate.depthOf(env.canonicalRootfs, canon) == 1 && canon.name in rootfsTop) {
            return "CRITICAL_PATH_ERROR 目标是 Ubuntu 根文件系统的系统目录（" + path + "）"
        }
        return null
    }

    /**
     * App 核心数据绝对禁区（任何档位都拒绝文件工具直写/直删）。
     *
     * 用途：会话、计划、待办、记忆、审计日志这些只能走各自的结构化接口；
     * 绕过接口直接改文件正是「能力被数据流持有」的反面。
     */
    private fun protectedReason(env: SandboxEnv, canon: File): String? {
        // 回收站：Agent 对它是黑区（主体分离）：能删就能「删完再清空」，恢复价值归零
        if (isTrashPath(canon.path, env.packageName)) {
            return "目标位于回收站（" + canon.path + "），Agent 不能读取、恢复或清空回收站；请让用户在设置里操作"
        }
        val exact = listOf(
            "App 私有数据目录" to env.canonicalAppPrivateRoot,
            "App 文件目录" to env.canonicalFilesDir,
            "App 外部私有目录" to env.canonicalExternalFilesDir
        )
        for ((label, root) in exact) {
            if (canon.path == root.path) return "目标位于" + label + "（" + canon.path + "），不允许直接写/删"
        }
        val prefixes = listOf(
            "共享偏好 shared_prefs" to File(env.canonicalAppPrivateRoot, "shared_prefs"),
            "数据库 databases" to File(env.canonicalAppPrivateRoot, "databases"),
            "App 偏好 app_flutter" to File(env.canonicalAppPrivateRoot, "app_flutter"),
            "App 数据 appdata" to File(env.canonicalFilesDir, "appdata"),
            "共享偏好（PRoot 视角）" to File(env.canonicalFilesDir, "shared_prefs"),
            "记忆内核 mingmem" to File(env.canonicalFilesDir, "mingmem"),
            "App 运行日志" to File(env.canonicalExternalFilesDir, "logs")
        )
        for ((label, root) in prefixes) {
            val base = root.path.trimEnd(File.separatorChar)
            if (canon.path == base || canon.path.startsWith(base + File.separatorChar)) {
                return "目标位于" + label + "（" + canon.path + "），不允许直接写/删"
            }
        }
        return null
    }

    // =========================================================================
    // 终端命令分析（文本层最佳努力）
    // =========================================================================

    private val SEGMENT_SPLIT = Regex("""(?:&&|\|\||;|\||\n)""")

    private val DELETE_VERBS = setOf("rm", "rmdir", "unlink", "shred")

    private val ROOTFS_TOP = setOf(
        "bin", "sbin", "lib", "lib64", "usr", "etc", "var", "opt",
        "boot", "dev", "proc", "sys", "run", "srv", "media", "mnt"
    )

    /**
     * 扫描一条 shell 命令，返回全部需关注的目标。
     *
     * 覆盖：`rm/rmdir/unlink/shred`、`find ... -delete`、`find ... -exec rm`、
     * `sh -c "..."` 嵌套、`xargs rm`、`git clean`。
     * 不覆盖（如实承认）：变量展开后的运行时值、脚本文件内部、eval、子 shell 构造。
     */
    fun classifyCommand(
        env: SandboxEnv,
        workspaceRoot: File,
        command: String,
        cwd: String,
        hostMode: Boolean = false
    ): List<RiskFinding> {
        if (command.isBlank()) return emptyList()
        val workspace = PathGate.canonicalOrNearestOrNull(workspaceRoot) ?: workspaceRoot.absoluteFile
        val out = ArrayList<RiskFinding>(4)
        var current = normalizeGuest(cwd.trim().ifEmpty { "/root" }, "/root")

        for (rawSegment in command.split(SEGMENT_SPLIT)) {
            val segment = rawSegment.trim()
            if (segment.isEmpty()) continue
            val words = wordsOf(segment)
            if (words.isEmpty()) continue

            // 去掉 sudo / busybox 前缀（busybox 后面仍可能是 rm）
            var index = 0
            while (index < words.size && (words[index] == "sudo" || words[index] == "busybox")) index++
            if (index >= words.size) continue

            val verb = File(words[index]).name

            // 内联 cd：只更新追踪目录，不产生判定
            if (verb == "cd") {
                val target = words.getOrNull(index + 1) ?: "/root"
                current = normalizeGuest(target, current)
                continue
            }

            when (verb) {
                in DELETE_VERBS -> {
                    val recursive = verb == "rmdir" || words.any { w ->
                        w.startsWith("-") && !w.startsWith("--") && (w.contains('r') || w.contains('R'))
                    } || words.contains("--recursive")
                    val operands = words.drop(index + 1).filter { !it.startsWith("-") }
                    for (operand in operands) {
                        out += classifyGuestOperand(
                            env, workspace, current, operand, recursive, verb + " " + operand, hostMode
                        )
                    }
                }

                "mv" -> {
                    // 宿主通道里 mv 等价于删除：源端消失、且不会进回收站。
                    // 只判第一个操作数（源）；第二个是目的端，不是被删对象。
                    if (hostMode) {
                        val operand = words.drop(index + 1).firstOrNull { !it.startsWith("-") }
                        if (!operand.isNullOrBlank()) {
                            out += classifyGuestOperand(
                                env, workspace, current, operand, true, "mv " + operand, true
                            )
                        }
                    }
                }

                "find" -> {
                    val delete = words.contains("-delete") ||
                        (words.contains("-exec") && words.dropWhile { it != "-exec" }.any { File(it).name == "rm" })
                    if (delete) {
                        val roots = words.drop(index + 1).takeWhile { !it.startsWith("-") && it != "(" }
                        for (root in roots.ifEmpty { listOf(".") }) {
                            // `find . -delete` 删的是当前目录的**内容**（find 删不掉 "." 自身），
                            // 所以按通配删除判：/root 下 → CONFIRM，工作区内 → SAFE，根级 → FORBIDDEN。
                            // 若按「删掉 /root 本身」判会误升成 FORBIDDEN，与 `rm -rf *` 的定档不一致。
                            val sameAsCwd = root == "." ||
                                normalizeGuest(root, current) == normalizeGuest(current, "/root")
                            if (sameAsCwd) {
                                out += classifyWildcard(
                                    env, workspace, current, "find " + root + " -delete", hostMode
                                )
                            } else {
                                out += classifyGuestOperand(
                                    env, workspace, current, root, false, "find " + root + " -delete", hostMode
                                )
                            }
                        }
                    }
                }

                "truncate" -> {
                    // 截断成 0 = 清空文件内容；按「删除这个文件」的同一套规则判
                    val sizeIndex = words.indexOf("-s")
                    val size = words.getOrNull(sizeIndex + 1)
                    val toZero = size == "0" || size == "+0"
                    if (toZero) {
                        val operands = words.drop(index + 1).filter { !it.startsWith("-") && it != size }
                        for (operand in operands) {
                            out += classifyGuestOperand(
                                env, workspace, current, operand, false, "truncate " + operand, hostMode
                            )
                        }
                    }
                }

                "git" -> {
                    if (words.getOrNull(index + 1)?.startsWith("clean") == true) {
                        val finding = if (isInsideWorkspace(workspace, mapPath(env, current, hostMode))) {
                            RiskFinding(Risk.SAFE, "工作区内 git clean", "git clean", current)
                        } else {
                            RiskFinding(Risk.CONFIRM, "工作区外执行 git clean，删除范围不可枚举", "git clean", current)
                        }
                        out += finding
                    }
                }

                "pm" -> {
                    // pm clear = 清空某个应用的全部数据（等价于删掉它的私有目录）；pm uninstall = 卸载
                    val sub = words.getOrNull(index + 1)?.lowercase()
                    val target = words.drop(index + 2).firstOrNull { !it.startsWith("-") } ?: ""
                    when (sub) {
                        "clear" -> out += if (target == env.packageName) {
                            RiskFinding(
                                Risk.FORBIDDEN,
                                "CRITICAL_PATH_ERROR pm clear 的目标是本 App，会清空全部会话、记忆与审计",
                                "pm clear " + target,
                                "pm:clear:" + env.packageName
                            )
                        } else {
                            RiskFinding(Risk.CONFIRM, "pm clear 会清空另一个应用的全部数据", "pm clear " + target, "pm:clear:" + target)
                        }
                        "uninstall" -> out += RiskFinding(
                            Risk.CONFIRM, "pm uninstall 会卸载应用", "pm uninstall " + target, "pm:uninstall:" + target
                        )
                    }
                }

                "xargs" -> {
                    if (words.any { File(it).name == "rm" }) {
                        out += RiskFinding(
                            Risk.CONFIRM,
                            "xargs rm 的删除范围由上游输出决定，不可枚举",
                            "xargs rm",
                            "xargs:" + current
                        )
                    }
                }

                "sh", "bash", "zsh", "dash" -> {
                    val cIndex = words.indexOf("-c")
                    val payload = words.getOrNull(cIndex + 1)
                    if (cIndex >= 0 && !payload.isNullOrBlank()) {
                        out += classifyCommand(env, workspace, payload, current, hostMode)
                    }
                }
            }
        }
        return out
    }

    /** 单个操作数的判定：先认通配符，再映射到宿主路径按深度判。 */
    private fun classifyGuestOperand(
        env: SandboxEnv,
        workspace: File,
        currentDir: String,
        raw: String,
        recursive: Boolean,
        label: String,
        hostMode: Boolean
    ): RiskFinding {
        val expanded = expandVars(raw, currentDir)
        if (containsWildcard(expanded)) {
            val basePart = expanded.substring(0, expanded.indexOfFirst { it == '*' || it == '?' || it == '[' })
            val base = normalizeGuest(basePart.trimEnd('/'), currentDir)
            return classifyWildcard(env, workspace, base, label, hostMode)
        }
        val guest = normalizeGuest(expanded, currentDir)
        val host = mapPath(env, guest, hostMode)
        val canon = PathGate.canonicalOrNearestOrNull(host) ?: host.absoluteFile
        val isDir = recursive || host.isDirectory
        // 双源交叉校验：字符串层只看字面落点，canonical 层跟随软链接；两者不一致就不信任任何一方
        val finding = reconcile(
            classifyCanonical(env, workspace, canon, isDir, label),
            classifyCanonical(env, workspace, File(host.path), isDir, label)
        )
        // hostFile 只给规模探测用：变量目标真实落点只有运行时才知道，不能拿字面量去扫盘
        return if (raw.contains('$')) finding else finding.copy(hostFile = canon)
    }

    /**
     * 通配删除（`rm -rf *`、`rm -rf 目录/` 后接通配）：范围只能按「基底目录」估。
     *
     * 判据：
     * - 工作区内 → 放行（自己的地盘，`rm -rf *` 是常规操作）；
     * - 基底是 `/`、共享存储根、Ubuntu 顶层系统目录 → 永久拒绝；
     * - 基底是 `/root` → 确认（`rm -rf *` 会烧掉整份工程）；
     * - 其它 → 确认（范围不可枚举）。
     */
    private fun classifyWildcard(
        env: SandboxEnv,
        workspace: File,
        baseGuest: String,
        label: String,
        hostMode: Boolean
    ): RiskFinding {
        val guest = normalizeGuest(baseGuest, "/root")
        val host = mapPath(env, guest, hostMode)
        val canon = PathGate.canonicalOrNearestOrNull(host) ?: host.absoluteFile
        return reconcile(
            wildcardAt(env, workspace, canon, guest, label),
            wildcardAt(env, workspace, File(host.path), guest, label)
        )
    }

    /** 通配删除的定档规则本体；[canon] 可能是 canonical 落点，也可能是纯字面落点。 */
    private fun wildcardAt(
        env: SandboxEnv,
        workspace: File,
        canon: File,
        guest: String,
        label: String
    ): RiskFinding {
        val summary = label + "（通配符，基底 " + guest + "）"

        if (canon.path == workspace.path || PathGate.depthOf(workspace, canon) > 0) {
            return RiskFinding(Risk.SAFE, "工作区内通配删除", summary, guest)
        }
        val rootfsRoot = File(env.canonicalRootfs, "root")
        if (canon.path == rootfsRoot.path) {
            return RiskFinding(Risk.CONFIRM, "Ubuntu 家目录 /root 下的无差别删除", summary, guest)
        }
        if (canon.path == env.canonicalRootfs.path || canon.path == "/" ||
            canon.path == env.canonicalExternalRoot.path
        ) {
            return RiskFinding(
                Risk.FORBIDDEN,
                "CRITICAL_PATH_ERROR 通配删除的基底是根级位置（" + guest + "）",
                summary,
                guest
            )
        }
        val rootfsDepth = PathGate.depthOf(env.canonicalRootfs, canon)
        if (rootfsDepth == 1 && canon.name in ROOTFS_TOP) {
            return RiskFinding(
                Risk.FORBIDDEN,
                "CRITICAL_PATH_ERROR 通配删除发生在 Ubuntu 系统目录（" + guest + "）",
                summary,
                guest
            )
        }
        if (canon.path in setOf("/data", "/system", "/vendor", "/product", "/apex", "/storage", "/proc", "/sys", "/dev")) {
            return RiskFinding(
                Risk.FORBIDDEN,
                "CRITICAL_PATH_ERROR 通配删除的基底是系统根级位置（" + guest + "）",
                summary,
                guest
            )
        }
        return RiskFinding(Risk.CONFIRM, "通配删除的范围不可枚举", summary, guest)
    }

    /**
     * 双源交叉校验：把「纯字面判定」与「canonical 落点判定」合起来。
     *
     * - 两者一致：按结论走（canonical 是主判定）；
     * - 任一为 FORBIDDEN：按 FORBIDDEN 走（取更严）；
     * - 其余不一致（SAFE 与 CONFIRM）：**不信任任何一方**，按 FORBIDDEN +
     *   `CRITICAL_PATH_ERROR` 处理：两个解析器看同一段文本给出不同答案，正是
     *   拼路径拼错的形态，这种情况下「放行」没有意义。
     */
    private fun reconcile(canonical: RiskFinding, lexical: RiskFinding): RiskFinding {
        if (canonical.risk == lexical.risk) return canonical
        if (canonical.risk == Risk.FORBIDDEN || lexical.risk == Risk.FORBIDDEN) {
            return if (canonical.risk == Risk.FORBIDDEN) canonical else lexical
        }
        return RiskFinding(
            Risk.FORBIDDEN,
            "CRITICAL_PATH_ERROR 字符串判定与真实落点判定不一致（字面：" + lexical.risk +
                " / 落点：" + canonical.risk + "），按不可覆盖处理",
            canonical.summary,
            canonical.target
        )
    }

    // =========================================================================
    // guest → host 映射
    // =========================================================================

    /**
     * 把 PRoot 里的路径映射回宿主路径（对应 terminal 模块的 bind mount 表）。
     *
     * 为什么必须映射：Ubuntu 里的 `/sdcard` 通过 bind 指向宿主共享存储，`/data/data/<pkg>`
     * 指向 App 私有 filesDir；只看命令文本会以为 `/sdcard` 是「Ubuntu 内部路径」而误放行。
     */
    fun mapGuestToHost(env: SandboxEnv, guestPath: String): File {
        val guest = normalizeGuest(guestPath, "/root")
        if (guest == "/sdcard" || guest.startsWith("/sdcard/")) {
            return under(env.canonicalExternalRoot, guest.removePrefix("/sdcard"))
        }
        val userRoot = "/data/user/" + env.userId
        val pkg = env.packageName
        if (pkg.isNotEmpty()) {
            for (prefix in listOf("/data/data/" + pkg, userRoot + "/" + pkg)) {
                if (guest == prefix || guest.startsWith(prefix + "/")) {
                    val rest = guest.removePrefix(prefix)
                    return under(env.canonicalFilesDir, rest)
                }
            }
        }
        if (guest == "/data/local/tmp" || guest.startsWith("/data/local/tmp/")) {
            return under(File("/data/local/tmp"), guest.removePrefix("/data/local/tmp"))
        }
        for (prefix in listOf("/storage/emulated/" + env.userId, "/storage/self/primary")) {
            if (guest == prefix || guest.startsWith(prefix + "/")) {
                return under(env.canonicalExternalRoot, guest.removePrefix(prefix))
            }
        }
        if (guest == "/") return env.canonicalRootfs
        return under(env.canonicalRootfs, guest)
    }

    private fun under(root: File, rest: String): File {
        val clean = rest.trim('/')
        return if (clean.isEmpty()) root else File(root, clean)
    }

    /**
     * 路径落到宿主：PRoot 视角按 bind 表映射，宿主视角（Shizuku shell）按原样。
     *
     * 宿主视角仍要认共享存储与 App 私有目录这两种前缀：它们与 PRoot 的 bind 目标
     * **是同一处**，落到同一个 env 根上才能用同一套深度与禁区规则；其余路径
     * （/system、/data/local/tmp…）就是宿主自己的字面路径。
     */
    private fun mapPath(env: SandboxEnv, guest: String, hostMode: Boolean): File {
        if (!hostMode) return mapGuestToHost(env, guest)
        if (guest == "/sdcard" || guest.startsWith("/sdcard/")) {
            return under(env.canonicalExternalRoot, guest.removePrefix("/sdcard"))
        }
        val userRoot = "/data/user/" + env.userId
        val pkg = env.packageName
        if (pkg.isNotEmpty()) {
            for (prefix in listOf("/data/data/" + pkg, userRoot + "/" + pkg)) {
                if (guest == prefix || guest.startsWith(prefix + "/")) {
                    return under(env.canonicalFilesDir, guest.removePrefix(prefix))
                }
            }
        }
        for (prefix in listOf("/storage/emulated/" + env.userId, "/storage/self/primary")) {
            if (guest == prefix || guest.startsWith(prefix + "/")) {
                return under(env.canonicalExternalRoot, guest.removePrefix(prefix))
            }
        }
        return File(guest)
    }

    /**
     * 纯字面规范化：消 `.`/`..`、合并斜杠，**不碰文件系统**。
     *
     * 为什么不 canonical：目标可能不存在，canonical 会抛；而且命令文本里的路径本来就是
     * guest 视角的语义路径，先按字面收敛，再交给 host 映射后的判定做 canonical。
     */
    fun normalizeGuest(raw: String, base: String): String {
        var path = raw.trim()
        if (path.isEmpty()) return base
        path = expandVars(path, base)
        if (path == "~") path = "/root"
        if (path.startsWith("~/")) path = "/root/" + path.substring(2)
        if (!path.startsWith("/")) {
            path = if (base.endsWith("/")) base + path else base + "/" + path
        }
        val parts = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** 只展开 `~`、`$HOME`、`$PWD` 三种最常见的写法；其余变量如实承认无法静态判定。 */
    private fun expandVars(raw: String, base: String): String {
        var text = raw
        text = text.replace("\${HOME}", "/root").replace("\$HOME", "/root")
        text = text.replace("\${PWD}", base).replace("\$PWD", base)
        return text
    }

    private fun containsWildcard(text: String): Boolean =
        text.any { it == '*' || it == '?' || it == '[' }

    private fun isInsideWorkspace(workspace: File, file: File): Boolean {
        val canon = PathGate.canonicalOrNearestOrNull(file) ?: return false
        return canon.path == workspace.path || PathGate.depthOf(workspace, canon) > 0
    }

    /** 极简引号感知分词：引号内的空格不拆；引号本身去掉，通配符保留。 */
    private fun wordsOf(segment: String): List<String> {
        val out = ArrayList<String>(8)
        val builder = StringBuilder()
        var quote = ' '
        for (ch in segment) {
            if (quote == ' ') {
                when {
                    ch == '\'' || ch == '"' -> quote = ch
                    ch.isWhitespace() -> if (builder.isNotEmpty()) {
                        out += builder.toString()
                        builder.setLength(0)
                    }
                    else -> builder.append(ch)
                }
            } else {
                if (ch == quote) quote = ' ' else builder.append(ch)
            }
        }
        if (builder.isNotEmpty()) out += builder.toString()
        return out
    }

    // =========================================================================
    // 判定 → 决策（令牌签发 / 校验 / 文案）
    // =========================================================================

    private fun decide(
        ctx: ToolContext,
        tool: String,
        op: String,
        findings: List<RiskFinding>,
        coverage: Coverage = Coverage.FULL
    ): Decision {
        findings.firstOrNull { it.risk == Risk.FORBIDDEN }?.let { forbidden ->
            audit(ctx, "deny", tool, op, forbidden.rule, forbidden.summary, null, "non-overridable")
            return Decision(
                false,
                Risk.FORBIDDEN,
                forbiddenText(forbidden.rule, forbidden.summary),
                forbidden.rule,
                coverage = coverage
            )
        }

        val confirms = findings.filter { it.risk == Risk.CONFIRM }
        if (confirms.isEmpty()) {
            val first = findings.firstOrNull()
            audit(
                ctx, "allow", tool, op,
                first?.rule ?: "无删除语义", first?.summary ?: "", null, "safe"
            )
            return Decision(true, Risk.SAFE, coverage = coverage)
        }

        val summary = confirms.map { it.summary }.distinct().joinToString("；").take(240)
        val fingerprint = fingerprint(tool, op, confirms.map { it.target })
        val approval = approve(ctx, fingerprint)

        if (approval == Approval.OK) {
            audit(ctx, "approve", tool, op, confirms.first().rule, summary, null, "token-ok")
            return Decision(true, Risk.CONFIRM, rule = confirms.first().rule, coverage = coverage)
        }

        val token = issue(ctx.sessionId, fingerprint, tool, op, summary)
        audit(ctx, "confirm", tool, op, confirms.first().rule, summary, token, approval.reason)
        return Decision(
            false,
            Risk.CONFIRM,
            confirmText(confirms, token, approval),
            confirms.first().rule,
            token,
            coverage
        )
    }

    private enum class Approval(val reason: String) {
        OK(""),
        NO_MESSAGE("会话里没有可用的真实用户消息"),
        PROGRAMMATIC("最近一条 user 消息是 App 代发的，不能作为高危授权"),
        NO_CODE("最近一条真实用户消息里没有授权码"),
        MISMATCH("授权码与本次操作不匹配"),
        USED("授权码已经用过（一次性）"),
        EXPIRED("授权码已过期")
    }

    /**
     * 二次校验：只看两个事实源：**最近一条真实 user 消息**与**令牌签发记录**。
     *
     * assistant 消息、模型复述、工具参数里的码完全不参与；`programmatic` 消息一律不认
     * （否则模型把码写进计划、用户点「同意执行」就成了一次伪造授权）。
     */
    private fun approve(ctx: ToolContext, fingerprint: String): Approval {
        val message: ChatMessage = ctx.lastUserMessage() ?: return Approval.NO_MESSAGE
        if (message.role != "user") return Approval.NO_MESSAGE
        if (message.programmatic) return Approval.PROGRAMMATIC
        val codes = TOKEN_REGEX.findAll(message.content).map { it.value }.toList()
        if (codes.isEmpty()) return Approval.NO_CODE

        var sawMismatch = false
        var sawUsed = false
        var sawExpired = false
        val now = System.currentTimeMillis()
        for (code in codes) {
            val entry = pending[code]
            if (entry == null) {
                // 不在待用表里，但本进程签发过：那是用过了（单次使用生效），不是「没给码」
                if (used[code] != null) sawUsed = true
                continue
            }
            if (entry.sessionId != ctx.sessionId) continue
            if (entry.fingerprint != fingerprint) {
                sawMismatch = true
                continue
            }
            if (now - entry.issuedAt > TOKEN_TTL_MS) {
                pending.remove(code)
                sawExpired = true
                continue
            }
            // 单次使用：先移除、记入 used，再返回，任何后续同码都不可能再命中
            pending.remove(code)
            used[code] = now
            return Approval.OK
        }
        return when {
            sawMismatch -> Approval.MISMATCH
            sawUsed -> Approval.USED
            sawExpired -> Approval.EXPIRED
            else -> Approval.NO_CODE
        }
    }

    /** 签发：已用/过期的一律清掉，避免表随会话无限增长。 */
    private fun issue(
        sessionId: String,
        fingerprint: String,
        tool: String,
        op: String,
        summary: String
    ): String {
        val now = System.currentTimeMillis()
        // 逐 key 删：ConcurrentHashMap 的迭代器不支持 remove()，不能对 entries 直接 removeAll
        for (key in pending.keys) {
            val entry = pending[key] ?: continue
            if (now - entry.issuedAt > TOKEN_TTL_MS) pending.remove(key)
        }
        // 「用过的码」同样按 TTL 清：它只用来把「用过」与「没给」区分开
        for (key in used.keys) {
            val at = used[key] ?: continue
            if (now - at > TOKEN_TTL_MS) used.remove(key)
        }
        val token = "AUTH-" + randomHex(12)
        pending[token] = Pending(token, sessionId, fingerprint, tool, op, summary, now)
        return token
    }

    private fun randomHex(length: Int): String {
        val bytes = ByteArray((length + 1) / 2)
        random.nextBytes(bytes)
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hex.append("0123456789ABCDEF"[v shr 4]).append("0123456789ABCDEF"[v and 0x0F])
        }
        return hex.substring(0, length)
    }

    /** 请求指纹：工具 + 操作 + 规范化目标集合。防止「拿 A 的批准放行 B」。 */
    private fun fingerprint(tool: String, op: String, targets: List<String>): String {
        val text = tool + "\n" + op + "\n" + targets.sorted().joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // 文案
    // =========================================================================

    private fun forbiddenText(rule: String, summary: String): String =
        PREFIX_FORBIDDEN + " " + rule + "（目标：" + summary + "）。" +
            "这是系统设计上不提供的能力：即使有授权码也不会放行。" +
            "请改到具体的文件或目录；确实要重置内置 Ubuntu，请让用户在设置里执行「重装终端环境」。"

    /** 启动自检 CRITICAL 失败后的统一文案：删除能力整体停用。 */
    private fun disabledText(reason: String): String =
        PREFIX_FORBIDDEN + " 安全自检未通过，删除能力已停用（原因：" + reason + "）。" +
            "重启 App 会重新自检，通过后删除自动恢复；在此之前请让用户在设置里手动处理。"

    /** 宿主通道（Shizuku）删除语义的统一文案：这条通道没有回收站，也不给授权码。 */
    private fun hostForbiddenText(summary: String): String =
        PREFIX_FORBIDDEN + " 宿主通道（Shizuku / shell）不支持删除：目标 " + summary + "。" +
            "这条通道的权限比 App 自身更大，删除既进不了回收站、也没有恢复路径；即使有授权码也不会放行。" +
            "请改用 delete_file 工具，或在终端里删除（终端走独立的确认档位）。"

    /** Agent 触碰回收站时的统一文案。 */
    fun trashReadText(): String =
        PREFIX_FORBIDDEN + " 回收站是 Agent 的禁区：不能读取、恢复或清空它。" +
            "恢复与清空只由用户在「设置 → 数据 → 回收站」里操作。"

    private fun confirmText(
        confirms: List<RiskFinding>,
        token: String,
        approval: Approval
    ): String {
        val summary = confirms.map { it.summary }.distinct().joinToString("；").take(240)
        val reason = confirms.first().rule
        val hint = when (approval) {
            Approval.OK -> ""
            Approval.NO_MESSAGE, Approval.NO_CODE ->
                ""
            else -> "（上一个授权码未被采纳：" + approval.reason + "）"
        }
        return PREFIX_BLOCKED + " 高危操作已被拦截：目标 " + summary + "。判定：" + reason + "。" +
            "处理方式（二选一）：①能缩小范围就缩小（只删具体文件或更深一层目录），直接重试；" +
            "②确实要执行，请用户在下一条消息里原样发送授权码 " + token +
            "（5 分钟内有效、只能使用一次），然后原样重试本次调用——授权码不要放进工具参数。" + hint
    }

    // =========================================================================
    // 审计
    // =========================================================================

    private fun audit(
        ctx: ToolContext,
        event: String,
        tool: String,
        op: String,
        rule: String,
        target: String,
        token: String?,
        status: String
    ) {
        runCatching {
            AuditLog.record(
                ctx.context,
                event,
                "tool" to tool,
                "op" to op,
                "session" to ctx.sessionId,
                "permission" to permissionName(ctx.permission),
                "workspace" to ctx.workspaceRoot.absolutePath,
                "target" to target,
                "rule" to rule,
                "token" to (token ?: ""),
                "status" to status
            )
        }
    }

    private fun permissionName(level: PermissionLevel): String = when (level) {
        PermissionLevel.READ_ONLY -> "read-only"
        PermissionLevel.WORKSPACE -> "workspace"
        PermissionLevel.FULL -> "danger-full-access"
    }

    /** 供回归用例读取当前签发数量（只读快照）。 */
    internal fun pendingCount(): Int = pending.size

    /** 供回归用例把某个码置为过期。 */
    internal fun ageForTest(sessionId: String) {
        val now = System.currentTimeMillis()
        for ((key, value) in pending) {
            if (value.sessionId == sessionId) {
                pending[key] = value.copy(issuedAt = now - TOKEN_TTL_MS - 1000L)
            }
        }
    }

    /** 供回归用例清空会话令牌。 */
    internal fun clearForTest(sessionId: String) {
        for (key in pending.keys) {
            if (pending[key]?.sessionId == sessionId) pending.remove(key)
        }
        // 「用过的码」只按时间记，没有会话归属；用例要的是确定性，这里整体清掉
        used.clear()
    }

    /**
     * 供回归用例验证规模探测。
     *
     * 为什么不直接把 [ScaleLevel] 改成 internal：那是只服务于「探测结果」的内部枚举，
     * 对外暴露会让人误以为它是判定结果；包一层测试专用枚举，语义边界更干净。
     */
    internal enum class ScaleLevelForTest { SMALL, LARGE, UNKNOWN }

    /** [scaleOf] 的测试专用出口：只做枚举映射，不暴露探测细节。 */
    internal fun scaleOfForTest(target: File): ScaleLevelForTest = when (scaleOf(target).first) {
        ScaleLevel.SMALL -> ScaleLevelForTest.SMALL
        ScaleLevel.LARGE -> ScaleLevelForTest.LARGE
        ScaleLevel.UNKNOWN -> ScaleLevelForTest.UNKNOWN
    }
}
