// Copyright (c) 2026 jpabscale — store-entry patch tests (game-gated fixture).
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ContainerHeaderPatchTest {
    private val headerChunk: Path = Path.of(
        System.getenv("SB_HEADER_CHUNK") ?: "/tmp/opencode/raw-keepoutfit/chunks/9bdb3fceb6b7da900000000a")
    private val packageId: ULong = (System.getenv("SB_PACKAGE_ID") ?: "9582239030029544658").toULong()

    @Test
    fun patchingTheSameEntryIsLosslessAndAModifiedEntrySticks() {
        assumeTrue(Files.isRegularFile(headerChunk), "container header chunk fixture missing at $headerChunk")
        val data = Files.readAllBytes(headerChunk)
        val entry = FIoContainerHeader.deserialize(ByteArrayInputStream(data), null)
            .get_store_entry(FPackageId(packageId))
        assertNotNull(entry, "no store entry for package $packageId")

        val unchanged = patch_container_header_store_entry(data, FPackageId(packageId), entry!!)
        // The container header's own re-serialization is semantically lossless but not byte-exact
        // (the ported writer normalizes fields the reader inferred), so compare parsed headers.
        val reread = FIoContainerHeader.deserialize(ByteArrayInputStream(unchanged), null)
        val original = FIoContainerHeader.deserialize(ByteArrayInputStream(data), null)
        assertEquals(original, reread, "re-writing the same entry must be lossless")

        val updated = entry.copy(export_bundles_size = entry.export_bundles_size + 1234u)
        val patched = patch_container_header_store_entry(data, FPackageId(packageId), updated)
        val header = FIoContainerHeader.deserialize(ByteArrayInputStream(patched), null)
        val roundTripped = header.get_store_entry(FPackageId(packageId))
        assertEquals(updated, roundTripped, "patched store entry survives re-serialization")
        assertEquals(
            FIoContainerHeader.deserialize(ByteArrayInputStream(data), null).package_ids().size,
            header.package_ids().size, "package count unchanged")
    }
}
