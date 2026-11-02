package com.zmf.agent.core.llm

/**
 * 模型与端点的默认值：纯常量，不依赖 Context 与配置，也不感知当前选中的 provider。
 *
 * 单独置于 core 的原因：ProviderStore（core）和 DeepSeekProvider（infra）都需使用这些值，
 * 而它们不能反向依赖 `ApiConfig`（「用户当前配置的读写」层，高于默认值）。
 * 拆分后依赖方向单一：core ← infra ← capability。
 */
object ApiDefaults {

    /** 空串表示「未配置」：界面显示为未填写，请求时由 provider 明确报错。 */
    const val DEFAULT_API_KEY = ""

    /** 内置 DeepSeek 模板的对话端点，也是 baseUrl 留空时的回落值。 */
    const val BASE_URL = "https://api.deepseek.com"

    /**
     * 官方检索端点（Anthropic Messages 兼容）。
     *
     * 实测口径：POST /messages + x-api-key + anthropic-version: 2023-06-01，
     * body 携带 tools:[{type:"web_search_20250305",name:"web_search"}] 即可获得
     * web_search_tool_result；返回 10 条 title/url/page_age。
     */
    const val SEARCH_BASE_URL = "https://api.deepseek.com/anthropic/v1"

    /** 检索用的模型（Anthropic 格式的名称，与对话模型的 ID 不属于同一套）。 */
    const val SEARCH_MODEL = "deepseek-v4-flash"

    /** 默认对话模型名，也是 provider 未记录模型且可选列表为空时的回落值。 */
    const val MODEL = "deepseek-flash"

    /** 模型上下文窗口（token 数），统计页拿它当「上下文水位」的分母。 */
    const val CONTEXT_WINDOW = 1_048_576

    /**
     * 可选模型：第一个是接口里的 model 名，第二个是设置页显示的名字。
     *
     * 官方接口（GET https://api.deepseek.com/models）：两个模型 context_window
     * 1,048,576、max_output 393,216，思考强度支持 low / high / max；deepseek-flash 另支持图片输入。
     */
    val MODELS: List<Pair<String, String>> = listOf(
        "deepseek-flash" to "DeepSeek-V4.1-Flash",
        "deepseek-v4-pro" to "DeepSeek-V4-Pro"
    )

    /**
     * 思考强度可选项：直接用接口取值（官方 reasoning_effort 支持 low / high / max，默认 high）。
     *
     * 界面亦原样显示这三个值：中文标签与接口取值不对应，排查时容易误读。
     */
    val EFFORTS: List<String> = listOf("low", "high", "max")

    const val DEFAULT_EFFORT = "high"
}
