package com.zmf.agent.core

/**
 * 壳层入口的类名。
 *
 * 为什么是字符串而不是 `MainActivity::class.java`：`Notifier`（core）、`AgentForegroundService`（capability）、
 * `FloatingBallService`（feature）都需要「点一下回到 App」，而它们都不该反向依赖装配层的 Activity。
 * 代价是改 MainActivity 的包名或类名时要同步这一行——所以只留这一处，别在别处硬编码。
 *
 * 全工程搜索 `AppEntry.ACTIVITY` 就能找到所有隐式依赖它的地方。
 */
object AppEntry {

    /** 应用主界面，通知与悬浮球点击后的落点。 */
    const val ACTIVITY = "com.zmf.agent.app.MainActivity"
}
