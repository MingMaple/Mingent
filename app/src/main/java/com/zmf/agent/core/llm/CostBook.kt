package com.zmf.agent.core.llm

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * 一家供应商的模型价目（元 / 1M tokens）。
 *
 * 表内为**高峰时段**单价；空闲时段按官方口径取半价。
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
 * DeepSeek 官方渠道价目（元 / 1M tokens，高峰时段）。
 *
 * 数据来源：DeepSeek 开放平台「模型 & 价格」（2026-11-12 核对）。
 * `deepseek-flash`（V4.1-Flash-1017）输入 2、缓存命中 0.04、输出 8；
 * `deepseek-v4-pro` 输入 9、缓存命中 0.30、输出 27。
 * 旧模型名 `deepseek-v4-flash` 与 `deepseek-v4-flash-vision-exp` 已路由到 V4.1 Flash，按 Flash 计价。
 */
object DeepSeekPriceProvider : ModelPriceProvider {

    private val prices = mapOf(
        "deepseek-flash" to ModelPrice(2.0, 0.04, 8.0),
        "deepseek-v4-flash" to ModelPrice(2.0, 0.04, 8.0),
        "deepseek-v4-flash-vision-exp" to ModelPrice(2.0, 0.04, 8.0),
        "deepseek-v4-pro" to ModelPrice(9.0, 0.30, 27.0)
    )

    override fun matches(provider: String, model: String): Boolean =
        provider.contains("deepseek", ignoreCase = true) ||
            model.contains("deepseek", ignoreCase = true)

    override fun price(model: String): ModelPrice? = prices[model]
}

/**
 * 计费入口：仅识别已注册的供应商；未注册或无价目的模型返回 null。
 *
 * 峰谷：以官方口径为准，北京时间周一至周五 09:00–12:00、14:00–18:00 为高峰，
 * 其余时段（含周末）为空闲，按高峰价的一半计；法定节假日尚未按官方口径排除。
 */
object CostBook {

    /** 官方 Search 单价：每 1000 次成功请求 5 元。 */
    const val SEARCH_CNY_PER_REQUEST = 0.005

    /** 已注册的供应商价目：新渠道往这里加一行即可。 */
    private val providers: List<ModelPriceProvider> = listOf(DeepSeekPriceProvider)

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 一次联网检索的费用（元）：按成功请求次数计价，与返回结果条数无关。 */
    fun searchCostCny(requests: Int): Double =
        requests.coerceAtLeast(0) * SEARCH_CNY_PER_REQUEST

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
        val cny = inputCost + cacheCost + outputCost
        val factor = if (isPeak(atMillis)) 1.0 else 0.5
        return cny * factor
    }

    /**
     * 该时间点是否处于高峰时段（北京时间周一至周五 09:00–12:00、14:00–18:00）。
     *
     * 官方口径还排除中国法定节假日；本实现不含节假日表，节假日当天按工作日计算。
     */
    fun isPeak(atMillis: Long): Boolean {
        val time = Instant.ofEpochMilli(atMillis).atZone(zone)
        val day = time.dayOfWeek
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        val minutes = time.hour * 60 + time.minute
        return (minutes in 9 * 60 until 12 * 60) || (minutes in 14 * 60 until 18 * 60)
    }
}
