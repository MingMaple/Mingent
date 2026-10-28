package com.zmf.agent.tools.file

import com.zmf.agent.agent.workspace.WorkspaceIo
import com.zmf.agent.agent.workspace.workspaceIoForRoot
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.core.model.PermissionLevel
import com.zmf.agent.tools.HighRiskGate
import com.zmf.agent.tools.PathGate
import com.zmf.agent.tools.Tool
import com.zmf.agent.tools.ToolContext
import com.zmf.agent.tools.ToolResult
import com.zmf.agent.tools.displayName
import org.json.JSONObject
import java.io.File

/**
 * 删除文件或目录，并把「删了多少 / 能不能恢复」如实回报给模型。
 *
 * 为什么单独做一个工具、而不是让模型用 terminal 里的 `rm`：
 * - `rm` 的判定只能看命令文本（变量、脚本、管道都能绕），而本工具拿到的就是**解析后的真实路径**，
 *   高危闸门可以对规范化后的落点做判定；
 * - 删除是最不可逆的操作，需要一处能返回「删了多少」的地方；
 * - 工作区档不该为了删一个文件去开终端。
 *
 * 分级（详见 [HighRiskGate]）：
 * - 工作区内的文件 / 目录：直接放行；
 * - 工作区外、Ubuntu 家目录下的一级目录等：返回一次性授权码，用户在下一条消息里发回才执行；
 * - 存储根 / 工作区根 / App 核心数据 / Ubuntu 根：永久拒绝。
 *
 * 可恢复性（见 [TrashStore]）：能进回收站的删除先做 rename（SAF 是复制），删完仍可在
 * 「设置 → 数据 → 回收站」撤销。进不了回收站时按「这条删除显然安全吗」分叉：
 * 工作区内显然是安全的 → 直接删、回执注明不可恢复，**不拿授权码打扰**；
 * 工作区外 → 升为确认档（那里本来就不可恢复，且范围常常不小）。
 */
class DeleteFileTool : Tool {

    override val name = "delete_file"

    /** 删除：只读档与计划模式都必须拒绝（由 ToolRegistry 按能力统一拦）。 */
    override val mutates = true

