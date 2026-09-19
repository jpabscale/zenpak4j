// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayOutputStream

/**
 * Payload-preserving repack: swap the export payloads of a cooked game package for the ones
 * serialized from a patched asset, keeping the cooker's own Zen header tables (name map, import
 * map, dependency arcs, bundle layout) — so a mod ships exactly the edits it made instead of a
 * re-derived package whose arc-only imports would not resolve.
 *
 * [changed_payloads] are the per-export payload bytes to swap in (the conversion of the patched
 * asset yields them; the caller keeps everything else the cooker's). The export payload region must
 * be contiguous and trailing-free (true for shipped cooked packages; anything else is refused
 * instead of guessed).
 */
data class GraftedZenPackage(val chunk: ByteArray, val store_entry: StoreEntry)

/** Payload position in the cooked-image space ([start], [size] bytes). */
private data class PayloadPosition(val start: Long, val size: Long)

/** Serialized size of one `FExportMapEntry` (8+8+8 name + 4×8 indices + 8 hash + 4 flags + 1 + 3). */
private const val FEXPORT_MAP_ENTRY_SIZE = 72

fun graft_zen_chunk(
    original: ByteArray,
    changed_payloads: Map<Int, ByteArray>,
    store_entry: StoreEntry,
    toc_version: EIoStoreTocVersion = EIoStoreTocVersion.PartitionSize,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): GraftedZenPackage {
    val header = FZenPackageHeader.deserialize(
        SeekableByteArrayInputStream(original), store_entry, toc_version, container_header_version, null)
    require(changed_payloads.keys.all { it in header.export_map.indices }) {
        "changed export index out of range: ${changed_payloads.keys} " +
            "(export count ${header.export_map.size})"
    }

    val header_size = header.summary.header_size.toInt()
    val region_size = original.size - header_size
    val cooked_header = header.summary.cooked_header_size.toLong()

    // Payload slots in cooked-image space: contiguous from cooked_header to the region end.
    data class Slot(val index: Int, val start: Long, val size: Long)
    val slots = header.export_map.mapIndexed { i, e ->
        Slot(i, e.cooked_serial_offset.toLong() - cooked_header, e.cooked_serial_size.toLong())
    }.sortedBy { it.start }
    var cursor = 0L
    for (slot in slots) {
        require(slot.start == cursor) {
            "cooked payload region is not contiguous: export ${slot.index} starts at ${slot.start}, " +
                "expected $cursor"
        }
        require(slot.size >= 0 && slot.start + slot.size <= region_size) {
            "export ${slot.index} payload [${slot.start}, ${slot.start + slot.size}) exceeds the " +
                "region ($region_size bytes)"
        }
        cursor = slot.start + slot.size
    }
    require(cursor == region_size.toLong()) {
        "cooked payload region has ${region_size - cursor} trailing byte(s); refusing to guess"
    }

    val region = ByteArrayOutputStream(region_size)
    var position = 0L
    for (slot in slots) {
        val payload = if (slot.index in changed_payloads) {
            changed_payloads.getValue(slot.index)
        } else {
            original.copyOfRange(header_size + slot.start.toInt(), header_size + (slot.start + slot.size).toInt())
        }
        header.export_map[slot.index].cooked_serial_offset = (cooked_header + position).toULong()
        header.export_map[slot.index].cooked_serial_size = payload.size.toULong()
        region.write(payload)
        position += payload.size
    }

    val updated_store_entry = store_entry.copy(
        export_bundles_size = (header_size + position).toULong())
    val header_bytes = SeekableByteArrayOutputStream()
    header.serialize(header_bytes, updated_store_entry, container_header_version)
    val header_region = header_bytes.toByteArray()
    require(header_region.size == header_size) {
        "serialized header size ${header_region.size} != original $header_size; the cooker's tables " +
            "must keep their layout"
    }
    val out = ByteArrayOutputStream(header_region.size + region.size())
    out.write(header_region)
    region.writeTo(out)
    return GraftedZenPackage(out.toByteArray(), updated_store_entry)
}

/**
 * Per-export payloads of a Zen chunk, read without the reader's bundle-consistency checks — a
 * to-zen conversion lays its export bundles out differently from a cooker package, so the strict
 * [FZenPackageHeader.deserialize] rejects it while its export map and payload region are perfectly
 * usable as the donor side of a graft.
 */
