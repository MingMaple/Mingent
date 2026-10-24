/*
 * 本文件源自 Operit AI 项目（https://github.com/AAswordman/Operit）。
 * Copyright (C) 2023-2024 Operit AI 项目团队
 *
 * 依 GNU Lesser General Public License v3.0 授权使用与分发，
 * 许可证全文见仓库根目录 terminal/LICENSE；Mingent 对其所做的修改
 * 同样以 LGPL-3.0 提供。
 */

package com.ai.assistance.operit.terminal

import java.io.InputStream
import java.io.OutputStream

/**
 * Represents an active terminal session, encapsulating the process and its I/O streams.
 *
 * @property process The underlying [Process] of the terminal session.
 * @property stdout The standard output stream from the terminal process, which the terminal view will read from.
 * @property stdin The standard input stream to the terminal process, which the terminal view will write to.
 */
data class TerminalSession(
    val process: Process,
    val stdout: InputStream,
    val stdin: OutputStream
) 