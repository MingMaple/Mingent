package com.zmf.agent.infra.llm

import java.io.File

/**
 * 线上请求落盘的开关。
 *
 * 非 null 时把每一条真正发出的请求 JSON 存到这个目录（只留最近 12 份）。
 * 为什么要有：前缀缓存只在「两个请求从第 0 个 token 起逐字节相同」时命中，光看命中百分比
 * 永远分不清「输入侧哪里变了」和「提供方侧行为」；存下真实请求，离线 diff 就能定位第一处差异。
 *
 * 为什么不在 ApiConfig 里：它是 provider 的内部调试手段，谁发请求谁才关心；
 * 放在配置层会让 infra 反向依赖上层配置对象。
 */
object WireDump {

    @Volatile
    var dir: File? = null
}
