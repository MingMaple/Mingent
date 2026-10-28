package com.zmf.agent.tools

import android.content.Context
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.ChatMessage
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.file.SafeDelete
import com.zmf.agent.tools.file.TrashStore
import java.io.File
import java.nio.file.Files

/**
 * 沙箱判定的启动期回归自检：用例覆盖删除判定、回收站禁区、终端命令与授权码。
 *
 * 为什么要有它：删除闸门的判定错误**不会抛异常**，只会表现为「某个本该拒绝的路径被放行」。
 * 这种错误在正常使用里几乎看不见（没人会天天删根目录），一旦发生却是 150 GB 级别的后果。
 * 所以把最关键的判定固化成用例，每次启动跑一遍纯逻辑，错了立刻在日志与审计里看得见。
 *
 * 边界（必须如实）：这里测的是**判定函数**，不是「真实文件系统一定安全」。
 * 终端里的变量展开、脚本内部、eval 等仍然只能靠文本层尽力，
 * 真正的兜底是 Android 权限与 PRoot 根文件系统的可重建性。
 *
 * 用假环境（cacheDir 下临时目录）而不是真机目录：判定逻辑是纯函数，给什么环境按什么判；
 * 这样用例在任何设备、任何权限档下结果都一样，不会因为「这台机器上没有 /sdcard」而误报。
 */
object SandboxRegression {

    private const val TAG = "sandbox-reg"

    /** 一个用例：名字 + 实际风险 + 期望风险 + 级别。 */
    private class Case(
        val name: String,
        val actual: HighRiskGate.Risk,
        val expected: HighRiskGate.Risk,
        /** CRITICAL 失败 → 删除能力整体停用；ADVISORY 只记日志。 */
        val critical: Boolean = true
    )

    /**
     * 跑一遍全部用例。
     *
     * @return 通过数 / 总数，方便调用方记一条审计
     *
     * CRITICAL 用例失败时**自动停用删除能力**：判定函数错了，任何「放行」都不可信；
     * 停用只影响本次进程，重启重跑自检、通过后自动恢复。
     */
    fun run(context: Context): Pair<Int, Int> {
        HighRiskGate.enableDeletion()
        val base = File(context.cacheDir, "sandbox-reg")
        return try {
            val env = buildEnv(base)
            val workspace = File(env.rootfsDir, "home/proj")
            val cases = ArrayList<Case>(96)
            collectPathCases(env, workspace, cases)
            collectTrashCases(env, workspace, cases)
            collectCommandCases(env, workspace, cases)
            collectTokenCases(context, env, workspace, cases)
            collectTerminalGateCases(context, env, workspace, cases)
            collectScaleCases(context, cases)
            val fsCases = collectFsCases(base) + collectTrashFsCases(context)

            val failed = cases.filter { it.actual != it.expected }
            val failedFs = fsCases.filter { !it.second }
            val total = cases.size + fsCases.size
            val passed = total - failed.size - failedFs.size
            FileLog.log(
                TAG,
                "回归自检 $passed/$total" +
                    if (failed.isEmpty() && failedFs.isEmpty()) " 通过" else "，失败 " + (failed.size + failedFs.size) + " 条"
            )
            for (case in failed) {
                FileLog.log(
                    TAG,
                    "用例失败：" + case.name + "，期望 " + case.expected + "，实际 " + case.actual +
                        if (case.critical) "（CRITICAL）" else "（ADVISORY）"
                )
            }
            for ((name, ok) in failedFs) {
                FileLog.log(TAG, "用例失败：" + name)
            }

            val criticalFailures = failed.filter { it.critical }.map { it.name } + failedFs.map { it.first }
            if (criticalFailures.isNotEmpty()) {
                val reason = criticalFailures.size.toString() + " 条关键用例失败（" + criticalFailures.first() + "）"
                HighRiskGate.disableDeletion(reason)
                FileLog.log(TAG, "CRITICAL 用例失败，删除能力已停用：" + reason)
            }
            passed to total
        } catch (e: Exception) {
            FileLog.log(TAG, "回归自检异常：" + e)
            0 to 0
        } finally {
            // 用例期间反复绑过假环境：跑完必须绑回真实设备，否则后续工具会按假路径判定
            runCatching { HighRiskGate.bind(context) }
            runCatching { base.deleteRecursively() }
        }
    }

