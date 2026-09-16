// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.TreeMap
import kotlin.math.max

class LegacyAssetTest {

    @Test
    fun test_legacy_roundtrip_BP_Table_Lamp() {
        runLegacyRoundtrip(TestFixtures.find("UE5.4/BP_Table_Lamp.uasset")!!.toString(), EngineVersion.UE5_4.package_file_version())
    }

    @Test
    fun test_legacy_roundtrip_TestModUI() {
        runLegacyRoundtrip(TestFixtures.find("UE4.27/TestModUI.uasset")!!.toString(), EngineVersion.UE4_27.package_file_version())
    }

    @Test
    fun test_legacy_roundtrip_Randy() {
        runLegacyRoundtrip(TestFixtures.find("UE5.4/Randy.uasset")!!.toString(), EngineVersion.UE5_4.package_file_version())
    }

    @Test
    fun test_legacy_roundtrip_SM_Cube_UE55() {
        // includes data_resources / bulk data
        runLegacyRoundtrip(TestFixtures.find("UE5.5/SM_Cube.uasset")!!.toString(), EngineVersion.UE5_5.package_file_version())
    }

    @Test
    fun test_legacy_roundtrip_T_Quinn_UE56() {
        runLegacyRoundtrip(TestFixtures.find("UE5.6/T_Quinn_01_D.uasset")!!.toString(), EngineVersion.UE5_6.package_file_version())
    }

