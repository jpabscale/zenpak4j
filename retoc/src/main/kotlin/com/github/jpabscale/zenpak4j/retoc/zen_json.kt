// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
package com.github.jpabscale.zenpak4j.retoc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * Zen-JSON dump of a cooked package: the container AST (header tables + payload inventory) as a
 * deterministic, diffable document. Content semantics stay in the legacy UAssetAPI JSON — this
 * layer deliberately carries no legacy data, only per-export payload sizes and hashes plus the
 * tables the legacy→Zen rebuild gets wrong (import map, arcs, bundles).
 *
 * Nodes are built with Jackson (deterministic insertion order) so consumers can query them like
 * any other automod JSON. Reads through the strict [FZenPackageHeader.deserialize] (cooker/valid
 * packages); a converted package whose bundle layout the strict reader rejects is a later phase
 * (payload inventory already comes from the tolerant reader).
 */
private val ZEN_MAPPER = ObjectMapper()

fun zen_package_to_json_node(
    chunk: ByteArray,
    include_payloads: Boolean = false,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
    toc_version: EIoStoreTocVersion = EIoStoreTocVersion.PartitionSize,
    store_entry: StoreEntry? = null,
): ObjectNode {
    val header = FZenPackageHeader.deserialize(
        SeekableByteArrayInputStream(chunk), store_entry, toc_version, container_header_version, null)
    val payloads = extract_zen_payloads(chunk, toc_version, container_header_version)

    val root = ZEN_MAPPER.createObjectNode()
    root.put("type", "zenpak4j.ZenPackageHeader, zenpak4j")
    root.put("toc_version", toc_version.name)
    root.put("container_header_version", container_header_version.name)
    root.set<ObjectNode>("summary", summary_node(header.summary))
    root.set<ObjectNode>("versioning_info", versioning_node(header.versioning_info))
    root.set<ObjectNode>("name_map", name_map_node(header.name_map))
    root.set<ArrayNode>("import_map", object_index_array(header.import_map))
    root.set<ArrayNode>("export_map", export_map_array(header.export_map, header.name_map))
    root.set<ArrayNode>("export_bundle_headers", export_bundle_headers_node(header.export_bundle_headers))
    root.set<ArrayNode>("export_bundle_entries", export_bundle_entries_node(header.export_bundle_entries))
    root.set<ArrayNode>(
        "dependency_bundle_headers", dependency_bundle_headers_node(header.dependency_bundle_headers))
    root.set<ArrayNode>(
        "dependency_bundle_entries", dependency_bundle_entries_node(header.dependency_bundle_entries))
    root.set<ArrayNode>("internal_dependency_arcs", internal_arcs_node(header.internal_dependency_arcs))
    root.set<ArrayNode>("external_package_dependencies", external_deps_node(header.external_package_dependencies))
    root.set<ArrayNode>("cell_import_map", object_index_array(header.cell_import_map))
    root.set<ArrayNode>("cell_export_map", cell_export_map_node(header.cell_export_map))
    root.set<ArrayNode>("imported_packages", id_array(header.imported_packages.map { it.value }))
    root.set<ArrayNode>("imported_package_names", ZEN_MAPPER.createArrayNode().apply {
        for (n in header.imported_package_names) add(n)
    })
    root.set<ArrayNode>("imported_public_export_hashes", id_array(header.imported_public_export_hashes))
    root.set<ArrayNode>("shader_map_hashes", hash_array(header.shader_map_hashes))
    root.set<ArrayNode>("bulk_data", bulk_data_node(header.bulk_data))
    root.set<ArrayNode>("payloads", payloads_node(payloads, include_payloads))
    root.put("payload_count", payloads.size)
    return root
}

/** The dump as (pretty) JSON text. */
fun zen_package_to_json(
    chunk: ByteArray,
    include_payloads: Boolean = false,
    container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.Initial,
    toc_version: EIoStoreTocVersion = EIoStoreTocVersion.PartitionSize,
    store_entry: StoreEntry? = null,
): String = zen_package_to_json_node(
    chunk, include_payloads, container_header_version, toc_version, store_entry).toPrettyString()

/** Parse a dump back into a node (for querying/validation by callers). */
fun zen_json_node(text: String): JsonNode = ZEN_MAPPER.readTree(text)

// ---------------------------------------------------------------------------
// per-structure nodes
// ---------------------------------------------------------------------------

private fun u64(node: ObjectNode, name: String, value: ULong) {
    // unsigned 64-bit values exceed exact JSON numbers in some consumers; keep them as strings
    node.put(name, value.toString())
}

