// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class ContainerHeaderTest {

    private fun test_rw_container_header(data: ByteArray, version_override: EIoContainerHeaderVersion?) {
        val header = FIoContainerHeader.deserialize(ByteArrayInputStream(data), version_override)

        val out = ByteArrayOutputStream()
        // Use serialize that handles Seekable buffering internally
        header.serialize(out)
        val outBytes = out.toByteArray()

        // Debug sizes
        println("original size ${data.size}, out size ${outBytes.size}, header version ${header.version} override $version_override")

        val header2 = FIoContainerHeader.deserialize(ByteArrayInputStream(outBytes), version_override)

        // For debug, if not equal, print details
        if (header != header2) {
            println("header != header2")
            println("header: $header")
            println("header2: $header2")
            // compare fields
            println("version ${header.version} vs ${header2.version}")
            println("container_id ${header.container_id} vs ${header2.container_id}")
            println("packages size ${header.packages.map.size} vs ${header2.packages.map.size}")
            println("redirect_name_map ${header.redirect_name_map.names.size} vs ${header2.redirect_name_map.names.size}")
        }

        assertEquals(header, header2, "header roundtrip equality failed")

        // Also check that serialized bytes equal original when version_override is None? For issue7/18 with override, original may have been read with override, so after serialize with same override, bytes should still roundtrip via header equality, but not necessarily byte equality due to deterministic re-serialization? Rust test only checks header == header2, not byte equality.
        // However for non-override cases, we can check byte equality or at least that re-serialized bytes when deserialized again gives same header.
        // For extra strictness, when version_override == null, check bytes equal (allow for deterministic reorder? but should be equal)
        if (version_override == null) {
            // Compare bytes length and content; if not equal, show diff
            if (!data.contentEquals(outBytes)) {
                println("bytes differ at first diff:")
                val minLen = minOf(data.size, outBytes.size)
                for (i in 0 until minLen) {
                    if (data[i] != outBytes[i]) {
                        println(" diff at $i: orig=${data[i].toInt() and 0xFF} out=${outBytes[i].toInt() and 0xFF}")
                        break
                    }
                }
                println("byte mismatch but header equality passed; checking if size diff is 16")
            }
            assertTrue(data.contentEquals(outBytes), "serialized bytes should equal original for version_override == null (original ${data.size} vs out ${outBytes.size})")
        }
    }

    private fun readFixture(vararg candidates: String): ByteArray {
        for (c in candidates) {
            val p = Paths.get(c)
            if (Files.exists(p)) {
                return Files.readAllBytes(p)
            }
        }
        // try absolute retoc path
        throw AssertionError("fixture not found among ${candidates.joinToString()}")
    }

    @Test
    fun test_container_header_new() {
        val data = readFixture(
            TestFixtures.find("UE5.3/ContainerHeader_1.bin")!!.toString(),
            "../retoc/retoc/tests/UE5.3/ContainerHeader_1.bin",
            "tests/UE5.3/ContainerHeader_1.bin"
        )
        test_rw_container_header(data, null)
    }

    @Test
    fun test_container_header_initial() {
        val data = readFixture(
            TestFixtures.find("UE4.27/ContainerHeader_1.bin")!!.toString(),
            "../retoc/retoc/tests/UE4.27/ContainerHeader_1.bin"
        )
        test_rw_container_header(data, null)
    }

    @Test
    fun test_container_header_issue7() {
        val data = readFixture(
            TestFixtures.find("issues/issue7/header.bin")!!.toString(),
            "../retoc/retoc/tests/issues/issue7/header.bin"
        )
        test_rw_container_header(data, EIoContainerHeaderVersion.PreInitial)
    }

    @Test
    fun test_container_header_issue18() {
        val data = readFixture(
            TestFixtures.find("issues/issue18/header.bin")!!.toString(),
            "../retoc/retoc/tests/issues/issue18/header.bin"
        )
        test_rw_container_header(data, EIoContainerHeaderVersion.PreInitial)
    }

    @Test
    fun test_container_header_localized() {
        val data = readFixture(
            TestFixtures.find("UE5.0/ContainerHeader_1.bin")!!.toString(),
            "../retoc/retoc/tests/UE5.0/ContainerHeader_1.bin"
        )
        test_rw_container_header(data, null)
    }
}
