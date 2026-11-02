package com.zmf.agent.core.llm

import java.time.Instant
import java.time.ZoneId

/**
 * 一家供应商的模型价目（USD / 1M tokens）。
 *
 * [cacheRead] 是缓存命中读取的单价；缓存写入没有独立价时按 [input] 计（DeepSeek 口径）。
 */
data class ModelPrice(
    val input: Double,
    val cacheRead: Double,
    val output: Double
)

/**
 * 供应商价目插件：新增供应商只需实现该接口并注册到 [CostBook]，
 * 计算逻辑与界面均无需改动。
 */
interface ModelPriceProvider {
    /** 这条 usageModel（`provider/model`）是否归本供应商管。 */
    fun matches(provider: String, model: String): Boolean

    /** 模型单价；不认识这个模型返回 null。 */
    fun price(model: String): ModelPrice?
}

/**
 * DeepSeek 官方渠道价目。
 *
 * 数据来源 models.dev（2026-10-30 拉取，USD / 1M tokens）：
 * `deepseek-flash` / `deepseek-v4-flash` 输入 0.15、缓存读 0.003、输出 0.60；
 * `deepseek-v4-pro` 输入 0.66、缓存读 0.022、输出 1.98。
 */
object DeepSeekPriceProvider : ModelPriceProvider {

    private val prices = mapOf(
        "deepseek-flash" to ModelPrice(0.15, 0.003, 0.60),
        "deepseek-v4-flash" to ModelPrice(0.15, 0.003, 0.60),
        "deepseek-v4-flash-vision-exp" to ModelPrice(0.15, 0.003, 0.60),
        "deepseek-v4-pro" to ModelPrice(0.66, 0.022, 1.98)
    )

    override fun matches(provider: String, model: String): Boolean =
        provider.contains("deepseek", ignoreCase = true) ||
            model.contains("deepseek", ignoreCase = true)

    override fun price(model: String): ModelPrice? = prices[model]
}

/**
 * 计费入口：仅识别已注册的供应商；未注册或无价目的模型返回 null。
 *
 * 峰谷：DeepSeek 谷时（北京时间 00:30–08:30）五折，其余时段原价。
 * 汇率由常量 [USD_CNY] 统一控制。
 */
object CostBook {

    /** 美元兑人民币汇率；如需支持配置，仅修改此处。 */
    const val USD_CNY = 7.0

    /** 已注册的供应商价目：新渠道往这里加一行即可。 */
    private val providers: List<ModelPriceProvider> = listOf(DeepSeekPriceProvider)

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * 单次请求的估算费用（人民币）。
     *
     * @param promptTokens 输入总量（含缓存读取，服务商口径）
     * @param cachedTokens 命中缓存读取的 tokens
     * @param uncachedTokens 未命中缓存的输入 tokens
     * @param cacheWriteTokens 写入缓存的 tokens（没有这一项时传 0）
     */
    fun costOf(
        usageModel: String,
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int,
        uncachedTokens: Int,
        cacheWriteTokens: Int,
        atMillis: Long
    ): Double? {
        if (usageModel.isBlank()) return null
        if (promptTokens <= 0 && completionTokens <= 0) return null
        val slash = usageModel.indexOf('/')
        val provider = if (slash > 0) usageModel.substring(0, slash) else ""
        val model = if (slash >= 0) usageModel.substring(slash + 1) else usageModel
        val priceProvider = providers.firstOrNull { it.matches(provider, model) } ?: return null
        val price = priceProvider.price(model) ?: return null

        val inputCost = (uncachedTokens.coerceAtLeast(0) + cacheWriteTokens.coerceAtLeast(0)) *
            price.input / 1_000_000
        val cacheCost = cachedTokens.coerceAtLeast(0) * price.cacheRead / 1_000_000
        val outputCost = completionTokens.coerceAtLeast(0) * price.output / 1_000_000
        val usd = inputCost + cacheCost + outputCost
        val factor = if (isOffPeak(atMillis)) 0.5 else 1.0
        return usd * factor * USD_CNY
    }

    /** 该时间点是否落在谷时（北京时间 00:30–08:30）。 */
    fun isOffPeak(atMillis: Long): Boolean {
        val time = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalTime()
        val minutes = time.hour * 60 + time.minute
        return minutes >= 30 && minutes < 8 * 60 + 30
    }
}
