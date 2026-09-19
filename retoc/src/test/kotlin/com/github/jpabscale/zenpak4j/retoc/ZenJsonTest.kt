// Copyright (c) 2026 jpabscale — Zen-JSON dump tests (game-gated fixture; hermetic assertions).
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ZenJsonTest {
    private val cookerChunk: Path = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val headerChunk: Path = Path.of(
        System.getenv("SB_HEADER_CHUNK") ?: "/tmp/opencode/raw-keepoutfit/chunks/9bdb3fceb6b7da900000000a")
    private val packageId = (System.getenv("SB_PACKAGE_ID") ?: "9582239030029544658").toULong()

    private fun storeEntry(): StoreEntry {
        val container = FIoContainerHeader.deserialize(
            ByteArrayInputStream(Files.readAllBytes(headerChunk)), null)
        return container.get_store_entry(FPackageId(packageId))!!
    }

    @Test
    fun dumpIsDeterministicAndCarriesTheVerifiedStructure() {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        assumeTrue(Files.isRegularFile(headerChunk), "container header fixture missing at $headerChunk")
        val chunk = Files.readAllBytes(cookerChunk)
        val entry = storeEntry()
        val node = zen_package_to_json_node(
            chunk, false, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize, entry)
        assertTrue(
            node.toString() == zen_package_to_json_node(
                chunk, false, EIoContainerHeaderVersion.Initial,
                EIoStoreTocVersion.PartitionSize, entry).toString(),
            "dump must be deterministic")

        // values verified byte-identical by ZenGraftSpikeTest
        assertEquals(64036, node.path("summary").path("header_size").asInt())
        assertEquals(76934, node.path("summary").path("cooked_header_size").asInt())
        assertEquals(480, node.path("import_map").size())
        assertEquals(2, node.path("export_bundle_headers").size())
        assertEquals(114, node.path("payload_count").asInt())
        assertEquals(114, node.path("export_map").size())
        assertEquals(228, node.path("export_bundle_entries").size())  // Create+Serialize per export
        val ubergraph = node.path("export_map").first {
            it.path("object_name_resolved").asText() == "ExecuteUbergraph_CH_P_EVE_01_Blueprint"
        }
        assertEquals(90803, ubergraph.path("cooked_serial_size").asText().toInt())
        assertTrue(node.path("payloads").any { it.path("size").asInt() == 90803 }, "payload inventory")

        val withPayloads = zen_package_to_json_node(
            chunk, true, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize, entry)
        assertTrue(withPayloads.path("payloads").all { it.has("bytes_b64") }, "bytes are opt-in")
        assertTrue(!node.path("payloads").any { it.has("bytes_b64") }, "default keeps hashes only")
    }

    @Test
    fun jsonRoundTripIsByteIdenticalAndTablePatchesOnlyTouchTheirFields() {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        val chunk = Files.readAllBytes(cookerChunk)
        val entry = storeEntry()
        val dumped = zen_package_to_json_node(
            chunk, true, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize, entry)

        val rebuilt = zen_package_from_json_node(dumped, entry)
        assertArrayEquals(chunk, rebuilt, "JSON->chunk must reproduce the cooker chunk byte-for-byte")

        // a table patch changes exactly the addressed field and stays structurally valid
        val patched = dumped.deepCopy()
        val before = patched.path("export_map").path(0).path("object_flags").asLong()
        val patch = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            """{"export_map":[{"index":0,"object_flags":${before + 1}}]}""")
        assertEquals(1, zen_package_apply_patch(patched, patch))
        assertEquals(before + 1, patched.path("export_map").path(0).path("object_flags").asLong())

        val patchedBytes = zen_package_from_json_node(patched, entry)
        assertTrue(patchedBytes.size == chunk.size, "flags are same-size")
        val diffBytes = chunk.indices.count { chunk[it] != patchedBytes[it] }
        assertTrue(diffBytes in 1..8, "only the flags word changed (got $diffBytes bytes)")

        val redumped = zen_package_to_json_node(
            patchedBytes, false, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize, entry)
        assertEquals(patched.path("export_map").path(0).path("object_flags").asLong(),
            redumped.path("export_map").path(0).path("object_flags").asLong())
        val a = dumped.path("export_map").path(0).deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        val b = redumped.path("export_map").path(0).deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        assertEquals(a.path("object_flags").asLong() + 1, b.path("object_flags").asLong())
        for (field in listOf("cooked_serial_offset", "cooked_serial_size", "object_name_resolved",
                "public_export_hash", "filter_flags")) {
            assertEquals(a.path(field).asText(), b.path(field).asText(), "unrelated field $field")
        }
        assertTrue(zen_package_validate(patchedBytes).isEmpty(),
            "patched chunk must validate: ${zen_package_validate(patchedBytes)}")
    }

    @Test
    fun nodePayloadSwapIsAFaithfulStandInForTheByteGraft() {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        val chunk = Files.readAllBytes(cookerChunk)
        val entry = storeEntry()
        val dumped = zen_package_to_json_node(
            chunk, true, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize, entry)

        // swapping an export's own payload back in must reproduce the chunk byte-for-byte
        val payloads = extract_zen_payloads(chunk)
        val noop = dumped.deepCopy()
        assertEquals(1, zen_package_apply_payloads(noop, mapOf(1 to payloads[1])))
        assertArrayEquals(chunk, zen_package_from_json_node(noop, entry),
            "node-level payload swap must be layout-exact")

        // and a grown payload relays the region out contiguously, validator-clean
        val grown = dumped.deepCopy()
        assertEquals(1, zen_package_apply_payloads(grown, mapOf(1 to payloads[1] + ByteArray(8))))
        val written = zen_package_from_json_node(grown, entry)
        assertTrue(written.size == chunk.size + 8, "one grown payload shifts the chunk by 8 bytes")
        assertTrue(zen_package_validate(written).isEmpty(),
            "relayed region must validate: ${zen_package_validate(written)}")
    }
}
