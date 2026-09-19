// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * Template mode: append-only edits to a cooked package's Zen tables so a game package can
 * reference content that only exists in a mod.
 *
 * Everything here is expressed on the [zen_package_to_json_node] dump, so the writer
 * ([zen_package_from_json_node]) keeps ownership of the layout: offsets, header size, bundle
 * tables and the store entry are all re-derived on write.
 *
 * **Append-only is the soundness argument.** Names, imports and exports are appended, never
 * reordered, so every index a payload already contains keeps its meaning — no payload parsing or
 * bytecode rewriting is needed. Removal renumbers references, so it can only be used when nothing
 * references the removed export; it refuses when the tables do (see
 * [zen_package_remove_export]), and the caller must vouch for payload contents by passing a
 * deliberate index choice (the `.zen.toml` surface makes that explicit with `force = true`).
 *
 * Import values are the legacy (container header version `Initial`) form: a cityhash of the
 * imported object's full path. The newer `LocalizedPackages`+ layout indexes its packages and
 * hashes instead; those packages are refused with a pointer to what they would need.
 */

/** What one template pass changed. */
data class ZenTemplateReport(
    val names_appended: Int,
    val imports_appended: Int,
    val exports_added: Int,
    val exports_removed: Int,
    val resized: Boolean,
) {
    val total: Int get() = names_appended + imports_appended + exports_added + exports_removed
}

/** One import to append: [objectPath] is the imported object's full path (`/Game/X/Y.Y_C`). */
data class ZenImportSpec(
    val objectPath: String,
    /** Bundle index inside the imported package (the legacy arc's `from` side). */
    val importedBundle: Int? = null,
)

/**
 * One export to append, with the payload bytes it serializes. A data class for the named-argument
 * convenience: its [payload] compares by reference (arrays), so do not use specs as map keys.
 */
data class ZenExportSpec(
    val objectName: String,
    val payload: ByteArray,
    val classIndex: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    val outerIndex: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    val superIndex: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    val templateIndex: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    val objectFlags: UInt = 0u,
    val filterFlags: EExportFilterFlags = EExportFilterFlags.None,
    /** When set, the export is public: its full object path feeds the legacy global import index. */
    val publicObjectPath: String? = null,
    /** Import-map indices this export depends on: a legacy arc is added per import. */
    val dependencies: List<Int> = emptyList(),
    /** Existing exports this export depends on: an internal arc is added to their bundles. */
    val exportDependencies: List<Int> = emptyList(),
)

/** Apply the template ops of [ops] (`names`/`imports`/`add_exports`/`remove_exports`) to [node]. */
fun zen_package_apply_template(node: ObjectNode, ops: JsonNode): ZenTemplateReport {
    val names = zen_package_append_names(node, rule_list(ops.path("names")).map { it.asText() })
        .count { it != null }
    var imports = 0
    for (rule in rule_list(ops.path("imports"))) {
        val before = node.path("import_map").size()
        zen_package_add_import(node, import_spec_from_json(rule))
        if (node.path("import_map").size() > before) imports++
    }
    var added = 0
    for (rule in rule_list(ops.path("add_exports"))) {
        zen_package_add_export(node, export_spec_from_json(node, rule))
        added++
    }
    var removed = 0
    for (rule in rule_list(ops.path("remove_exports"))) {
        zen_package_remove_export(
            node, rule.path("index").asInt(), rule.path("force").asBoolean(false))
        removed++
    }
    return ZenTemplateReport(names, imports, added, removed, names + imports + added + removed > 0)
}

/** `[[zen.x]]` is an array, but a lone table parses as a single object in some TOML/JSON shapes. */
internal fun rule_list(node: JsonNode?): List<JsonNode> = when {
    node == null || node.isMissingNode || node.isNull -> emptyList()
    node.isArray -> node.toList()
    else -> listOf(node)
}

// ---------------------------------------------------------------------------
// names
// ---------------------------------------------------------------------------

