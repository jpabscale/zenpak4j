// Copyright (c) 2026 jpabscale — end-to-end graft service test (game-gated fixture).
package com.github.jpabscale.zenpak4j

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ZenPakGraftServiceTest {
    private val original = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val donor = Path.of(
        System.getenv("SB_DONOR_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-mod/CH_P_EVE_01_Blueprint.uasset")
    private val headerChunk = Path.of(
        System.getenv("SB_HEADER_CHUNK") ?: "/tmp/opencode/raw-keepoutfit/chunks/9bdb3fceb6b7da900000000a")
    private val packageId = (System.getenv("SB_PACKAGE_ID") ?: "9582239030029544658").toULong()

    @Test
    fun graftServiceWritesTheChunkAndAnUpdatedHeaderEntry() {
        assumeTrue(Files.isRegularFile(original), "chunk fixture missing at $original")
        assumeTrue(Files.isRegularFile(donor), "donor fixture missing at $donor")
        assumeTrue(Files.isRegularFile(headerChunk), "header fixture missing at $headerChunk")
        val dir = Files.createTempDirectory("graft-service")
        try {
            val outChunk = dir.resolve("chunk.bin")
            val outHeader = dir.resolve("header.bin")
            val changed = ZenPakService.retoc_graft_package_chunk(
                originalChunk = original, donorChunk = donor, headerChunk = headerChunk,
                packageId = packageId, outputChunk = outChunk, outputHeaderChunk = outHeader)

            assertTrue(changed.isNotEmpty(), "the donor must differ somewhere")
            val graftedSize = Files.size(outChunk).toULong()
            val entry = com.github.jpabscale.zenpak4j.retoc.FIoContainerHeader
                .deserialize(ByteArrayInputStream(Files.readAllBytes(outHeader)), null)
                .get_store_entry(com.github.jpabscale.zenpak4j.retoc.FPackageId(packageId))!!
            assertEquals(graftedSize, entry.export_bundles_size, "entry describes the grafted chunk")
            assertTrue(changed.size < 10, "only the patched payloads are swapped (got $changed)")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
