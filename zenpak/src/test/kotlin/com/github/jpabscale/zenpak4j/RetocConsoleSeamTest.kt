// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// Retoc action console seam tests (mapping §13 / EXC-016): a real (synthetic) container driven
// through Console.action_info must write into the injected sink with no leak to the real stdout,
// and the iostore mixed-version warning must reach the caller-supplied err stream. Fixture-free
// by design: containers are built with IoStoreWriter, so this always runs.
package com.github.jpabscale.zenpak4j

import com.github.jpabscale.zenpak4j.console.Console
import com.github.jpabscale.zenpak4j.retoc.Config
import com.github.jpabscale.zenpak4j.retoc.EIoStoreTocVersion
import com.github.jpabscale.zenpak4j.retoc.FIoChunkIdRaw
import com.github.jpabscale.zenpak4j.retoc.IoStoreWriter
import com.github.jpabscale.zenpak4j.retoc.open_with_container_paths
import com.github.jpabscale.zenpak4j.retoc_actions.ActionInfo
import com.github.jpabscale.zenpak4j.retoc_actions.action_info
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetocConsoleSeamTest {

    @Test
    fun test_action_info_lines_land_in_sink_only() {
        val dir = Files.createTempDirectory("zen-console-retoc")
        val utoc = dir.resolve("seam.utoc")
        val writer = IoStoreWriter.new(utoc, EIoStoreTocVersion.Initial, null, "")
        writer.write_chunk_raw(FIoChunkIdRaw.from_string("000000000000000000000000"), null, byteArrayOf(1, 2, 3))
        writer.finalize()

        val sink = ByteArrayOutputStream()
        val cap = ByteArrayOutputStream()
        val realOut = System.out
        System.setOut(PrintStream(cap, true))
        try {
            Console(PrintStream(sink, true)).action_info(ActionInfo(path = utoc), Config())
        } finally {
            System.setOut(realOut)
        }

        val text = sink.toString("UTF-8")
        assertTrue(text.contains("container_id:"), "print_info lines must reach the sink: $text")
        assertTrue(text.contains("chunks: 1"), "print_info chunk count must reach the sink: $text")
        assertEquals("", cap.toString("UTF-8"), "no line may leak to the real stdout")
    }

    @Test
    fun test_iostore_mixed_version_warning_goes_to_console_err() {
        val dir = Files.createTempDirectory("zen-console-retoc-mixed")
        val a = dir.resolve("a.utoc")
        val b = dir.resolve("b.utoc")
        IoStoreWriter.new(a, EIoStoreTocVersion.DirectoryIndex, null, "").finalize()
        IoStoreWriter.new(b, EIoStoreTocVersion.Initial, null, "").finalize()

        val errSink = ByteArrayOutputStream()
        val cap = ByteArrayOutputStream()
        val realErr = System.err
        System.setErr(PrintStream(cap, true))
        try {
            val config = Config().apply { toc_version_override = EIoStoreTocVersion.DirectoryIndex }
            open_with_container_paths(listOf(a, b), config, PrintStream(errSink, true)).close()
        } finally {
            System.setErr(realErr)
        }

        assertTrue(
            errSink.toString("UTF-8").contains("warning: composite container mixes TOC versions"),
            "mixed-version warning must reach the console err: ${errSink.toString("UTF-8")}"
        )
        assertEquals("", cap.toString("UTF-8"), "no warning may leak to the real stderr")
    }
}
