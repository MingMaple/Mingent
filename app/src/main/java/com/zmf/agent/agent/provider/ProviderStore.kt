package com.zmf.agent.agent.provider

import android.content.Context
import com.zmf.agent.agent.ApiConfig
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个可用的模型服务商。
 *
 * 请求侧只实现 OpenAI 兼容的 /chat/completions（见 [DeepSeekProvider]），所以 Anthropic 原生协议
 * 不在这里建模：多一列协议类型只会让「什么情况下能用」变复杂，而 ollama / vllm / 各家云厂商的
 * 兼容端点都能用同一套字段填。
 *
 * @param id 稳定标识（"deepseek" / "openai" / "kimi" / "custom-<毫秒时间戳>"），会话与偏好都按它引用
 * @param models 可用模型 id；空列表表示「交给用户自己填」，[ApiConfig.loadModel] 会记住上次用的那个
 * @param builtIn 来自内置目录的模板（界面上据此显示「内置」并禁止改 id / 删除 id 语义）
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String>,
    val builtIn: Boolean = false
)

/**
 * 多提供商落盘：filesDir/appdata/providers.json + app_prefs 里的选中态。
 *
 * 为什么不是一个数据库：提供商是用户在设置页手填出来的，几条到十几条量级，
 * 和 sessions.json / workspaces.json 一样「整份覆盖写」最省事，也避免引入新依赖。
 *
 * 一个进程只读一次盘（[cache]）：[ApiConfig] 每次拼提示词 / 发请求都要问当前 provider，
 * 每次都读盘纯属浪费；所有写入都走 [save]，缓存和文件不会走偏。
 */
object ProviderStore {

    private const val TAG = "provider"
    private const val FILE_NAME = "providers.json"

    private const val PREFS_NAME = "app_prefs"

    /** 当前选中的 provider id（唯一跨文件的状态，放 app_prefs）。 */
    private const val KEY_CURRENT = "provider_current"

    /** 每个 provider 记住「上次用的模型」：key = model_<providerId>。 */
    private const val KEY_MODEL_PREFIX = "model_"

    private const val KEY_PROVIDERS = "providers"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_API_KEY = "apiKey"
    private const val KEY_MODELS = "models"
    private const val KEY_BUILT_IN = "builtIn"

    /**
     * 迁移时读的旧键名，语义与 [ApiConfig] 里的同名键一一对应。
     *
     * 这里直接读 prefs 而不是调 ApiConfig.loadXxx：ApiConfig 现在反过来要问 ProviderStore
     * 「当前 provider」，绕回去就是 load → 迁移 → load 的无限递归。
     */
    private const val LEGACY_KEY_API_KEY = "api_key"
    private const val LEGACY_KEY_BASE_URL = "api_base_url"
    private const val LEGACY_KEY_MODEL = "model"

    /** 内置 DeepSeek 的固定 id：只有它允许回落到源码里的兜底 Key / 官方端点。 */
    const val DEEPSEEK_ID = "deepseek"

    const val OPENAI_ID = "openai"
    const val KIMI_ID = "kimi"

    /** 自定义模板的 id 前缀；真建出来的 provider id 是 custom-<毫秒时间戳>。 */
    const val CUSTOM_PREFIX = "custom"

    /** 进程内唯一的一份列表；null = 还没读过盘。 */
    @Volatile
    private var cache: List<ProviderConfig>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun storeFile(context: Context): File = AppPaths.dataFile(context, FILE_NAME)

    // ---- 对外 ----

    /**
     * 读全部 provider；结果保证非空。
     *
     * 首次运行（文件不存在）时用旧的单 provider 配置迁一条 id="deepseek" 出来，
     * 老用户填过的 Key / 端点 / 模型一个不丢；文件坏掉则回落到同一份默认值并顺手修好文件
     * （坏文件里本来也读不出东西，留着只会每次启动都失败一次）。
     */
    fun load(context: Context): List<ProviderConfig> {
        cache?.let { return it }
        val file = storeFile(context)
        if (!file.exists()) return migrate(context)
        val text = if (file.length() == 0L) {
            ""
        } else {
            runCatching { file.readText(Charsets.UTF_8) }.getOrElse { e ->
                FileLog.log(TAG, "读取提供商配置失败：" + e)
                ""
            }
        }
        if (text.isBlank()) return repair(context)
        val parsed = runCatching { parse(text) }.getOrElse { e ->
            FileLog.log(TAG, "providers.json 解析失败：" + e)
            emptyList()
        }
        if (parsed.isEmpty()) return repair(context)
        cache = parsed
        return parsed
    }

    /** 整体覆盖写（.tmp + rename）；失败只记日志，界面上最多是这次改动没生效，不会崩。 */
    fun save(context: Context, list: List<ProviderConfig>) {
        // 空列表等于「App 一个模型都用不了」，直接拒掉：宁可保留磁盘上的旧配置
        if (list.isEmpty()) {
            FileLog.log(TAG, "拒绝保存空的提供商列表")
            return
        }
        try {
            val array = JSONArray()
            for (cfg in list) array.put(cfg.toJson())
            val json = JSONObject().put(KEY_PROVIDERS, array).toString()

            val file = storeFile(context)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, FILE_NAME + ".tmp")
            temp.writeText(json, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // rename 失败（个别文件系统）时退化成直接覆盖，保证内容落地
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
            cache = list
        } catch (e: Exception) {
            // 写到盘上的可能只是半截：缓存立刻作废，下次 load 以文件为准
            cache = null
            FileLog.log(TAG, "保存提供商失败：" + e)
        }
    }

