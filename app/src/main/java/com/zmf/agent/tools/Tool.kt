package com.zmf.agent.tools

import android.content.Context
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject
import java.io.File

/** 权限档的中文名（用户可见一律中文）。 */
fun PermissionLevel.displayName(): String = when (this) {
    PermissionLevel.READ_ONLY -> "只读"
    PermissionLevel.WORKSPACE -> "工作区"
    PermissionLevel.FULL -> "完全"
}

/** 工具的下限权限：枚举顺序 READ_ONLY < WORKSPACE < FULL，可以直接比 ordinal。 */
fun PermissionLevel.atLeast(other: PermissionLevel): Boolean = this.ordinal >= other.ordinal

/**
 * 工具执行上下文：工作区根、当前权限档、应用 Context。
 *
 * workspaceRoot 由调用方（ChatViewModel）按当前工作区传入，
 * 不再是「永远等于默认工作区目录」，这样文件工具的相对路径才会跟着工作区走。
 */
data class ToolContext(
    val context: Context,
    val permission: PermissionLevel,
    val workspaceRoot: File,
    /** 当前会话 id：超大输出落盘按会话分目录；空串落到 default。 */
    val sessionId: String = ""
) {
    init {
        // 没有 context 的 ToolPolicy.checkPath 需要「当前工作区根」当越界比较基准；
        // ToolContext 是唯一保证这个根准确的入口（每次工具调用新建一个），所以在这里顺手刷新快照。
        ToolPolicy.bindWorkspaceRoot(workspaceRoot)
    }

    val workspace: File get() = workspaceRoot
}

/**
 * 工具执行结果。
 * 失败不是异常：错误文本会作为 tool 消息回给模型，让它自己改参数重试，
 * 这样「连续 5 轮工具调用」才不会因为一次越界直接断掉。
 */
sealed interface ToolResult {
    val ok: Boolean
    val text: String

    data class Ok(override val text: String) : ToolResult {
        override val ok: Boolean get() = true
    }

    data class Error(override val text: String) : ToolResult {
        override val ok: Boolean get() = false
    }
}

/** 一个可被模型调用的工具。 */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema 字符串，直接塞进 tools[].function.parameters。 */
    val parametersJson: String

    /** 使用本工具要求的最低权限档。 */
    val minPermission: PermissionLevel

    suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult
}

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
     * 现在 tmp 物理上就在工作区根里，单独判一次是让「可写根」与 DSH 的 sandbox 逐条对齐：
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

    fun canRead(ctx: ToolContext, file: File): Boolean {
        if (isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) return true
        return ctx.permission == PermissionLevel.FULL
    }

    fun canWrite(ctx: ToolContext, file: File): Boolean {
        if (ctx.permission == PermissionLevel.READ_ONLY) return false
        if (isInsideWorkspaceOrTemp(ctx.workspaceRoot, file)) return true
        return ctx.permission == PermissionLevel.FULL
    }
}
