// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

/**
 * Build-time guard for converted Zen packages.
 *
 * A to-zen conversion that cannot resolve a dependency emits **legacy dependency arcs with a `-1`
 * bundle index** (see `zen_asset_conversion.kt`, the `fixup_legacy_external_arcs` fallback), which
 * the game's package store cannot follow. This is the measured freeze signature of a rebuilt game
 * Blueprint: the cooker's arcs point at real bundle indices (480 import-map entries, 2 bundles)
 * while the conversion writes `-1` arcs (495 entries) and the game hangs on load. The payloads can
 * still be byte-faithful, so reading the package back is not enough — only the tables reveal it.
 *
 * Cooker packages and payload-preserving grafts keep valid arcs and report nothing. Packages using
 * the newer dependency-bundle layout resolve arcs through the entries themselves and are skipped.
 */
fun zen_package_warnings(chunk: ByteArray): List<String> {
    val seekable = SeekableByteArrayInputStream(chunk)
    val summary = FZenPackageSummary.deserialize(seekable, EIoContainerHeaderVersion.Initial)
    if (summary.has_versioning_info != 0u) seekable.de(FZenPackageVersioningInfo)
    val newerLayout = summary.dependency_bundle_headers_offset > 0 &&
        summary.dependency_bundle_entries_offset > 0
    if (newerLayout || summary.graph_data_offset <= 0) return emptyList()

    val referenced_package_count = seekable.let {
        it.seek(summary.graph_data_offset.toLong())
        it.read_i32_le()
    }
    require(referenced_package_count in 0..MAX_REFERENCED_PACKAGES) {
        "unreasonable referenced package count $referenced_package_count"
    }
    val warnings = mutableListOf<String>()
    for (i in 0 until referenced_package_count) {
        val package_id = seekable.de(FPackageId)
        val arcs = seekable.read_vec { s -> s.de(FInternalDependencyArc) }
        val dangling = arcs.count { it.from_export_bundle_index < 0 || it.to_export_bundle_index < 0 }
        if (dangling > 0) {
            warnings.add(
                "package ${package_id.value}: $dangling/${arcs.size} legacy (-1) dependency arc(s)")
        }
    }
    return warnings
}

/**
 * Structural findings for a Zen package chunk: header/region sanity, payload layout coverage, and
 * the `-1` legacy-arc signature ([zen_package_warnings]). Bundle/dependency table shape is reported
 * when the strict reader rejects the chunk (a rebuilt package's own layout is not the cooker's).
 * Empty when the chunk is structurally sound. Used by automod's post-pack guard for grafted chunks
 * and rebuilt game packages.
 */
fun zen_package_validate(
    chunk: ByteArray,
    toc_version: EIoStoreTocVersion = EIoStoreTocVersion.PartitionSize,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
): List<String> {
    val findings = mutableListOf<String>()
    val summary = try {
        zen_read_summary(chunk, container_header_version)
    } catch (e: Exception) {
        return listOf("unreadable Zen summary: ${e.message}")
    }
    val headerSize = summary.header_size.toLong()
    if (headerSize <= 0L || headerSize > chunk.size.toLong()) {
        findings.add("header_size $headerSize outside the chunk (${chunk.size} bytes)")
    }
    val payloads = try {
        extract_zen_payloads(chunk, toc_version, container_header_version)
    } catch (e: Exception) {
        findings.add("export payloads are not addressable: ${e.message}")
        emptyList()
    }
    if (payloads.isNotEmpty()) {
        val covered = headerSize + payloads.sumOf { it.size.toLong() }
        if (covered != chunk.size.toLong()) {
            findings.add(
                "payload region covers $covered bytes of the ${chunk.size}-byte chunk " +
                    "(${chunk.size.toLong() - covered} trailing/unassigned)")
        }
    }
    findings.addAll(zen_package_warnings(chunk))
    try {
        FZenPackageHeader.deserialize(
            SeekableByteArrayInputStream(chunk), null, toc_version, container_header_version, null)
    } catch (e: Exception) {
        // Initial packages cannot be read at all without their store entry; that is not a finding,
        // while a conversion's own (non-cooker) table layout still is.
        if (e.message?.contains("store entry") != true) {
            findings.add("bundle/dependency tables not self-consistent: ${e.message}")
        }
    }
    return findings
}
