// Copyright (c) 2026 jpabscale — graft spike: pristine cooked chunk round-trip (game-gated).
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

/**
 * Foundation probe for a payload-preserving repack: read the game's own ExportBundleData chunk
 * with the ported Zen reader, re-serialize it with the ported writer, and require the bytes to
 * come back identical (header tables *and* the untouched payload region). Anything less means a
 * graft that keeps the cooker's metadata cannot be byte-faithful, so it must be fixed first.
 */
class ZenGraftSpikeTest {
    private val packageChunk: Path = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val headerChunk: Path = Path.of(
        System.getenv("SB_HEADER_CHUNK") ?: "/tmp/opencode/raw-keepoutfit/chunks/9bdb3fceb6b7da900000000a")
    private val packageId: ULong = (System.getenv("SB_PACKAGE_ID") ?: "9582239030029544658").toULong()

    @Test
    fun pristineChunkRoundTripsByteIdentically() {
        assumeTrue(Files.isRegularFile(packageChunk), "package chunk fixture missing at $packageChunk")
        assumeTrue(Files.isRegularFile(headerChunk), "container header chunk fixture missing at $headerChunk")
        val bytes = Files.readAllBytes(packageChunk)
        val container = FIoContainerHeader.deserialize(
            ByteArrayInputStream(Files.readAllBytes(headerChunk)), null)
        val storeEntry = container.get_store_entry(FPackageId(packageId))
        assertNotNull(storeEntry, "no store entry for package $packageId")

        val header = FZenPackageHeader.deserialize(
            SeekableByteArrayInputStream(bytes), storeEntry, EIoStoreTocVersion.PartitionSize,
            EIoContainerHeaderVersion.Initial, null)

        val out = SeekableByteArrayOutputStream()
        header.serialize(out, storeEntry!!, EIoContainerHeaderVersion.Initial)
        val headerBytes = out.toByteArray()

        // Layout facts the graft relies on: payloads follow the header, each export at
        // cooked_serial_offset with cooked_serial_size inside the payload region.
        // cooked_serial_offset counts from the start of the cooked package image (its header is
        // summary.cooked_header_size bytes); the chunk stores the payload region after the Zen
        // header, so the in-chunk position is the cooked offset minus that header.
        val region = bytes.size - headerBytes.size
        val cookedHeader = header.summary.cooked_header_size.toLong()
        val maxEnd = header.export_map.maxOfOrNull {
            (it.cooked_serial_offset.toLong() - cookedHeader + it.cooked_serial_size.toLong())
        } ?: 0L
        val minStart = header.export_map.minOfOrNull { it.cooked_serial_offset.toLong() - cookedHeader } ?: 0L
        println(
            "graft spike: chunk=${bytes.size} header=${headerBytes.size} " +
                "(summary.header_size=${header.summary.header_size}) payloadRegion=$region " +
                "exports=${header.export_map.size} bundles=${header.export_bundle_headers.size} " +
                "bundleEntries=${header.export_bundle_entries.size} cookedHeader=$cookedHeader " +
                "payloadStartsAt=$minStart maxExportEnd=$maxEnd " +
                "importMap=${header.import_map.size} arcs=${header.external_package_dependencies.size} " +
                "storeEntry.export_bundles_size=${storeEntry.export_bundles_size}")

        assertEquals(header.summary.header_size.toLong(), headerBytes.size.toLong(),
            "serialized header size vs summary.header_size")
        assertTrue(maxEnd <= region.toLong(), "export payloads exceed the payload region")
        val whole = headerBytes + bytes.copyOfRange(headerBytes.size, bytes.size)
        assertEquals(bytes.size, whole.size, "round-tripped chunk size")
        assertArrayEquals(bytes, whole, "pristine chunk round-trip must be byte-identical")
    }
}