/**
 * Append [name] to the dump's name map and return its index. Existing names return their current
 * index unchanged (all indices are stable — appending never reorders). The `_N` suffix form of
 * [FNameMap.store] applies: the index addresses the base name.
 */
fun zen_package_append_name(node: ObjectNode, name: String): Int? =
    zen_package_append_names(node, listOf(name)).single()

/**
 * Batch form of [zen_package_append_name]: one name-map rebuild for [names], returning per name
 * its new index (or null when it already existed).
 */
fun zen_package_append_names(node: ObjectNode, names: List<String>): List<Int?> {
    val nameMapNode = node.path("name_map") as? ObjectNode
        ?: error("dump has no name_map; names can only be appended to a full dump")
    val kind = EMappedNameType.valueOf(nameMapNode.path("kind").asText())
    val map = FNameMap.create_from_names(kind, raw_names_of(nameMapNode))
    val result = names.map { name ->
        val existing = map.name_lookup[break_down_name_string(name).first]
        val mapped = map.store(name)
        if (existing == null) mapped.index().toInt() else null
    }
    write_raw_names(nameMapNode, map)
    return result
}

private fun raw_names_of(nameMapNode: ObjectNode): List<String> =
    nameMapNode.path("raw_names_b64").map {
        String(Base64.getDecoder().decode(it.asText()), Charsets.UTF_8)
    }

private fun write_raw_names(nameMapNode: ObjectNode, map: FNameMap) {
    val names = nameMapNode.withArray("names")
    val raw = nameMapNode.withArray("raw_names_b64")
    names.removeAll()
    raw.removeAll()
    for (n in map.names) {
        names.add(n)
        raw.add(Base64.getEncoder().encodeToString(n.toByteArray(Charsets.UTF_8)))
    }
}

// ---------------------------------------------------------------------------
// imports
// ---------------------------------------------------------------------------

/** The full object path a `PackageImport`'s legacy hash is derived from. */
private fun legacy_import_index(objectPath: String): FPackageObjectIndex =
    if (objectPath.startsWith("/Script/")) {
        FPackageObjectIndex.create_script_import(objectPath)
    } else {
        FPackageObjectIndex.create_legacy_package_import_from_path(objectPath)
    }

/**
 * Append the import for [spec] and return its import-map index; an import that is already present
 * returns its index (idempotent). Package imports also register the imported package's id so the
 * store entry and the dependency graph can name it. Package imports get no arc here: arcs are
 * created by the importing export ([ZenExportSpec.dependencies]) or by explicit arc rules.
 */
fun zen_package_add_import(node: ObjectNode, spec: ZenImportSpec): Int {
    val containerVersion = container_header_version_of(node)
    require(containerVersion.value <= EIoContainerHeaderVersion.Initial.value) {
        "imports append supports the legacy (hash) import form of container header versions up to " +
            "Initial; this dump is $containerVersion and would need sorted package/hash index " +
            "tables and a payload-index remap"
    }
    val importMap = node.withArray("import_map")
    val index = legacy_import_index(spec.objectPath)
    val existing = importMap.indexOfFirst {
        it.path("type_and_id").asText().toULong() == index.type_and_id
    }
    if (existing >= 0) return existing
    val entry = object_index_node_of(index)
    spec.importedBundle?.let { entry.put("imported_bundle", it) }
    entry.put("object_path", spec.objectPath)
    importMap.add(entry)
    if (!spec.objectPath.startsWith("/Script/")) ensure_imported_package(node, spec.objectPath)
    return importMap.size() - 1
}

private fun object_index_node_of(index: FPackageObjectIndex): ObjectNode =
    com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("type_and_id", index.type_and_id.toString())
        put("is_null", index.is_null())
    }

/**
 * Register the imported package (`FPackageId` of the package path) in `imported_packages`. The
 * import path is `Package.Path.Export`, so the package is everything before the final `.`.
 */
