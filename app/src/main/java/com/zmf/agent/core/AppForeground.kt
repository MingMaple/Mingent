package com.zmf.agent.core

/**
 * App 是否在前台（Activity 级别的粗粒度信号）。
 *
 * 为什么在 Activity 里自己数，而不是查进程重要性：通知的用途是「用户没在看的时候提醒他」，
 * 只有真正露在屏幕上的 Activity 才算「在看」；小窗/前台服务跑着但界面不在前台时，通知照发。
 *
 * [active] 由 MainActivity 的 onResume/onPause 加减，用 @Volatile 让通知侧（可能在别的线程
 * 的 finally 里）立刻看到新值——计数短暂不准最多多发/少发一条通知，不影响主流程。
 */
object AppForeground {

    /** 前台 Activity 的个数（正常 0 或 1；分屏/多实例时可能更多）。 */
    @Volatile
    var active: Int = 0

    /** 有任何一个 Activity 在前台。 */
    val isForeground: Boolean get() = active > 0
}
