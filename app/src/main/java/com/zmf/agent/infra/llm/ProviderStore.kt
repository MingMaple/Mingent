package com.zmf.agent.infra.llm
import com.zmf.agent.core.llm.ApiDefaults

import android.content.Context
import com.zmf.agent.core.AppPaths
import com.zmf.agent.core.log.FileLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个可用的模型服务商。
 *
 * 请求侧仅实现 OpenAI 兼容的 /chat/completions（见 [DeepSeekProvider]），因此此处不对 Anthropic
 * 原生协议建模：增加协议类型会使「何种情况下可用」的判断复杂化，而 ollama、vllm 及各云厂商的
 * 兼容端点均可使用同一套字段配置。
 *
 * @param id 稳定标识（"deepseek" / "openai" / "kimi" / "custom-<毫秒时间戳>"），会话与偏好都按它引用
 * @param models 可用模型 id；空列表表示由用户自行填写，ApiConfig.loadModel 会记住上次使用的模型
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
 * 不使用数据库的原因：提供商由用户在设置页手动填写，量级为几条至十几条，
 * 与 sessions.json / workspaces.json 一致采用整份覆盖写，实现简单，也避免引入新依赖。
 *
 * 每个进程仅读盘一次（[cache]）：ApiConfig 每次拼接提示词或发送请求均需查询当前 provider，
 * 每次读盘并无必要；所有写入均经由 [save]，缓存与文件不会不一致。
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
     * 迁移时读的旧键名，语义与 ApiConfig 里的同名键一一对应。
     *
     * 此处直接读取 prefs 而不调用 ApiConfig.loadXxx：ApiConfig 会反向查询 ProviderStore
     * 的「当前 provider」，回绕将形成 load → 迁移 → load 的无限递归。
     */
    private const val LEGACY_KEY_API_KEY = "api_key"
    private const val LEGACY_KEY_BASE_URL = "api_base_url"
    private const val LEGACY_KEY_MODEL = "model"

    /** 内置 DeepSeek 的固定 id：仅它允许回落至源码中的默认 Key 与官方端点。 */
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
     * 首次运行（文件不存在）时由旧的单 provider 配置迁移出一条 id="deepseek"，
     * 保留用户已填写的 Key、端点与模型；文件损坏时回落至同一份默认值并同时修复文件
     * （损坏文件无法读出内容，保留只会导致每次启动均失败一次）。
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

    /** 整体覆盖写（.tmp + rename）；失败只记日志，界面表现为本次改动未生效，不会崩溃。 */
    fun save(context: Context, list: List<ProviderConfig>) {
        // 空列表意味着 App 无任何可用模型，直接拒绝：宁可保留磁盘上的旧配置
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
                // rename 失败（个别文件系统）时退化为直接覆盖，确保内容写入
                file.writeText(json, Charsets.UTF_8)
                temp.delete()
            }
            cache = list
        } catch (e: Exception) {
            // 磁盘上可能仅写入部分内容：缓存立即作废，下次 load 以文件为准
            cache = null
            FileLog.log(TAG, "保存提供商失败：" + e)
        }
    }

    /** 当前选中的 provider id；存储的 id 已被删除时回落至第一个，并同步修正 pref。 */
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

    /** 切换当前 provider；id 不存在时不作改动（表现为本次点击无响应，状态不会被置空）。 */
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
     * 模板仅包含除 API Key 之外的全部字段，Key 一律留空：模板是供用户参照填写的样例，
     * 预置任何 Key 都会导致用户误以为使用的是自己的 Key。
     */
    fun catalog(): List<ProviderConfig> = listOf(
        ProviderConfig(
            id = DEEPSEEK_ID,
            name = "DeepSeek",
            baseUrl = ApiDefaults.BASE_URL,
            apiKey = "",
            models = ApiDefaults.MODELS.map { it.first },
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
            // 自定义模型名仅用户知晓：留空列表即使用用户自行填写的模型（见 ApiConfig.loadModel）
            models = emptyList(),
            // 空白模板不标记为内置：由其创建的 provider 应与用户自建的 provider 同等对待
            builtIn = false
        )
    )

    /** 新增或整条替换（按 id 匹配）；id 为空时直接拒绝：无 id 则无法被引用。 */
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
     * 删除；最后一个不允许删除（删除后 App 将无可用 provider）。
     *
     * 删除当前选中项时，[currentId] 会将选中状态移至第一项并落盘，
     * 调用方随后读取 [currentId] 即可获得删除后应使用的 provider。
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
        // 先落盘再查询当前项：currentId 会将仍指向已删除 id 的 pref 修正为第一个
        val current = currentId(context)
        FileLog.log(TAG, "删除提供商 " + id + "，剩 " + next.size + " 个，当前=" + current)
        return true
    }

    /** 该 provider 上次使用的模型（空串表示未记录）。 */
    fun modelOf(context: Context, providerId: String): String =
        prefs(context).getString(KEY_MODEL_PREFIX + providerId, null)?.trim().orEmpty()

    /** 记录该 provider 上次使用的模型：每个 provider 一个键，切换时不会相互覆盖。 */
    fun saveModel(context: Context, providerId: String, model: String) {
        if (providerId.isBlank()) return
        prefs(context).edit().putString(KEY_MODEL_PREFIX + providerId, model.trim()).apply()
    }

    /** 新建自定义提供商时使用的 id：时间戳确保同一毫秒内连续两次创建也不会重复。 */
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

    /** 文件损坏：回落至由旧配置生成的默认值并重写，后续启动无需再进入该分支。 */
    private fun repair(context: Context): List<ProviderConfig> {
        val list = defaultsFromLegacy(context)
        FileLog.log(TAG, "providers.json 不可用，用默认配置重建")
        save(context, list)
        return list
    }

    /**
     * 由旧版本的三个 pref 键拼出唯一一条 DeepSeek provider。
     *
     * 旧模型取值也需迁移至 model_deepseek：否则升级后 ApiConfig.loadModel 只会取得第一个模型，
     * 用户此前选择的 deepseek-v4-pro 会被静默替换为 flash。
     */
    private fun defaultsFromLegacy(context: Context): List<ProviderConfig> {
        val p = prefs(context)
        val key = p.getString(LEGACY_KEY_API_KEY, null)?.trim().orEmpty()
            .ifEmpty { ApiDefaults.DEFAULT_API_KEY }
        val base = p.getString(LEGACY_KEY_BASE_URL, null)?.trim()?.trimEnd('/').orEmpty()
            .ifEmpty { ApiDefaults.BASE_URL }
        val model = p.getString(LEGACY_KEY_MODEL, null)?.trim().orEmpty().ifEmpty { ApiDefaults.MODEL }

        val modelKey = KEY_MODEL_PREFIX + DEEPSEEK_ID
        if (!p.contains(modelKey)) p.edit().putString(modelKey, model).apply()

        // 旧模型同样需保留在可用列表中（自定义端点上的模型可能不在 MODELS 中）
        val models = (ApiDefaults.MODELS.map { it.first } + model).distinct()
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

    /** 统一清洗：名称回落至 id、地址去除尾部斜杠、Key 去除空白、模型去除空白并去重。 */
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
            // optString 遇到 JSON null 会返回 "null"，因此统一使用 stringOr
            val id = obj.stringOr(KEY_ID).trim()
            // id 为唯一标识：缺失则无法引用，重复会使切换指向不确定项，均丢弃
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

    /** 读取字符串：键不存在、为 JSON null 或空串时均返回空串（org.json 会将 JSON null 读取为 "null"，同时处理）。 */
    private fun JSONObject.stringOr(key: String): String {
        if (!has(key) || isNull(key)) return ""
        val value = optString(key, "")
        return if (value == "null") "" else value
    }
}
