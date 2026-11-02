package com.zmf.agent.core

/**
 * App 是否在前台（Activity 级信号）。
 *
 * 以 Activity 计数而非查询进程重要性：通知用于在用户未查看界面时提醒，
 * 只有实际显示在前台的 Activity 才视为用户正在查看；小窗或前台服务运行时界面不在前台，通知仍发送。
 *
 * [active] 由 MainActivity 的 onResume/onPause 增减，使用 @Volatile 使通知侧（可能位于其他线程
 * 的 finally 块）立即读到新值：计数短暂不准最多多发或少发一条通知，不影响主流程。
 */
object AppForeground {

    /** 前台 Activity 的个数（正常 0 或 1；分屏/多实例时可能更多）。 */
    @Volatile
    var active: Int = 0

    /** 有任何一个 Activity 在前台。 */
    val isForeground: Boolean get() = active > 0
}
