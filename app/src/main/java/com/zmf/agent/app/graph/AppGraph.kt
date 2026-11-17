package com.zmf.agent.app.graph

import android.content.Context
import com.zmf.agent.capability.notify.NotifierAdapter
import com.zmf.agent.capability.port.ToolEnv
import com.zmf.agent.capability.port.ToolServices
import com.zmf.agent.capability.runtime.ContextStatusBridge
import com.zmf.agent.feature.chat.BrowserAutomation
import com.zmf.agent.feature.schedule.ScheduleRunner
import com.zmf.agent.app.llm.ProviderImageGenerator

/**
 * 组合根：装配工具可用的跨层服务。
 *
 * 进程启动时调用一次；会话相关端口（投递、网页控制）由各自的持有者在界面绑定后补充。
 * 本对象是全工程唯一允许同时引用 `capability`、`infra` 与 `feature` 的装配点。
 */
object AppGraph {

    /** 安装进程级服务；重复调用以最后一次为准。 */
    fun installTools(context: Context) {
        val app = context.applicationContext
        ToolEnv.install(
            ToolServices(
                notifier = NotifierAdapter(app),
                screenCapture = AppScreenCapture(app),
                contextMetrics = ContextStatusBridge,
                imageGenerator = ProviderImageGenerator(app)
            )
        )
        // 网页控制端口：浏览页在组合时建立租赁，此处只装配端口引用
        ToolEnv.attach { it.copy(webControl = BrowserAutomation) }
        // 会话间投递端口：send_message 经此向其他会话投递消息并取得节点
        ScheduleRunner.installDelivery(app)
        ToolEnv.attach { it.copy(delivery = ScheduleRunner) }
    }
}
