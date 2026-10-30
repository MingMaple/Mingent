package com.zmf.agent.core.fs

import android.content.Context

import java.io.File

/**
 * 路径闸门：把「权限三档」落到文件路径上。
 *
 * - 只读：只能读工作区内的文件；写操作一律拒绝。
 * - 工作区：工作区内可读可写；越界路径拒绝。
 * - 完全：任意路径可读可写。
 *
 * 工作区根由 ToolContext.workspaceRoot 决定（默认工作区仍是 getExternalFilesDir()/workspace，
 * adb 可直接 pull；自建工作区是 externalFilesDir/workspaces/<dirName>），默认根的便捷重载保留。
 */
object PathGate {

    private const val WORKSPACE_DIR = "workspace"

    /** 工作区里给工具放临时文件的子目录名：它是工作区档允许写的第二根。 */
    private const val TMP_DIR = "tmp"

    /** 默认工作区根：externalFilesDir/workspace（外部存储不可用时退回内部 filesDir），不存在就建。 */
    fun workspaceRoot(context: Context): File {
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        val dir = File(base, WORKSPACE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 相对路径接在指定根下；绝对路径按原样。 */
    fun resolve(root: File, rawPath: String): File {
        val trimmed = rawPath.trim()
        require(trimmed.isNotEmpty()) { "路径为空" }
        return if (trimmed.startsWith("/")) File(trimmed) else File(root, trimmed)
    }

    /** 便捷重载：按默认工作区根解析（兼容旧调用）。 */
    fun resolve(context: Context, rawPath: String): File = resolve(workspaceRoot(context), rawPath)

    /** 目标是否在指定根之内（canonical 比较）。 */
    fun isInside(root: File, file: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { canonicalOrNearest(file) }.getOrNull() ?: return false
        return target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)
    }

    /**
     * 目标是否在工作区根或 `<workspace>/tmp` 内（canonical 比较）。
     *
     * 现在 tmp 物理上就在工作区根里，单独判一次是让「可写根」与提示词承诺的沙箱范围逐条对齐：
     * 提示词向模型承诺的就是「workspace 与 workspace/tmp 可写」，以后 tmp 真挪出去也不用改调用方。
     * 用户可见的路径闸（ToolPolicy.checkPath）也复用这里，避免两处各写一套越界判断。
     */
    fun isInsideWorkspaceOrTemp(root: File, file: File): Boolean =
        isInside(root, file) || isInside(File(root, TMP_DIR), file)

    /** 便捷重载：按默认工作区根判断（兼容旧调用）。 */
    fun isInsideWorkspace(context: Context, file: File): Boolean = isInside(workspaceRoot(context), file)

    private fun canonicalOrNearest(file: File): File {
        var cursor: File? = file.absoluteFile
        val tail = ArrayList<String>()
        while (cursor != null && !cursor.exists()) {
            cursor.name.takeIf { it.isNotEmpty() }?.let { tail.add(0, it) }
            cursor = cursor.parentFile
        }
        var result = cursor?.canonicalFile ?: file.absoluteFile
        for (name in tail) result = File(result, name)
        return result
    }

    /**
     * 规范化失败（路径非法、权限异常）返回 null。
     *
     * 与 [canonicalOrNearest] 的区别：这里**不**给不存在的路径兜底拼回尾部，
     * 用于「必须确认真实落点」的判定（高危删除）。
     */
    fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()

    /**
     * 规范化后的绝对路径；路径还不存在时按「最近存在的祖先 + 剩余段」拼出来。
     *
     * 供「按真实落点判定」的调用方使用（回收站读面封锁）：既能识破软链接，
     * 又不会因为目标刚被删掉就返回 null。
     */
    fun canonicalPathOrNull(file: File): String? =
        runCatching { canonicalOrNearest(file).path }.getOrNull()

    /** 是否符号链接（不跟随）。删除时不进链接内部，只删链接本身。 */
    fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * [file] 相对 [root] 的层级深度：root 本身 0，直接子项 1，依此类推；不在 root 下返回 -1。
     *
     * 用「就近规范化」而不是严格 canonical：删除目标常常还不存在（拼错/刚被删），
     * 这时仍要能按它的意图位置判深度。
     */
    fun depthFrom(root: File, file: File): Int {
        val canonicalRoot = runCatching { canonicalOrNearest(root) }.getOrNull() ?: return -1
        val target = runCatching { canonicalOrNearest(file) }.getOrNull() ?: return -1
        return depthOf(canonicalRoot, target)
    }

    /** 同 [depthFrom]，但两个参数都已是规范化路径（调用方自己 canonical 过时用）。 */
    internal fun depthOf(canonicalRoot: File, target: File): Int {
        if (target.path == canonicalRoot.path) return 0
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        if (!target.path.startsWith(prefix)) return -1
        return target.path.removePrefix(prefix).count { it == File.separatorChar } + 1
    }

    /** [maybeAncestor] 是否等于 [file]，或是它的祖先目录（canonical 比较）。 */
    fun isSameOrAncestor(maybeAncestor: File, file: File): Boolean {
        val a = runCatching { canonicalOrNearest(maybeAncestor) }.getOrNull() ?: return false
        val b = runCatching { canonicalOrNearest(file) }.getOrNull() ?: return false
        return b.path == a.path || b.path.startsWith(a.path.trimEnd(File.separatorChar) + File.separator)
    }

    /** 就近规范化的公开入口：高危闸门判「最终落点」时用。 */
    fun canonicalOrNearestOrNull(file: File): File? =
        runCatching { canonicalOrNearest(file) }.getOrNull()

}
