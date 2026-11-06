package com.zmf.agent.capability.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.zmf.agent.core.AppEntry
import com.zmf.agent.core.log.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 定时任务用到的平台能力窄口：闹钟、部分唤醒锁与应用级协程作用域。
 *
 * feature 层只表达「何时排期、执行什么」，不直接持有 AlarmManager / PowerManager；
 * 将来若改用 WorkManager 等其它调度实现，只需替换这一处。
 *
 * 排期失败返回 false 而不吞异常：精确闹钟在部分机型会抛 SecurityException，
 * 调用方据此降级为非精确闹钟，并把原因写入日志。
 */
object PlatformAlarms {

    private const val TAG = "schedule"

    /** 唤醒锁标签：便于在系统电池统计中识别为定时任务持有。 */
    private const val WAKE_LOCK_TAG = "Mingent:schedule"

    /** 应用级作用域：广播返回后协程仍需继续执行，因此不能绑定到 Receiver 的生命周期。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一把部分唤醒锁的句柄；重复 release 不做任何操作。 */
    interface WakeLockHandle {
        fun release()
    }

    /**
     * 精确排期。
     *
     * clockAlarm = true 时改用 setAlarmClock（系统不会推迟，代价是状态栏闹钟图标）；
     * 否则用 setExactAndAllowWhileIdle。拿不到 AlarmManager 时返回 false。
     */
    fun setExact(context: Context, atMillis: Long, intent: PendingIntent, clockAlarm: Boolean): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (manager == null) {
            FileLog.log(TAG, "拿不到 AlarmManager")
            return false
        }
        if (clockAlarm) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, clockShowIntent(context)), intent)
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
        return true
    }

    /** 非精确排期：可能延迟数分钟，但保证任务最终执行；拿不到 AlarmManager 时返回 false。 */
    fun setInexact(context: Context, atMillis: Long, intent: PendingIntent): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        return true
    }

    /** 取消一条闹钟；不存在或系统拒绝时静默忽略。 */
    fun cancel(context: Context, intent: PendingIntent) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(intent) }
    }

    /**
     * 获取部分唤醒锁；系统拒绝（权限缺失或厂商限制）时返回 null，任务仍照常尝试执行。
     *
     * 不启用引用计数：每次执行只获取一次、释放一次，超时由系统兜底。
     */
    fun keepAwake(context: Context, timeoutMillis: Long): WakeLockHandle? = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(timeoutMillis)
        }
        object : WakeLockHandle {
            override fun release() {
                // 超时自动释放后 isHeld 为 false，不重复释放
                runCatching { if (lock.isHeld) lock.release() }
                    .onFailure { FileLog.log(TAG, "唤醒锁释放失败：" + it) }
            }
        }
    }.onFailure { FileLog.log(TAG, "唤醒锁获取失败：" + it) }.getOrNull()

    /**
     * setAlarmClock 需要「点闹钟图标去哪里」的意图：点状态栏闹钟图标回到应用即可。
     * requestCode 固定：全应用只需要一个展示意图，与具体任务无关。
     */
    private fun clockShowIntent(context: Context): PendingIntent {
        val intent = Intent().setClassName(context, AppEntry.ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