    // =========================================================================
    // 环境
    // =========================================================================

    private fun buildEnv(base: File): HighRiskGate.SandboxEnv {
        val files = File(base, "env/data/data/app/files")
        val appPrivate = files.parentFile!!
        val externalRoot = File(base, "env/storage/emulated/0")
        // 回收站判定按「/Android/data/<包名>/files/.trash」匹配，假环境必须照真实结构摆，
        // 否则禁区规则在自检里永远命中不了
        val externalFiles = File(externalRoot, "Android/data/com.zmf.agent/files")
        val rootfs = File(base, "env/rootfs")

        // 真实落盘一份最小目录树：判定里的「就近规范化」要能走通，目录不存在会退化到父级
        listOf(
            files, File(files, "shared_prefs"), File(files, "appdata"), File(files, "mingmem"),
            File(files, "workspace"),
            File(appPrivate, "databases"), externalRoot, File(externalRoot, "DCIM"), externalFiles,
            File(externalFiles, ".trash"), File(externalFiles, ".trash/session1/entry1"),
            File(rootfs, "bin"), File(rootfs, "usr"), File(rootfs, "etc"), File(rootfs, "home"),
            File(rootfs, "root/proj/node_modules"), File(rootfs, "root/other"),
            File(rootfs, "home/proj/node_modules"), File(rootfs, "home/proj/src"),
            File(rootfs, "home/other")
        ).forEach { it.mkdirs() }

        return HighRiskGate.SandboxEnv(
            filesDir = files,
            appPrivateRoot = appPrivate,
            externalRoot = externalRoot,
            externalFilesDir = externalFiles,
            rootfsDir = rootfs,
            packageName = "com.zmf.agent",
            userId = 0
        )
    }

    // =========================================================================
    // 路径判定
    // =========================================================================

