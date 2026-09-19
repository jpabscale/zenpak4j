// Copyright (c) 2026 jpabscale — original tests (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hermetic tests for the Zen-JSON write path: the summary's versioning bit and the patch-index
 * contract. No fixtures — the node is minimal but layout-complete for a `> Initial` container.
 */
class ZenJsonWriteTest {
    private val mapper = ObjectMapper()

    private fun emptyArray(): ArrayNode = mapper.createArrayNode()

    private fun minimalNode(hasVersioningInfo: Long): ObjectNode {
        val node = mapper.createObjectNode()
        node.put("type", "zenpak4j.ZenPackageHeader, zenpak4j")
        node.put("toc_version", "PartitionSize")
        node.put("container_header_version", "SoftPackageReferencesOffset")

        val summary = node.putObject("summary")
        summary.put("has_versioning_info", hasVersioningInfo)
        summary.put("header_size", 0L)
        summary.putObject("name").put("index_and_type", 0L).put("number", 0L)
        summary.putObject("source_name").put("index_and_type", 0L).put("number", 0L)
        summary.put("package_flags", 0L)
        summary.put("cooked_header_size", 0L)
        for (f in listOf(
            "imported_public_export_hashes_offset", "import_map_offset", "export_map_offset",
            "export_bundle_entries_offset", "graph_data_offset", "dependency_bundle_headers_offset",
            "dependency_bundle_entries_offset", "imported_package_names_offset", "name_map_names_offset",
            "name_map_names_size", "name_map_hashes_offset", "name_map_hashes_size", "graph_data_size",
        )) summary.put(f, 0)

        val vi = node.putObject("versioning_info")
        vi.put("zen_version", "ExportDependencies")
        vi.put("licensee_version", 0)
        vi.putObject("package_file_version").put("file_version_ue4", 0).put("file_version_ue5", 0)
        vi.set<ArrayNode>("custom_versions", emptyArray())

        val nm = node.putObject("name_map")
        nm.put("kind", "Package")
        nm.set<ArrayNode>("names", emptyArray())
        nm.set<ArrayNode>("raw_names_b64", emptyArray())

        for (k in listOf(
            "import_map", "export_map", "export_bundle_headers", "export_bundle_entries",
            "dependency_bundle_headers", "dependency_bundle_entries", "internal_dependency_arcs",
            "external_package_dependencies", "cell_import_map", "cell_export_map", "imported_packages",
            "imported_package_names", "imported_public_export_hashes", "shader_map_hashes",
            "bulk_data", "payloads",
        )) node.set<ArrayNode>(k, emptyArray())
        return node
    }

    /** For a `> Initial` container the chunk starts with the summary's `has_versioning_info` word. */
    private fun firstU32(bytes: ByteArray): Long =
        ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    @Test
    fun summaryVersioningBitDrivesTheWrittenFlagAndVersioningBlock() {
        val unversioned = zen_package_write(minimalNode(0L)).chunk
        val versioned = zen_package_write(minimalNode(1L)).chunk
        assertEquals(0L, firstU32(unversioned), "has_versioning_info must stay 0 for an unversioned dump")
        assertEquals(1L, firstU32(versioned), "has_versioning_info must stay 1 for a versioned dump")
        assertTrue(
            unversioned.size < versioned.size,
            "an unversioned write must skip the versioning block " +
                "(${unversioned.size} vs ${versioned.size} bytes)")
    }

    @Test
    fun patchEntryWithoutIndexIsRefused() {
        val node = minimalNode(1L)
        node.withArray("export_map").add(mapper.createObjectNode().put("object_flags", 0))
        val patch = mapper.readTree("""{"export_map":[{"object_flags":3}]}""")
        assertThrows<IllegalArgumentException> { zen_package_apply_patch(node, patch) }
    }

    @Test
    fun patchEntryAppliesToItsIndex() {
        val node = minimalNode(1L)
        node.withArray("export_map").add(mapper.createObjectNode().put("object_flags", 0))
        val patch = mapper.readTree("""{"export_map":[{"index":0,"object_flags":3}]}""")
        assertEquals(1, zen_package_apply_patch(node, patch))
        assertEquals(3, node.path("export_map").path(0).path("object_flags").asInt())
    }
}
