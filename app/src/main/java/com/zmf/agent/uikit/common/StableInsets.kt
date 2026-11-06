package com.zmf.agent.uikit.common

import android.content.res.Configuration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 竖屏固定按系统状态栏的实际高度留出顶部空白，横屏沿用按可见性计算的 inset。
 *
 * 本应用默认隐藏状态栏；回到前台时系统会短暂显示状态栏，若竖屏也按可见性取 inset，
 * 顶栏会先被下推、随后弹回。竖屏改用「忽略可见性」的 inset 后，留白在状态栏临时出现时
 * 保持不变，顶栏也不会跳动。
 *
 * 横屏可用高度更紧张：保持原有行为（常态为 0），下滑状态栏时允许少量覆盖。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.stableStatusBarPadding(): Modifier =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) {
        windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
    } else {
        statusBarsPadding()
    }