    private fun collectPathCases(
        env: HighRiskGate.SandboxEnv,
        workspace: File,
        out: MutableList<Case>
    ) {
        val rootfs = env.rootfsDir
        /** 目录目标 → 风险级（isDirectory=true 走目录分支）。 */
        fun dir(path: File) = HighRiskGate.classifyDelete(env, workspace, path, true).risk
        /** 文件目标 → 风险级（isDirectory=false，软链接也按文件判）。 */
        fun file(path: File) = HighRiskGate.classifyDelete(env, workspace, path, false).risk

        // ① 根级退化：等于根 / 包含关键根
        out += Case("删除工作区根本身", dir(workspace), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除 App 文件目录", dir(env.filesDir), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除 App 私有目录", dir(env.appPrivateRoot), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除共享存储根", dir(env.externalRoot), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除 Ubuntu 根文件系统", dir(rootfs), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除 Ubuntu 家目录 /root", dir(File(rootfs, "root")), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除包含工作区的父目录", dir(File(rootfs, "home")), HighRiskGate.Risk.FORBIDDEN)

        // ② App 核心数据绝对禁区
        out += Case("删除共享偏好目录", dir(File(env.filesDir, "shared_prefs")), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除记忆内核目录", dir(File(env.filesDir, "mingmem")), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除审计日志目录", dir(File(env.filesDir, "appdata")), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除数据库目录", dir(File(env.appPrivateRoot, "databases")), HighRiskGate.Risk.FORBIDDEN)

        // ③ Ubuntu 系统目录
        out += Case("删除 Ubuntu /bin", dir(File(rootfs, "bin")), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除 Ubuntu /usr", dir(File(rootfs, "usr")), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除 Ubuntu /etc", dir(File(rootfs, "etc")), HighRiskGate.Risk.FORBIDDEN)

        // ④ 工作区内一律放行（用户亲手授权的能力路径）
        out += Case("删除工作区内目录", dir(File(workspace, "src")), HighRiskGate.Risk.SAFE)
        out += Case("删除工作区内文件", file(File(workspace, "src/main.js")), HighRiskGate.Risk.SAFE)
        out += Case("删除工作区内 node_modules", dir(File(workspace, "node_modules")), HighRiskGate.Risk.SAFE)

        // ⑤ 工作区外：一级工程目录要确认，深层目录放行
        out += Case("删除 /root 下一级目录", dir(File(rootfs, "root/proj")), HighRiskGate.Risk.CONFIRM)
        out += Case(
            "删除 /root 下深层目录",
            dir(File(rootfs, "root/proj/node_modules")),
            HighRiskGate.Risk.SAFE
        )
        out += Case("删除共享存储一级目录", dir(File(env.externalRoot, "DCIM")), HighRiskGate.Risk.CONFIRM)
        out += Case("删除共享存储内的文件", file(File(env.externalRoot, "DCIM/a.jpg")), HighRiskGate.Risk.SAFE)
    }

    // =========================================================================
    // 回收站禁区
    // =========================================================================

    /**
     * 回收站对 Agent 必须是黑区：删（会抹掉恢复可能）、写（会伪造条目）、读（会看到已删内容）。
     *
     * 这三条只要有一条漏了，「删完再清空」就能由 Agent 自己完成，回收站等于不存在。
     */
    private fun collectTrashCases(
        env: HighRiskGate.SandboxEnv,
        workspace: File,
        out: MutableList<Case>
    ) {
        val trash = File(env.externalFilesDir, TrashStore.DIR_NAME)
        /** 同上：目录目标 → 风险级；回收站这几条必须全是 FORBIDDEN。 */
        fun dir(path: File) = HighRiskGate.classifyDelete(env, workspace, path, true).risk
        /** 同上：文件目标 → 风险级。 */
        fun file(path: File) = HighRiskGate.classifyDelete(env, workspace, path, false).risk

        out += Case("删除回收站根目录", dir(trash), HighRiskGate.Risk.FORBIDDEN)
        out += Case("删除回收站会话目录", dir(File(trash, "session1")), HighRiskGate.Risk.FORBIDDEN)
        out += Case(
            "删除回收站条目",
            dir(File(trash, "session1/entry1")),
            HighRiskGate.Risk.FORBIDDEN
        )
        out += Case(
            "删除回收站条目里的文件",
            file(File(trash, "session1/entry1/payload")),
            HighRiskGate.Risk.FORBIDDEN
        )

        // 写入禁区走的是另一条入口（protectedWriteReason），必须单独验
        HighRiskGate.bind(env)
        val writeBlocked = HighRiskGate.protectedWriteReason(File(trash, "session1/entry1/payload")) != null
        out += Case(
            "写入回收站被拒绝",
            if (writeBlocked) HighRiskGate.Risk.FORBIDDEN else HighRiskGate.Risk.SAFE,
            HighRiskGate.Risk.FORBIDDEN
        )
        val outsideAllowed = HighRiskGate.protectedWriteReason(File(env.externalFilesDir, "notes.txt")) == null
        out += Case(
            "App 外部私有目录的普通文件仍可写",
            if (outsideAllowed) HighRiskGate.Risk.SAFE else HighRiskGate.Risk.FORBIDDEN,
            HighRiskGate.Risk.SAFE
        )
    }

    // =========================================================================
    // 终端命令判定
    // =========================================================================

    private fun collectCommandCases(
        env: HighRiskGate.SandboxEnv,
        workspace: File,
        out: MutableList<Case>
    ) {
        val workspaceGuest = "/home/proj"
        /** 终端命令 → 风险级；cwd 默认 /root（家目录一级目录最容易和深层目录混判）。 */
        fun cmd(command: String, cwd: String = "/root"): HighRiskGate.Risk =
            worst(HighRiskGate.classifyCommand(env, workspace, command, cwd))

        // 明显安全的命令：不产生任何删除语义
        out += Case("普通命令 ls", cmd("ls -la"), HighRiskGate.Risk.SAFE)
        out += Case("工作区内删除文件", cmd("rm -f src/main.js", workspaceGuest), HighRiskGate.Risk.SAFE)
        out += Case(
            "工作区内 rm -rf *",
            cmd("rm -rf *", workspaceGuest),
            HighRiskGate.Risk.SAFE
        )
        out += Case(
            "工作区内 git clean",
            cmd("git clean -xfd", workspaceGuest),
            HighRiskGate.Risk.SAFE
        )

        // 根级退化
        out += Case("rm -rf /", cmd("rm -rf /"), HighRiskGate.Risk.FORBIDDEN)
        out += Case("rm -rf /sdcard", cmd("rm -rf /sdcard"), HighRiskGate.Risk.FORBIDDEN)
        out += Case(
            "rm -rf App 共享偏好",
            cmd("rm -rf /data/data/com.zmf.agent/shared_prefs"),
            HighRiskGate.Risk.FORBIDDEN
        )
        out += Case("rm -rf /bin", cmd("rm -rf /bin"), HighRiskGate.Risk.FORBIDDEN)
        out += Case("sh -c 嵌套 rm -rf /", cmd("sh -c \"rm -rf /\""), HighRiskGate.Risk.FORBIDDEN)
        out += Case("sudo rm -rf /usr", cmd("sudo rm -rf /usr"), HighRiskGate.Risk.FORBIDDEN)

        // 需要确认：/root 一级目录、通配、xargs、find -delete、截断
        out += Case("rm -rf /root/proj", cmd("rm -rf /root/proj"), HighRiskGate.Risk.CONFIRM)
        out += Case("rm -rf /root/*", cmd("rm -rf /root/*"), HighRiskGate.Risk.CONFIRM)
        out += Case("cwd=/root 的 rm -rf *", cmd("rm -rf *", "/root"), HighRiskGate.Risk.CONFIRM)
        out += Case("find . -delete", cmd("find . -delete"), HighRiskGate.Risk.CONFIRM)
        out += Case("xargs rm", cmd("ls | xargs rm -f"), HighRiskGate.Risk.CONFIRM)
        out += Case("truncate -s 0 目录", cmd("truncate -s 0 /root/proj"), HighRiskGate.Risk.CONFIRM)

        // /root 下的深层目录：放行（这是日常开发最频繁的删除）
        out += Case(
            "rm -rf /root/proj/node_modules",
            cmd("rm -rf /root/proj/node_modules"),
            HighRiskGate.Risk.SAFE
        )
        // cwd=/root 的 ./build 解析后是 /root/build：家目录的**一级目录**，
        // 可能就是整份工程，按定档要一次授权码，不能算「深层目录」直接放行。
        out += Case("cwd=/root 的 rm -rf ./build", cmd("rm -rf ./build"), HighRiskGate.Risk.CONFIRM)
        out += Case(
            "rm -rf ./node_modules（cwd 在工作区内）",
            cmd("rm -rf ./node_modules", workspaceGuest),
            HighRiskGate.Risk.SAFE
        )

        // 字符串层交叉校验：这四种写法最容易让「文本拼路径」与真实落点分叉
        out += Case("rm -rf ./..（逃出工作区）", cmd("rm -rf ./..", workspaceGuest), HighRiskGate.Risk.FORBIDDEN)
        out += Case("rm -rf a/../../b", cmd("rm -rf a/../../b", workspaceGuest), HighRiskGate.Risk.CONFIRM)
        out += Case("rm -rf //sdcard", cmd("rm -rf //sdcard"), HighRiskGate.Risk.FORBIDDEN)
        out += Case("rm -rf /root/", cmd("rm -rf /root/"), HighRiskGate.Risk.FORBIDDEN)

        // Shizuku 通道：宿主视角，不经过 bind 映射（否则「不拦」就成了删除后门）
        /** 宿主视角（Shizuku）的终端命令 → 风险级；不走 bind 映射。 */
        fun host(command: String): HighRiskGate.Risk =
            worst(HighRiskGate.classifyCommand(env, workspace, command, "/", hostMode = true))
        out += Case("shizuku rm -rf /sdcard", host("rm -rf /sdcard"), HighRiskGate.Risk.FORBIDDEN)
        out += Case(
            "shizuku pm clear 本 App",
            host("pm clear com.zmf.agent"),
            HighRiskGate.Risk.FORBIDDEN
        )
        out += Case(
            "shizuku pm uninstall",
            host("pm uninstall com.example.other"),
            HighRiskGate.Risk.CONFIRM
        )
        // mv 在宿主通道里等价于删除（源端消失且不进回收站）
        out += Case("shizuku mv", host("mv /sdcard/DCIM /sdcard/DCIM2"), HighRiskGate.Risk.CONFIRM)
    }

    // =========================================================================
    // 真实入口：覆盖度降档 / 宿主通道不可覆盖 / 规模阈值
    // =========================================================================

    /**
     * 这些用例走 [HighRiskGate.checkTerminal] / [HighRiskGate.checkHostCommand]，
     * 验证的是「入口最终给不给放行」，而不是内部用例的中间结论。
     */
    private fun collectTerminalGateCases(
        context: Context,
        env: HighRiskGate.SandboxEnv,
        workspace: File,
        out: MutableList<Case>
    ) {
        val session = "sandbox-regression-terminal"
        /** 现造一个上下文并重绑假环境：ToolContext.init 会把环境刷回真实设备。 */
        fun ctx(): ToolContext {
            val result = ToolContext(
                context = context,
                permission = PermissionLevel.WORKSPACE,
                workspaceRoot = workspace,
                sessionId = session,
                lastUserMessage = { null }
            )
            HighRiskGate.bind(env)
            return result
        }
        /** 走真实入口 [HighRiskGate.checkTerminal] 的终端命令 → 风险级。 */
        fun terminal(command: String, cwd: String = "/home/proj"): HighRiskGate.Risk =
            riskOf(HighRiskGate.checkTerminal(ctx(), command, cwd))

        // 覆盖度：出现静态看不见的构造时，安全档降为确认
        out += Case("覆盖度：普通命令", coverageCase("ls -la"), HighRiskGate.Risk.SAFE)
        out += Case("覆盖度：\$变量", coverageCase("rm -rf \$DIR/x"), HighRiskGate.Risk.CONFIRM)
        out += Case("覆盖度：反引号", coverageCase("rm -rf `pwd`/x"), HighRiskGate.Risk.CONFIRM)
        out += Case("覆盖度：sh -c", coverageCase("sh -c \"rm -rf x\""), HighRiskGate.Risk.CONFIRM)
        out += Case("覆盖度：xargs", coverageCase("ls | xargs rm"), HighRiskGate.Risk.CONFIRM)
        out += Case("覆盖度：find -delete", coverageCase("find . -delete"), HighRiskGate.Risk.CONFIRM)
        out += Case("覆盖度：base64 -d", coverageCase("echo aGk= | base64 -d > x"), HighRiskGate.Risk.CONFIRM)

        out += Case("工作区内 rm -f 单文件放行", terminal("rm -f src/main.js"), HighRiskGate.Risk.SAFE)
        out += Case("变量目标降为确认", terminal("rm -rf \$DIR/src"), HighRiskGate.Risk.CONFIRM)
        out += Case(
            "find . -delete 走入口仍为确认",
            terminal("find . -delete"),
            HighRiskGate.Risk.CONFIRM
        )

        // 宿主通道：任何删除语义一律 FORBIDDEN 且不签发令牌
        out += Case(
            "宿主通道 rm 一律拒绝",
            riskOf(HighRiskGate.checkHostCommand(ctx(), "rm -rf /sdcard/DCIM")),
            HighRiskGate.Risk.FORBIDDEN
        )
        out += Case(
            "宿主通道 mv 一律拒绝",
            riskOf(HighRiskGate.checkHostCommand(ctx(), "mv /sdcard/DCIM /sdcard/DCIM2")),
            HighRiskGate.Risk.FORBIDDEN
        )
        out += Case(
            "宿主通道普通命令放行",
            riskOf(HighRiskGate.checkHostCommand(ctx(), "ls /sdcard")),
            HighRiskGate.Risk.SAFE
        )

        // 回收站出现在命令文本里 → 永久拒绝（连读都不行）
        out += Case(
            "命令触碰回收站",
            riskOf(HighRiskGate.checkTerminal(ctx(), "ls /data/data/com.zmf.agent/files/.trash", "/root")),
            HighRiskGate.Risk.FORBIDDEN
        )

        HighRiskGate.clearForTest(session)
    }

    /** 只测覆盖度判定本身：FULL → SAFE 档，PARTIAL → CONFIRM 档（调用方把它当「会不会降档」）。 */
    private fun coverageCase(command: String): HighRiskGate.Risk =
        if (HighRiskGate.coverageOf(command) == HighRiskGate.Coverage.FULL) {
            HighRiskGate.Risk.SAFE
        } else {
            HighRiskGate.Risk.CONFIRM
        }

    /**
     * 工作区内的大目录删除要升为确认档。
     *
     * 用稀疏文件造 256 MB（`setLength` 不真占盘，跑得快）；造不出来就跳过这两条，
     * 不伪装成通过。这两条是 ADVISORY：它依赖文件系统行为，失败不该停用整个删除能力。
     */
    private fun collectScaleCases(context: Context, out: MutableList<Case>) {
        val base = File(File(context.cacheDir, "sandbox-reg"), "scale")
        val big = File(base, "big.bin")
        val ok = base.mkdirs() && runCatching {
            java.io.RandomAccessFile(big, "rw").use { it.setLength(256L * 1024L * 1024L) }
        }.isSuccess
        if (!ok) return
        val found = HighRiskGate.scaleOfForTest(big)
        out += Case(
            "R21：256 MB 文件被判定为大",
            if (found == HighRiskGate.ScaleLevelForTest.LARGE) HighRiskGate.Risk.FORBIDDEN else HighRiskGate.Risk.SAFE,
            HighRiskGate.Risk.FORBIDDEN,
            critical = false
        )
        val small = File(base, "small.bin")
        runCatching { small.writeText("x") }
        val smallLevel = HighRiskGate.scaleOfForTest(small)
        out += Case(
            "R21：小文件不被判为大",
            if (smallLevel == HighRiskGate.ScaleLevelForTest.SMALL) HighRiskGate.Risk.SAFE else HighRiskGate.Risk.FORBIDDEN,
            HighRiskGate.Risk.SAFE,
            critical = false
        )
    }

    private fun worst(findings: List<HighRiskGate.RiskFinding>): HighRiskGate.Risk = when {
        findings.any { it.risk == HighRiskGate.Risk.FORBIDDEN } -> HighRiskGate.Risk.FORBIDDEN
        findings.any { it.risk == HighRiskGate.Risk.CONFIRM } -> HighRiskGate.Risk.CONFIRM
        else -> HighRiskGate.Risk.SAFE
    }

    // =========================================================================
    // 删除执行层（真实文件系统）
    // =========================================================================

    /**
     * [SafeDelete] 的真实落盘行为：这里不再用假环境，而是在 cache 下真建目录与软链接。
     *
     * 为什么必须测执行层：判定正确不代表执行正确。历史事故里 harness 的**拼接**出错，
     * 而拼接发生的正是「从目标往下走」的执行阶段；软链接跟随也是执行阶段才会发生的事。
     * 用例跑在 App 自己的 cache 里，删坏也只影响自检目录，[run] 的 finally 会整体清掉。
     *
     * 软链接创建在个别文件系统上会被拒绝：失败就跳过那两条用例，不伪装成通过。
     */
    private fun collectFsCases(base: File): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>(5)
        val root = File(base, "fs")
        val tree = File(root, "tree")
        File(tree, "sub").mkdirs()
        File(tree, "a.txt").writeText("a")
        val victim = File(root, "victim.txt").apply { writeText("keep") }
        val link = File(tree, "link")
        val linkOk = runCatching { Files.createSymbolicLink(link.toPath(), victim.toPath()) }.isSuccess

        val result = SafeDelete.delete(tree)
        out += "删除后目标目录不存在" to (!tree.exists())
        out += "删除统计里至少有一个文件" to (result.stats.files > 0)
        if (linkOk) {
            // 关键用例：工作区里的软链接指向外部文件时，只删链接本身，绝不能顺着链接删目标
            out += "软链接指向的文件未被跟随删除" to victim.exists()
            out += "软链接本身已被删除" to !PathGate.isSymlink(link)
        }
        out += "目标本来不存在时按成功返回" to (!SafeDelete.delete(File(root, "missing")).existed)
        return out
    }

