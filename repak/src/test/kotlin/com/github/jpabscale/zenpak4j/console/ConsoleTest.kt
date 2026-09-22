// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// Console seam tests (mapping §13 / EXC-016): sink injection for out and err streams, System.out
// default, nearest-receiver resolution for nested consoles, and the no-print-member guard.
package com.github.jpabscale.zenpak4j.console

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleTest {

    private fun sinkConsole(sink: ByteArrayOutputStream): Console =
        Console(PrintStream(sink, true))

    @Test
    fun test_members_write_to_injected_stream() {
        val sink = ByteArrayOutputStream()
        val console = sinkConsole(sink)
        console.println("hello")
        console.println()
        assertEquals(
            "hello${System.lineSeparator()}${System.lineSeparator()}",
            sink.toString("UTF-8")
        )
    }

    @Test
    fun test_default_console_writes_to_system_out() {
        val cap = ByteArrayOutputStream()
        val real = System.out
        System.setOut(PrintStream(cap, true))
        try {
            Console().println("default-route")
        } finally {
            System.setOut(real)
        }
        assertTrue(cap.toString("UTF-8").contains("default-route"))
    }

    @Test
    fun test_nested_receivers_use_nearest_console() {
        val outerSink = ByteArrayOutputStream()
        val innerSink = ByteArrayOutputStream()
        val outer = sinkConsole(outerSink)
        val inner = sinkConsole(innerSink)
        with(outer) {
            with(inner) {
                emit("nested")
            }
        }
        assertEquals("", outerSink.toString("UTF-8"), "outer console must stay silent")
        assertTrue(innerSink.toString("UTF-8").contains("nested"))
    }

    @Test
    fun test_err_stream_is_injectable_and_defaults_to_system_err() {
        assertSame(System.err, Console().err)
        val errSink = ByteArrayOutputStream()
        Console(PrintStream(ByteArrayOutputStream(), true), PrintStream(errSink, true)).err.println("boom")
        assertEquals("boom${System.lineSeparator()}", errSink.toString("UTF-8"))
    }

    @Test
    fun test_console_declares_no_print_member() {
        // mapping §13 / EXC-016: `fun Console.print(obj: ZenScriptObjects)` must stay reachable.
        // A member named `print` beats the extension on the same receiver (members win), which
        // would silently reroute the ported script_objects.rs print statements.
        assertTrue(
            Console::class.java.methods.none { it.name == "print" },
            "Console must not declare a print member — it would shadow the Console.print(ZenScriptObjects) extension"
        )
    }
}

// Extension form so the nearest-receiver rule is exercised on an extension call
// (the same resolution the action extensions rely on).
private fun Console.emit(msg: String) = println(msg)
