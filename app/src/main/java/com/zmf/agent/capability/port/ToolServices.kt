package com.zmf.agent.capability.port

import com.zmf.agent.core.log.FileLog
import kotlin.reflect.KClass

/**
 * 工具可用的跨层服务集合。
 *
 * 字段为 null 表示该服务尚未装配；依赖它的工具包在装配期整体不注册，
 * 不在运行期退化为「工具存在但不可用」。
 */
data class ToolServices(
    val delivery: SessionDelivery? = null,
    val notifier: UserNotifier? = null,
    val screenCapture: ScreenCapture? = null,
    val contextMetrics: ContextMetrics? = null,
    val imageGenerator: ImageGenerator? = null,
    val webControl: WebControl? = null
) {
    /** 尚未装配的服务类型；[requires] 为空表示无跨层依赖。 */
    fun missing(requires: Set<KClass<out Any>>): List<String> {
        if (requires.isEmpty()) return emptyList()
        return requires.mapNotNull { type ->
            val installed = when (type) {
                SessionDelivery::class -> delivery
                UserNotifier::class -> notifier
                ScreenCapture::class -> screenCapture
                ContextMetrics::class -> contextMetrics
                ImageGenerator::class -> imageGenerator
                WebControl::class -> webControl
                else -> null
            }
            if (installed == null) type.simpleName ?: "未知服务" else null
        }
    }
}

/**
 * 工具服务装配点。
 *
 * 组合根在进程启动时安装一次；界面层在绑定会话时补装会话相关端口。
 * 读写均同步，读取不加锁：替换为整体引用赋值。
 */
object ToolEnv {

    private const val TAG = "tools"

    @Volatile
    private var current = ToolServices()

    /** 当前服务集合。 */
    fun services(): ToolServices = current

    /** 整体替换。 */
    fun install(value: ToolServices) {
        current = value
    }

    /** 局部补充；用于界面绑定后补装端口。 */
    @Synchronized
    fun attach(block: (ToolServices) -> ToolServices) {
        current = block(current)
    }

    /** 注销全部服务；仅测试使用。 */
    fun reset() {
        current = ToolServices()
    }

    /** 装配期校验：返回缺失的服务名，并记一条错误日志；只影响当前工具，不影响其他工具。 */
    fun verify(toolId: String, requires: Set<KClass<out Any>>): List<String> {
        val missing = current.missing(requires)
        if (missing.isNotEmpty()) {
            FileLog.log(TAG, "工具「" + toolId + "」缺少服务：" + missing.joinToString(", ") + "，该工具未注册")
        }
        return missing
    }
}
