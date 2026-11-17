package com.zmf.agent.capability.tools.context.context_status

import com.zmf.agent.capability.port.ContextBreakdown
import com.zmf.agent.capability.port.ContextPolicyView
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.tools.Tool
import com.zmf.agent.capability.tools.ToolContext
import com.zmf.agent.capability.tools.ToolResult
import com.zmf.agent.core.model.PermissionLevel
import org.json.JSONObject
import kotlin.math.round

/**
 * 查看上下文水位。
 *
 * 只读取界面发布的快照，不触发压缩，也不修改会话与策略。
 * 水位按请求视图估算，分段按整份请求统计，两者口径不同，回执以固定说明区分。
 */
class ContextStatusTool : Tool {

    override val name = "context_status"

    override val description =
        "读取当前会话的上下文水位与分段占用，返回只读快照。" +
            "不触发压缩，也不修改会话与策略。" +
            "detail 为 true 时附带分段与策略明细；水位只计请求视图；只读档可用。"

    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "detail": {"type": "boolean", "description": "为 true 时附带分段占用与压缩策略明细；默认 false"}
          },
          "required": []
        }
    """.trimIndent()

    override val minPermission = PermissionLevel.READ_ONLY

    override val mutates = false

    override suspend fun invoke(args: JSONObject, ctx: ToolContext): ToolResult {
        val metrics = ToolEnv.services().contextMetrics
            ?: return ToolResult.Error(
                "[E_CONTEXT_UNAVAILABLE] 上下文度量服务尚未装配，无法读取；此项不能通过调整参数解决。"
            )
        val reading = metrics.read(ctx.sessionId)
            ?: return ToolResult.Error(
                "[E_CONTEXT_UNAVAILABLE] 当前会话尚未绑定上下文读数；请在本会话内重试或稍后再试。"
            )

        val root = JSONObject()
            .put("tokens", reading.estimatedTokens)
            .put("window", reading.windowTokens)
            .put("ratio", reading.ratio)
            .put("percent", round(reading.ratio * 100f).toInt())
            .put("coveredMessages", reading.coveredMessages)
            .put("totalMessages", reading.totalMessages)
            .put("hasSummary", reading.hasSummary)
            .put("lastSummaryAt", reading.lastSummaryAt)
            .put("note", NOTE)

        if (args.optBoolean("detail", false)) {
            root.put("breakdown", breakdownJson(reading.breakdown))
            root.put("policy", policyJson(reading.policy))
        }
        return ToolResult.Ok(root.toString())
    }

    private fun breakdownJson(value: ContextBreakdown): JSONObject = JSONObject()
        .put("system", value.system)
        .put("skills", value.skills)
        .put("summary", value.summary)
        .put("tools", value.tools)
        .put("user", value.user)
        .put("assistant", value.assistant)
        .put("toolResults", value.toolResults)

    private fun policyJson(value: ContextPolicyView): JSONObject = JSONObject()
        .put("enabled", value.enabled)
        .put("triggerRatio", value.triggerRatio)
        .put("triggerCount", value.triggerCount)
        .put("keepRounds", value.keepRounds)
        .put("retainRatio", value.retainRatio)
        .put("retainTokens", value.retainTokens)

    private companion object {
        /** 两种统计口径不同，必须在回执中固定说明，避免模型把分段合计当作水位。 */
        const val NOTE = "水位只计请求视图；分段按整份请求统计，两者数值不相等。"
    }
}
