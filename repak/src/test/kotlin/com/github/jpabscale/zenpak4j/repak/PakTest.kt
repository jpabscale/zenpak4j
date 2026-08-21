// Rust: repak/tests/test.rs
package com.github.jpabscale.zenpak4j.repak

import java.io.ByteArrayOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class PakTest {
    companion object {
        const val AES_KEY = "lNJbw660IOC+kU7cnVQ1oeqrXyhk4J6UAZrCBbcnp94="

        // no win-arm64 native artifacts exist for Oodle OR zstd-jni; current_platform()
        // throws on win-arm64, which is exactly where native compression is unavailable
        fun oodle_usable(): Boolean =
            runCatching { com.github.jpabscale.zenpak4j.oodle_loader.current_platform() }.isSuccess
        fun decode_key(): SecretKeySpec {
            val bytes = Base64.getDecoder().decode(AES_KEY)
            return SecretKeySpec(bytes, "AES")
        }

        // Fixtures resolve via RepakFixtures (env, build/fixtures from downloadFixtures,
        // or a sibling trumank/repak checkout)
        fun packs_dir(): Path = RepakFixtures.require("packs")

        fun pack_root_dir(): Path = RepakFixtures.require("pack/root")

        @JvmStatic
        fun read_params(): List<Array<Any>> {
            val versions = listOf("v5" to Version.V5, "v7" to Version.V7, "v8a" to Version.V8A, "v8b" to Version.V8B, "v9" to Version.V9, "v11" to Version.V11)
            val compresses = listOf("", "_compress")
            val encrypts = listOf("", "_encrypt")
            val encryptIndexes = listOf("", "_encryptindex")
            val list = mutableListOf<Array<Any>>()
            for ((verStr, ver) in versions) {
                for (c in compresses) for (e in encrypts) for (ei in encryptIndexes) {
                    val name = "pack_${verStr}${c}${e}${ei}.pak"
                    list.add(arrayOf(ver, name, verStr + c + e + ei))
                }
            }
            return list
        }

        @JvmStatic
        fun write_params(): List<Array<Any>> {
            val versions = listOf("v5" to Version.V5, "v7" to Version.V7, "v8a" to Version.V8A, "v8b" to Version.V8B, "v9" to Version.V9, "v11" to Version.V11)
            return versions.map { (str, v) -> arrayOf(v, "pack_${str}.pak") }
        }

        @JvmStatic
        fun rewrite_params(): List<Array<Any>> {
            val versions = listOf("v5" to Version.V5, "v7" to Version.V7, "v8a" to Version.V8A, "v8b" to Version.V8B, "v9" to Version.V9, "v11" to Version.V11)
            val compresses = listOf("", "_compress")
            val encrypts = listOf("", "_encrypt")
            val list = mutableListOf<Array<Any>>()
            for ((verStr, ver) in versions) for (c in compresses) for (e in encrypts) {
                val name = "pack_${verStr}${c}${e}.pak"
                list.add(arrayOf(ver, name))
            }
            return list
        }
    }

    @ParameterizedTest
    @MethodSource("read_params")
    fun test_read(version: Version, file_name: String, _key: String) {
        val packs = packs_dir()
        val file = packs.resolve(file_name)
        assertTrue(Files.exists(file), "pak file $file_name not found at $file")
        val bytes = Files.readAllBytes(file)
        // Use SeekableByteChannel via FileChannel
        val key = decode_key()
        val chan = FileChannel.open(file, StandardOpenOption.READ)
        chan.use {
            val pak = PakBuilder.new().key(key).reader(it)
            assertEquals("../mount/point/root/", pak.mount_point())
            assertEquals(version, pak.version())
            val files = pak.files().toSet()
            val expected = setOf("test.txt", "test.png", "zeros.bin", "directory/nested.txt")
            assertEquals(expected, files, "files mismatch for $file_name")
            for (f in files) {
                val data = pak.get(f, chan)
                val expectedBytes = when (f) {
                    "test.txt" -> Files.readAllBytes(pack_root_dir().resolve("test.txt"))
                    "test.png" -> Files.readAllBytes(pack_root_dir().resolve("test.png"))
                    "zeros.bin" -> Files.readAllBytes(pack_root_dir().resolve("zeros.bin"))
                    "directory/nested.txt" -> Files.readAllBytes(pack_root_dir().resolve("directory/nested.txt"))
                    else -> error("unknown $f")
                }
                assertArrayEquals(expectedBytes, data, "content mismatch for $f in $file_name")
            }
        }
    }

    @ParameterizedTest
    @MethodSource("write_params")
    fun test_write(version: Version, file_name: String) {
        val packs = packs_dir()
        val file = packs.resolve(file_name)
        val bytes = Files.readAllBytes(file)
        val key = decode_key()
        // reader
        val readChan = FileChannel.open(file, StandardOpenOption.READ)
        val pak_reader = PakBuilder.new().key(key).reader(readChan)
        readChan.close()

        // writer to memory via temp file channel
        val tmp = Files.createTempFile("test_write", ".pak")
        val writeChan = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            val pak_writer = PakBuilder.new().writer(writeChan, pak_reader.version(), pak_reader.mount_point(), 0x205C5A7Du)
            // need to read original bytes again for get
            val readChan2 = FileChannel.open(file, StandardOpenOption.READ)
            for (path in pak_reader.files()) {
                val data = pak_reader.get(path, readChan2)
                pak_writer.write_file(path, false, data)
            }
            readChan2.close()
            pak_writer.write_index()
            writeChan.position(0)
            val outBytes = ByteArray(writeChan.size().toInt())
            val buf = java.nio.ByteBuffer.wrap(outBytes)
            writeChan.read(buf)
            assertArrayEquals(bytes, outBytes, "write roundtrip bytes mismatch for $file_name version $version")
        } finally {
            writeChan.close()
            Files.deleteIfExists(tmp)
        }
    }

    @ParameterizedTest
    @MethodSource("rewrite_params")
    fun test_rewrite_index(version: Version, file_name: String) {
        val packs = packs_dir()
        val file = packs.resolve(file_name)
        val bytes = Files.readAllBytes(file)
        val key = decode_key()
        // open as read+write copy
        val tmp = Files.createTempFile("rewrite", ".pak")
        Files.write(tmp, bytes)
        val chan = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE)
        try {
            val pak_reader = PakBuilder.new().key(key).reader(chan)
            val rewritten = pak_reader.into_pakwriter(chan).write_index()
            rewritten.position(0)
            val outBytes = ByteArray(rewritten.size().toInt())
            rewritten.read(java.nio.ByteBuffer.wrap(outBytes))
            assertArrayEquals(bytes, outBytes, "rewrite_index mismatch for $file_name")
        } finally {
            chan.close()
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun test_entry_roundtrip() {
        val data = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x54, 0x02, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x54, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0xDD.toByte(), 0x94.toByte(), 0xFD.toByte(), 0xC3.toByte(), 0x5F.toByte(), 0xF5.toByte(), 0x91.toByte(), 0xA9.toByte(), 0x9A.toByte(), 0x5E.toByte(), 0x14.toByte(), 0xDC.toByte(), 0x9B.toByte(),
            0xD3.toByte(), 0x58.toByte(), 0x89.toByte(), 0x78.toByte(), 0xA6.toByte(), 0x1C.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00.toByte()
        )
        val input = java.io.ByteArrayInputStream(data)
        val entry = Entry.read(input, Version.V5, null)
        val out = java.io.ByteArrayOutputStream()
        entry.write(out, Version.V5, EntryLocation.Data, null)
        assertArrayEquals(data, out.toByteArray(), "entry roundtrip")
    }

    @Test
    fun test_simple_roundtrip_none() {
        roundtrip(Version.V11, null)
    }

    @Test
    fun test_simple_roundtrip_zlib() {
        roundtrip(Version.V11, Compression.Zlib)
    }

    @Test
    fun test_simple_roundtrip_gzip() {
        roundtrip(Version.V11, Compression.Gzip)
    }

    @Test
    fun test_simple_roundtrip_zstd() {
        assumeTrue(oodle_usable(), "no native compression stack on this platform (win-arm64)")
        roundtrip(Version.V11, Compression.Zstd)
    }

    @Test
    fun test_simple_roundtrip_lz4() {
        roundtrip(Version.V11, Compression.LZ4)
    }

    private fun roundtrip(version: Version, compression: Compression?) {
        val file_contents = listOf(
            "hello.txt" to "Hello, world!".toByteArray(),
            "empty.txt" to ByteArray(0),
            "large.bin" to ByteArray(4096) { 0xAB.toByte() },
            "dir/nested.txt" to "nested file content".toByteArray()
        )
        val allowed = compression?.let { listOf(it) } ?: emptyList()
        val tmp = Files.createTempFile("roundtrip", ".pak")
        val chan = FileChannel.open(tmp, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try {
            var builder = PakBuilder.new()
            if (allowed.isNotEmpty()) builder = builder.compression(allowed)
            val writer = builder.writer(chan, version, "../../../", 0u)
            for ((path, data) in file_contents) {
                writer.write_file(path, compression != null, data)
            }
            writer.write_index()
            chan.position(0)
            val reader = PakBuilder.new().reader(chan)
            assertEquals(version, reader.version())
            assertEquals("../../../", reader.mount_point())
            val files = reader.files().sorted()
            val expected = file_contents.map { it.first }.sorted()
            assertEquals(expected, files)
            for ((path, expectedData) in file_contents) {
                val data = reader.get(path, chan)
                assertArrayEquals(expectedData, data, "mismatch for $path version $version compression $compression")
            }
        } finally {
            chan.close()
            Files.deleteIfExists(tmp)
        }
    }
}
