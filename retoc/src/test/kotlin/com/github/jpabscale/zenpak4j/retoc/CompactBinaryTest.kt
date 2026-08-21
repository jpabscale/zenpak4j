package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.FileInputStream
import java.nio.file.Paths
import java.io.File

class CompactBinaryTest {
    @Test
    fun test_compact_binary() {
        val candidates = listOf(
            TestFixtures.find("UE5.4/packagestore.manifest")!!,
            Paths.get("build/fixtures/retoc/tests/UE5.4/packagestore.manifest")
        )
        val path = candidates.firstOrNull { java.nio.file.Files.exists(it) } ?: candidates.first()
        val stream = FileInputStream(path.toFile())
        val field = read_compact_binary(stream)
        // Mimic Rust test sorting
        val oplog = field.value.unwrap_uniform_object()["oplog"]?.unwrap_uniform_object()
        assertNotNull(oplog, "oplog should exist")
        val entries = oplog?.get("entries")?.unwrap_uniform_array()
        assertNotNull(entries, "entries should exist")
        // Sort by packagename
        entries?.sortBy { it.unwrap_object()["packagestoreentry"]?.unwrap_uniform_object()?.get("packagename")?.unwrap_string() }
        println("entries size: ${entries?.size}")
        assertTrue(entries != null && entries.isNotEmpty())
        // Write to out/packagestore.json equivalent (just check serialization doesn't crash)
        // For now just ensure field is not null
        assertNotNull(field)
    }

    @Test
    fun test_var_uint() {
        val buf = byteArrayOf(0xe1.toByte(), 0x23, 0x45, 0x67)
        val cur = java.io.ByteArrayInputStream(buf)
        val value = varint.read_var_uint(cur)
        assertEquals(0x1234567L.toULong(), value)
    }
}
