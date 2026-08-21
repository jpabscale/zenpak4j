package com.github.jpabscale.zenpak4j.repak

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class OodlePakTest {
    @Test
    fun test_oodle_roundtrip() {
        // no native Oodle artifact for this platform (win-arm64): skip rather than fail
        assumeTrue(
            runCatching { com.github.jpabscale.zenpak4j.oodle_loader.current_platform() }.isSuccess,
            "native Oodle not available on this platform")
        val tmp = Files.createTempFile("oodle_test", ".pak")
        val chan = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            val builder = PakBuilder.new().compression(listOf(Compression.Oodle))
            val writer = builder.writer(chan, Version.V11, "../../../", 0u)
            val data = "Hello Oodle via FFM on Linux x64 with Java 25 - testing repak".toByteArray()
            writer.write_file("test.txt", true, data)
            writer.write_file("empty.txt", true, ByteArray(0))
            val large = ByteArray(300000) { (it % 256).toByte() }
            writer.write_file("large.bin", true, large)
            writer.write_index()
            chan.position(0)
            val reader = PakBuilder.new().reader(chan)
            assertEquals(Version.V11, reader.version())
            assertEquals("../../../", reader.mount_point())
            assertEquals(setOf("test.txt", "empty.txt", "large.bin"), reader.files().toSet())
            assertArrayEquals(data, reader.get("test.txt", chan))
            assertArrayEquals(ByteArray(0), reader.get("empty.txt", chan))
            assertArrayEquals(large, reader.get("large.bin", chan))
        } finally {
            chan.close()
            Files.deleteIfExists(tmp)
        }
    }
}
