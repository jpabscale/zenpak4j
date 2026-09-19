// Copyright (c) 2026 jpabscale — graft tests: cooker-table preservation and payload swap (game-gated).
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ZenGraftTest {
    @Test
    fun nameMapNamesAreReadFromTheCookerChunk() {
        val chunk = java.nio.file.Path.of(
            System.getenv("SB_PKG_CHUNK")
                ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            java.nio.file.Files.isRegularFile(chunk), "cooker fixture missing at $chunk")
        val bytes = java.nio.file.Files.readAllBytes(chunk)
        val names = zen_name_map_names(bytes)
        assertTrue(names.isNotEmpty(), "the cooker chunk must expose its name map")
        val dumped = zen_package_to_json_node(
            bytes, false, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize,
            storeEntry())
        assertEquals(dumped.path("name_map").path("names").size(), names.size, "same name count as the dump")
        assertEquals(dumped.path("name_map").path("names").path(0).asText(), names.first())
    }


    private val packageChunk: Path = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val convertedChunk: Path = Path.of(
        System.getenv("SB_DONOR_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-mod/CH_P_EVE_01_Blueprint.uasset")
    private val pristineConversion: Path = Path.of(
        System.getenv("SB_PRISTINE_CHUNK") ?:
            "/tmp/opencode/hb-work/.temp/kinesis-graft/SB/Content/Art/Character/PC/CH_P_EVE_01/Blueprints/pristine.chunk")
    private val headerChunk: Path = Path.of(
        System.getenv("SB_HEADER_CHUNK") ?: "/tmp/opencode/raw-keepoutfit/chunks/9bdb3fceb6b7da900000000a")
    private val packageId: ULong = (System.getenv("SB_PACKAGE_ID") ?: "9582239030029544658").toULong()

    private fun storeEntry(): StoreEntry {
        assumeTrue(Files.isRegularFile(packageChunk), "package chunk fixture missing at $packageChunk")
        assumeTrue(Files.isRegularFile(headerChunk), "container header chunk fixture missing at $headerChunk")
        val container = FIoContainerHeader.deserialize(
            ByteArrayInputStream(Files.readAllBytes(headerChunk)), null)
        val entry = container.get_store_entry(FPackageId(packageId))
        assertNotNull(entry, "no store entry for package $packageId")
        return entry!!
    }

    private fun deserialize(chunk: ByteArray, entry: StoreEntry): FZenPackageHeader =
        FZenPackageHeader.deserialize(
            SeekableByteArrayInputStream(chunk), entry, EIoStoreTocVersion.PartitionSize,
            EIoContainerHeaderVersion.Initial, null)

    private fun payloadsOf(chunk: ByteArray, header: FZenPackageHeader): List<ByteArray> {
        val headerSize = header.summary.header_size.toInt()
        val cooked = header.summary.cooked_header_size.toLong()
        return header.export_map.map { e ->
            val start = (e.cooked_serial_offset.toLong() - cooked).toInt() + headerSize
            chunk.copyOfRange(start, (start + e.cooked_serial_size.toInt()))
        }
    }

    @Test
    fun graftWithNoChangedExportsKeepsTheCookerChunk() {
        val entry = storeEntry()
        val original = Files.readAllBytes(packageChunk)
        val grafted = graft_zen_chunk(original, emptyMap(), entry)

        assertEquals(entry, grafted.store_entry, "store entry unchanged when nothing is swapped")
        assertArrayEquals(original, grafted.chunk, "no-op graft must be byte-identical")
    }

    @Test
    fun graftSwapsOnlyTheChangedPayloadsAndRelaysOutTheRegion() {
        val entry = storeEntry()
        val original = Files.readAllBytes(packageChunk)
        val originalPayloads = payloadsOf(original, deserialize(original, entry))

        // a changed payload must stay the only edited one, and move the offsets after it
        val changed = originalPayloads.indices.minBy { i ->
            if (originalPayloads[i].isEmpty()) Int.MAX_VALUE else -originalPayloads[i].size
        }
        val grown = originalPayloads[changed] + ByteArray(8) { 0xAB.toByte() }
        val changedMap = mapOf(changed to grown)
        val grafted = graft_zen_chunk(original, changedMap, entry)

        val header = deserialize(grafted.chunk, grafted.store_entry)
        val payloads = payloadsOf(grafted.chunk, header)
        assertEquals(entry.export_bundles_size.toInt(), original.size, "fixture sanity")
        assertEquals(grafted.chunk.size.toULong(), grafted.store_entry.export_bundles_size,
            "store entry size tracks the new chunk")
        assertTrue(payloads[changed].contentEquals(grown),
            "changed export carries the patched payload")
        for (i in payloads.indices) {
            if (i == changed) continue
            assertTrue(payloads[i].contentEquals(originalPayloads[i]),
                "export $i payload must stay the cooker's bytes")
        }
        // cooked offsets are contiguous in cooked-image space, as the format requires
        val cooked = header.summary.cooked_header_size.toLong()
        var expected = cooked
        for (e in header.export_map.sortedBy { it.cooked_serial_offset }) {
            assertEquals(expected, e.cooked_serial_offset.toLong(), "contiguous cooked offsets")
            expected += e.cooked_serial_size.toLong()
        }
        // cooker metadata survives the graft untouched
        val before = deserialize(original, entry)
        assertEquals(before.import_map, header.import_map, "import map preserved")
        assertEquals(before.export_bundle_headers, header.export_bundle_headers, "bundle headers preserved")
        assertEquals(before.export_bundle_entries, header.export_bundle_entries, "bundle entries preserved")
        assertEquals(before.external_package_dependencies, header.external_package_dependencies,
            "dependency arcs preserved")
        assertEquals(before.name_map.copy_raw_names(), header.name_map.copy_raw_names(), "name map preserved")
        assertEquals(before.imported_public_export_hashes, header.imported_public_export_hashes,
            "public export hashes preserved")
    }

    @Test
    fun extractorReadsTheCookerChunkAndAConvertedChunk() {
        val entry = storeEntry()
        assumeTrue(Files.isRegularFile(convertedChunk), "converted chunk fixture missing at $convertedChunk")
        val original = Files.readAllBytes(packageChunk)
        val converted = Files.readAllBytes(convertedChunk)
        val originalPayloads = extract_zen_payloads(original)
        val convertedPayloads = extract_zen_payloads(converted)

        assertEquals(originalPayloads.size, convertedPayloads.size, "export count")
        val changed = originalPayloads.indices.filterNot {
            originalPayloads[it].contentEquals(convertedPayloads[it])
        }
        println("graft: conversion rewrote ${changed.size}/${originalPayloads.size} export payloads")

        // the conversion's payloads can drive a graft, and the result reads back consistently
        val grafted = graft_zen_chunk(
            original, changed.associateWith { convertedPayloads[it] }, entry)
        val header = deserialize(grafted.chunk, grafted.store_entry)
        assertEquals(originalPayloads.size, header.export_map.size)
        assertEquals(grafted.chunk.size.toULong(), grafted.store_entry.export_bundles_size)
    }

    @Test
    fun convertedChunkExportsAlignWithTheCookerChunk() {
        storeEntry()
        assumeTrue(Files.isRegularFile(pristineConversion),
            "pristine conversion fixture missing at $pristineConversion")
        val original = Files.readAllBytes(packageChunk)
        val pristine = Files.readAllBytes(pristineConversion)

        val names = extract_zen_export_names(original)
        val pristineNames = extract_zen_export_names(pristine)
        assertEquals(names.size, pristineNames.size, "export count")
        assertEquals(names, pristineNames, "export order must match for index-based payload swapping")

        val originalPayloads = extract_zen_payloads(original)
        val pristinePayloads = extract_zen_payloads(pristine)
        val differed = originalPayloads.indices.count {
            !originalPayloads[it].contentEquals(pristinePayloads[it])
        }
        println("graft: pristine conversion differs from the cooker chunk in " +
            "$differed/${originalPayloads.size} payloads (names align)")

        // the patched conversion from the size-changing build, when present: where does it differ?
        val patchedConversion = pristineConversion.resolveSibling("converted.chunk")
        assumeTrue(Files.isRegularFile(patchedConversion),
            "patched conversion fixture missing at $patchedConversion")
        val patched = Files.readAllBytes(patchedConversion)
        val patchedNames = extract_zen_export_names(patched)
        assertEquals(names, patchedNames, "patched conversion must keep the export order")
        val patchedPayloads = extract_zen_payloads(patched)
        assertEquals(originalPayloads.size, patchedPayloads.size, "patched export count")
        val patchedDiff = originalPayloads.indices.filter {
            !patchedPayloads[it].contentEquals(pristinePayloads[it])
        }
        println("graft: patched conversion differs from pristine in ${patchedDiff.size} payloads")
        for (i in patchedDiff.take(6)) {
            println("  export $i '${names[i]}': pristine ${pristinePayloads[i].size} -> patched ${patchedPayloads[i].size}")
        }
        val patchedVsCooker = originalPayloads.indices.filter {
            !patchedPayloads[it].contentEquals(originalPayloads[it])
        }
        println("graft: patched conversion differs from the cooker in ${patchedVsCooker.size} payloads " +
            "${patchedVsCooker.take(6)}")

        // the graft keeps the cooker's import map, so every index a donor payload embeds must
        // still resolve the same way: the maps must be identical
        val cookerMap = zen_import_map_bytes(original)
        val pristineMap = zen_import_map_bytes(pristine)
        val patchedMap = zen_import_map_bytes(patched)
        assertTrue(pristineMap.size >= cookerMap.size && patchedMap.size >= cookerMap.size,
            "conversions append their arc entries after the cooker's entries")
        assertArrayEquals(cookerMap, pristineMap.copyOfRange(0, cookerMap.size),
            "the cooker's entries keep their indices in the pristine conversion")
        assertArrayEquals(pristineMap, patchedMap,
            "the patch must not reorder the conversion's import map")
        println("graft: import map prefix identical (cooker ${cookerMap.size / 8} entries, " +
            "conversion ${pristineMap.size / 8}; extra entries are appended, so cooker-referenced " +
            "indices are unchanged)")
    }
}
