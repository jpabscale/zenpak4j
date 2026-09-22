// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// Console→Log binding tests (mapping §13 / EXC-016): same levels, same line format as
// Log::new_stdout, non-error lines to the console out stream, Error+ to the console err stream
// (the same split as StdoutLogBackend, whose System.err default keeps CLI stderr byte-identical).
package com.github.jpabscale.zenpak4j.retoc

import com.github.jpabscale.zenpak4j.console.Console
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleLogTest {

    @Test
    fun test_info_lines_land_in_the_console_stream() {
        val sink = ByteArrayOutputStream()
        Console(PrintStream(sink, true)).new_log(false, false).info("hello")
        assertEquals("info: : hello${System.lineSeparator()}", sink.toString("UTF-8"))
    }

    @Test
    fun test_level_gating_matches_new_stdout() {
        val sink = ByteArrayOutputStream()
        val log = Console(PrintStream(sink, true)).new_log(false, false)
        log.debug("d") // below Info — suppressed
        log.verbose("v") // below Info — suppressed
        log.warning("w") // Info threshold passes
        // LogLevel's Display already carries "level: " and the backend adds ": {msg}"
        // (upstream `println!("{}: {}", level, msg)`) — so the line is "warning: : w",
        // byte-identical to what StdoutLogBackend emits.
        assertEquals("warning: : w${System.lineSeparator()}", sink.toString("UTF-8"))
    }

    @Test
    fun test_error_lines_land_in_the_console_err() {
        val sink = ByteArrayOutputStream()
        val errSink = ByteArrayOutputStream()
        Console(PrintStream(sink, true), PrintStream(errSink, true)).new_log(false, false).error("boom")
        assertEquals("", sink.toString("UTF-8"), "error lines must not enter the console out stream")
        assertTrue(errSink.toString("UTF-8").contains("error: : boom"))
    }
}
