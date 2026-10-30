package com.zmf.agent.capability.config
import com.zmf.agent.core.llm.ApiDefaults

import android.content.Context
import com.zmf.agent.infra.llm.ProviderStore

/**
 * 全局 API 配置。
 *
 * 数据从「一对全局 pref 键」升级成了「多提供商」：Key / 端点 / 模型现在都存在
 * [ProviderStore] 的当前 provider 里（providers.json + app_prefs 的选中 id）。
 * **对外签名一个都没变**，所以设置页、子智能体、定时任务、余额查询这些调用方不用改，
 * 它们自动就是「读写当前 provider」。
 *
 * 还留在这里的只有常量与当前 provider 无关的开关（思考档、检索端点）：
 * BASE_URL / MODEL / MODELS 是内置模板与默认值；Key 一律由用户自己填，源码里不带任何可用 Key。
 */
object ApiConfig {

    // 端点默认值、模型清单、思考强度可选值都已下沉到 core/llm/ApiDefaults：
    // 那些是 core 的 ProviderStore 与 infra 的 DeepSeekProvider 也要用的默认值，
    // 留在这个更高层的配置对象里，会让 core/infra 反向依赖它。

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_THINKING = "thinking_enabled"
    private const val KEY_EFFORT = "thinking_effort"

    // 旧的 api_key / api_base_url / model 三个键已交给 ProviderStore 只读迁移：
    // 这里不再读写它们，老用户的值由 ProviderStore 首次运行时搬进 providers.json。

    /** 检索端点（Anthropic 兼容，官方 web_search 用），空串＝用 SEARCH_BASE_URL。 */
    private const val KEY_SEARCH_BASE_URL = "search_base_url"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 当前 provider 的 Key；空串就是「未配置」。
     *
     * 不再对内置 DeepSeek 做源码兜底：开源版本里没有可用的 Key，
     * 让调用方拿到空串（设置页显示未填写、请求时明确报错）比悄悄用别人的 Key 安全。
     */
    fun loadApiKey(context: Context): String = ProviderStore.current(context).apiKey.trim()

    /** 写回当前 provider（其余 provider 的 Key 不受影响）。 */
    fun saveApiKey(context: Context, key: String) {
        val cfg = ProviderStore.current(context)
        ProviderStore.upsert(context, cfg.copy(apiKey = key.trim()))
    }

    /**
     * 当前 provider 的对话端点。
     *
     * 末尾的斜杠统一去掉，拼接路径时不会出现 "//chat/completions"。
     */
    fun loadBaseUrl(context: Context): String {
        val cfg = ProviderStore.current(context)
        val saved = cfg.baseUrl.trim().trimEnd('/')
        // 空地址只对内置 DeepSeek 回落官方端点；别家留空就保持空（DeepSeekProvider 会明确报错），
        // 否则用户填的自定义 Key 会被悄悄发到 DeepSeek 的域名上去
        return if (saved.isEmpty() && cfg.id == ProviderStore.DEEPSEEK_ID) ApiDefaults.BASE_URL else saved
    }

    /** 写回当前 provider 的对话端点，末尾斜杠统一去掉。 */
    fun saveBaseUrl(context: Context, url: String) {
        val cfg = ProviderStore.current(context)
        ProviderStore.upsert(context, cfg.copy(baseUrl = url.trim().trimEnd('/')))
    }

    /**
     * 检索端点：官方 web_search 走 Anthropic 兼容格式（POST {base}/messages）。
     *
     * 和对话端点分开存，因为自定义中转站的对话端点和检索端点常常不同。
     */
    fun loadSearchBaseUrl(context: Context): String {
        val saved = prefs(context).getString(KEY_SEARCH_BASE_URL, null)
        return if (saved.isNullOrBlank()) ApiDefaults.SEARCH_BASE_URL else saved.trimEnd('/')
    }

    /** 写回检索端点；保存空串即恢复使用 [SEARCH_BASE_URL]。 */
    fun saveSearchBaseUrl(context: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_SEARCH_BASE_URL, clean).apply()
    }

    /**
     * 当前 provider 上次用的模型。
     *
     * 模型不是全局一个：每个 provider 各记各的（ProviderStore 的 model_<id>），
     * 否则在 DeepSeek 与 OpenAI 之间切一下，模型名就互相覆盖成一个对方不认的 id。
     * 优先用记住的那个；它被从可选列表里删掉、或这个 provider 还没记过，就用列表第一个；
     * 列表本身是空的（自定义 provider）则回落到默认的 MODEL。
     */
    fun loadModel(context: Context): String {
        val cfg = ProviderStore.current(context)
        val saved = ProviderStore.modelOf(context, cfg.id)
        return when {
            // 空列表 = 用户自己填的模型名，记住什么就用什么，不做校验
            saved.isNotEmpty() && (cfg.models.isEmpty() || cfg.models.contains(saved)) -> saved
            cfg.models.isNotEmpty() -> cfg.models.first()
            else -> ApiDefaults.MODEL
        }
    }

    /** 记住当前 provider 使用的模型（按 provider id 分开存放）。 */
    fun saveModel(context: Context, model: String) {
        ProviderStore.saveModel(context, ProviderStore.currentId(context), model.trim())
    }

    /** 思考开关，默认打开。 */
    fun loadThinkingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_THINKING, true)

    /** 写入思考开关；只影响之后的请求，正在跑的那一轮不再改。 */
    fun saveThinkingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_THINKING, enabled).apply()
    }

    /** 思考强度，默认「高」。只在思考开启时随请求发出。 */
    fun loadThinkingEffort(context: Context): String {
        val saved = prefs(context).getString(KEY_EFFORT, null)
        return if (saved.isNullOrBlank()) ApiDefaults.DEFAULT_EFFORT else saved
    }

    /** 保存思考强度；非法值由 [loadThinkingEffort] 与 [effortLabel] 负责回落。 */
    fun saveThinkingEffort(context: Context, effort: String) {
        prefs(context).edit().putString(KEY_EFFORT, effort).apply()
    }

    /** 强度标签：就是接口取值本身（low / high / max），非法值回落默认。 */
    fun effortLabel(effort: String): String =
        if (ApiDefaults.EFFORTS.contains(effort)) effort else ApiDefaults.DEFAULT_EFFORT
}