private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

private fun summary_node(s: FZenPackageSummary): ObjectNode = ZEN_MAPPER.createObjectNode().apply {
    put("has_versioning_info", s.has_versioning_info.toLong())
    put("header_size", s.header_size.toLong())
    set<ObjectNode>("name", mapped_name_node(s.name))
    set<ObjectNode>("source_name", mapped_name_node(s.source_name))
    put("package_flags", s.package_flags.toLong())
    put("cooked_header_size", s.cooked_header_size.toLong())
    put("imported_public_export_hashes_offset", s.imported_public_export_hashes_offset)
    put("import_map_offset", s.import_map_offset)
    put("export_map_offset", s.export_map_offset)
    put("export_bundle_entries_offset", s.export_bundle_entries_offset)
    put("graph_data_offset", s.graph_data_offset)
    put("dependency_bundle_headers_offset", s.dependency_bundle_headers_offset)
    put("dependency_bundle_entries_offset", s.dependency_bundle_entries_offset)
    put("imported_package_names_offset", s.imported_package_names_offset)
    put("name_map_names_offset", s.name_map_names_offset)
    put("name_map_names_size", s.name_map_names_size)
    put("name_map_hashes_offset", s.name_map_hashes_offset)
    put("name_map_hashes_size", s.name_map_hashes_size)
    put("graph_data_size", s.graph_data_size)
}

private fun versioning_node(v: FZenPackageVersioningInfo): ObjectNode = ZEN_MAPPER.createObjectNode().apply {
    put("zen_version", v.zen_version.name)
    put("licensee_version", v.licensee_version.toLong())
    set<ObjectNode>("package_file_version", ZEN_MAPPER.createObjectNode().apply {
        put("file_version_ue4", v.package_file_version.file_version_ue4)
        put("file_version_ue5", v.package_file_version.file_version_ue5)
    })
    set<ArrayNode>("custom_versions", ZEN_MAPPER.createArrayNode().apply {
        for (cv in v.custom_versions) {
            add(ZEN_MAPPER.createObjectNode().apply {
                put("key", "${cv.key.a}:${cv.key.b}:${cv.key.c}:${cv.key.d}")
                put("version", cv.version)
            })
        }
    })
}

private fun mapped_name_node(m: FMappedName): ObjectNode = ZEN_MAPPER.createObjectNode().apply {
    put("index_and_type", m.index_and_type.toLong())
    put("number", m.number.toLong())
}

private fun object_index_node(o: FPackageObjectIndex): ObjectNode = ZEN_MAPPER.createObjectNode().apply {
    u64(this, "type_and_id", o.type_and_id)
    put("is_null", o.is_null())
}

private fun object_index_array(map: List<FPackageObjectIndex>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply { for (o in map) add(object_index_node(o)) }

private fun name_map_node(name_map: FNameMap): ObjectNode = ZEN_MAPPER.createObjectNode().apply {
    put("kind", name_map.kind.name)
    set<ArrayNode>("names", ZEN_MAPPER.createArrayNode().apply {
        for (n in name_map.names) add(n)
    })
    set<ArrayNode>("raw_names_b64", ZEN_MAPPER.createArrayNode().apply {
        for (n in name_map.copy_raw_names()) add(b64(n.toByteArray(Charsets.UTF_8)))
    })
}

private fun export_map_array(map: List<FExportMapEntry>, name_map: FNameMap): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (e in map) {
            add(ZEN_MAPPER.createObjectNode().apply {
                u64(this, "cooked_serial_offset", e.cooked_serial_offset)
                u64(this, "cooked_serial_size", e.cooked_serial_size)
                set<ObjectNode>("object_name", mapped_name_node(e.object_name))
                put("object_name_resolved", name_map.get(e.object_name))
                set<ObjectNode>("outer_index", object_index_node(e.outer_index))
                set<ObjectNode>("class_index", object_index_node(e.class_index))
                set<ObjectNode>("super_index", object_index_node(e.super_index))
                set<ObjectNode>("template_index", object_index_node(e.template_index))
                u64(this, "public_export_hash", e.public_export_hash)
                put("object_flags", e.object_flags.toLong())
                put("filter_flags", e.filter_flags.name)
                put("padding", b64(e.padding))
            })
        }
    }

private fun export_bundle_headers_node(headers: List<FExportBundleHeader>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (h in headers) {
            add(ZEN_MAPPER.createObjectNode().apply {
                u64(this, "serial_offset", h.serial_offset)
                put("first_entry_index", h.first_entry_index.toLong())
                put("entry_count", h.entry_count.toLong())
            })
        }
    }