private fun ensure_imported_package(node: ObjectNode, objectPath: String) {
    val packageName = objectPath.substringBeforeLast('.')
    val packageId = FPackageId.from_name(packageName)
    val packages = node.withArray("imported_packages")
    if (packages.any { it.asText().toULong() == packageId.value }) return
    packages.add(packageId.value.toString())
}

/** The object path recorded on an import-map entry, when the entry was added by template mode. */
internal fun import_object_path_of(node: ObjectNode, importIndex: Int): String? =
    node.path("import_map").get(importIndex)?.path("object_path")?.asText()

private fun imported_bundle_of(node: ObjectNode, importIndex: Int): Int? {
    val entry = node.path("import_map").get(importIndex) ?: return null
    return entry.path("imported_bundle").asInt(-1).takeIf { it >= 0 }
}

// ---------------------------------------------------------------------------
// exports
// ---------------------------------------------------------------------------

/**
 * Append [spec]'s export and payload, as its own export bundle: the new bundle header carries the
 * Create+Serialize commands and a `serial_offset` at the current region end, so the payload region
 * stays ordered exactly as the bundles serialize. Dependency arcs are added for
 * [ZenExportSpec.dependencies] and [ZenExportSpec.exportDependencies] against the new bundle.
 */
fun zen_package_add_export(node: ObjectNode, spec: ZenExportSpec): Int {
    val exportMap = node.withArray("export_map")
    val payloads = node.withArray("payloads")
    require(payloads.size() == exportMap.size()) {
        "payload inventory (${payloads.size()}) does not match the export map (${exportMap.size()}); " +
            "dump with payloads to add exports"
    }
    val cookedHeader = node.path("summary").path("cooked_header_size").asText().toLong()
    val regionEnd = region_end_of(exportMap, cookedHeader)
    val nameNode = node.path("name_map") as? ObjectNode ?: error("dump has no name_map")
    val kind = EMappedNameType.valueOf(nameNode.path("kind").asText())
    val map = FNameMap.create_from_names(kind, raw_names_of(nameNode))
    val objectName = map.store(spec.objectName)
    write_raw_names(nameNode, map)

    val index = exportMap.size()
    exportMap.add(export_map_entry_node(spec, objectName, cookedHeader + regionEnd, padding_b64_of(exportMap)))
    payloads.add(payload_node(index, spec.payload))
    append_bundle_for(node, index, regionEnd)
    zen_package_relayout_payloads(node, mapOf(index to spec.payload), requireContiguous = true)
    node.put("payload_count", exportMap.size())
    add_dependency_arcs(node, index, spec)
    return index
}

private fun region_end_of(exportMap: ArrayNode, cookedHeader: Long): Long =
    exportMap.maxOfOrNull {
        it.path("cooked_serial_offset").asText().toLong() + it.path("cooked_serial_size").asText().toLong()
    }?.minus(cookedHeader) ?: 0L

private fun export_map_entry_node(
    spec: ZenExportSpec,
    objectName: FMappedName,
    offset: Long,
    paddingB64: String,
): ObjectNode =
    com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("cooked_serial_offset", offset.toString())
        put("cooked_serial_size", spec.payload.size.toString())
        set<ObjectNode>("object_name", mapped_name_node_of(objectName))
        put("object_name_resolved", spec.objectName)
        set<ObjectNode>("outer_index", object_index_node_of(spec.outerIndex))
        set<ObjectNode>("class_index", object_index_node_of(spec.classIndex))
        set<ObjectNode>("super_index", object_index_node_of(spec.superIndex))
        set<ObjectNode>("template_index", object_index_node_of(spec.templateIndex))
        val publicHash = spec.publicObjectPath
            ?.let { FPackageObjectIndex.create_legacy_package_import_from_path(it).type_and_id }
            ?: 0uL
        put("public_export_hash", publicHash.toString())
        put("object_flags", spec.objectFlags.toLong())
        put("filter_flags", spec.filterFlags.name)
        put("padding", paddingB64)
    }