    /**
     * 回收站的真实落盘行为：搬进去 → 原路径消失 → 恢复 → 再删掉。
     *
     * 为什么必须测这条链：判定说「已进回收站」而实际没进，等于静默永久删除，
     * 这正是回收站机制最不能出的错。目标与池都放在 App 外部私有目录（同卷，rename 可用）；
     * 外部卷不可用时直接跳过，不伪装成通过。
     *
     * 自检痕迹：临时目录与测试条目在结尾都删掉，跑完回收站里不会多出东西。
     */
    private fun collectTrashFsCases(context: Context): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>(4)
        val external = context.getExternalFilesDir(null) ?: return out
        val root = File(external, "sandbox-reg-trash")
        val target = File(root, "keep.txt")
        val session = "sandbox-regression-trash"
        root.mkdirs()
        target.writeText("keep")

        val entry = runCatching { TrashStore.moveInto(context, target, session, "test") }.getOrNull()
        out += "回收站：移入后原路径消失" to (entry != null && !target.exists())
        if (entry == null) {
            root.deleteRecursively()
            return out
        }
        out += "回收站：payload 已就位" to entry.payload.exists()

        val restored = runCatching { TrashStore.restore(context, entry) }
            .getOrElse { TrashStore.Outcome(false, it.message ?: "") }
        out += "回收站：恢复到原路径" to (restored.ok && target.exists() && target.readText() == "keep")

