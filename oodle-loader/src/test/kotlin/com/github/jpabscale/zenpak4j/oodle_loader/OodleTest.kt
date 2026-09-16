// Copyright (c) 2026 jpabscale — original tests (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.oodle_loader

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

class OodleTest {
    // no win-arm64 artifact exists in OodleUE; current_platform() throws there
    private fun oodle_usable(): Boolean =
        runCatching { current_platform() }.isSuccess

    @Test
    fun test_fetch_url() {
        assumeTrue(oodle_usable(), "no native Oodle artifact on this platform")
        val platform = current_platform()
        val url = platform.url()
        println("Platform: $platform")
        println("URL: $url")
        assertTrue(url.contains("2.9.10"))
        assertTrue(url.contains(platform.name))
    }

    @Test
    fun test_oodle_load_and_compress() {
        assumeTrue(oodle_usable(), "no native Oodle artifact on this platform")
        val path = fetch_oodle()
        println("Fetched oodle at: $path exists=${Files.exists(path)} size=${if (Files.exists(path)) Files.size(path) else -1}")
        assertTrue(Files.exists(path), "oodle lib should exist after fetch")
        // Try to load
        val oodle = Oodle.load_from_path(path)
        println("Oodle loaded: $oodle")
        val data = "In tools and when compressing large inputs in one call, consider using \$OodleXLZ_Compress_AsyncAndWait (in the Oodle2 Ext lib) instead to get parallelism. Alternatively, chop the data into small fixed size chunks (we recommend at least 256KiB, i.e. 262144 bytes) and call compress on each of them, which decreases compression ratio but makes for trivial parallel compression and decompression.".toByteArray()
        val compressed = oodle.compress(data, Compressor.Mermaid, CompressionLevel.Optimal5)
        println("compressed ${data.size} -> ${compressed.size}")
        assertTrue(compressed.isNotEmpty())
        val decompressed = ByteArray(data.size)
        val ret = oodle.decompress(compressed, decompressed)
        println("decompress ret=$ret")
        assertEquals(data.size, ret)
        assertArrayEquals(data, decompressed)
        oodle.close()
    }
}
