package com.zmf.agent.core.model

/**
 * 会话级偏好的落盘取值。
 *
 * 权限档与思考强度都要写进 `sessions.json` 并在界面、工具、请求组装三处读回，
 * 因此取值必须是一份稳定的字符串常量，不能依赖枚举序号或界面文案。
 */
object SessionPreference {

    /** 思考关闭：请求中不携带思考参数。 */
    const val EFFORT_OFF = "off"

    /** 思考强度可选项，与接口 `reasoning_effort` 的取值一致，`off` 为本应用自加的关闭档。 */
    val EFFORTS: List<String> = listOf(EFFORT_OFF, "low", "high", "max")

    /** 缺省强度：与既有全局默认一致。 */
    const val EFFORT_DEFAULT = "high"

    /** 规整强度取值；不认识时返回 null，由调用方决定兜底。 */
    fun effortOrNull(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        return if (value in EFFORTS) value else null
    }
}

/** 权限档的落盘名：与工具策略前缀、提示词里的叫法保持同一套。 */
fun PermissionLevel.wireName(): String = when (this) {
    PermissionLevel.READ_ONLY -> "read-only"
    PermissionLevel.WORKSPACE -> "workspace-write"
    PermissionLevel.FULL -> "danger-full-access"
}

/** 落盘名反解；不认识时返回 null，由调用方回落到当前全局默认。 */
fun permissionLevelOfWire(raw: String?): PermissionLevel? =
    when (raw?.trim()?.lowercase()) {
        "read-only", "read_only", "readonly" -> PermissionLevel.READ_ONLY
        "workspace-write", "workspace_write", "workspace" -> PermissionLevel.WORKSPACE
        "danger-full-access", "danger_full_access", "full", "danger" -> PermissionLevel.FULL
        else -> null
    }
