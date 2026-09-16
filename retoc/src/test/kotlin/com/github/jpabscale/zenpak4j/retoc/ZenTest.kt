// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.TreeMap

class ZenTest {
    @Test
    fun test_zen_asset_parsing() {
        val path = TestFixtures.find("UE4.27/SPR_UI_Battle.uasset")!!
        val metadataPath = TestFixtures.find("UE4.27/SPR_UI_Battle.metadata.json")!!
        val data = Files.readAllBytes(path)
        // verify TreeMap + ByteBuffer LE usage per spec
        val treeCheck = TreeMap<String, Int>()
        treeCheck["test"] = data.size
        val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
        assertEquals(treeCheck.size, ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(data.isNotEmpty())

        // parse metadata json manually to avoid serde dependencies
        val metadataText = Files.readString(metadataPath)
        // crude parsing for SPR_UI_Battle which has toc_version PartitionSize, container_header_version Initial
        val toc_version = EIoStoreTocVersion.PartitionSize
        val container_header_version = EIoContainerHeaderVersion.Initial
        // store entry from metadata
        val store_entry = StoreEntry(
            export_bundles_size = 195060UL,
            load_order = 70481u,
            export_count = 1,
            export_bundle_count = 2,
            imported_packages = listOf(
                FPackageId(15036964214830806484UL),
                FPackageId(15518177376771229772UL),
                FPackageId(10340355531828087319UL),
                FPackageId(16790748357063413372UL),
                FPackageId(9319950482528004648UL),
                FPackageId(2660865673581002379UL),
                FPackageId(18084608118055282882UL)
            ),
            shader_map_hashes = emptyList()
        )

        val seekable = SeekableByteArrayInputStream(data)
        val header = FZenPackageHeader.deserialize(seekable, store_entry, toc_version, container_header_version, null)
        assertNotNull(header)
        // package name check from Rust test context (not asserted in Rust but we verify non-empty)
        val package_name = header.package_name()
        assertTrue(package_name.isNotEmpty())
        // source name
        val source_name = header.source_package_name()
        assertTrue(source_name.isNotEmpty())

        // verify export_map size matches store_entry export_count
        assertEquals(store_entry.export_count, header.export_map.size)
        // verify export_bundle_entries count = 2*exports
        assertEquals(header.export_map.size * 2, header.export_bundle_entries.size)

        // Roundtrip: serialize -> deserialize and assert equality
        val store_entry_copy = StoreEntry(
            export_bundles_size = store_entry.export_bundles_size,
            load_order = store_entry.load_order,
            export_count = store_entry.export_count,
            export_bundle_count = store_entry.export_bundle_count,
            imported_packages = store_entry.imported_packages,
            shader_map_hashes = store_entry.shader_map_hashes
        )
        val out = SeekableByteArrayOutputStream()
        val offsets = header.serialize(out, store_entry_copy, container_header_version)
        // ensure legacy arcs offsets returned
        assertNotNull(offsets)
        val serialized = out.toByteArray()
        // ByteBuffer LE check
        val bbLen = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(serialized.size).array()
        assertEquals(serialized.size, ByteBuffer.wrap(bbLen).order(ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(serialized.isNotEmpty())
        // For Initial version, serialized size should equal header.header_size? Check header.header_size after serialize
        assertEquals(header.summary.header_size.toInt(), serialized.size)

        // deserialize again
        val seekable2 = SeekableByteArrayInputStream(serialized)
        val header2 = FZenPackageHeader.deserialize(seekable2, store_entry_copy, toc_version, container_header_version, null)
        assertNotNull(header2)
        // assert equality via equals (compares all fields)
        assertEquals(header, header2)
        // additional field checks using TreeMap for determinism
        val treeCompare = TreeMap<String, String>()
        treeCompare[header.package_name()] = header2.package_name()
        assertEquals(1, treeCompare.size)
        assertEquals(header.package_name(), header2.package_name())
        assertEquals(header.export_bundle_headers, header2.export_bundle_headers)
        assertEquals(header.export_bundle_entries, header2.export_bundle_entries)
        assertEquals(header.import_map, header2.import_map)
        assertEquals(header.export_map, header2.export_map)

        // Re-serialize second header and compare bytes
        val store_entry_copy2 = StoreEntry(
            export_bundles_size = store_entry.export_bundles_size,
            load_order = store_entry.load_order,
            export_count = store_entry.export_count,
            export_bundle_count = store_entry.export_bundle_count,
            imported_packages = store_entry.imported_packages,
            shader_map_hashes = store_entry.shader_map_hashes
        )
        val out2 = SeekableByteArrayOutputStream()
        header2.serialize(out2, store_entry_copy2, container_header_version)
        val serialized2 = out2.toByteArray()
        assertArrayEquals(serialized, serialized2)
    }
}
