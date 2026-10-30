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
 * 从 [ChatViewModel] 搬出，行为不变：状态字段名与原成员逐字一致，方法体原样照搬；
 * 提示词输入、上下文策略与摘要视图、子智能体依赖同步都用构造 lambda 注入，不反向攥住 ViewModel。
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

    /** 用户自填的 Key；没填过时 ApiConfig 会回落到源码里的默认 Key。 */
    var apiKey by mutableStateOf(ApiConfig.loadApiKey(appContext))
        private set

    /** 当前模型（deepseek-flash / deepseek-v4-pro / 别家的模型 id）；改了立刻重建请求链路。 */
    var model by mutableStateOf(ApiConfig.loadModel(appContext))
        private set

    /** 全部提供商；多提供商设置页直接读它，[currentProviderId] 指向其中一条。 */
    var providers by mutableStateOf<List<ProviderConfig>>(ProviderStore.load(appContext))
        private set

    /** 当前提供商 id；选中态落在 app_prefs 里，重启后还停在这一条。 */
    var currentProviderId by mutableStateOf(ProviderStore.currentId(appContext))
        private set

    // 端点与 Key 都取自当前 provider：一个 provider 都没配时 ProviderStore 会自动建一条内置
    // DeepSeek（老用户的 Key / 端点 / 模型都在里面），所以默认行为与单提供商版本完全一致
    private var provider = DeepSeekProvider(
        apiKey = ApiConfig.loadApiKey(appContext),
        baseUrl = ApiConfig.loadBaseUrl(appContext)
    )

    /** 请求链路只有这里能换：Key / 端点 / 模型都是构造时固定的。 */
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

    /** 当前请求对象（子智能体要拿同一个，否则它还在用旧 Key / 旧端点）。 */
    val currentProvider: LlmProvider get() = provider

    /** 保存并立即生效（重建 provider 与 loop）。 */
    fun applyApiKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isEmpty() || trimmed == apiKey) return
        apiKey = trimmed
        ApiConfig.saveApiKey(appContext, trimmed)
        // Key 写在当前 provider 里：设置页的列表也要跟着变
        providers = ProviderStore.load(appContext)
        rebuildClient()
        FileLog.log("api", "已更新 API Key（长度 " + trimmed.length + "）")
    }

    /** 切换模型：落盘并重建请求链路；值没变时直接返回，避免无谓的重建。 */
    fun applyModel(newModel: String) {
        if (newModel == model) return
        model = newModel
        ApiConfig.saveModel(appContext, newModel)
        // 用 rebuildPipeline 而不是 rebuildClient：后者会把模型重新读一遍，
        // 而「记下的模型不在 provider 的模型列表里就回落第一个」会把刚选的值悄悄改掉
        rebuildPipeline()
        FileLog.log("api", "模型切到 " + newModel)
    }

    /**
     * 保存当前 provider 的对话端点并立刻生效（重建 provider 与 loop）。
     *
     * 空串对内置 DeepSeek 等于「回落官方地址」（ApiConfig 内部处理），别家则保持空、由 provider 报错提示。
     */
    fun applyBaseUrl(url: String) {
        val before = ApiConfig.loadBaseUrl(appContext)
        ApiConfig.saveBaseUrl(appContext, url)
        // 端点是当前 provider 的一部分：设置页的列表也要跟着变
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

    /** 新增或整条替换一个提供商；改的正好是当前这条时才需要重建请求链路。 */
    fun saveProvider(cfg: ProviderConfig) {
        ProviderStore.upsert(appContext, cfg)
        providers = ProviderStore.load(appContext)
        if (cfg.id == currentProviderId) rebuildClient()
        FileLog.log("api", "保存提供商 " + cfg.id + "（" + cfg.name + "）")
    }

    /** 删除提供商；最后一个删不掉（[ProviderStore.remove] 会拒绝，这里什么都不做）。 */
    fun deleteProvider(id: String) {
        if (!ProviderStore.remove(appContext, id)) return
        providers = ProviderStore.load(appContext)
        // 删的是当前 provider：ProviderStore 已把选中态挪到第一个，这里跟着切过去重建
        if (ProviderStore.currentId(appContext) != currentProviderId) rebuildClient()
        FileLog.log("api", "删除提供商 " + id + "，当前=" + currentProviderId)
    }

    /** 换 Key / 换端点 / 换提供商后重建请求链路：Key、端点、模型都重新从当前 provider 读一遍。 */
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
     * 单独拆出来是给「换模型」用的（见 [applyModel]）。
     */
    private fun rebuildPipeline() {
        provider = DeepSeekProvider(apiKey = apiKey, baseUrl = ApiConfig.loadBaseUrl(appContext))
        // provider 是新对象：子智能体必须拿到新的那个，否则它还在用旧 Key / 旧端点
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