/** Existing exports' padding bytes (three bytes in the ABI; mirror the cooker's own values). */
private fun padding_b64_of(exportMap: ArrayNode): String =
    exportMap.lastOrNull()?.path("padding")?.asText("")?.takeIf { it.isNotEmpty() }
        ?: Base64.getEncoder().encodeToString(ByteArray(3))

private fun mapped_name_node_of(m: FMappedName): ObjectNode =
    com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("index_and_type", m.index_and_type.toLong())
        put("number", m.number.toLong())
    }

private fun payload_node(index: Int, bytes: ByteArray): ObjectNode =
    com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("index", index)
        put("size", bytes.size)
        put("sha256", sha256(bytes))
        put("bytes_b64", Base64.getEncoder().encodeToString(bytes))
    }

/** Append a one-export bundle (Create+Serialize) starting at [regionEnd] of the payload region. */
private fun append_bundle_for(node: ObjectNode, exportIndex: Int, regionEnd: Long) {
    val headers = node.withArray("export_bundle_headers")
    val entries = node.withArray("export_bundle_entries")
    val header = com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("serial_offset", regionEnd.toString())
        put("first_entry_index", entries.size())
        put("entry_count", 2)
    }
    headers.add(header)
    for (command in listOf(EExportCommandType.Create, EExportCommandType.Serialize)) {
        entries.add(com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
            put("local_export_index", exportIndex)
            put("command_type", command.name)
        })
    }
}

/** Internal + external dependency arcs for an appended export's own bundle. */
private fun add_dependency_arcs(node: ObjectNode, exportIndex: Int, spec: ZenExportSpec) {
    val toBundle = bundle_index_of(node, exportIndex)
    for (dep in spec.exportDependencies) {
        val fromBundle = bundle_index_of(node, dep)
        if (fromBundle != toBundle) add_internal_arc(node, fromBundle, toBundle)
    }
    for (dep in spec.dependencies) {
        val importedBundle = imported_bundle_of(node, dep)
            ?: throw IllegalStateException(
                "export ${spec.objectName} depends on import $dep, which carries no " +
                    "imported_bundle; set imported_bundle on its [[zen.import]] rule")
        val objectPath = import_object_path_of(node, dep)
            ?: throw IllegalStateException(
                "export ${spec.objectName} depends on import $dep, which was not added by a " +
                    "template import rule; add the arc with [[zen.dependency_arc]] instead")
        add_legacy_arc(node, objectPath.substringBeforeLast('.'), importedBundle, toBundle)
    }
}

private fun add_internal_arc(node: ObjectNode, fromBundle: Int, toBundle: Int) {
    val arcs = node.withArray("internal_dependency_arcs")
    val present = arcs.any {
        it.path("from_export_bundle_index").asInt() == fromBundle &&
            it.path("to_export_bundle_index").asInt() == toBundle
    }
    if (present) return
    arcs.add(com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("from_export_bundle_index", fromBundle)
        put("to_export_bundle_index", toBundle)
    })
}

private fun add_legacy_arc(node: ObjectNode, packageName: String, fromBundle: Int, toBundle: Int) {
    val packageId = FPackageId.from_name(packageName)
    val deps = node.withArray("external_package_dependencies")
    var dep = deps.firstOrNull { it.path("from_package_id").asText().toULong() == packageId.value }
    if (dep == null) {
        dep = com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
            put("from_package_id", packageId.value.toString())
            set<ArrayNode>("external_dependency_arcs", com.fasterxml.jackson.databind.ObjectMapper().createArrayNode())
            set<ArrayNode>("legacy_dependency_arcs", com.fasterxml.jackson.databind.ObjectMapper().createArrayNode())
        }
        deps.add(dep)
    }
    val arcs = (dep as ObjectNode).withArray("legacy_dependency_arcs")
    val present = arcs.any {
        it.path("from_export_bundle_index").asInt() == fromBundle &&
            it.path("to_export_bundle_index").asInt() == toBundle
    }
    if (present) return
    arcs.add(com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().apply {
        put("from_export_bundle_index", fromBundle)
        put("to_export_bundle_index", toBundle)
    })
}