private fun export_bundle_entries_node(entries: List<FExportBundleEntry>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (e in entries) {
            add(ZEN_MAPPER.createObjectNode().apply {
                put("local_export_index", e.local_export_index.toLong())
                put("command_type", e.command_type.name)
            })
        }
    }

private fun dependency_bundle_headers_node(headers: List<FDependencyBundleHeader>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (h in headers) {
            add(ZEN_MAPPER.createObjectNode().apply {
                put("first_entry_index", h.first_entry_index)
                put("create_before_create_dependencies", h.create_before_create_dependencies.toLong())
                put("serialize_before_create_dependencies", h.serialize_before_create_dependencies.toLong())
                put("create_before_serialize_dependencies", h.create_before_serialize_dependencies.toLong())
                put("serialize_before_serialize_dependencies", h.serialize_before_serialize_dependencies.toLong())
            })
        }
    }

private fun dependency_bundle_entries_node(entries: List<FDependencyBundleEntry>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (e in entries) {
            add(ZEN_MAPPER.createObjectNode().apply {
                put("local_import_or_export_index", e.local_import_or_export_index.index)
            })
        }
    }

private fun internal_arcs_node(arcs: List<FInternalDependencyArc>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (a in arcs) {
            add(ZEN_MAPPER.createObjectNode().apply {
                put("from_export_bundle_index", a.from_export_bundle_index)
                put("to_export_bundle_index", a.to_export_bundle_index)
            })
        }
    }

private fun external_deps_node(deps: List<ExternalPackageDependency>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (dep in deps) {
            add(ZEN_MAPPER.createObjectNode().apply {
                u64(this, "from_package_id", dep.from_package_id.value)
                set<ArrayNode>("external_dependency_arcs", ZEN_MAPPER.createArrayNode().apply {
                    for (a in dep.external_dependency_arcs) {
                        add(ZEN_MAPPER.createObjectNode().apply {
                            put("from_import_index", a.from_import_index)
                            put("from_command_type", a.from_command_type.name)
                            put("to_export_bundle_index", a.to_export_bundle_index)
                        })
                    }
                })
                set<ArrayNode>("legacy_dependency_arcs", ZEN_MAPPER.createArrayNode().apply {
                    for (a in dep.legacy_dependency_arcs) {
                        add(ZEN_MAPPER.createObjectNode().apply {
                            put("from_export_bundle_index", a.from_export_bundle_index)
                            put("to_export_bundle_index", a.to_export_bundle_index)
                        })
                    }
                })
            })
        }
    }

private fun cell_export_map_node(map: List<FCellExportMapEntry>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (c in map) {
            add(ZEN_MAPPER.createObjectNode().apply {
                u64(this, "cooked_serial_offset", c.cooked_serial_offset)
                u64(this, "cooked_serial_layout_size", c.cooked_serial_layout_size)
                u64(this, "cooked_serial_size", c.cooked_serial_size)
                set<ObjectNode>("cpp_class_info", mapped_name_node(c.cpp_class_info))
            })
        }
    }

private fun id_array(values: List<ULong>): ArrayNode = ZEN_MAPPER.createArrayNode().apply {
    for (v in values) add(v.toString())
}

private fun hash_array(hashes: List<FSHAHash>): ArrayNode = ZEN_MAPPER.createArrayNode().apply {
    for (h in hashes) add(h.bytes.joinToString("") { b -> "%02x".format(b) })
}

private fun bulk_data_node(entries: List<FBulkDataMapEntry>): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        for (b in entries) {
            add(ZEN_MAPPER.createObjectNode().apply {
                put("serial_offset", b.serial_offset)
                put("duplicate_serial_offset", b.duplicate_serial_offset)
                put("serial_size", b.serial_size)
                put("flags", b.flags.toLong())
                put("cooked_index", b.cooked_index.toInt())
                put("pad", b64(b.pad))
            })
        }
    }

private fun payloads_node(payloads: List<ByteArray>, include: Boolean): ArrayNode =
    ZEN_MAPPER.createArrayNode().apply {
        payloads.forEachIndexed { i, p ->
            add(ZEN_MAPPER.createObjectNode().apply {
                put("index", i)
                put("size", p.size)
                put("sha256", sha256(p))
                if (include) put("bytes_b64", b64(p))
            })
        }
    }

internal fun sha256(data: ByteArray): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(data).joinToString("") { b -> "%02x".format(b) }
}
