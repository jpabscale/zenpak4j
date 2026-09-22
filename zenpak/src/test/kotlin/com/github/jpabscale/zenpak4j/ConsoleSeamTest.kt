// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// Embedder console seam tests (mapping §13 / EXC-016): parallel workers print through the
// injected Console with no leak to the real stdout; the exception path leaks nothing; the
// service `out` parameter forwards end-to-end. Fixture-free by design (always runnable).
package com.github.jpabscale.zenpak4j

import com.github.jpabscale.zenpak4j.console.Console
import com.github.jpabscale.zenpak4j.repak.RepakError
import com.github.jpabscale.zenpak4j.repak_actions.ActionPack
import com.github.jpabscale.zenpak4j.repak_actions.pack
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleSeamTest {

    private fun tmpDir(tag: String): Path = Files.createTempDirectory("zen-console-$tag")

    private fun threeFileInput(tag: String): Path {
        val inDir = tmpDir(tag)
        Files.writeString(inDir.resolve("a.txt"), "alpha")
        Files.writeString(inDir.resolve("b.txt"), "beta")
        Files.writeString(inDir.resolve("c.txt"), "gamma")
        return inDir
    }

    @Test
    fun test_parallel_pack_lines_land_in_sink_only() {
        val inDir = threeFileInput("pack-in")
        val outPak = inDir.resolveSibling("pack-out.pak")

        val sink = ByteArrayOutputStream()
        val console = Console(PrintStream(sink, true))

        val cap = ByteArrayOutputStream()
        val realOut = System.out
        System.setOut(PrintStream(cap, true))
        try {
            // verbose prints "packing <path>" from inside the parallel worker scope;
            // quiet=false adds the completion line — all of it must reach the sink.
            console.pack(
                ActionPack(
                    input = inDir.toString(),
                    output = outPak.toString(),
                    verbose = true,
                    quiet = false
                )
            )
        } finally {
            System.setOut(realOut)
        }

        val lines = sink.toString("UTF-8").lineSequence().filter { it.isNotBlank() }.toList()
        assertTrue(lines.any { it.startsWith("packing ") }, "worker lines must reach the sink: $lines")
        assertTrue(lines.any { it.startsWith("Packed 3 files to ") }, "completion line must reach the sink: $lines")
        assertEquals("", cap.toString("UTF-8"), "no line may leak to the real stdout")
    }

    @Test
    fun test_exception_leaves_real_stdout_clean() {
        val sink = ByteArrayOutputStream()
        val console = Console(PrintStream(sink, true))

        val cap = ByteArrayOutputStream()
        val realOut = System.out
        System.setOut(PrintStream(cap, true))
        try {
            assertThrows(RepakError.InputNotADirectory::class.java) {
                console.pack(ActionPack(input = "/nonexistent/zen-console-dir"))
            }
        } finally {
            System.setOut(realOut)
        }
        assertEquals("", cap.toString("UTF-8"), "an exception path may not leak to the real stdout")
    }

    @Test
    fun test_service_out_parameter_forwards_end_to_end() {
        val inDir = threeFileInput("svc-pack-in")
        val outPak = inDir.resolveSibling("svc-out.pak")

        val sink = ByteArrayOutputStream()
        val cap = ByteArrayOutputStream()
        val realOut = System.out
        System.setOut(PrintStream(cap, true))
        try {
            ZenPakService.repak_pack(
                inputDir = inDir,
                outputPak = outPak,
                verbose = true,
                out = PrintStream(sink, true)
            )
        } finally {
            System.setOut(realOut)
        }
        val text = sink.toString("UTF-8")
        assertTrue(text.contains("packing "), "service out must receive the worker lines: $text")
        assertEquals("", cap.toString("UTF-8"), "service call may not leak to the real stdout")
    }
}