/** The bundle header index whose entry range contains [exportIndex]. */
fun bundle_index_of(node: ObjectNode, exportIndex: Int): Int {
    val headers = node.path("export_bundle_headers")
    for (i in 0 until headers.size()) {
        val first = headers[i].path("first_entry_index").asInt()
        val count = headers[i].path("entry_count").asInt()
        val entries = node.path("export_bundle_entries")
        for (e in first until first + count) {
            if (entries.get(e)?.path("local_export_index")?.asInt() == exportIndex) return i
        }
    }
    throw IllegalStateException("export $exportIndex is not in any export bundle")
}

// ---------------------------------------------------------------------------
// removal
// ---------------------------------------------------------------------------

/**
 * Remove export [index]: its payload, its Create+Serialize bundle entries and its bundle when it
 * becomes empty, with every reference renumbered. Refuses when another export's
 * outer/class/super/template index or a dependency-bundle entry still points at [index] — those
 * are the references visible in the tables. **Payload bytes cannot be rewritten here**, so the
 * caller guarantees no payload references [index] (the `.zen.toml` surface requires `force = true`).
 */
fun zen_package_remove_export(node: ObjectNode, index: Int, force: Boolean = false) {
    val exportMap = node.withArray("export_map")
    val payloads = node.withArray("payloads")
    require(index in 0 until exportMap.size()) {
        "cannot remove export $index: the export map has ${exportMap.size()} entries"
    }
    val referencedBy = referencing_exports(node, index)
    check(referencedBy.isEmpty()) {
        "cannot remove export $index: export(s) ${referencedBy.joinToString(", ")} reference it; " +
            "removal cannot rewrite those references"
    }
    check(dependency_entry_referencing(node, index) == null) {
        "cannot remove export $index: a dependency-bundle entry references it; removal cannot " +
            "rewrite the entry's command graph"
    }
    val bundle = bundle_index_of(node, index)
    // Refuse the prune path before any mutation: the emptied bundle's dependents are readable now.
    if (wouldPruneBundle(node, bundle, index)) {
        val dependents = bundle_dependents(node, bundle)
        check(dependents == null) {
            "cannot remove export $index: $dependents still depends on its bundle, which became " +
                "empty; the dependency graph would change"
        }
    }
    require(force) {
        "removing export $index needs force = true: payload bytes cannot be rewritten, so the " +
            "caller must confirm nothing references it"
    }
    val removed = remove_bundle_entries(node, index)
    val pruned = prune_bundle(node, bundle, removed)
    renumber_export_references(node, index)
    exportMap.remove(index)
    payloads.remove(index)
    zen_package_relayout_payloads(node, emptyMap(), requireContiguous = false)
    node.put("payload_count", exportMap.size())
    if (pruned != null) {
        drop_arcs_to_bundle(node, pruned)
        remap_bundle_indices(node, pruned)
    }
    recompute_bundle_offsets(node)
}

/** True when removing [index] would empty its bundle (the prune path's extra refusal). */
private fun wouldPruneBundle(node: ObjectNode, bundle: Int, index: Int): Boolean {
    val header = node.path("export_bundle_headers").get(bundle) ?: return false
    val entries = node.path("export_bundle_entries")
    val first = header.path("first_entry_index").asInt()
    val count = header.path("entry_count").asInt()
    var remaining = 0
    for (e in first until first + count) {
        if (entries.get(e)?.path("local_export_index")?.asInt() != index) remaining++
    }
    return remaining == 0
}

