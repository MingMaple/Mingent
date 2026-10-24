/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal.view.canvas

/**
 * Lightweight tab model used by CanvasTerminalView for in-surface tab rendering.
 */
data class TerminalTabRenderItem(
    val id: String,
    val title: String,
    val canClose: Boolean
)
