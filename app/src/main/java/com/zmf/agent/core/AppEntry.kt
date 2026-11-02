package com.zmf.agent.core

/**
 * 壳层入口的类名。
 *
 * 使用字符串而非 `MainActivity::class.java`：`Notifier`（core）、`AgentForegroundService`（capability）、
 * `FloatingBallService`（feature）均需回到 App，而这些模块不应反向依赖装配层的 Activity。
 * 代价是 MainActivity 的包名或类名变更时需同步此处，因此该字面量仅保留一份。
 *
 * 搜索 `AppEntry.ACTIVITY` 可定位所有隐式依赖。
 */
object AppEntry {

    /** 应用主界面，通知与悬浮球点击后的落点。 */
    const val ACTIVITY = "com.zmf.agent.app.MainActivity"
}