/**
 * A description of the first arc that makes bundle [bundle] a *dependency* of another bundle, or
 * null. Only internal arcs carry our bundles on both ends; legacy arcs keep the imported package's
 * bundle in `from`, and external arcs keep an import index there.
 */
private fun bundle_dependents(node: ObjectNode, bundle: Int): String? =
    node.path("internal_dependency_arcs").firstOrNull {
        it.path("from_export_bundle_index").asInt() == bundle
    }?.let { "internal arc from bundle ${it.path("from_export_bundle_index").asInt()}" }

/** Drop arcs whose depending end (`to`) is the removed bundle: they belonged to its export. */
private fun drop_arcs_to_bundle(node: ObjectNode, bundle: Int) {
    val arcs = node.withArray("internal_dependency_arcs")
    val kept = arcs.filterNot { it.path("to_export_bundle_index").asInt() == bundle }
    arcs.removeAll()
    kept.forEach { arcs.add(it) }
    for (dep in node.withArray("external_package_dependencies")) {
        val depNode = dep as ObjectNode
        for (name in listOf("legacy_dependency_arcs", "external_dependency_arcs")) {
            val list = depNode.withArray(name)
            val filtered = list.filterNot { it.path("to_export_bundle_index").asInt() == bundle }
            list.removeAll()
            filtered.forEach { list.add(it) }
        }
    }
}

/** Exports whose outer/class/super/template index is an export reference to [index]. */
private fun referencing_exports(node: ObjectNode, index: Int): List<Int> {
    val fields = listOf("outer_index", "class_index", "super_index", "template_index")
    return node.path("export_map").mapIndexedNotNull { i, e ->
        if (i == index) null else if (fields.any { field_references_export(e.path(it), index) }) i else null
    }
}

private fun field_references_export(indexNode: JsonNode, index: Int): Boolean {
    val ref = FPackageObjectIndex(indexNode.path("type_and_id").asText("0").toULong())
    return ref.kind() == FPackageObjectIndexType.Export && ref.raw_index() == index.toULong()
}

/** A dependency-bundle entry (`FPackageIndex`) that references export [index]. */
private fun dependency_entry_referencing(node: ObjectNode, index: Int): ObjectNode? =
    node.path("dependency_bundle_entries").firstOrNull {
        val ref = FPackageIndex(it.path("local_import_or_export_index").asInt())
        ref.is_export() && ref.to_export_index().toInt() == index
    } as? ObjectNode

private fun remove_bundle_entries(node: ObjectNode, index: Int): Int {
    val entries = node.withArray("export_bundle_entries")
    val kept = entries.filterNot { it.path("local_export_index").asInt() == index }
    val removed = entries.size() - kept.size
    entries.removeAll()
    kept.forEach { entries.add(it) }
    return removed
}

/** Drop [bundle] when its entry range became empty; returns its old index when pruned. */
private fun prune_bundle(node: ObjectNode, bundle: Int, removed: Int): Int? {
    val headers = node.withArray("export_bundle_headers")
    val header = headers[bundle] as ObjectNode
    val newCount = header.path("entry_count").asInt() - removed
    if (newCount > 0) {
        header.put("entry_count", newCount)
        shift_bundle_starts(node, bundle, -removed)
        return null
    }
    headers.remove(bundle)
    shift_bundle_starts(node, bundle - 1, -removed)
    return bundle
}

/** Shift `first_entry_index` of bundles after [afterIndex] by [delta] entries. */
private fun shift_bundle_starts(node: ObjectNode, afterIndex: Int, delta: Int) {
    if (delta == 0) return
    val headers = node.withArray("export_bundle_headers")
    for (i in afterIndex + 1 until headers.size()) {
        val header = headers[i] as ObjectNode
        header.put("first_entry_index", header.path("first_entry_index").asInt() + delta)
    }
}

