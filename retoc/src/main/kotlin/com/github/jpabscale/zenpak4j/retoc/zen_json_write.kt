// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Empty payload bytes are valid (zero-length exports exist in cooked packages). */
private fun b64_decode(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

/**
 * Rebuild a Zen chunk from its [zen_package_to_json_node] dump: all header tables plus the payload
 * bytes (`bytes_b64`, so dump in write mode) are written back through the ported serializer.
 * [store_entry] carries the imported packages for Initial containers; when absent, a synthetic
 * entry is derived from the dump's own imported packages (the header tables themselves live in the
 * dump, so the round trip stays faithful — see the byte-identity test).
 *
 * Layout: payloads are appended in cooked-offset order (the cooker's own region order), which the
 * dump preserves.
 */
/** A written chunk plus the store entry the write produced (counters, imports, bundles size). */
data class WrittenZenPackage(val chunk: ByteArray, val store_entry: StoreEntry)

fun zen_package_from_json_node(node: ObjectNode, store_entry: StoreEntry? = null): ByteArray =
    zen_package_write(node, store_entry).chunk

/**
 * Write [node] and return the chunk together with the store entry it was written against — the
 * entry's counters/imports are updated by the ported serializer and `export_bundles_size` is set to
 * the written chunk's size, so a table edit that resizes the package can patch its container
 * header's store entry with the result ([patch_container_header_store_entry]).
 */
fun zen_package_write(node: ObjectNode, store_entry: StoreEntry? = null): WrittenZenPackage {
    val summary = summary_from_json(node.path("summary"))
    val versioning = versioning_from_json(node.path("versioning_info"))
    val name_map = FNameMap.create_from_names(
        EMappedNameType.valueOf(node.path("name_map").path("kind").asText()),
        node.path("name_map").path("raw_names_b64").map { String(b64_decode(it.asText()), Charsets.UTF_8) })
    val import_map = node.path("import_map").map { object_index_from_json(it) }
    val export_map = node.path("export_map").map { export_map_entry_from_json(it) }
    val export_bundle_headers = node.path("export_bundle_headers").map { bundle_header_from_json(it) }
    val export_bundle_entries = node.path("export_bundle_entries").map { bundle_entry_from_json(it) }
    val dependency_bundle_headers =
        node.path("dependency_bundle_headers").map { dependency_header_from_json(it) }
    val dependency_bundle_entries =
        node.path("dependency_bundle_entries").map { dependency_entry_from_json(it) }
    val internal_arcs = node.path("internal_dependency_arcs").map { internal_arc_from_json(it) }
    val external_deps = node.path("external_package_dependencies").map { external_dep_from_json(it) }
    val cell_import_map = node.path("cell_import_map").map { object_index_from_json(it) }
    val cell_export_map = node.path("cell_export_map").map { cell_export_from_json(it) }
    val imported_packages = node.path("imported_packages").map { FPackageId(it.asText().toULong()) }
    val imported_public_export_hashes = node.path("imported_public_export_hashes").map { it.asText().toULong() }
    val shader_hashes = node.path("shader_map_hashes").map { FSHAHash(hex_to_bytes(it.asText(), 20)) }
    val bulk_data = node.path("bulk_data").map { bulk_data_from_json(it) }
    val payloads = node.path("payloads").map {
        val bytes = it.get("bytes_b64")
            ?: error("payload ${it.path("index").asInt()} has no bytes_b64; dump with payloads to write")
        b64_decode(bytes.asText())
    }
    // the region is physically ordered by cooked offset, not by export index
    val region = payloads_indices_by_offset(node).map { payloads[it] }

    val header = FZenPackageHeader(
        summary = summary,
        versioning_info = versioning,
        name_map = name_map,
        bulk_data = bulk_data.toMutableList(),
        imported_public_export_hashes = imported_public_export_hashes.toMutableList(),
        import_map = import_map.toMutableList(),
        export_map = export_map.toMutableList(),
        export_bundle_headers = export_bundle_headers.toMutableList(),
        export_bundle_entries = export_bundle_entries.toMutableList(),
        dependency_bundle_headers = dependency_bundle_headers.toMutableList(),
        dependency_bundle_entries = dependency_bundle_entries.toMutableList(),
        imported_package_names = node.path("imported_package_names").map { it.asText() }.toMutableList(),
        imported_packages = imported_packages.toMutableList(),
        shader_map_hashes = shader_hashes.toMutableList(),
        is_unversioned = node.path("summary").path("has_versioning_info").asLong() == 0L,
        internal_dependency_arcs = internal_arcs.toMutableList(),
        external_package_dependencies = external_deps.toMutableList(),
        container_header_version = EIoContainerHeaderVersion.valueOf(
            node.path("container_header_version").asText()),
        cell_import_map = cell_import_map.toMutableList(),
        cell_export_map = cell_export_map.toMutableList(),
    )
    val effective_store_entry = store_entry ?: StoreEntry(
        export_bundles_size = 0uL,
        export_count = export_map.size,
        export_bundle_count = export_bundle_headers.size,
        imported_packages = imported_packages,
        shader_map_hashes = shader_hashes,
    )
    val out = SeekableByteArrayOutputStream()
    header.serialize(out, effective_store_entry, header.container_header_version)
    for (p in region) out.write(p)
    effective_store_entry.export_bundles_size = out.size().toULong()
    return WrittenZenPackage(out.toByteArray(), effective_store_entry)
}

/** Export indices ordered by their cooked offset (the region's physical order). */
private fun payloads_indices_by_offset(node: ObjectNode): List<Int> =
    node.path("export_map").mapIndexed { i, e -> i to e.path("cooked_serial_offset").asText().toLong() }
        .sortedBy { it.second }.map { it.first }

// ---------------------------------------------------------------------------
// node -> structure
// ---------------------------------------------------------------------------

private fun mapped_name_from_json(node: JsonNode): FMappedName =
    FMappedName(node.path("index_and_type").asLong().toUInt(), node.path("number").asLong().toUInt())

private fun object_index_from_json(node: JsonNode): FPackageObjectIndex =
    FPackageObjectIndex(node.path("type_and_id").asText().toULong())

private fun summary_from_json(node: JsonNode): FZenPackageSummary = FZenPackageSummary(
    has_versioning_info = node.path("has_versioning_info").asLong().toUInt(),
    header_size = node.path("header_size").asLong().toUInt(),
    name = mapped_name_from_json(node.path("name")),
    source_name = mapped_name_from_json(node.path("source_name")),
    package_flags = node.path("package_flags").asLong().toUInt(),
    cooked_header_size = node.path("cooked_header_size").asLong().toUInt(),
    imported_public_export_hashes_offset = node.path("imported_public_export_hashes_offset").asInt(),
    import_map_offset = node.path("import_map_offset").asInt(),
    export_map_offset = node.path("export_map_offset").asInt(),
    export_bundle_entries_offset = node.path("export_bundle_entries_offset").asInt(),
    graph_data_offset = node.path("graph_data_offset").asInt(),
    dependency_bundle_headers_offset = node.path("dependency_bundle_headers_offset").asInt(),
    dependency_bundle_entries_offset = node.path("dependency_bundle_entries_offset").asInt(),
    imported_package_names_offset = node.path("imported_package_names_offset").asInt(),
    name_map_names_offset = node.path("name_map_names_offset").asInt(),
    name_map_names_size = node.path("name_map_names_size").asInt(),
    name_map_hashes_offset = node.path("name_map_hashes_offset").asInt(),
    name_map_hashes_size = node.path("name_map_hashes_size").asInt(),
    graph_data_size = node.path("graph_data_size").asInt(),
)

private fun versioning_from_json(node: JsonNode): FZenPackageVersioningInfo = FZenPackageVersioningInfo(
    zen_version = EZenPackageVersion.valueOf(node.path("zen_version").asText()),
    licensee_version = node.path("licensee_version").asInt(),
    package_file_version = FPackageFileVersion(
        file_version_ue4 = node.path("package_file_version").path("file_version_ue4").asInt(),
        file_version_ue5 = node.path("package_file_version").path("file_version_ue5").asInt(),
    ),
    custom_versions = node.path("custom_versions").map { cv ->
        val parts = cv.path("key").asText().split(":")
        FCustomVersion(
            key = FGuid(
                parts.getOrElse(0) { "0" }.toUInt(),
                parts.getOrElse(1) { "0" }.toUInt(),
                parts.getOrElse(2) { "0" }.toUInt(),
                parts.getOrElse(3) { "0" }.toUInt(),
            ),
            version = cv.path("version").asInt(),
        )
    }.toMutableList(),
)

private fun export_map_entry_from_json(node: JsonNode): FExportMapEntry = FExportMapEntry(
    cooked_serial_offset = node.path("cooked_serial_offset").asText().toULong(),
    cooked_serial_size = node.path("cooked_serial_size").asText().toULong(),
    object_name = mapped_name_from_json(node.path("object_name")),
    outer_index = object_index_from_json(node.path("outer_index")),
    class_index = object_index_from_json(node.path("class_index")),
    super_index = object_index_from_json(node.path("super_index")),
    template_index = object_index_from_json(node.path("template_index")),
    public_export_hash = node.path("public_export_hash").asText().toULong(),
    object_flags = node.path("object_flags").asLong().toUInt(),
    filter_flags = EExportFilterFlags.valueOf(node.path("filter_flags").asText()),
    padding = b64_decode(node.path("padding").asText()),
)

private fun bundle_header_from_json(node: JsonNode): FExportBundleHeader = FExportBundleHeader(
    serial_offset = node.path("serial_offset").asText().toULong(),
    first_entry_index = node.path("first_entry_index").asLong().toUInt(),
    entry_count = node.path("entry_count").asLong().toUInt(),
)

private fun bundle_entry_from_json(node: JsonNode): FExportBundleEntry = FExportBundleEntry(
    local_export_index = node.path("local_export_index").asLong().toUInt(),
    command_type = EExportCommandType.valueOf(node.path("command_type").asText()),
)

private fun dependency_header_from_json(node: JsonNode): FDependencyBundleHeader = FDependencyBundleHeader(
    first_entry_index = node.path("first_entry_index").asInt(),
    create_before_create_dependencies =
        node.path("create_before_create_dependencies").asLong().toUInt(),
    serialize_before_create_dependencies =
        node.path("serialize_before_create_dependencies").asLong().toUInt(),
    create_before_serialize_dependencies =
        node.path("create_before_serialize_dependencies").asLong().toUInt(),
    serialize_before_serialize_dependencies =
        node.path("serialize_before_serialize_dependencies").asLong().toUInt(),
)

private fun dependency_entry_from_json(node: JsonNode): FDependencyBundleEntry = FDependencyBundleEntry(
    local_import_or_export_index = FPackageIndex(node.path("local_import_or_export_index").asInt()))

private fun internal_arc_from_json(node: JsonNode): FInternalDependencyArc = FInternalDependencyArc(
    from_export_bundle_index = node.path("from_export_bundle_index").asInt(),
    to_export_bundle_index = node.path("to_export_bundle_index").asInt(),
)

private fun external_arc_from_json(node: JsonNode): FExternalDependencyArc = FExternalDependencyArc(
    from_import_index = node.path("from_import_index").asInt(),
    from_command_type = EExportCommandType.valueOf(node.path("from_command_type").asText()),
    to_export_bundle_index = node.path("to_export_bundle_index").asInt(),
)

private fun external_dep_from_json(node: JsonNode): ExternalPackageDependency = ExternalPackageDependency(
    from_package_id = FPackageId(node.path("from_package_id").asText().toULong()),
    external_dependency_arcs = node.path("external_dependency_arcs").map { external_arc_from_json(it) }
        .toMutableList(),
    legacy_dependency_arcs = node.path("legacy_dependency_arcs").map { internal_arc_from_json(it) }
        .toMutableList(),
)

private fun cell_export_from_json(node: JsonNode): FCellExportMapEntry = FCellExportMapEntry(
    cooked_serial_offset = node.path("cooked_serial_offset").asText().toULong(),
    cooked_serial_layout_size = node.path("cooked_serial_layout_size").asText().toULong(),
    cooked_serial_size = node.path("cooked_serial_size").asText().toULong(),
    cpp_class_info = mapped_name_from_json(node.path("cpp_class_info")),
)

private fun bulk_data_from_json(node: JsonNode): FBulkDataMapEntry = FBulkDataMapEntry(
    serial_offset = node.path("serial_offset").asLong(),
    duplicate_serial_offset = node.path("duplicate_serial_offset").asLong(),
    serial_size = node.path("serial_size").asLong(),
    flags = node.path("flags").asLong().toUInt(),
    cooked_index = node.path("cooked_index").asInt().toUByte(),
    pad = b64_decode(node.path("pad").asText()),
)

private fun hex_to_bytes(hex: String, expect: Int): ByteArray {
    require(hex.length == expect * 2) { "expected ${expect * 2} hex chars, got ${hex.length}" }
    return ByteArray(expect) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Apply a Zen **table** patch to a dump in place. The vocabulary is explicit and index-addressed;
 * every referenced index must exist and nothing else is touched (payload bytes are never patched
 * here — content edits stay in the legacy path).
 *
 * ```
 * {
 *   "export_map":                 [ {"index": 1, "object_flags": 0, "filter_flags": "None",
 *                                    "public_export_hash": "123"} ],
 *   "internal_dependency_arcs":   [ {"index": 0, "from_export_bundle_index": 1,
 *                                    "to_export_bundle_index": 0} ],
 *   "external_package_dependencies": [ {"index": 3,
 *       "legacy_dependency_arcs":   [ {"index": 0, "from_export_bundle_index": 1} ],
 *       "external_dependency_arcs": [ {"index": 0, "to_export_bundle_index": 2} ] } ]
 * }
 * ```
 *
 * Returns the number of applied field edits.
 */
fun zen_package_apply_patch(node: ObjectNode, patch: JsonNode): Int {
    var applied = 0
    for (edit in patch.path("export_map")) {
        val target = patch_target(node, "export_map", edit_index(edit), "export")
        edit.get("object_flags")?.let { target.put("object_flags", it.asLong()); applied++ }
        edit.get("filter_flags")?.let {
            EExportFilterFlags.valueOf(it.asText())
            target.put("filter_flags", it.asText())
            applied++
        }
        edit.get("public_export_hash")?.let { target.put("public_export_hash", it.asText()); applied++ }
    }
    for (edit in patch.path("internal_dependency_arcs")) {
        val target = patch_target(node, "internal_dependency_arcs", edit_index(edit), "arc")
        edit.get("from_export_bundle_index")?.let {
            target.put("from_export_bundle_index", it.asInt()); applied++
        }
        edit.get("to_export_bundle_index")?.let {
            target.put("to_export_bundle_index", it.asInt()); applied++
        }
    }
    for (edit in patch.path("external_package_dependencies")) {
        val dep = patch_target(
            node, "external_package_dependencies", edit_index(edit), "dependency")
        for (arcEdit in edit.path("legacy_dependency_arcs")) {
            val arc = patch_target(dep, "legacy_dependency_arcs", edit_index(arcEdit), "arc")
            arcEdit.get("from_export_bundle_index")?.let {
                arc.put("from_export_bundle_index", it.asInt()); applied++
            }
            arcEdit.get("to_export_bundle_index")?.let {
                arc.put("to_export_bundle_index", it.asInt()); applied++
            }
        }
        for (arcEdit in edit.path("external_dependency_arcs")) {
            val arc = patch_target(
                dep, "external_dependency_arcs", edit_index(arcEdit), "arc")
            arcEdit.get("to_export_bundle_index")?.let {
                arc.put("to_export_bundle_index", it.asInt()); applied++
            }
        }
    }
    return applied
}

/** A patch entry's target index; required, so a typo cannot silently address entry 0. */
private fun edit_index(edit: JsonNode): Int {
    require(edit.path("index").isIntegralNumber) {
        "patch entry needs an integer index: $edit"
    }
    return edit.path("index").asInt()
}

/** The [index]-th element of [arrayName] on [parent], or a loud failure naming the array. */
private fun patch_target(parent: ObjectNode, arrayName: String, index: Int, what: String): ObjectNode {
    val array = parent.get(arrayName)
    require(array != null && array.isArray && index in 0 until array.size()) {
        "patch targets $what $index but $arrayName has ${array?.size() ?: 0} entries"
    }
    return array[index] as? ObjectNode ?: error("$arrayName[$index] is not an object")
}

/**
 * Replace the payload bytes of the exports in [changed] (export index -> new bytes) inside a dump
 * that carries payloads, relaying the cooked region out in cooked-offset order — the same layout
 * math the byte graft performs, expressed on the JSON AST so payload swaps and table patches share
 * one dump/write cycle. Returns the number of exports replaced.
 *
 * The dump must be internally contiguous (offsets ascending from `cooked_header_size`, no gaps);
 * coverage against the actual chunk is re-checked by `zen_package_validate` after writing.
 */
fun zen_package_apply_payloads(node: ObjectNode, changed: Map<Int, ByteArray>): Int =
    zen_package_relayout_payloads(node, changed, requireContiguous = true)

/**
 * The relayout behind [zen_package_apply_payloads]: reassigns every export's cooked offset so the
 * region is dense and ordered. Swaps require the cooker's contiguous layout
 * ([requireContiguous] = true); template-mode removals have already dropped an export, so they
 * only need the surviving payloads re-laid out in order.
 */
fun zen_package_relayout_payloads(
    node: ObjectNode,
    changed: Map<Int, ByteArray>,
    requireContiguous: Boolean,
): Int {
    val exportMap = node.withArray("export_map")
    require(changed.keys.all { it in 0 until exportMap.size() }) {
        "changed export index out of range: ${changed.keys} (export count ${exportMap.size()})"
    }
    val payloads = node.withArray("payloads")
    require(payloads.size() == exportMap.size()) {
        "payload inventory (${payloads.size()}) does not match the export map (${exportMap.size()})"
    }
    val cookedHeader = node.path("summary").path("cooked_header_size").asText().toLong()
    val order = exportMap.mapIndexed { i, e -> i to e.path("cooked_serial_offset").asText().toLong() }
        .sortedBy { it.second }.map { it.first }
    var cursor = 0L
    val exportNodes = order.associateWith { exportMap[it] as? ObjectNode }
    val payloadNodes = order.associateWith { payloads[it] as? ObjectNode }
    for (i in order) {
        val e = exportNodes.getValue(i) ?: error("export_map[$i] is not an object")
        val p = payloadNodes.getValue(i) ?: error("payloads[$i] is not an object")
        val start = e.path("cooked_serial_offset").asText().toLong() - cookedHeader
        if (requireContiguous) {
            require(start == cursor) {
                "payload region is not contiguous at export $i (start $start, expected $cursor)"
            }
        }
        cursor += p.path("size").asLong()
    }
    var position = 0L
    var replaced = 0
    for (i in order) {
        val e = exportNodes.getValue(i)!!
        val p = payloadNodes.getValue(i)!!
        changed[i]?.let { bytes ->
            p.put("bytes_b64", java.util.Base64.getEncoder().encodeToString(bytes))
            p.put("size", bytes.size)
            p.put("sha256", sha256(bytes))
            replaced++
        }
        e.put("cooked_serial_offset", (cookedHeader + position).toString())
        e.put("cooked_serial_size", p.path("size").asLong().toString())
        position += p.path("size").asLong()
    }
    // The inventory's own index field is cosmetic (the writer uses array order) but must not go
    // stale after a removal relay.
    for (i in 0 until payloads.size()) {
        (payloads.get(i) as? ObjectNode)?.put("index", i)
    }
    return replaced
}
