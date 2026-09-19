// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

/** Sanity bound for table counts (a corrupt file must not allocate). */
internal const val MAX_REFERENCED_PACKAGES = 1_000_000

/**
 * A repaired chunk plus the repair breakdown: [paired] fields took the cooker's own value for the
 * same dependency and arc position, [heuristic] fields had no counterpart (the conversion's bundle
 * layout is finer) and used the cooker's arcs cyclically — valid indices, exactness unverified.
 * No `Pair`: this module has its own.
 */
data class ArcRepairedChunk(val chunk: ByteArray, val paired: Int, val heuristic: Int) {
    val edits: Int get() = paired + heuristic
}

/** One legacy arc's `int32` fields: their file offsets and current values. */
private data class LegacyArcField(
    val fromOffset: Int,
    val toOffset: Int,
    val from: Int,
    val to: Int,
)

/** One dependency's legacy arc block. */
private class LegacyArcBlock(val packageId: ULong, val arcs: List<LegacyArcField>)

/**
 * Repair the legacy dependency arcs of a converted package from a template (the game's own chunk):
 * a to-zen conversion that cannot resolve a dependency writes `from_export_bundle_index = -1`
 * (see `zen_asset_conversion.kt`), which the store cannot follow — the measured freeze signature.
 * The cooker's arcs for the same dependency package carry the values the store expects, and arcs
 * are fixed-width `int32` pairs, so the repair is a same-size byte patch.
 *
 * Pairing is by package id; arcs are matched positionally and only values that are negative in
 * [chunk] are replaced. A conversion can emit *more* arcs for a dependency than the cooker holds
 * (its bundle layout is finer): for those extras the cooker's arcs repeat cyclically, which keeps
 * duplicate edges pointing at the same target as the cooker's own first edge — a heuristic the
 * in-game run has to confirm. Returns the repaired chunk and the number of fields written.
 */
fun repair_legacy_dependency_arcs(chunk: ByteArray, template: ByteArray): ArcRepairedChunk {
    val target = legacy_arc_blocks(chunk)
    if (target.isEmpty()) return ArcRepairedChunk(chunk, 0, 0)
    val source = legacy_arc_blocks(template).associateBy { it.packageId }
    val out = chunk.copyOf()
    var paired = 0
    var heuristic = 0
    for (block in target) {
        val donor = source[block.packageId] ?: continue
        // A template block with no arcs has nothing to pair (the cooker only serializes
        // non-empty blocks, but a hand-built template must not divide by zero here).
        if (donor.arcs.isEmpty()) continue
        for (i in block.arcs.indices) {
            val exact = i < donor.arcs.size
            val d = i % donor.arcs.size
            val arc = block.arcs[i]
            if (arc.from < 0 && donor.arcs[d].from >= 0) {
                write_i32(out, arc.fromOffset, donor.arcs[d].from)
                if (exact) paired++ else heuristic++
            }
            if (arc.to < 0 && donor.arcs[d].to >= 0) {
                write_i32(out, arc.toOffset, donor.arcs[d].to)
                if (exact) paired++ else heuristic++
            }
        }
    }
    return ArcRepairedChunk(out, paired, heuristic)
}

/** Parse the graph-data dependency blocks of an Initial-layout package (tolerant). */
private fun legacy_arc_blocks(chunk: ByteArray): List<LegacyArcBlock> {
    val summary = zen_read_summary(chunk)
    val newerLayout = summary.dependency_bundle_headers_offset > 0 &&
        summary.dependency_bundle_entries_offset > 0
    if (newerLayout || summary.graph_data_offset <= 0) return emptyList()
    val seekable = SeekableByteArrayInputStream(chunk)
    seekable.seek(summary.graph_data_offset.toLong())
    val count = seekable.read_i32_le()
    require(count in 0..MAX_REFERENCED_PACKAGES) { "unreasonable referenced package count $count" }
    val out = mutableListOf<LegacyArcBlock>()
    for (i in 0 until count) {
        val packageId = seekable.de(FPackageId).value
        val arcCount = seekable.read_i32_le()
        require(arcCount in 0..MAX_REFERENCED_PACKAGES) { "unreasonable arc count $arcCount" }
        val arcs = mutableListOf<LegacyArcField>()
        for (j in 0 until arcCount) {
            val fromOffset = seekable.position().toInt()
            val from = seekable.read_i32_le()
            val toOffset = seekable.position().toInt()
            val to = seekable.read_i32_le()
            arcs.add(LegacyArcField(fromOffset, toOffset, from, to))
        }
        out.add(LegacyArcBlock(packageId, arcs))
    }
    return out
}

private fun write_i32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = (value and 0xFF).toByte()
    bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
    bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
}