    /** 当前选中的 provider id；存的是已删除的 id 时回落到第一个，并把 pref 顺手改对。 */
    fun currentId(context: Context): String {
        val list = load(context)
        val saved = prefs(context).getString(KEY_CURRENT, null)?.trim().orEmpty()
        if (saved.isNotEmpty() && list.any { it.id == saved }) return saved
        val fallback = list.first().id
        if (saved != fallback) prefs(context).edit().putString(KEY_CURRENT, fallback).apply()
        return fallback
    }

    /** 当前 provider；[load] 保证非空，所以这里一定拿得到。 */
    fun current(context: Context): ProviderConfig {
        val list = load(context)
        val id = currentId(context)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    /** 切换当前 provider；id 不存在就不动（界面上最多是这次点击没反应，不会把状态弄成空的）。 */
    fun select(context: Context, id: String) {
        if (load(context).none { it.id == id }) {
            FileLog.log(TAG, "切换失败：没有这个提供商 " + id)
            return
        }
        prefs(context).edit().putString(KEY_CURRENT, id).apply()
        FileLog.log(TAG, "当前提供商切到 " + id)
    }

    /**
     * 内置模板：DeepSeek / OpenAI / Kimi / 自定义（空地址）。
     *
     * 只有模板带 API Key 之外的全部字段，Key 一律留空——模板是给用户照着填的样例，
     * 预置任何 Key 都会让「我以为用的是自己的 Key」变成一次事故。
     */
    fun catalog(): List<ProviderConfig> = listOf(
        ProviderConfig(
            id = DEEPSEEK_ID,
            name = "DeepSeek",
            baseUrl = ApiConfig.BASE_URL,
            apiKey = "",
            models = ApiConfig.MODELS.map { it.first },
            builtIn = true
        ),
        ProviderConfig(
            id = OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com",
            apiKey = "",
            models = listOf("gpt-4o", "gpt-4o-mini", "o4-mini"),
            builtIn = true
        ),
        ProviderConfig(
            id = KIMI_ID,
            name = "Kimi",
            baseUrl = "https://api.moonshot.cn",
            apiKey = "",
            models = listOf("kimi-k2-0905-preview", "moonshot-v1-32k"),
            builtIn = true
        ),
        ProviderConfig(
            id = CUSTOM_PREFIX,
            name = "自定义",
            baseUrl = "",
            apiKey = "",
            // 自定义的模型名只有用户知道：留空列表，等于「用你自己填的那个」（见 ApiConfig.loadModel）
            models = emptyList(),
            // 空白模板不标内置：用它建出来的 provider 就该和用户自建的一视同仁
            builtIn = false
        )
    )

    /** 新增或整条替换（按 id 认人）；id 为空直接拒绝——没有 id 就没法被引用。 */
    fun upsert(context: Context, cfg: ProviderConfig) {
        if (cfg.id.isBlank()) {
            FileLog.log(TAG, "拒绝保存没有 id 的提供商")
            return
        }
        val clean = normalize(cfg)
        val list = load(context)
        val next = if (list.any { it.id == clean.id }) {
            list.map { if (it.id == clean.id) clean else it }
        } else {
            list + clean
        }
        save(context, next)
        FileLog.log(TAG, "保存提供商 " + clean.id + "（" + clean.name + "，模型 " + clean.models.size + " 个）")
    }

    /**
     * 删除；最后一个不允许删（删完 App 就没有可用 provider 了）。
     *
     * 删的是当前选中的那个时，[currentId] 会把选中态挪到第一个并落盘，
     * 调用方紧接着读 [currentId] 就能拿到删完之后该用哪个。
     */
    fun remove(context: Context, id: String): Boolean {
        val list = load(context)
        if (list.none { it.id == id }) return false
        if (list.size <= 1) {
            FileLog.log(TAG, "只剩一个提供商，不允许删除")
            return false
        }
        val next = list.filter { it.id != id }
        save(context, next)
        // 先落盘再问「现在是谁」：currentId 会顺手把停在被删 id 上的 pref 改到第一个
        val current = currentId(context)
        FileLog.log(TAG, "删除提供商 " + id + "，剩 " + next.size + " 个，当前=" + current)
        return true
    }

    /** 这个 provider 上次用的模型（空串 = 没记过）。 */
    fun modelOf(context: Context, providerId: String): String =
        prefs(context).getString(KEY_MODEL_PREFIX + providerId, null)?.trim().orEmpty()

    /** 记住这个 provider 上次用的模型：每个 provider 一个键，切来切去不会互相覆盖。 */
    fun saveModel(context: Context, providerId: String, model: String) {
        if (providerId.isBlank()) return
        prefs(context).edit().putString(KEY_MODEL_PREFIX + providerId, model.trim()).apply()
    }

    /** 新建自定义提供商时用的 id：时间戳保证同一毫秒内连点两次也不撞号。 */
    fun newCustomId(): String = CUSTOM_PREFIX + "-" + System.currentTimeMillis()

    // ---- 内部 ----

    /** 首次运行：用旧的单 provider 配置建一条内置 DeepSeek 并落盘。 */
    private fun migrate(context: Context): List<ProviderConfig> {
        val list = defaultsFromLegacy(context)
        FileLog.log(
            TAG,
            "首次读取提供商：用旧配置建了内置 DeepSeek（Key 长度 " + list[0].apiKey.length + "）"
        )
        save(context, list)
        select(context, list[0].id)
        return list
    }

    /** 文件坏掉：回落到旧配置推出的默认值并重写，下次启动就不用再走这条路。 */
    private fun repair(context: Context): List<ProviderConfig> {
        val list = defaultsFromLegacy(context)
        FileLog.log(TAG, "providers.json 不可用，用默认配置重建")
        save(context, list)
        return list
    }

    /**
     * 由旧版本的三个 pref 键拼出唯一一条 DeepSeek provider。
     *
     * 老模型的取值也要搬到 model_deepseek：不然升级后 [ApiConfig.loadModel] 只会拿到第一个模型，
     * 用户明明选过 deepseek-v4-pro，升级后却悄悄换成了 flash。
     */
    private fun defaultsFromLegacy(context: Context): List<ProviderConfig> {
        val p = prefs(context)
        val key = p.getString(LEGACY_KEY_API_KEY, null)?.trim().orEmpty()
            .ifEmpty { ApiConfig.DEFAULT_API_KEY }
        val base = p.getString(LEGACY_KEY_BASE_URL, null)?.trim()?.trimEnd('/').orEmpty()
            .ifEmpty { ApiConfig.BASE_URL }
        val model = p.getString(LEGACY_KEY_MODEL, null)?.trim().orEmpty().ifEmpty { ApiConfig.MODEL }

        val modelKey = KEY_MODEL_PREFIX + DEEPSEEK_ID
        if (!p.contains(modelKey)) p.edit().putString(modelKey, model).apply()

        // 老模型也要在可用列表里（自定义端点上的模型可能根本不在 MODELS 里）
        val models = (ApiConfig.MODELS.map { it.first } + model).distinct()
        return listOf(
            ProviderConfig(
                id = DEEPSEEK_ID,
                name = "DeepSeek",
                baseUrl = base,
                apiKey = key,
                models = models,
                builtIn = true
            )
        )
    }

    /** 统一清洗：名字回落到 id、地址去尾部斜杠、Key 去空白、模型去空去重。 */
    private fun normalize(cfg: ProviderConfig): ProviderConfig {
        val id = cfg.id.trim()
        val models = cfg.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return cfg.copy(
            id = id,
            name = cfg.name.trim().ifEmpty { id },
            baseUrl = cfg.baseUrl.trim().trimEnd('/'),
            apiKey = cfg.apiKey.trim(),
            models = models
        )
    }

    private fun ProviderConfig.toJson(): JSONObject {
        val obj = JSONObject()
        obj.put(KEY_ID, id)
        obj.put(KEY_NAME, name)
        obj.put(KEY_BASE_URL, baseUrl)
        obj.put(KEY_API_KEY, apiKey)
        val array = JSONArray()
        for (model in models) array.put(model)
        obj.put(KEY_MODELS, array)
        obj.put(KEY_BUILT_IN, builtIn)
        return obj
    }

    private fun parse(text: String): List<ProviderConfig> {
        val array = JSONObject(text).optJSONArray(KEY_PROVIDERS) ?: return emptyList()
        val list = ArrayList<ProviderConfig>(array.length())
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // optString 遇到 JSON null 会给出 "null"，所以统一走 stringOr
            val id = obj.stringOr(KEY_ID).trim()
            // id 是唯一标识：缺了没法被引用，重了切换会指到不确定的那条，都丢掉
            if (id.isEmpty() || !seen.add(id)) continue
            val models = ArrayList<String>()
            obj.optJSONArray(KEY_MODELS)?.let { arr ->
                for (j in 0 until arr.length()) {
                    val model = arr.optString(j, "").trim()
                    if (model.isNotEmpty() && model != "null") models.add(model)
                }
            }
            list.add(
                ProviderConfig(
                    id = id,
                    name = obj.stringOr(KEY_NAME).ifEmpty { id },
                    baseUrl = obj.stringOr(KEY_BASE_URL).trimEnd('/'),
                    apiKey = obj.stringOr(KEY_API_KEY),
                    models = models,
                    builtIn = obj.optBoolean(KEY_BUILT_IN, false)
                )
            )
        }
        return list
    }

    /** 读字符串：key 不存在 / 是 JSON null / 空串都当空串（org.json 会把 JSON null 读成 "null"，一并防住）。 */
    private fun JSONObject.stringOr(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key, "")
        return if (value == "null") "" else value
    }
}
