package com.zmf.agent.capability.tools.file.apply_patch

import com.zmf.agent.capability.tools.HighRiskGate
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.capability.tools.file.support.Hunk
import com.zmf.agent.capability.tools.file.support.PatchFile
import com.zmf.agent.capability.tools.file.support.PatchFileKind
import com.zmf.agent.capability.tools.file.support.PatchFormatException
import com.zmf.agent.capability.tools.file.support.PatchParser
import com.zmf.agent.core.fs.PathGate
import com.zmf.agent.core.model.PermissionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 按补丁文本批量修改工作区文件。
 *
 * 执行分两个阶段：先对本批全部文件完成预检（路径、存在性、上下文、编码、体积、行数、保护区），
 * 任一文件不通过则不写入任何文件；预检通过后逐文件以「临时文件 + 重命名」写入，
 * 新增文件自动创建父目录，删除文件必须经 [HighRiskGate] 判定。
 *
 * 读写保留原有行尾与 BOM：CRLF 文件写回仍为 CRLF，带 BOM 的文件写回仍带 BOM。
 */
class ApplyPatchTool : Tool {

    override val name = "apply_patch"

    override val description =
        "按 *** Begin Patch 格式新增、更新或删除工作区内的文件，写入前先对全批做一次预检。" +
            "跨文件或跨多处的改动用它，单文件小改动用 edit_file。" +
            "单批最多 20 个文件、2000 行，正文上限 200 KB；提交阶段遇错即停且不回滚，工作区档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "input": {"type": "string", "description": "补丁正文，必须以 *** Begin Patch 开始、以 *** End Patch 结束，路径相对工作区，长度不得超过 200 KB"},
            "dry_run": {"type": "boolean", "description": "为 true 时只做预检、不写入文件；默认 false"}
          },
          "required": ["input"]
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.WORKSPACE

    override val mutates = true

    override fun mutatesWith(args: JSONObject): Boolean = !args.optBoolean("dry_run", false)

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult =
        withContext(Dispatchers.IO) { run(args, ctx) }

    private fun run(args: JSONObject, ctx: ToolContext): ToolResult {
        val patch = args.optString("input", "").ifEmpty { args.optString("patch", "") }
        if (patch.isEmpty()) {
            return ToolResult.Error("[E_PATCH_FORMAT] input 不能为空：请给出以 *** Begin Patch 开始、以 *** End Patch 结束的补丁正文")
        }
        if (patch.toByteArray(Charsets.UTF_8).size > MAX_PATCH_BYTES) {
            return ToolResult.Error(
                "[E_TOO_LARGE] 补丁正文超过上限 " + (MAX_PATCH_BYTES / 1024) + " KB；请拆成多批调用"
            )
        }
        val dryRun = args.optBoolean("dry_run", false)

        val plan = try {
            PatchParser.parse(patch)
        } catch (e: PatchFormatException) {
            return ToolResult.Error("[E_PATCH_FORMAT] " + (e.message ?: "补丁格式不合法") + "；请检查 *** 标记与 @@ 段落后重试")
        }
        if (plan.files.isEmpty()) {
            return ToolResult.Ok("补丁解析完成，没有可执行的操作。")
        }
        if (plan.files.size > MAX_FILES) {
            return ToolResult.Error(
                "[E_TOO_LARGE] 单批最多 " + MAX_FILES + " 个文件，本批 " + plan.files.size + " 个；请拆成多批调用"
            )
        }
        val totalLines = plan.files.sumOf { file ->
            file.hunks.sumOf { it.oldLines.size + it.newLines.size }
        }
        if (totalLines > MAX_TOTAL_LINES) {
            return ToolResult.Error(
                "[E_TOO_LARGE] 单批最多 " + MAX_TOTAL_LINES + " 行，本批 " + totalLines + " 行；请拆成多批调用"
            )
        }

        // 全批预检：任一操作不通过都不写入任何文件，并把全部问题一次性告诉模型。
        val targets = ArrayList<Target>(plan.files.size)
        val refusals = ArrayList<String>()
        for (index in plan.files.indices) {
            val patchFile = plan.files[index]
            when (val prepared = prepare(ctx, patchFile)) {
                is PrepareResult.Failed -> refusals.add(
                    "- 第 " + (index + 1) + " 个操作（" + kindWord(patchFile.kind) + " " +
                        patchFile.path + "）：" + prepared.result.text
                )
                is PrepareResult.Prepared -> targets.add(prepared.target)
            }
        }
        if (refusals.isNotEmpty()) {
            return ToolResult.Error(
                "[E_PATCH_CONTEXT] 预检未通过，未写入任何文件（共 " + refusals.size + " 项问题）：\n" + refusals.joinToString("\n")
            )
        }

        if (dryRun) {
            val summary = targets.size.toString() + " 个文件（更新 " +
                targets.count { it.kind == PatchFileKind.UPDATE } + "、新增 " +
                targets.count { it.kind == PatchFileKind.ADD } + "、删除 " +
                targets.count { it.kind == PatchFileKind.DELETE } + "）"
            return ToolResult.Ok("预检通过（dry run，未写入）：" + summary)
        }

        // 提交阶段：按顺序执行；失败即停止，已完成的变更保留（不回滚）。
        val completed = ArrayList<String>()
        var failedOperation = -1
        var failureText = ""
        for (index in targets.indices) {
            val target = targets[index]
            try {
                when (target.kind) {
                    PatchFileKind.UPDATE, PatchFileKind.ADD -> writeAtomically(target.file, target.text ?: "")
                    PatchFileKind.DELETE -> if (!target.file.delete()) {
                        throw IOException("删除失败")
                    }
                }
            } catch (e: Exception) {
                failedOperation = index
                failureText = e.message ?: e.javaClass.simpleName
                break
            }
            completed.add(letterOf(target.kind) + " " + target.path)
        }

        if (failedOperation < 0) {
            return ToolResult.Ok("已更新以下文件：\n" + completed.joinToString("\n"))
        }

        val body = StringBuilder()
        body.append("补丁在第 ").append(failedOperation + 1).append(" 个操作处停止，已完成 ")
            .append(completed.size).append("/").append(targets.size).append(" 个操作。")
        if (completed.isNotEmpty()) {
            body.append("\n已完成：\n").append(completed.joinToString("\n"))
        }
        val failed = targets[failedOperation]
        body.append("\n失败：\n- 第 ").append(failedOperation + 1)
            .append(" 个操作（").append(kindWord(failed.kind)).append(' ').append(failed.path).append("）：")
            .append(failureText)
        val remaining = targets.drop(failedOperation + 1)
        if (remaining.isNotEmpty()) {
            body.append("\n未执行：")
            for ((offset, target) in remaining.withIndex()) {
                body.append("\n- 第 ").append(failedOperation + 2 + offset)
                    .append(" 个操作（").append(kindWord(target.kind)).append(' ').append(target.path).append("）")
            }
        }
        return ToolResult.Ok(body.toString())
    }

    private fun kindWord(kind: PatchFileKind): String = when (kind) {
        PatchFileKind.ADD -> "新增"
        PatchFileKind.UPDATE -> "更新"
        PatchFileKind.DELETE -> "删除"
    }

    private fun letterOf(kind: PatchFileKind): String = when (kind) {
        PatchFileKind.ADD -> "A"
        PatchFileKind.UPDATE -> "M"
        PatchFileKind.DELETE -> "D"
    }

    /** 单个文件的预检结果：要么给出可写入的目标，要么给出已可返回的失败结果。 */
    private sealed interface PrepareResult {
        data class Prepared(val target: Target) : PrepareResult
        data class Failed(val result: ToolResult.Error) : PrepareResult
    }

    private fun prepare(ctx: ToolContext, patchFile: PatchFile): PrepareResult {
        val path = patchFile.path
        val file = runCatching { PathGate.resolve(ctx.workspaceRoot, path) }
            .getOrElse {
                return PrepareResult.Failed(
                    ToolResult.Error("[E_PATH_OUTSIDE] 路径不合法：" + path + "；本批未写入任何文件")
                )
            }
        if (!PathGate.isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) {
            return PrepareResult.Failed(
                ToolResult.Error("[E_PATH_OUTSIDE] 路径越出工作区：" + path + "；本批未写入任何文件")
            )
        }
        if (file.exists() && file.isDirectory) {
            return PrepareResult.Failed(
                ToolResult.Error("[E_PATCH_CONTEXT] " + path + "：目标是目录，不能按文件处理；本批未写入任何文件")
            )
        }

        val exists = file.exists()
        when (patchFile.kind) {
            PatchFileKind.UPDATE -> if (!exists) {
                return PrepareResult.Failed(
                    ToolResult.Error("[E_PATCH_CONTEXT] " + path + "：文件不存在，无法更新；本批未写入任何文件")
                )
            }

            PatchFileKind.ADD -> if (exists) {
                return PrepareResult.Failed(
                    ToolResult.Error("[E_PATCH_CONTEXT] " + path + "：文件已存在，无法新增；本批未写入任何文件")
                )
            }

            PatchFileKind.DELETE -> if (!exists) {
                return PrepareResult.Failed(
                    ToolResult.Error("[E_PATCH_CONTEXT] " + path + "：文件不存在，无法删除；本批未写入任何文件")
                )
            }
        }

        HighRiskGate.protectedWriteReason(file)?.let { reason ->
            return PrepareResult.Failed(ToolResult.Error("[E_PROTECTED] " + reason + "；本批未写入任何文件"))
        }
        if (patchFile.kind == PatchFileKind.DELETE) {
            val decision = HighRiskGate.checkDelete(ctx, file, false)
            if (!decision.allowed) {
                return PrepareResult.Failed(
                    ToolResult.Error(
                        "[E_PROTECTED] " + (decision.message ?: "删除被安全闸门拒绝") + "；本批未写入任何文件"
                    )
                )
            }
            return PrepareResult.Prepared(
                Target(path, file, patchFile.kind, null, 0, 0)
            )
        }

        if (exists && file.length() > MAX_FILE_BYTES) {
            return PrepareResult.Failed(
                ToolResult.Error(
                    "[E_TOO_LARGE] " + path + "：文件 " + file.length() + " 字节，超过单文件上限 " +
                        (MAX_FILE_BYTES / (1024 * 1024)) + " MB；本批未写入任何文件"
                )
            )
        }

        val added = patchFile.hunks.sumOf { it.newLines.size }
        val removed = patchFile.hunks.sumOf { it.oldLines.size }
        if (patchFile.kind == PatchFileKind.ADD) {
            val content = patchFile.hunks.firstOrNull()?.newLines ?: emptyList()
            val text = if (content.isEmpty()) "" else content.joinToString("\n") + "\n"
            return finishPrepared(path, file, patchFile.kind, text, added, removed)
        }

        val document = try {
            readDocument(file)
        } catch (e: BinaryFileException) {
            return PrepareResult.Failed(
                ToolResult.Error("[E_BINARY] " + path + "：" + e.message + "；本批未写入任何文件，补丁只用于文本文件")
            )
        } catch (e: IOException) {
            return PrepareResult.Failed(
                ToolResult.Error(
                    "[E_WRITE] 读取失败：" + path + "：" + (e.message ?: e.javaClass.simpleName) +
                        "；本批未写入任何文件"
                )
            )
        }
        val outcome = applyHunks(document.lines, patchFile.hunks)
        val newLines = outcome.lines
        if (newLines == null) {
            return PrepareResult.Failed(
                ToolResult.Error(
                    "[E_PATCH_CONTEXT] " + path + "：第 " + (outcome.failedHunk + 1) +
                        " 块未匹配；本批未写入任何文件，请先用 read_file 读取原文再按现有内容重写该块"
                )
            )
        }
        return finishPrepared(path, file, patchFile.kind, document.render(newLines), added, removed)
    }

    private fun finishPrepared(
        path: String,
        file: File,
        kind: PatchFileKind,
        text: String,
        added: Int,
        removed: Int
    ): PrepareResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FILE_BYTES) {
            return PrepareResult.Failed(
                ToolResult.Error(
                    "[E_TOO_LARGE] " + path + "：写入后超过单文件上限 " +
                        (MAX_FILE_BYTES / (1024 * 1024)) + " MB；本批未写入任何文件"
                )
            )
        }
        return PrepareResult.Prepared(Target(path, file, kind, text, added, removed))
    }

    /** 预检通过的单个写入目标；[text] 仅对 UPDATE 与 ADD 有意义。 */
    private data class Target(
        val path: String,
        val file: File,
        val kind: PatchFileKind,
        val text: String?,
        val added: Int,
        val removed: Int
    )

    /**
     * 按顺序应用补丁块：每块从上一块结束处开始查找 [Hunk.oldLines] 的连续匹配，
     * 因此同一片段在文件中重复出现时，命中的是后续出现位置，而非再次命中同一处。
     */
    private fun applyHunks(original: List<String>, hunks: List<Hunk>): HunkOutcome {
        val work = original.toMutableList()
        var cursor = 0
        for (hunkIndex in hunks.indices) {
            val hunk = hunks[hunkIndex]
            if (hunk.oldLines.isEmpty()) {
                work.addAll(cursor, hunk.newLines)
                cursor += hunk.newLines.size
                continue
            }
            var found = -1
            var probe = cursor
            while (probe + hunk.oldLines.size <= work.size) {
                var matched = true
                for (offset in hunk.oldLines.indices) {
                    if (work[probe + offset] != hunk.oldLines[offset]) {
                        matched = false
                        break
                    }
                }
                if (matched) {
                    found = probe
                    break
                }
                probe++
            }
            if (found < 0) return HunkOutcome(null, hunkIndex)
            repeat(hunk.oldLines.size) { work.removeAt(found) }
            work.addAll(found, hunk.newLines)
            cursor = found + hunk.newLines.size
        }
        return HunkOutcome(work, -1)
    }

    /** [applyHunks] 的结论：[lines] 为 null 表示第 [failedHunk] 块未匹配。 */
    private class HunkOutcome(val lines: List<String>?, val failedHunk: Int)

    /**
     * 读取文本文件并识别 BOM 与行尾。
     *
     * 编码必须为合法 UTF-8 且不得含 NUL，否则按二进制拒绝：本工具只处理文本文件。
     */
    private fun readDocument(file: File): TextDocument {
        val bytes = file.readBytes()
        var offset = 0
        var bom = false
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            bom = true
            offset = 3
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        } catch (e: CharacterCodingException) {
            throw BinaryFileException("文件不是合法的 UTF-8 编码")
        }
        if (text.indexOf('\u0000') >= 0) {
            throw BinaryFileException("文件含 NUL 字节，按二进制处理")
        }
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val endsWithNewline = text.endsWith("\n")
        val lines = if (text.isEmpty()) {
            emptyList()
        } else {
            var parts = text.split('\n')
            if (endsWithNewline) parts = parts.dropLast(1)
            parts.map { it.removeSuffix("\r") }
        }
        return TextDocument(bom, eol, endsWithNewline, lines)
    }

    /** 文本内容及其编码元信息；写回时必须沿用 [bom] 与 [eol]。 */
    private class TextDocument(
        val bom: Boolean,
        val eol: String,
        val endsWithNewline: Boolean,
        val lines: List<String>
    ) {
        fun render(newLines: List<String>): String {
            val body = newLines.joinToString(eol)
            val tail = if (endsWithNewline && newLines.isNotEmpty()) eol else ""
            return (if (bom) "\uFEFF" else "") + body + tail
        }
    }

    private class BinaryFileException(message: String) : Exception(message)

    /**
     * 先写入同目录的临时文件，再用重命名替换目标。
     *
     * 重命名在同一目录内进行，目标要么保持原内容、要么为新内容，不会出现写了一半的文件。
     */
    private fun writeAtomically(file: File, text: String) {
        val parent = file.parentFile ?: throw IOException("目标没有父目录")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建父目录：" + parent.absolutePath)
        }
        val temp = File.createTempFile("patch-", ".tmp", parent)
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                throw IOException("无法以临时文件替换目标：" + file.absolutePath)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        /** 补丁正文上限：200 KB。 */
        const val MAX_PATCH_BYTES = 200 * 1024

        /** 单批文件数上限。 */
        const val MAX_FILES = 20

        /** 单批补丁块的行数上限（旧行与新行合计）。 */
        const val MAX_TOTAL_LINES = 2000

        /** 单文件大小上限：8 MB。 */
        const val MAX_FILE_BYTES = 8L * 1024L * 1024L
    }
}
