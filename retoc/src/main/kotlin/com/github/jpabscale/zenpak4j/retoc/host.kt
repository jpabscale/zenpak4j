// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// host.kt — binds the :repak Console to :retoc's Log (embedder log seam, EXC-016):
// the embedder form of Log::new_stdout — same levels, same line format, its streams.
// See docs/mapping.md §13.
@file:Suppress("FunctionName", "unused")

package com.github.jpabscale.zenpak4j.retoc

import com.github.jpabscale.zenpak4j.console.Console

fun Console.new_log(verbose: Boolean = false, debug: Boolean = false): Log {
    val min_log_level = when {
        debug -> LogLevel.Debug
        verbose -> LogLevel.Verbose
        else -> LogLevel.Info
    }
    return Log.new(min_log_level, PrintStreamLogBackend(out, err))
}