/** Decrement export indices above [index] in bundle entries, references and dependency entries. */
private fun renumber_export_references(node: ObjectNode, index: Int) {
    for (e in node.withArray("export_bundle_entries")) {
        val eNode = e as ObjectNode
        val local = eNode.path("local_export_index").asInt()
        if (local > index) eNode.put("local_export_index", local - 1)
    }
    for (e in node.withArray("export_map")) {
        val eNode = e as ObjectNode
        for (field in listOf("outer_index", "class_index", "super_index", "template_index")) {
            val ref = FPackageObjectIndex(eNode.path(field).path("type_and_id").asText().toULong())
            if (ref.kind() == FPackageObjectIndexType.Export && ref.raw_index() > index.toULong()) {
                (eNode.path(field) as ObjectNode).put(
                    "type_and_id", FPackageObjectIndex.create_export(ref.raw_index().toUInt() - 1u).type_and_id.toString())
            }
        }
    }
    for (e in node.withArray("dependency_bundle_entries")) {
        val eNode = e as ObjectNode
        val ref = FPackageIndex(eNode.path("local_import_or_export_index").asInt())
        if (ref.is_export() && ref.to_export_index() > index.toUInt()) {
            eNode.put("local_import_or_export_index", ref.index - 1)
        }
    }
}

/** Apply bundle-index shifts after a pruned bundle: [pruned] is the removed bundle's old index. */
private fun remap_bundle_indices(node: ObjectNode, pruned: Int) {
    fun remapped(index: Int): Int = if (index > pruned) index - 1 else index
    for (arc in node.withArray("internal_dependency_arcs")) {
        val a = arc as ObjectNode
        a.put("from_export_bundle_index", remapped(a.path("from_export_bundle_index").asInt()))
        a.put("to_export_bundle_index", remapped(a.path("to_export_bundle_index").asInt()))
    }
    for (dep in node.withArray("external_package_dependencies")) {
        val depNode = dep as ObjectNode
        for (arc in depNode.withArray("external_dependency_arcs")) {
            val a = arc as ObjectNode
            a.put("to_export_bundle_index", remapped(a.path("to_export_bundle_index").asInt()))
        }
        // legacy arcs' `from` is the imported package's bundle index: never remapped here
        for (arc in depNode.withArray("legacy_dependency_arcs")) {
            val a = arc as ObjectNode
            a.put("to_export_bundle_index", remapped(a.path("to_export_bundle_index").asInt()))
        }
    }
}

