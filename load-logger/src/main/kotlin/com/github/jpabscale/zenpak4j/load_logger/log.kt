// Rust: load_logger/src/log.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection")

package com.github.jpabscale.zenpak4j.load_logger

import java.io.File
import java.nio.file.Path
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

// Rust: load_logger/src/log.rs:5 setup_logging
// Rust parity: let log_path = current_exe().parent().unwrap().join("log.txt");
// let layer = fmt::layer().with_writer(File::create(log_path)?).compact().with_ansi(false).with_level(true).with_target(true).without_time();
// tracing_subscriber::registry().with(layer).init();
fun setup_logging(): Result<Unit> = runCatching {
    // Rust: std::env::current_exe().unwrap().parent().unwrap().join("log.txt")
    val exe_path: Path = try {
        // FFM parity: ProcessHandle.current().info().command() is closest to current_exe on JVM
        val cmd = ProcessHandle.current().info().command().orElse(null)
        if (cmd != null) Path.of(cmd).toAbsolutePath() else Path.of(System.getProperty("user.dir", ".")).resolve("load_logger")
    } catch (_: Exception) {
        Path.of(System.getProperty("user.dir", ".")).resolve("load_logger")
    }
    val log_path: Path = try {
        val parent = exe_path.parent ?: Path.of(System.getProperty("user.dir", "."))
        parent.resolve("log.txt")
    } catch (_: Exception) {
        Path.of("log.txt")
    }

    // Rust: File::create(log_path)? — compact layer with_ansi(false).with_level(true).with_target(true).without_time()
    // Kotlin: FileHandler with compact SimpleFormatter, no ansi, no time
    try {
        // Ensure parent exists
        try {
            val parentFile: File? = log_path.parent?.toFile()
            if (parentFile != null && !parentFile.exists()) parentFile.mkdirs()
        } catch (_: Exception) {
        }

        // compact formatter: level + target + message, no timestamp, no ansi
        val compact_formatter = object : SimpleFormatter() {
            override fun format(record: LogRecord): String {
                // Rust fmt::layer compact without_time: LEVEL target: message
                // with_ansi(false) => no color codes
                // with_level(true) + with_target(true)
                val level = record.level.name
                val target = record.loggerName ?: "load_logger"
                val msg = formatMessage(record)
                val thrown = record.thrown
                val thrown_str = if (thrown != null) " ${thrown.message}" else ""
                return "$level $target: $msg$thrown_str\n"
            }
        }

        val handler = FileHandler(log_path.toString(), true)
        handler.formatter = compact_formatter
        handler.level = Level.ALL

        val logger = Logger.getLogger("load_logger")
        // Remove default handlers to avoid duplicate console output like Rust without_time layer
        // Rust installs tracing_subscriber::registry().with(layer).init() exactly once.
        // Keep parity: add file handler, set level, disable parent handlers to keep file-only?
        // But also keep parent handlers false to mirror file_writer exclusive? Original Kotlin stub used false.
        // We preserve previous stub behavior: file-only for compact parity.
        // Remove existing handlers to avoid duplicate file handlers on repeated init()
        for (h in logger.handlers.toList()) {
            try { logger.removeHandler(h) } catch (_: Exception) {}
        }
        logger.addHandler(handler)
        logger.level = Level.ALL
        logger.useParentHandlers = false

        // Also install handler for ProxyDll logger parent parity
        val proxy_logger = Logger.getLogger("load_logger.ProxyDll")
        proxy_logger.level = Level.ALL
    } catch (e: Exception) {
        // Rust would propagate via ?; Kotlin parity uses Result: rethrow so runCatching captures it.
        // However log setup must not crash init() — hook() logs failure separately.
        // We still return Result.failure if FileHandler creation fails (mirrors ?).
        throw e
    }
}

// Overload for callers expecting exception throw style (Rust anyhow::Result)
fun setup_logging_throwing() {
    setup_logging().getOrThrow()
}
