package com.zmf.agent.feature.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zmf.agent.capability.config.ApiConfig
import com.zmf.agent.capability.context.ContextPolicy
import com.zmf.agent.capability.loop.AgentLoop
import com.zmf.agent.capability.loop.ContextSummaryState
import com.zmf.agent.capability.prompt.PromptInput
import com.zmf.agent.capability.tools.ToolRegistry
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.infra.llm.DeepSeekProvider
import com.zmf.agent.infra.llm.LlmProvider
import com.zmf.agent.infra.llm.ProviderConfig
import com.zmf.agent.infra.llm.ProviderStore

/**
 * 模型与提供商配置：Key、模型、端点、提供商列表，以及换配置时重建请求链路。
 *
 * 从 [ChatViewModel] 拆出，行为不变：状态字段名与原成员逐字一致，方法体原样保留；
 * 提示词输入、上下文策略与摘要视图、子智能体依赖同步均以构造 lambda 注入，不反向持有 ViewModel。
 */
class LlmConfigController(
    private val appContext: Context,
    private val registry: ToolRegistry,
    private val maxRounds: Int,
    private val promptInput: () -> PromptInput,
    private val contextPolicy: () -> ContextPolicy,
    private val contextState: () -> ContextSummaryState,
    private val syncSubAgentDeps: () -> Unit
) {

    /** 用户自填的 Key；未填写时 ApiConfig 会回退至源码中的默认 Key。 */
    var apiKey by mutableStateOf(ApiConfig.loadApiKey(appContext))
        private set

    /** 当前模型（deepseek-flash / deepseek-v4-pro / 其他厂商模型 id）；修改后立即重建请求链路。 */
    var model by mutableStateOf(ApiConfig.loadModel(appContext))
        private set

    /** 全部提供商；多提供商设置页直接读它，[currentProviderId] 指向其中一条。 */
    var providers by mutableStateOf<List<ProviderConfig>>(ProviderStore.load(appContext))
        private set

    /** 当前提供商 id；选中态存于 app_prefs，重启后仍停留于该项。 */
    var currentProviderId by mutableStateOf(ProviderStore.currentId(appContext))
        private set

    // 端点与 Key 均取自当前 provider：未配置任何 provider 时 ProviderStore 会自动创建一条内置
    // DeepSeek（旧用户的 Key / 端点 / 模型均在其中），因此默认行为与单提供商版本完全一致
    private var provider = DeepSeekProvider(
        apiKey = ApiConfig.loadApiKey(appContext),
        baseUrl = ApiConfig.loadBaseUrl(appContext)
    )

    /** 请求链路仅此处可更换：Key / 端点 / 模型均为构造时固定。 */
    var loop: AgentLoop = AgentLoop(
        provider = provider,
        registry = registry,
        model = model,
        maxRounds = maxRounds,
        contextPolicy = contextPolicy,
        contextState = contextState,
        promptProvider = promptInput
    )
        private set

    /** 当前请求对象（子智能体需使用同一个，否则仍在使用旧 Key / 旧端点）。 */
    val currentProvider: LlmProvider get() = provider

    /** 保存并立即生效（重建 provider 与 loop）。 */
    fun applyApiKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isEmpty() || trimmed == apiKey) return
        apiKey = trimmed
        ApiConfig.saveApiKey(appContext, trimmed)
        // Key 写入当前 provider：设置页的列表也需同步变化
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "已更新 API Key（长度 " + trimmed.length + "）")
    }

    /** 切换模型：落盘并重建请求链路；值未变化时直接返回，避免无谓重建。 */
    fun applyModel(newModel: String) {
        if (newModel == model) return
        model = newModel
        ApiConfig.saveModel(appContext, newModel)
        // 使用 rebuildPipeline 而非 rebuildClient：后者会重新读取模型，
        // 而「记录的模型不在 provider 的模型列表中即回退第一个」会静默修改刚选的值
        rebuildPipeline()
        FileLog.log("api", "模型切到 " + newModel)
    }

    /**
     * 保存当前 provider 的对话端点并立刻生效（重建 provider 与 loop）。
     *
     * 空串对内置 DeepSeek 等于「回退官方地址」（ApiConfig 内部处理），其他厂商则保持空，由 provider 报错提示。
     */
    fun applyBaseUrl(url: String) {
        val before = ApiConfig.loadBaseUrl(appContext)
        ApiConfig.saveBaseUrl(appContext, url)
        // 端点为当前 provider 的一部分：设置页的列表也需同步变化
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "对话端点：" + before + " → " + ApiConfig.loadBaseUrl(appContext))
    }

    /**
     * 切换当前提供商：落盘 + 重建请求链路 + 刷新列表状态。
     *
     * Key / 端点 / 模型全都跟着 provider 走，所以不在这里单独读它们：[rebuildClient] 统一读。
     */
    fun selectProvider(id: String) {
        if (providers.none { it.id == id }) return
        if (id == currentProviderId) return
        ProviderStore.select(appContext, id)
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "切换提供商 " + id + "（模型=" + model + "）")
    }

    /** 新增或整条替换一个提供商；仅当修改的正是当前项时才需重建请求链路。 */
    fun saveProvider(cfg: ProviderConfig) {
        ProviderStore.upsert(appContext, cfg)
        providers = ProviderStore.load(appContext)
        if (cfg.id == currentProviderId) rebuildClient()
        FileLog.log("api", "保存提供商 " + cfg.id + "（" + cfg.name + "）")
    }

    /** 删除提供商；最后一个不可删除（[ProviderStore.remove] 会拒绝，此处不执行任何操作）。 */
    fun deleteProvider(id: String) {
        if (!ProviderStore.remove(appContext, id)) return
        providers = ProviderStore.load(appContext)
        // 删除的是当前 provider：ProviderStore 已将选中态移至第一个，此处随之切换并重建
        if (ProviderStore.currentId(appContext) != currentProviderId) rebuildClient()
        FileLog.log("api", "删除提供商 " + id + "，当前=" + currentProviderId)
    }

    /** 更换 Key / 端点 / 提供商后重建请求链路：Key、端点、模型均重新从当前 provider 读取。 */
    private fun rebuildClient() {
        val cfg = ProviderStore.current(appContext)
        currentProviderId = cfg.id
        apiKey = ApiConfig.loadApiKey(appContext)
        model = ApiConfig.loadModel(appContext)
        rebuildPipeline()
    }

    /**
     * 只换请求对象、不重新读配置。
     *
     * provider 与 loop 的 Key / 端点 / 模型都是构造时固定的，配置一变就必须换一套新对象；
     * 单独拆出供「换模型」使用（见 [applyModel]）。
     */
    private fun rebuildPipeline() {
        provider = DeepSeekProvider(apiKey = apiKey, baseUrl = ApiConfig.loadBaseUrl(appContext))
        // provider 为新对象：子智能体必须获取新实例，否则仍在使用旧 Key / 旧端点
        syncSubAgentDeps()
        loop = AgentLoop(
            provider = provider,
            registry = registry,
            model = model,
            maxRounds = maxRounds,
            contextPolicy = contextPolicy,
            contextState = contextState,
            promptProvider = promptInput
        )
    }
}