/** Re-derive every bundle's `serial_offset` from the payload region (Create has no bytes). */
private fun recompute_bundle_offsets(node: ObjectNode) {
    val headers = node.withArray("export_bundle_headers")
    val entries = node.path("export_bundle_entries")
    val exportMap = node.path("export_map")
    var offset = 0L
    for (i in 0 until headers.size()) {
        (headers[i] as ObjectNode).put("serial_offset", offset.toString())
        val first = headers[i].path("first_entry_index").asInt()
        val count = headers[i].path("entry_count").asInt()
        for (e in first until first + count) {
            val entry = entries.get(e) ?: error(
                "bundle $i entry range [$first, ${first + count}) exceeds the " +
                    "${entries.size()} bundle entries")
            if (entry.path("command_type").asText() == EExportCommandType.Serialize.name) {
                val exportIndex = entry.path("local_export_index").asInt()
                val export = exportMap.get(exportIndex) ?: error(
                    "bundle entry references export $exportIndex, but the export map has " +
                        "${exportMap.size()} entries")
                offset += export.path("cooked_serial_size").asText().toLong()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// spec parsing
// ---------------------------------------------------------------------------

internal fun container_header_version_of(node: ObjectNode): EIoContainerHeaderVersion =
    EIoContainerHeaderVersion.valueOf(node.path("container_header_version").asText())

private fun import_spec_from_json(rule: JsonNode): ZenImportSpec {
    val objectPath = rule.path("object_path").asText()
    require(objectPath.startsWith("/")) {
        "template import needs a full object_path starting with '/', got '$objectPath'"
    }
    val bundle = rule.path("imported_bundle")
    if (bundle.isMissingNode || bundle.isNull) return ZenImportSpec(objectPath, null)
    require(bundle.isIntegralNumber) {
        "imported_bundle must be an integer when the import is added (\"auto\" is resolved by " +
            "automod against the game's copy of the imported package), got '$bundle'"
    }
    return ZenImportSpec(objectPath, bundle.asInt())
}

private fun export_spec_from_json(node: ObjectNode, rule: JsonNode): ZenExportSpec {
    val payload = rule.path("payload_b64").asText()
    require(payload.isNotEmpty()) { "added export needs payload_b64 bytes" }
    val objectName = rule.path("object_name").asText()
    require(objectName.isNotEmpty()) { "added export needs an object_name" }
    val publicPath = rule.path("public_object_path").asText()
    return ZenExportSpec(
        objectName = objectName,
        payload = Base64.getDecoder().decode(payload),
        classIndex = object_index_from_ref(node, rule.path("class")),
        outerIndex = object_index_from_ref(node, rule.path("outer")),
        superIndex = object_index_from_ref(node, rule.path("super")),
        templateIndex = object_index_from_ref(node, rule.path("template")),
        objectFlags = rule.path("object_flags").asLong(0).toUInt(),
        filterFlags = rule.path("filter_flags").asText(EExportFilterFlags.None.name)
            .let { EExportFilterFlags.valueOf(it) },
        publicObjectPath = publicPath.ifEmpty { null },
        dependencies = rule.path("dependencies").map { dependency_index(it, "dependencies", node) },
        exportDependencies = rule.path("export_dependencies").map {
            dependency_index(it, "export_dependencies", node)
        },
    )
}

/** A dependency index from a spec: non-negative, and an existing export for export refs. */
private fun dependency_index(index: JsonNode, what: String, node: ObjectNode): Int {
    val value = index.asInt()
    require(value >= 0) { "$what entries must be non-negative indices, got $value" }
    if (what == "export_dependencies") {
        require(value < node.path("export_map").size()) {
            "export_dependencies references export $value, but the export map has " +
                "${node.path("export_map").size()} entries"
        }
    }
    return value
}

/**
 * Resolve an object-index reference: `null`, `{"export": N}`, `{"import": N}`,
 * `{"script": "/Script/..."}` or `{"object_path": "/Game/..."}` (the legacy hash form; the import
 * must already be in the import map).
 */
internal fun object_index_from_ref(node: ObjectNode, ref: JsonNode): FPackageObjectIndex {
    if (ref.isMissingNode || ref.isNull || ref.path("null").asBoolean(false)) {
        return FPackageObjectIndex.create_null()
    }
    ref.get("export")?.let {
        val index = it.asInt()
        require(index in 0 until node.path("export_map").size()) {
            "reference to export $index, but the export map has ${node.path("export_map").size()} " +
                "entries"
        }
        return FPackageObjectIndex.create_export(index.toUInt())
    }
    ref.get("import")?.let { idx ->
        val entry = node.path("import_map").get(idx.asInt())
            ?: throw IllegalArgumentException(
                "reference to import ${idx.asInt()}, but the import map has " +
                    "${node.path("import_map").size()} entries")
        return FPackageObjectIndex(entry.path("type_and_id").asText("0").toULong())
    }
    ref.get("script")?.let { return FPackageObjectIndex.create_script_import(it.asText()) }
    ref.get("object_path")?.let {
        val index = FPackageObjectIndex.create_legacy_package_import_from_path(it.asText())
        require(node.path("import_map").any { e ->
            e.path("type_and_id").asText("0").toULong() == index.type_and_id
        }) {
            "object_path '${it.asText()}' is not in the import map; add it with a template import " +
                "rule first"
        }
        return index
    }
    throw IllegalArgumentException("unrecognized object-index reference: $ref")
}