        // 再搬一次并永久删除，把这次自检留下的条目清掉
        val again = runCatching { TrashStore.moveInto(context, target, session, "test") }.getOrNull()
        if (again != null) {
            out += "回收站：永久删除条目" to (
                runCatching { TrashStore.deleteEntry(context, again) }.getOrDefault(false) &&
                    !again.payload.exists()
                )
        }
        root.deleteRecursively()
        return out
    }

    // =========================================================================
    // 授权码判定
    // =========================================================================

    /**
     * 一次性授权码的四类拒绝与一类放行。
     *
     * 判定走的是真实入口 [HighRiskGate.checkDelete]，只是环境与消息源换成假的，
     * 要测的正是「闸门拿到什么样的用户消息才愿意放行」，不是内部函数本身。
     */
    private fun collectTokenCases(
        context: Context,
        env: HighRiskGate.SandboxEnv,
        workspace: File,
        out: MutableList<Case>
    ) {
        val session = "sandbox-regression"
        val target = File(env.rootfsDir, "root/proj")
        val other = File(env.rootfsDir, "root/other")

        // 构造上下文时 ToolContext.init 会把环境刷回真实设备，所以每次都要再绑一次假环境
        /** 带一条用户消息的上下文：授权码判定只看这条消息与签发记录。 */
        fun ctx(message: ChatMessage?): ToolContext {
            val result = ToolContext(
                context = context,
                permission = PermissionLevel.WORKSPACE,
                workspaceRoot = workspace,
                sessionId = session,
                lastUserMessage = { message }
            )
            HighRiskGate.bind(env)
            return result
        }

        /** 走真实入口 [HighRiskGate.checkDelete] 对固定目标发起一次删除判定。 */
        fun deleteAt(message: ChatMessage?, file: File = target): HighRiskGate.Decision =
            HighRiskGate.checkDelete(ctx(message), file, true)

        HighRiskGate.clearForTest(session)

        // ① 没有任何用户消息：拒绝并签发授权码
        val first = deleteAt(null)
        out += Case("无用户消息时拦截", riskOf(first), HighRiskGate.Risk.CONFIRM)
        val token = first.token

        // ② 用户消息里没有码：仍然拒绝
        val noCode = deleteAt(ChatMessage(role = "user", content = "删了吧"))
        out += Case("用户消息无授权码时拦截", riskOf(noCode), HighRiskGate.Risk.CONFIRM)

        if (token == null) {
            out += Case("首次拦截应签发授权码", HighRiskGate.Risk.FORBIDDEN, HighRiskGate.Risk.SAFE)
            HighRiskGate.clearForTest(session)
            return
        }

        // ③ App 代发消息里带码：不认（否则「批准计划」会变成删除后门）
        val programmatic = deleteAt(
            ChatMessage(role = "user", content = "按计划开始执行 " + token, programmatic = true)
        )
        out += Case("App 代发消息不能授权", riskOf(programmatic), HighRiskGate.Risk.CONFIRM)

        // ④ 码与本次操作不匹配（换了目标）：不认
        val mismatch = deleteAt(ChatMessage(role = "user", content = token), other)
        out += Case("授权码与目标不匹配时拒绝", riskOf(mismatch), HighRiskGate.Risk.CONFIRM)

        // ⑤ 真实用户消息里原样带上码：放行
        val ok = deleteAt(ChatMessage(role = "user", content = "确认删除 " + token))
        out += Case("真实用户消息授权后放行", riskOf(ok), HighRiskGate.Risk.SAFE)
        val reuse = deleteAt(ChatMessage(role = "user", content = token))
        out += Case("授权码使用后即失效", riskOf(reuse), HighRiskGate.Risk.CONFIRM)
        // 报错原因必须说「用过了」而不是「没给码」：码用掉后直接从 pending 删掉就查不到，
        // 用户重发旧码会看到「最近一条消息里没有授权码」，把一次性的正确行为说成了误解
        out += Case(
            "重发旧码时说明「已用过」",
            if (reuse.message?.contains("用过") == true) HighRiskGate.Risk.SAFE else HighRiskGate.Risk.FORBIDDEN,
            HighRiskGate.Risk.SAFE
        )

        // ⑥ 过期：签发后人为置旧，再带码重试应被拒绝
        val second = deleteAt(null)
        val secondToken = second.token
        if (secondToken != null) {
            HighRiskGate.ageForTest(session)
            val expired = deleteAt(ChatMessage(role = "user", content = secondToken))
            out += Case("授权码过期后拒绝", riskOf(expired), HighRiskGate.Risk.CONFIRM)
        } else {
            out += Case("第二次拦截应签发授权码", HighRiskGate.Risk.FORBIDDEN, HighRiskGate.Risk.SAFE)
        }
        HighRiskGate.clearForTest(session)
    }

    /**
     * Decision 是「是否放行」+ 风险级，用例里统一成三级：
     * 放行 = SAFE，不放行但可授权 = CONFIRM，永久拒绝 = FORBIDDEN。
     */
    private fun riskOf(decision: HighRiskGate.Decision): HighRiskGate.Risk = when {
        decision.allowed -> HighRiskGate.Risk.SAFE
        decision.risk == HighRiskGate.Risk.FORBIDDEN -> HighRiskGate.Risk.FORBIDDEN
        else -> HighRiskGate.Risk.CONFIRM
    }
}
