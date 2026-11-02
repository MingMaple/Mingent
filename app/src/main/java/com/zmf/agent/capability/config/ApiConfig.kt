package com.zmf.agent.capability.config
import com.zmf.agent.core.llm.ApiDefaults

import android.content.Context
import com.zmf.agent.infra.llm.ProviderStore

/**
 * 全局 API 配置。
 *
 * 数据由「一对全局 pref 键」调整为「多提供商」：Key、端点与模型均保存在
 * [ProviderStore] 的当前 provider 中（providers.json + app_prefs 的选中 id）。
 * **对外签名保持不变**，因此设置页、子智能体、定时任务、余额查询等调用方无需修改，
 * 它们自动读写当前 provider。
 *
 * 此处仅保留常量与当前 provider 无关的开关（思考档、检索端点）：
 * BASE_URL / MODEL / MODELS 是内置模板与默认值；Key 一律由用户填写，源码中不包含任何可用 Key。
 */
object ApiConfig {

    // 端点默认值、模型清单、思考强度可选值均已下沉到 core/llm/ApiDefaults：
    // 这些是 core 的 ProviderStore 与 infra 的 DeepSeekProvider 也需使用的默认值，
    // 若保留在此更高层的配置对象中，将导致 core/infra 反向依赖。

    private const val PREFS_NAME = "app_prefs"
    private const val KEY_THINKING = "thinking_enabled"
    private const val KEY_EFFORT = "thinking_effort"

    // 旧键 api_key / api_base_url / model 交由 ProviderStore 只读迁移：
    // 此处不再读写它们，用户既有取值由 ProviderStore 首次运行时写入 providers.json。

    /** 检索端点（Anthropic 兼容，官方 web_search 用），空串＝用 SEARCH_BASE_URL。 */
    private const val KEY_SEARCH_BASE_URL = "search_base_url"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 当前 provider 的 Key；空串表示未配置。
     *
     * 不对内置 DeepSeek 提供源码兜底：开源版本中没有可用的 Key，
     * 让调用方拿到空串（设置页显示未填写、请求时明确报错）比使用他人 Key 更安全。
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
        // 空地址仅对内置 DeepSeek 回落到官方端点；其他提供商留空则保持为空（DeepSeekProvider 会明确报错），
        // 否则用户填写的自定义 Key 会被发往 DeepSeek 域名
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
     * 与对话端点分开存储，因为自定义中转站的对话端点与检索端点通常不同。
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
     * 模型并非全局唯一：每个 provider 单独记录（ProviderStore 的 model_<id>），
     * 否则在 DeepSeek 与 OpenAI 之间切换时，模型名会互相覆盖为对方不支持的 id。
     * 优先使用已记录的模型；若它已从可选列表移除、或该 provider 尚未记录，则使用列表第一个；
     * 列表为空（自定义 provider）时回落到默认 MODEL。
     */
    fun loadModel(context: Context): String {
        val cfg = ProviderStore.current(context)
        val saved = ProviderStore.modelOf(context, cfg.id)
        return when {
            // 空列表表示用户自行填写的模型名，按已记录值使用，不做校验
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

    /** 写入思考开关；仅影响后续请求，不改变进行中的回合。 */
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

    /** 强度标签：即接口取值本身（low / high / max），非法值回落为默认值。 */
    fun effortLabel(effort: String): String =
        if (ApiDefaults.EFFORTS.contains(effort)) effort else ApiDefaults.DEFAULT_EFFORT
}