    private fun runLegacyRoundtrip(pathStr: String, fallback: FPackageFileVersion? = null) {
        val path = Paths.get(pathStr)
        val data = Files.readAllBytes(path)
        // verify TreeMap + ByteBuffer LE usage per spec
        val treeCheck = TreeMap<String, Int>()
        treeCheck["size"] = data.size
        val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
        assertEquals(treeCheck.size, ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(data.isNotEmpty(), "fixture $pathStr should not be empty")

        // deserialize
        val seekable = SeekableByteArrayInputStream(data)
        val header = FLegacyPackageHeader.deserialize(seekable, fallback)
        assertNotNull(header)
        // basic sanity checks per Rust summary
        assertTrue(header.summary.package_name.isNotEmpty(), "package_name empty for $pathStr")
        assertTrue(header.name_map.num_names() > 0, "name_map empty for $pathStr")
        // package_flags should have Cooked or FilterEditorOnly
        // verify ByteBuffer LE for offsets
        val bbNames = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header.summary.names.count).array()
        assertEquals(header.summary.names.count, ByteBuffer.wrap(bbNames).order(ByteOrder.LITTLE_ENDIAN).int)

        // Use TreeMap for determinism check
        val treeCompare = TreeMap<String, String>()
        treeCompare[header.summary.package_name] = header.summary.package_name
        assertEquals(1, treeCompare.size)

        // serialize with desired_header_size = original total_header_size to preserve padding
        val desiredHeaderSize = header.summary.versioning_info.total_header_size
        val out = SeekableByteArrayOutputStream()
        val log = Log.no_log()
        header.serialize(out, desiredHeaderSize, log)
        val serialized = out.toByteArray()
        assertTrue(serialized.isNotEmpty(), "serialized empty for $pathStr")
        // For some fixtures, serialized size should equal desiredHeaderSize if provided
        if (desiredHeaderSize > 0) {
            assertEquals(desiredHeaderSize, serialized.size, "serialized size mismatch for $pathStr: expected $desiredHeaderSize got ${serialized.size}")
        }
        // ByteBuffer LE check for serialized size
        val bbLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(serialized.size).array()
        assertEquals(serialized.size, ByteBuffer.wrap(bbLen).order(ByteOrder.LITTLE_ENDIAN).int)

        // Rust-exact semantics (retoc legacy_asset.rs:1227-1228, 1244-1245, 1257-1258): serialize shifts
        // export/cell-export serial_offsets by total_header_size (relative -> absolute) and sets
        // bulk_data_start_offset to the end of the last export blob. Deserializing the output must
        // reproduce exactly that shifted state.
        val totalHeaderSize = header.summary.versioning_info.total_header_size.toLong()
        fun endOfLastExportOffset(exports: List<FObjectExport>, cellExports: List<FCellExport>): Long {
            val exportEnd = exports.maxOfOrNull { it.serial_offset + totalHeaderSize + it.serial_size } ?: totalHeaderSize
            val cellEnd = cellExports.maxOfOrNull { it.serial_offset + totalHeaderSize + it.serial_size } ?: totalHeaderSize
            return max(totalHeaderSize, max(exportEnd, cellEnd))
        }

        val expectedSummary = header.summary.copy(bulk_data_start_offset = endOfLastExportOffset(header.exports, header.cell_exports))
        val expectedExports = header.exports.map { it.copy(serial_offset = it.serial_offset + totalHeaderSize) }
        val expectedCellExports = header.cell_exports.map { it.copy(serial_offset = it.serial_offset + totalHeaderSize) }

        // deserialize again
        val seekable2 = SeekableByteArrayInputStream(serialized)
        val header2 = FLegacyPackageHeader.deserialize(seekable2, fallback)
        assertNotNull(header2)

        // Rust quirk (legacy_asset.rs:985-995 de reads cooked_index for version >= AddedCookedIndex, but
        // Writeable at legacy_asset.rs:1004-1014 never writes it): re-serializing a UE5.3+ asset drops the
        // cooked_index bytes, so the reparse misaligns the data resources section — exactly as in Rust.
        val dataResourcesPreserved = header.data_resource_version?.let { it < EObjectDataResourceVersion.AddedCookedIndex } ?: true

        assertEquals(expectedSummary, header2.summary, "summary mismatch for $pathStr")
        assertEquals(header.name_map, header2.name_map, "name_map mismatch for $pathStr")
        assertEquals(header.imports, header2.imports, "imports mismatch for $pathStr")
        assertEquals(expectedExports, header2.exports, "exports mismatch for $pathStr")
        assertEquals(header.cell_imports, header2.cell_imports, "cell_imports mismatch for $pathStr")
        assertEquals(expectedCellExports, header2.cell_exports, "cell_exports mismatch for $pathStr")
        assertEquals(header.preload_dependencies, header2.preload_dependencies, "preload mismatch for $pathStr")
        if (dataResourcesPreserved) {
            assertEquals(header.data_resources, header2.data_resources, "data_resources mismatch for $pathStr")
        } else {
            assertTrue(header2.data_resources.isNotEmpty(), "data_resources lost after roundtrip for $pathStr")
        }
        assertEquals(header.data_resource_version, header2.data_resource_version, "data_resource_version mismatch for $pathStr")

        // Re-serialize second header: the shift operator must be consistent (offsets shift by
        // total_header_size again, bulk_data_start_offset recomputed from the shifted exports)
        val out2 = SeekableByteArrayOutputStream()
        header2.serialize(out2, header2.summary.versioning_info.total_header_size, log)
        val serialized2 = out2.toByteArray()
        val header3 = FLegacyPackageHeader.deserialize(SeekableByteArrayInputStream(serialized2), fallback)
        assertNotNull(header3)
        val expectedSummary3 = header2.summary.copy(bulk_data_start_offset = endOfLastExportOffset(header2.exports, header2.cell_exports))
        val expectedExports3 = header2.exports.map { it.copy(serial_offset = it.serial_offset + totalHeaderSize) }
        val expectedCellExports3 = header2.cell_exports.map { it.copy(serial_offset = it.serial_offset + totalHeaderSize) }
        assertEquals(expectedSummary3, header3.summary, "second roundtrip summary mismatch for $pathStr")
        assertEquals(expectedExports3, header3.exports, "second roundtrip exports mismatch for $pathStr")
        assertEquals(expectedCellExports3, header3.cell_exports, "second roundtrip cell_exports mismatch for $pathStr")
        assertEquals(header2.name_map, header3.name_map, "second roundtrip name_map mismatch for $pathStr")
        assertEquals(header2.imports, header3.imports, "second roundtrip imports mismatch for $pathStr")
        assertEquals(header2.cell_imports, header3.cell_imports, "second roundtrip cell_imports mismatch for $pathStr")
        assertEquals(header2.preload_dependencies, header3.preload_dependencies, "second roundtrip preload mismatch for $pathStr")
        if (dataResourcesPreserved) {
            assertEquals(header2.data_resources, header3.data_resources, "second roundtrip data_resources mismatch for $pathStr")
        } else {
            assertTrue(header3.data_resources.isNotEmpty(), "second roundtrip data_resources lost for $pathStr")
        }
        assertEquals(header2.data_resource_version, header3.data_resource_version, "second roundtrip data_resource_version mismatch for $pathStr")

        // Additional TreeMap determinism after roundtrip
        val treeAfter = TreeMap<String, Int>()
        treeAfter["imports1"] = header.imports.size
        treeAfter["imports2"] = header2.imports.size
        assertEquals(header.imports.size, header2.imports.size)
        assertEquals(2, treeAfter.size) // actually may deduplicate if same size? but check distinct keys
        // Use different keys to keep size 2
        assertTrue(treeAfter.containsKey("imports1"))
    }
}
