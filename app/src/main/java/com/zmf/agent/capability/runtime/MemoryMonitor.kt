package com.zmf.agent.capability.runtime

import android.os.Debug
import com.zmf.agent.capability.jobs.JobRegistry
import com.zmf.agent.capability.subagent.SubAgentRegistry
import com.zmf.agent.core.AppForeground
import com.zmf.agent.core.log.FileLog
import com.zmf.agent.infra.terminal.TerminalApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 内存采样与留证（X09，对齐 DSH memory-monitor 的口径）。
 *
 * **只采样、只留证，不做任何限流**：每 30 秒读一次本进程的内存水位，一行写进
 * FileLog（tag `mem`），同时记录运行中的子智能体、后台任务与终端通道数量——
 * 出问题时这些行就是事故现场，除此之外它们不驱动任何行为。
 *
 * 生命周期：两个触发方各登记一次——后台前台服务（[OWNER_SERVICE]）与前台界面（[OWNER_UI]），
 * 双方都退出后才停止循环，不常驻线程。采样前再做一次活动判定（应用在前台，
 * 或仍有任务/子智能体/终端通道在跑）：进程空闲且无人查看时跳过该次采样，
 * 避免为一个静止的进程每 30 秒写一行无信息量的日志（省电：跳过时零磁盘写入）。
 *
 * 采样失败（含 Debug API 异常）不重试、不向上抛，与通知同口径：留证是附加动作。
 */
object MemoryMonitor {

    private const val TAG = "mem"

    /** 采样触发方：后台前台服务存活。 */
    const val OWNER_SERVICE = "service"

    /** 采样触发方：应用有界面在前台。 */
    const val OWNER_UI = "ui"

    /** 采样间隔（DSH 的 MEMORY_SAMPLE_INTERVAL_MS = 3e4，对齐 30 秒）。 */
    private const val INTERVAL_MS = 30_000L

    @Volatile
    private var scope: CoroutineScope? = null

    /** 当前仍在要求采样的触发方；任一方在场就保持循环。 */
    private val owners = LinkedHashSet<String>()

    /** 登记一个触发方；循环已在跑时只登记，不重复起线程。 */
    @Synchronized
    fun start(owner: String) {
        if (!owners.add(owner)) return
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = newScope
        newScope.launch {
            // 启动即采一次：短时前台会话也能留下一条现场，随后按固定间隔继续
            while (isActive) {
                runCatching { sampleOnce() }
                delay(INTERVAL_MS)
            }
        }
        FileLog.log(TAG, "内存采样启动（触发：" + owner + "，间隔 " + INTERVAL_MS / 1000 + "s）")
    }

    /** 注销一个触发方；最后一个触发方退出后停止循环，delay 随作用域取消立即退出。 */
    @Synchronized
    fun stop(owner: String) {
        if (!owners.remove(owner)) return
        if (owners.isNotEmpty()) return
        scope?.cancel()
        scope = null
        FileLog.log(TAG, "内存采样停止")
    }

    /** 采样一次并写一行日志；应用空闲时跳过（不写，零开销）。 */
    private fun sampleOnce() {
        // 活动判定：前台有人看，或后台仍有活干，才值得留证。全空闲时跳过本次采样。
        val busy = AppForeground.isForeground ||
            SubAgentRegistry.runningCount() > 0 ||
            JobRegistry.runningCount() > 0 ||
            TerminalApi.activeChannelCount() > 0
        if (!busy) return
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory()
        val free = runtime.freeMemory()
        val used = total - free
        val dalvik = Debug.MemoryInfo()
        Debug.getMemoryInfo(dalvik)
        val line = String.format(
            Locale.ROOT,
            "total=%dK used=%dK free=%dK pss=%dK subs=%d jobs=%d shells=%d fg=%s",
            total / 1024,
            used / 1024,
            free / 1024,
            dalvik.totalPss,
            SubAgentRegistry.runningCount(),
            JobRegistry.runningCount(),
            TerminalApi.activeChannelCount(),
            AppForeground.isForeground
        )
        FileLog.log(TAG, line)
    }
}