fun extract_zen_payloads(
    chunk: ByteArray,
    toc_version: EIoStoreTocVersion = EIoStoreTocVersion.PartitionSize,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): List<ByteArray> {
    val seekable = SeekableByteArrayInputStream(chunk)
    val summary = FZenPackageSummary.deserialize(seekable, container_header_version)
    if (summary.has_versioning_info != 0u) seekable.de(FZenPackageVersioningInfo)
    require(container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        "extract_zen_payloads supports Initial containers only (got $container_header_version, " +
            "toc $toc_version)"
    }
    // export map: absolute, bounded by the next table (Initial keeps the cell maps at the
    // bundle-entries offset)
    val count = (summary.export_bundle_entries_offset - summary.export_map_offset) / FEXPORT_MAP_ENTRY_SIZE
    require(count >= 0) { "negative export map count for zen chunk" }
    seekable.seek(summary.export_map_offset.toLong())
    val export_map = read_array(count, seekable) { s -> s.de(FExportMapEntry) }

    val header_size = summary.header_size.toInt()
    val cooked_header = summary.cooked_header_size.toLong()
    return export_map.map { e ->
        val start = (e.cooked_serial_offset.toLong() - cooked_header).toInt() + header_size
        val end = start + e.cooked_serial_size.toInt()
        require(start >= header_size && end <= chunk.size) {
            "export payload [$start, $end) outside chunk (${chunk.size} bytes, header $header_size)"
        }
        chunk.copyOfRange(start, end)
    }
}

/**
 * Export object names of a Zen chunk, resolved through its name map (same tolerant parsing as
 * [extract_zen_payloads]). Index-by-index equality with another chunk is what makes swapping
 * payloads by export index safe.
 */
fun extract_zen_export_names(
    chunk: ByteArray,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): List<String> {
    require(container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        "extract_zen_export_names supports Initial containers only (got $container_header_version)"
    }
    val seekable = SeekableByteArrayInputStream(chunk)
    val summary = FZenPackageSummary.deserialize(seekable, container_header_version)
    if (summary.has_versioning_info != 0u) seekable.de(FZenPackageVersioningInfo)
    seekable.seek(summary.name_map_names_offset.toLong())
    val names_buffer = ByteArray(summary.name_map_names_size)
    if (summary.name_map_names_size > 0) seekable.read_exact(names_buffer)
    val name_map = FNameMap.create_from_names(EMappedNameType.Package, read_name_batch_parts(names_buffer))
    val count = (summary.export_bundle_entries_offset - summary.export_map_offset) / FEXPORT_MAP_ENTRY_SIZE
    keep(
        seekable.seek(summary.export_map_offset.toLong()),
        "seek to the export map",
    )
    val export_map = read_array(count, seekable) { s -> s.de(FExportMapEntry) }
    return export_map.map { name_map.get(it.object_name) }
}

/** Keeps [value] (statement shape for the tolerant reader steps above). */
private fun <T> keep(value: T, @Suppress("UNUSED_PARAMETER") what: String): T = value

/** Tolerant summary read (consumes the versioning block when present). */
internal fun zen_read_summary(
    chunk: ByteArray,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): FZenPackageSummary {
    val seekable = SeekableByteArrayInputStream(chunk)
    val summary = FZenPackageSummary.deserialize(seekable, container_header_version)
    if (summary.has_versioning_info != 0u) seekable.de(FZenPackageVersioningInfo)
    return summary
}

/**
 * The cooked name map of a Zen chunk (tolerant read, like [extract_zen_export_names]). A payload
 * swap between two chunks is only sound when the shipped chunk's map starts with the donor's: the
 * donor's payloads reference names by index, and every such index must resolve to the same string.
 */
fun zen_name_map_names(
    chunk: ByteArray,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): List<String> {
    require(container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        "zen_name_map_names supports Initial containers only (got $container_header_version)"
    }
    val seekable = SeekableByteArrayInputStream(chunk)
    val summary = FZenPackageSummary.deserialize(seekable, container_header_version)
    if (summary.has_versioning_info != 0u) seekable.de(FZenPackageVersioningInfo)
    seekable.seek(summary.name_map_names_offset.toLong())
    val namesBuffer = ByteArray(summary.name_map_names_size)
    if (summary.name_map_names_size > 0) seekable.read_exact(namesBuffer)
    return read_name_batch_parts(namesBuffer)
}

/** Raw import-map bytes of a Zen chunk (dense `FPackageObjectIndex` array; comparable by content). */
fun zen_import_map_bytes(
    chunk: ByteArray,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): ByteArray {
    require(container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        "zen_import_map_bytes supports Initial containers only (got $container_header_version)"
    }
    val seekable = SeekableByteArrayInputStream(chunk)
    val summary = FZenPackageSummary.deserialize(seekable, container_header_version)
    val count = (summary.export_map_offset - summary.import_map_offset) / 8
    require(count >= 0) { "negative import map count" }
    seekable.seek(summary.import_map_offset.toLong())
    val out = ByteArray(count * 8)
    if (out.isNotEmpty()) seekable.read_exact(out)
    return out
}