    override val description =
        "删除一个文件或目录。工作区内的路径直接相对工作区写，也可以给绝对路径。" +
            "删目录必须传 recursive=true；只删单个文件不用传。工作区内的删除直接执行，不必征求用户同意。" +
            "能进回收站的删除会先移入回收站（「设置 → 数据 → 回收站」可恢复，保留 7 天）；" +
            "进不了回收站时，工作区内的删除仍然直接执行（回执会注明不可恢复），工作区外的删除才要求一次授权码。" +
            "工作区外的目标同样会被安全闸门判定：明显安全的放行，范围过大的返回授权码，根级位置永久拒绝。" +
            "删除前想确认范围，先用 list_files 看一眼目录里有什么。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "path": {"type": "string", "description": "要删除的文件或目录路径，相对工作区或绝对路径"},
            "recursive": {"type": "boolean", "description": "删目录时必须为 true；删单个文件不用传"}
          },
          "required": ["path"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val rawPath = args.optString("path", "").trim()
        if (rawPath.isEmpty()) return ToolResult.Error("缺少参数 path")
        val recursive = args.optBoolean("recursive", false)

        val io = workspaceIoForRoot(ctx.context, ctx.workspaceRoot)
        io.problem?.let { return ToolResult.Error(it) }
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, rawPath) }
            .getOrElse { return ToolResult.Error("路径不合法：" + (it.message ?: "未知原因")) }

        val rel = io.relativePathOf(file)
        if (rel == null && io.isSaf) {
            return ToolResult.Error("这个工作区是系统授权的目录（SAF），只能用相对路径删除它里面的条目：" + rawPath)
        }
        val inWorkspace = rel != null

        // 存在性与类型：SAF 走 DocumentsContract，File 走文件系统
        val exists = if (inWorkspace && io.isSaf) io.exists(rel) else file.exists()
        val display = if (inWorkspace && io.isSaf) io.displayPath(rel) else file.absolutePath
        if (!exists) return ToolResult.Ok("目标不存在，无需删除：" + display)
        val isDir = if (inWorkspace && io.isSaf) io.isDirectory(rel) else file.isDirectory
        if (isDir && !recursive) {
            return ToolResult.Error("「" + display + "」是目录。要整个删除请传 recursive=true；只是想清理里面的几个文件，就分别删具体文件。")
        }

        // 只读档：连判定都不必做，先挡掉
        if (ctx.permission == PermissionLevel.READ_ONLY) {
            return ToolResult.Error("当前是「只读」权限档，不能删除任何文件。")
        }

        // 高危闸门：**先于越界判定**。根级目标要报「设计上不提供、给码也没用」，
        // 而不是被「越界需要完全档」这条盖过去：两者都拒绝，但模型与用户看到的下一步完全不同。
        // 顺序反过来时，完全档以外的档位永远看不到 FORBIDDEN 文案。
        val decision = if (inWorkspace && io.isSaf) {
            HighRiskGate.checkWorkspaceDelete(ctx, rel)
        } else {
            HighRiskGate.checkDelete(ctx, file, isDir)
        }
        if (!decision.allowed) {
            return ToolResult.Error(decision.message ?: "删除被安全闸门拒绝")
        }
        // 走到这里仍可能是「确认档」：那说明用户刚用授权码批准了本次操作
        val approved = decision.risk == HighRiskGate.Risk.CONFIRM

        // 越界：工作区外必须「完全」档
        if (!inWorkspace && !PathGate.canWrite(ctx, file)) {
            return ToolResult.Error(
                "当前权限档是「" + ctx.permission.displayName() + "」，不能删除工作区外的路径：" + rawPath +
                    "。工作区内的文件用相对路径直接删；越界需要切到「完全」权限。"
            )
        }

        return if (inWorkspace && io.isSaf) {
            deleteInSaf(ctx, io, rel, display, isDir)
        } else {
            deleteOnDisk(ctx, file, display, isDir, approved, inWorkspace)
        }
    }

    /**
     * 普通目录：先尝试 rename 进同卷回收站；进不去再按「这条删除是不是显然安全」分叉。
     *
     * [obviouslySafe]（工作区内）的含义是「显然安全的删除直接删，不要用授权码打扰」：
     * 回收站进不去就直删，回执里如实写明「未进回收站、不可恢复」。
     * 工作区外没有这个豁免：那里的删除要么已确认，要么必须确认。
     */
    private fun deleteOnDisk(
        ctx: ToolContext,
        file: File,
        display: String,
        isDir: Boolean,
        approved: Boolean,
        obviouslySafe: Boolean
    ): ToolResult {
        val entry = runCatching { TrashStore.moveInto(ctx.context, file, ctx.sessionId, name) }
            .onFailure { FileLog.log("delete_file", "移入回收站失败：" + it.message) }
            .getOrNull()
        if (entry != null) return ToolResult.Ok(trashedText(display, entry))

        // 回收站进不去（没有同卷池 / rename 失败，目标未被改动）
        if (!approved && !obviouslySafe) {
            val ask = HighRiskGate.checkDelete(ctx, file, isDir, forceConfirm = true)
            return ToolResult.Error(ask.message ?: "该位置无法进入回收站，删除后不可恢复，需要授权码")
        }

        val result = SafeDelete.delete(file)
        if (!result.existed) return ToolResult.Ok("目标不存在，无需删除：" + display)
        val stats = result.stats
        val text = describe(display, SafCount(stats.files, stats.dirs, stats.bytes), isDir, result.complete)
        val note = if (obviouslySafe) "\n（这个位置没有可用的回收站，本次是直接删除、不可恢复）" else ""
        return if (result.complete) {
            ToolResult.Ok(text + note)
        } else {
            ToolResult.Error(
                text + note + "\n还有 " + stats.failed + " 项没能删掉（权限或占用）。可以先 list_files 看还剩下什么。"
            )
        }
    }

    /** SAF 授权目录：能复制就先复制进回收站，再删原物；超预算就直接删（工作区内不索码）。 */
    private fun deleteInSaf(
        ctx: ToolContext,
        io: WorkspaceIo,
        rel: String,
        display: String,
        isDir: Boolean
    ): ToolResult {
        val stats = SafCount()
        if (isDir) countEntries(io, rel, stats, 0)

        val local = io.localRootFile
        // SAF 的「本地可读路径」是恢复的唯一依据：推得出来就记进 meta，推不出来只能看不能恢复
        val localTarget = local?.let { if (rel.isEmpty()) it else File(it, rel) }
        val copyable = localTarget != null &&
            stats.total <= TRASH_COPY_MAX_COUNT &&
            stats.bytes <= TRASH_COPY_MAX_BYTES
        if (copyable) {
            val entry = runCatching {
                TrashStore.copyInto(
                    context = ctx.context,
                    probe = localTarget!!,
                    originalPath = display,
                    localPath = localTarget.absolutePath,
                    sessionId = ctx.sessionId,
                    tool = name
                ) { dest ->
                    io.copyTo(dest, rel, TRASH_COPY_MAX_COUNT, TRASH_COPY_MAX_BYTES)?.let {
                        TrashStore.Stats(it.bytes, it.files, it.dirs)
                    }
                }
            }.onFailure { FileLog.log("delete_file", "复制到回收站失败：" + it.message) }.getOrNull()

            if (entry != null) {
                val ok = runCatching { io.delete(rel) }
                    .getOrElse {
                        return ToolResult.Error(
                            "已复制到回收站，但原位置删除失败：" + (it.message ?: it.javaClass.simpleName) +
                                "。可在「设置 → 数据 → 回收站」恢复。"
                        )
                    }
                return if (ok) {
                    ToolResult.Ok(trashedText(display, entry))
                } else {
                    ToolResult.Error(
                        "已复制到回收站（可在「设置 → 数据 → 回收站」恢复），但原位置有部分未能删除：" + display +
                            "。系统授权目录可能已失效，或目录非空。"
                    )
                }
            }
        }

        // SAF 工作区是用户亲手授权的目录：这是「显然安全」的删除。
        // 复制进回收站超预算（条目/体积）时直接删，不再拿授权码打扰用户
        // （授权码只留给危险操作）。代价如实写进回执。
        val ok = runCatching { io.delete(rel) }
            .getOrElse { return ToolResult.Error("删除失败：" + (it.message ?: it.javaClass.simpleName)) }
        if (!ok) return ToolResult.Error("删除失败：" + display + "（系统授权目录可能已失效，或目录非空）")
        return ToolResult.Ok(
            describe(display, stats, isDir) + "\n（条目或体积超出回收站预算，没有复制进回收站，本次删除不可恢复）"
        )
    }

    private fun countEntries(io: WorkspaceIo, rel: String, stats: SafCount, depth: Int) {
        if (depth > MAX_DEPTH || stats.total >= MAX_COUNT) return
        for (entry in io.list(rel)) {
            if (stats.total >= MAX_COUNT) return
            if (entry.isDirectory) {
                stats.dirs++
                countEntries(io, if (rel.isEmpty()) entry.name else rel + "/" + entry.name, stats, depth + 1)
            } else {
                stats.files++
                if (entry.size > 0) stats.bytes += entry.size
            }
        }
    }

    private fun trashedText(display: String, entry: TrashStore.Entry): String =
        "已移入回收站：" + display + "\n共 " + entry.files + " 个文件 / " + entry.dirs + " 个目录 / " +
            entry.bytes + " 字节 · 可在「设置 → 数据 → 回收站」恢复（保留 7 天，超期自动清理）"

    /**
     * 回执文案。
     *
     * [done] 区分「真的删完了」和「删了一部分」：删不动时还能写成「已删除：<路径>」，
     * 模型会把第一行当结论转述给用户，用户就以为已经删掉了（真机实测踩过）。
     */
    private fun describe(display: String, stats: SafCount, isDir: Boolean, done: Boolean = true): String {
        val head = if (done) "已删除：" else "删除未完成："
        if (!isDir) return head + display
        val more = if (stats.total >= MAX_COUNT) "（达到统计上限 " + MAX_COUNT + "，实际可能更多）" else ""
        return head + display + "\n共 " + stats.files + " 个文件 / " + stats.dirs + " 个目录 / " +
            stats.bytes + " 字节" + more
    }

    private data class SafCount(
        var files: Int = 0,
        var dirs: Int = 0,
        var bytes: Long = 0L
    ) {
        val total: Int get() = files + dirs
    }

    private companion object {
        /** 统计上限与递归深度上限：只为一个回执数字，不值得把整棵大树遍历两遍。 */
        const val MAX_COUNT = 20_000
        const val MAX_DEPTH = 64

        /**
         * SAF 复制进回收站的预算：SAF 没有 rename，只能「复制再删」。
         * 超预算不复制（升确认档），避免为一次删除在手机上搬几百 MB。
         */
        const val TRASH_COPY_MAX_COUNT = 3_000
        const val TRASH_COPY_MAX_BYTES = 128L * 1024 * 1024
    }
}
