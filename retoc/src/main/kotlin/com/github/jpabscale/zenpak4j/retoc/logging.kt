// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/logging.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "EnumEntryName")

package com.github.jpabscale.zenpak4j.retoc

import java.util.concurrent.atomic.AtomicReference

// Rust: retoc/src/logging.rs:47 LogLevel
enum class LogLevel {
    Debug,
    Verbose,
    Info,
    Warning,
    Error;

    // Rust: strum::Display -> override toString with "debug: " etc
    override fun toString(): String = when (this) {
        Debug -> "debug: "
        Verbose -> "verbose: "
        Info -> "info: "
        Warning -> "warning: "
        Error -> "error: "
    }

    companion object {
        fun from_repr(value: Int): LogLevel? = entries.getOrNull(value)
    }
}

// Rust: retoc/src/logging.rs:61 LogBackend
interface LogBackend {
    // Writes messages to this log backend. Note that this function can be called from multiple threads simultaneously
    fun write_message(level: LogLevel, msg: String)
}

// Rust: retoc/src/logging.rs:66 StdoutLogBackend
class StdoutLogBackend : LogBackend {
    override fun write_message(level: LogLevel, msg: String) {
        if (level >= LogLevel.Error) {
            System.err.println("$level: $msg")
        } else {
            println("$level: $msg")
        }
    }
}

// Rust: retoc/src/logging.rs:77 NoopLogBackend
class NoopLogBackend : LogBackend {
    override fun write_message(level: LogLevel, msg: String) {}
}

// Rust: retoc/src/logging.rs:82 Log
class Log(
    var min_log_level: LogLevel,
    var progress: Any? = null,
    var backend: LogBackend
) {

    // Rust: retoc/src/logging.rs:88 Log::new
    companion object {
        fun new(min_log_level: LogLevel, backend: LogBackend): Log =
            Log(min_log_level, null, backend)

        // Rust: retoc/src/logging.rs:91 new_stdout
        fun new_stdout(verbose: Boolean, debug: Boolean): Log {
            val min_log_level = when {
                debug -> LogLevel.Debug
                verbose -> LogLevel.Verbose
                else -> LogLevel.Info
            }
            return new(min_log_level, StdoutLogBackend())
        }

        // Rust: retoc/src/logging.rs:101 no_log
        fun no_log(): Log = new(LogLevel.Error, NoopLogBackend())
    }

    // Rust: retoc/src/logging.rs:104 set_progress
    fun set_progress(progress: Any?) {
        this.progress = progress
    }

    // Rust: retoc/src/logging.rs:107 is_level_enabled
    fun is_level_enabled(level: LogLevel): Boolean = level >= min_log_level

    // Rust: retoc/src/logging.rs:110 log
    fun log(level: LogLevel, msg: String) {
        if (is_level_enabled(level)) {
            // progress handling: if progress present, use its println, else backend
            val prog = progress
            if (prog != null) {
                // best-effort: try to invoke println via reflection, fallback to backend
                try {
                    val m = prog.javaClass.getMethod("println", String::class.java)
                    m.invoke(prog, msg)
                } catch (_: Exception) {
                    backend.write_message(level, msg)
                }
            } else {
                backend.write_message(level, msg)
            }
        }
    }

    // Rust: macros info!/verbose!/debug!/warning!/error! as methods
    fun info(msg: String) {
        if (is_level_enabled(LogLevel.Info)) log(LogLevel.Info, msg)
    }

    fun verbose(msg: String) {
        if (is_level_enabled(LogLevel.Verbose)) log(LogLevel.Verbose, msg)
    }

    fun debug(msg: String) {
        if (is_level_enabled(LogLevel.Debug)) log(LogLevel.Debug, msg)
    }

    fun warning(msg: String) {
        if (is_level_enabled(LogLevel.Warning)) log(LogLevel.Warning, msg)
    }

    fun error(msg: String) {
        if (is_level_enabled(LogLevel.Error)) log(LogLevel.Error, msg)
    }

    // macro-style overloads with format (vararg) for parity
    fun info_fmt(fmt: String, vararg args: Any?) = info(fmt.format(*args))
    fun verbose_fmt(fmt: String, vararg args: Any?) = verbose(fmt.format(*args))
    fun debug_fmt(fmt: String, vararg args: Any?) = debug(fmt.format(*args))
    fun warning_fmt(fmt: String, vararg args: Any?) = warning(fmt.format(*args))
    fun error_fmt(fmt: String, vararg args: Any?) = error(fmt.format(*args))
}

// Top-level helpers mirroring macro names for scriptable parity `rg "fun info"`
fun info(log: Log, msg: String) = log.info(msg)
fun verbose(log: Log, msg: String) = log.verbose(msg)
fun debug(log: Log, msg: String) = log.debug(msg)
fun warning(log: Log, msg: String) = log.warning(msg)
fun error(log: Log, msg: String) = log.error(msg)
