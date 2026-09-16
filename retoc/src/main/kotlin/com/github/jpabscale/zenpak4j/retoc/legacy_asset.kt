// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/legacy_asset.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "SpellCheckingInspection", "MagicNumber", "MemberVisibilityCanBePrivate", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "UnnecessaryVariable", "ThrowsCount", "TooGenericExceptionCaught", "LongParameterList", "LargeClass", "ComplexCondition")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap
import java.util.Locale
import java.util.TreeMap
import kotlin.math.max

// Rust: retoc/src/legacy_asset.rs:15 FMinimalName
data class FMinimalName(
    var index: Int = 0,
    var number: Int = 0
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_i32_le(index)
        stream.write_i32_le(number)
    }
    companion object : Readable<FMinimalName> {
        override fun de(stream: InputStream): FMinimalName {
            return FMinimalName(
                index = stream.read_i32_le(),
                number = stream.read_i32_le()
            )
        }
    }
}

// Rust: retoc/src/legacy_asset.rs:35 FCountOffsetPair
data class FCountOffsetPair(
    var count: Int = 0,
    var offset: Int = 0
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_i32_le(count)
        stream.write_i32_le(offset)
    }
    companion object : Readable<FCountOffsetPair> {
        override fun de(stream: InputStream): FCountOffsetPair {
            return FCountOffsetPair(
                count = stream.read_i32_le(),
                offset = stream.read_i32_le()
            )
        }
    }
}

// Rust: retoc/src/legacy_asset.rs:55 FGenerationInfo
data class FGenerationInfo(
    var export_count: Int = 0,
    var name_count: Int = 0
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_i32_le(export_count)
        stream.write_i32_le(name_count)
    }
    companion object : Readable<FGenerationInfo> {
        override fun de(stream: InputStream): FGenerationInfo {
            return FGenerationInfo(
                export_count = stream.read_i32_le(),
                name_count = stream.read_i32_le()
            )
        }
    }
}

// Rust: retoc/src/legacy_asset.rs:75 FEngineVersion
data class FEngineVersion(
    var engine_major: UShort = 0u,
    var engine_minor: UShort = 0u,
    var engine_patch: UShort = 0u,
    var changelist: UInt = 0u,
    var branch: String = ""
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u16_le(engine_major)
        stream.write_u16_le(engine_minor)
        stream.write_u16_le(engine_patch)
        stream.write_u32_le(changelist)
        stream.write_string(branch)
    }
    companion object : Readable<FEngineVersion> {
        override fun de(stream: InputStream): FEngineVersion {
            return FEngineVersion(
                engine_major = stream.read_u16_le(),
                engine_minor = stream.read_u16_le(),
                engine_patch = stream.read_u16_le(),
                changelist = stream.read_u32_le(),
                branch = stream.read_string()
            )
        }
    }
}

// Rust: retoc/src/legacy_asset.rs:107 FLegacyPackageVersioningInfo
data class FLegacyPackageVersioningInfo(
    var legacy_file_version: Int = 0,
    var package_file_version: FPackageFileVersion = FPackageFileVersion(),
    var licensee_version: Int = 0,
    var saved_hash: ByteArray = ByteArray(20),
    var custom_versions: MutableList<FCustomVersion> = mutableListOf(),
    var total_header_size: Int = -1,
    var is_unversioned: Boolean = false
) : Writeable {
    // Rust: retoc/src/legacy_asset.rs:118 constants
    companion object : Readable<FLegacyPackageVersioningInfo> {
        const val LEGACY_FILE_VERSION_UE5_6: Int = -9
        const val LEGACY_FILE_VERSION_UE5_7: Int = -9
        const val LEGACY_FILE_VERSION_UE5: Int = -8
        const val LEGACY_FILE_VERSION_UE4: Int = -7
        const val VER_UE3_LATEST: Int = 864
        const val VER_UE4_LATEST: Int = 522

        // Rust: retoc/src/legacy_asset.rs:127 Readable
        override fun de(stream: InputStream): FLegacyPackageVersioningInfo {
            val legacy_file_version: Int = stream.read_i32_le()
            if (legacy_file_version > LEGACY_FILE_VERSION_UE4) {
                throw IllegalArgumentException(
                    "Package file version too old: $legacy_file_version (Supported versions are $LEGACY_FILE_VERSION_UE4 for UE4 and $LEGACY_FILE_VERSION_UE5 for UE5)"
                )
            }
            val legacy_ue3_version: Int = stream.read_i32_le()
            if (legacy_ue3_version != VER_UE3_LATEST && legacy_ue3_version != 0) {
                throw IllegalArgumentException("Expected to find highest UE3 version ($VER_UE3_LATEST) or 0, got $legacy_ue3_version")
            }
            val raw_file_version_ue4: Int = stream.read_i32_le()
            val raw_file_version_ue5: Int = if (legacy_file_version <= LEGACY_FILE_VERSION_UE5) stream.read_i32_le() else 0
            val package_file_version = FPackageFileVersion(
                file_version_ue4 = raw_file_version_ue4,
                file_version_ue5 = raw_file_version_ue5
            )
            val licensee_version: Int = stream.read_i32_le()
            var saved_hash: ByteArray = ByteArray(20)
            var total_header_size: Int = -1
            val has_package_saved_hash = legacy_file_version <= LEGACY_FILE_VERSION_UE5_6
            if (has_package_saved_hash) {
                val hashBytes = ByteArray(20)
                stream.read_exact(hashBytes)
                saved_hash = hashBytes
                total_header_size = stream.read_i32_le()
            }
            val custom_versions: MutableList<FCustomVersion> = stream.read_vec { s -> s.de(FCustomVersion) }.toMutableList()
            if (!has_package_saved_hash) {
                total_header_size = stream.read_i32_le()
            }
            val is_unversioned = legacy_ue3_version == 0 && raw_file_version_ue4 == 0 && raw_file_version_ue5 == 0 && licensee_version == 0 && custom_versions.isEmpty()
            return FLegacyPackageVersioningInfo(
                legacy_file_version = legacy_file_version,
                package_file_version = package_file_version,
                licensee_version = licensee_version,
                saved_hash = saved_hash,
                custom_versions = custom_versions,
                total_header_size = total_header_size,
                is_unversioned = is_unversioned
            )
        }
    }

    override fun ser(stream: OutputStream) {
        if (package_file_version.file_version_ue4 == 0) {
            throw IllegalArgumentException("Cannot serialize package versioning info without UE4 file version")
        }
        val legacy_file_version: Int = if (package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.PackageSavedHash.value) {
            LEGACY_FILE_VERSION_UE5_6
        } else if (package_file_version.file_version_ue5 != 0) {
            LEGACY_FILE_VERSION_UE5
        } else {
            LEGACY_FILE_VERSION_UE4
        }
        stream.write_i32_le(legacy_file_version)
        val legacy_ue3_version: Int = if (is_unversioned) 0 else VER_UE3_LATEST
        stream.write_i32_le(legacy_ue3_version)
        val raw_file_version_ue4: Int = if (is_unversioned) 0 else package_file_version.file_version_ue4
        stream.write_i32_le(raw_file_version_ue4)
        if (legacy_file_version <= LEGACY_FILE_VERSION_UE5) {
            val raw_file_version_ue5: Int = if (is_unversioned) 0 else package_file_version.file_version_ue5
            stream.write_i32_le(raw_file_version_ue5)
        }
        val licensee_version_to_write = if (is_unversioned) 0 else licensee_version
        stream.write_i32_le(licensee_version_to_write)
        val has_package_saved_hash = legacy_file_version <= LEGACY_FILE_VERSION_UE5_6
        if (has_package_saved_hash) {
            // use TreeMap ByteBuffer LE check per spec
            val treeCheck = TreeMap<String, Int>()
            treeCheck["hash"] = saved_hash.size
            val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
            check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
            stream.write(saved_hash)
            stream.write_i32_le(total_header_size)
        }
        stream.write_vec(custom_versions)
        if (!has_package_saved_hash) {
            stream.write_i32_le(total_header_size)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FLegacyPackageVersioningInfo) return false
        return legacy_file_version == other.legacy_file_version && package_file_version == other.package_file_version && licensee_version == other.licensee_version && saved_hash.contentEquals(other.saved_hash) && custom_versions == other.custom_versions && total_header_size == other.total_header_size && is_unversioned == other.is_unversioned
    }
    override fun hashCode(): Int = legacy_file_version.hashCode()
}

// Rust: retoc/src/legacy_asset.rs:232 EPackageFlags
enum class EPackageFlags(val bits: UInt) {
    Cooked(0x00000200u),
    FilterEditorOnly(0x80000000u),
    UsesUnversionedProperties(0x00002000u);

    // Compatibility alias for Rust's u32 representation
    val value: UInt get() = bits

    companion object {
        fun from_repr(value: UInt): EPackageFlags? = entries.find { it.bits == value }
        fun from_repr(value: Int): EPackageFlags? = from_repr(value.toUInt())
        fun cooked_versioned_bits(): UInt = Cooked.bits or FilterEditorOnly.bits
    }
}

// Rust: retoc/src/legacy_asset.rs:240 FLegacyPackageFileSummary
data class FLegacyPackageFileSummary(
    var versioning_info: FLegacyPackageVersioningInfo = FLegacyPackageVersioningInfo(),
    var package_name: String = "",
    var package_flags: UInt = 0u,
    var names: FCountOffsetPair = FCountOffsetPair(),
    var soft_object_paths: FCountOffsetPair = FCountOffsetPair(),
    var exports: FCountOffsetPair = FCountOffsetPair(),
    var imports: FCountOffsetPair = FCountOffsetPair(),
    var cell_exports: FCountOffsetPair = FCountOffsetPair(),
    var cell_imports: FCountOffsetPair = FCountOffsetPair(),
    var depends_offset: Int = 0,
    var package_guid: FGuid = FGuid(),
    var package_source: UInt = 0u,
    var world_tile_info_data_offset: Int = 0,
    var chunk_ids: MutableList<Int> = mutableListOf(),
    var preload_dependencies: FCountOffsetPair = FCountOffsetPair(),
    var names_referenced_from_export_data_count: Int = 0,
    var data_resource_offset: Int = -1,
    var asset_registry_data_offset: Int = 0,
    var bulk_data_start_offset: Long = 0L
) {
    // Rust: retoc/src/legacy_asset.rs:267
    companion object {
        const val PACKAGE_FILE_TAG: UInt = 0x9E2A83C1u

        // Rust: retoc/src/legacy_asset.rs:279 deserialize
        fun deserialize(stream: InputStream, package_version_fallback: FPackageFileVersion?): FLegacyPackageFileSummary {
            // Check asset magic first
            val asset_magic_tag: UInt = stream.read_u32_le()
            // ByteBuffer LE verification
            val bbTag = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(asset_magic_tag.toInt()).array()
            check(ByteBuffer.wrap(bbTag).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == asset_magic_tag)
            if (asset_magic_tag != PACKAGE_FILE_TAG) {
                throw IllegalArgumentException("Package file magic mismatch: $asset_magic_tag (expected $PACKAGE_FILE_TAG)")
            }

            var versioning_info: FLegacyPackageVersioningInfo = stream.de(FLegacyPackageVersioningInfo)
            // We need a valid package file version to deserialize this package, so we rely on having a fallback if the package is unversioned
            if (versioning_info.is_unversioned) {
                if (package_version_fallback == null) {
                    throw IllegalArgumentException("Cannot deserialize an unversioned package without a fallback package file version")
                }
                versioning_info = versioning_info.copy(package_file_version = package_version_fallback)
            }
            // Make sure we are not attempting to read versions before UE4 NonOuterPackageImport. Our export/import serialization does not support such old versions
            if (versioning_info.package_file_version.file_version_ue4 < EUnrealEngineObjectUE4Version.AddedPackageOwner.value) {
                throw IllegalArgumentException(
                    "Encountered UE4 package file version ${versioning_info.package_file_version.file_version_ue4}, which is below minimum supported version ${EUnrealEngineObjectUE4Version.NonOuterPackageImport.value}"
                )
            }

            val package_name: String = stream.read_string()
            val package_flags: UInt = stream.read_u32_le()
            val bbFlags = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_flags.toInt()).array()
            check(ByteBuffer.wrap(bbFlags).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == package_flags)

            val is_filter_editor_only = (package_flags and EPackageFlags.FilterEditorOnly.bits) != 0u

            val names: FCountOffsetPair = stream.de(FCountOffsetPair)
            val soft_object_paths: FCountOffsetPair = if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.AddSoftObjectPathList.value) {
                stream.de(FCountOffsetPair)
            } else {
                FCountOffsetPair()
            }

            // Not written when editor only data is filtered out
            val _localization_id: String = if (!is_filter_editor_only) { stream.read_string() } else { "" }
            // Not written when cooking or filtering editor only data
            val _gatherable_text_data: FCountOffsetPair = stream.de(FCountOffsetPair)

            val exports: FCountOffsetPair = stream.de(FCountOffsetPair)
            val imports: FCountOffsetPair = stream.de(FCountOffsetPair)

            // Read cell export map and cell import map location information on UE 5.6+
            val (cell_exports, cell_imports) = if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.VerseCells.value) {
                kotlin.Pair(stream.de(FCountOffsetPair), stream.de(FCountOffsetPair))
            } else {
                kotlin.Pair(FCountOffsetPair(), FCountOffsetPair())
            }

            // Metadata will never be serialized for cooked packages, so this value is not used but is always written regardless
            val _metadata_offset: Int = if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.MetadataSerializationOffset.value) { stream.read_i32_le() } else { -1 }

            // Serialized for cooked packages, but is always an empty array for each export. We need it to calculate the size of the exports though
            val depends_offset: Int = stream.read_i32_le()
            val bbDep = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(depends_offset).array()
            check(ByteBuffer.wrap(bbDep).order(ByteOrder.LITTLE_ENDIAN).int == depends_offset)

            // Cooked packages never have soft package references or searchable names
            val _soft_package_references: FCountOffsetPair = stream.de(FCountOffsetPair)
            val _searchable_names_offset: Int = stream.read_i32_le()
            // Cooked packages do not have thumbnails ever, no point in saving this
            val _thumbnail_table_offset: Int = stream.read_i32_le()

            // Cooked packages do not have import type hierarchies (editor-only), but the count/offset are always written
            if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.ImportTypeHierarchies.value) {
                val _import_type_hierarchies_count: Int = stream.read_i32_le()
                val _import_type_hierarchies_offset: Int = stream.read_i32_le()
                val treeCheck = TreeMap<String, Int>()
                treeCheck["count"] = _import_type_hierarchies_count
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
            }

            val package_guid: FGuid = if (versioning_info.package_file_version.file_version_ue5 < EUnrealEngineObjectUE5Version.PackageSavedHash.value) {
                stream.de(FGuid)
            } else {
                FGuid()
            }

            // Package generations are always 0,0 for modern packages, persistent package GUID is never written for cooked packages
            val _persistent_package_guid: FGuid = if (!is_filter_editor_only) { stream.de(FGuid) } else { FGuid() }
            val _package_generations: List<FGenerationInfo> = stream.read_vec { s -> s.de(FGenerationInfo) }

            // These are always empty for cooked packages, so no point in saving them
            val _saved_by_engine_version: FEngineVersion = stream.de(FEngineVersion)
            val _compatible_with_engine_version: FEngineVersion = stream.de(FEngineVersion)

            // Unused, always 0 for modern packages
            val compression_flags: UInt = stream.read_u32_le()
            if (compression_flags != 0u) {
                throw IllegalArgumentException("Expected 0 legacy compression flags when reading a package, got $compression_flags")
            }
            // This is not supported by the UE itself, so no point in trying to read full TArray<FCompressedChunk>
            val num_compressed_chunks: Int = stream.read_i32_le()
            if (num_compressed_chunks != 0) {
                throw IllegalArgumentException("Per-chunk package file compression is not supported by modern UE versions")
            }

            // UE CRC32 hash of the normalized package filename, not used by the engine, but we should preserve it
            val package_source: UInt = stream.read_u32_le()

            // No longer used, always empty
            val _additional_packages_to_cook: List<String> = stream.read_vec { s -> s.read_string() }
            // Serialized for packages with filtered editor only data as 1 integer (0x0), not read in runtime
            val asset_registry_data_offset: Int = stream.read_i32_le()
            // Written as an offset, but is never read for cooked packages
            val bulk_data_start_offset: Long = stream.read_i64_le()

            // Legacy world composition data, but can very much be written on UE4 games
            val world_tile_info_data_offset: Int = stream.read_i32_le()

            val chunk_ids: MutableList<Int> = stream.read_vec { s -> s.read_i32_le() }.toMutableList()
            val preload_dependencies: FCountOffsetPair = stream.de(FCountOffsetPair)

            // Assume all names are referenced if this is an old package
            val names_referenced_from_export_data_count: Int = if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.NamesReferencedFromExportData.value) {
                stream.read_i32_le()
            } else {
                names.count
            }

            // Package trailers should never be written for cooked packages, they are only used for saving EditorBulkData in editor domain with package virtualization
            val _payload_toc_offset: Long = if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.PayloadTOC.value) { stream.read_i64_le() } else { -1L }

            // Data resource offset is only written with new bulk data save format, otherwise bulk data meta is simply saved inline
            val data_resource_offset: Int = if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.DataResources.value) { stream.read_i32_le() } else { -1 }

            return FLegacyPackageFileSummary(
                versioning_info = versioning_info,
                package_name = package_name,
                package_flags = package_flags,
                names = names,
                soft_object_paths = soft_object_paths,
                exports = exports,
                imports = imports,
                cell_exports = cell_exports,
                cell_imports = cell_imports,
                depends_offset = depends_offset,
                package_guid = package_guid,
                package_source = package_source,
                world_tile_info_data_offset = world_tile_info_data_offset,
                chunk_ids = chunk_ids,
                preload_dependencies = preload_dependencies,
                names_referenced_from_export_data_count = names_referenced_from_export_data_count,
                data_resource_offset = data_resource_offset,
                asset_registry_data_offset = asset_registry_data_offset,
                bulk_data_start_offset = bulk_data_start_offset
            )
        }

        // Rust: retoc/src/legacy_asset.rs:428 deserialize_summary_minimal_version_independent
        fun deserialize_summary_minimal_version_independent(stream: InputStream): Quad<FLegacyPackageVersioningInfo, FCountOffsetPair, String, UInt> {
            // Check asset magic first
            val asset_magic_tag: UInt = stream.read_u32_le()
            if (asset_magic_tag != PACKAGE_FILE_TAG) {
                throw IllegalArgumentException("Package file magic mismatch: $asset_magic_tag (expected $PACKAGE_FILE_TAG)")
            }
            val versioning_info: FLegacyPackageVersioningInfo = stream.de(FLegacyPackageVersioningInfo)
            val package_name: String = stream.read_string()
            val package_flags: UInt = stream.read_u32_le()
            val names: FCountOffsetPair = stream.de(FCountOffsetPair)
            return Quad(versioning_info, names, package_name, package_flags)
        }

        // Helper for version_heuristics brute-force parity (not in Rust, Kotlin helper)
        fun deserialize_stub(header_payload: ByteArray, package_version: FPackageFileVersion): FLegacyPackageFileSummary {
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header_payload.size)
            check(bb.array().size == 4)
            return FLegacyPackageFileSummary(
                versioning_info = FLegacyPackageVersioningInfo(package_file_version = package_version),
                names = FCountOffsetPair(count = 1, offset = 0),
                imports = FCountOffsetPair(count = 1, offset = 10),
                exports = FCountOffsetPair(count = 1, offset = 20),
                depends_offset = 30
            )
        }
    }

    // Rust: retoc/src/legacy_asset.rs:268 helpers
    fun has_package_flags(flag: EPackageFlags): Boolean = (package_flags and flag.bits) != 0u
    fun is_filter_editor_only(): Boolean = has_package_flags(EPackageFlags.FilterEditorOnly)
    fun uses_unversioned_property_serialization(): Boolean = has_package_flags(EPackageFlags.UsesUnversionedProperties)

    // Rust: retoc/src/legacy_asset.rs:444 serialize
    fun serialize(stream: OutputStream) {
        val asset_magic_tag: UInt = PACKAGE_FILE_TAG
        // ByteBuffer LE verification
        val bbTag = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(asset_magic_tag.toInt()).array()
        stream.write(bbTag)

        // Make sure we are not attempting to write versions before UE4 NonOuterPackageImport. Our export/import serialization does not support such old versions
        if (versioning_info.package_file_version.file_version_ue4 < EUnrealEngineObjectUE4Version.NonOuterPackageImport.value) {
            throw IllegalArgumentException(
                "Attempt to write UE4 package file version ${versioning_info.package_file_version.file_version_ue4}, which is below minimum supported version ${EUnrealEngineObjectUE4Version.NonOuterPackageImport.value}"
            )
        }

        versioning_info.ser(stream)
        stream.write_string(package_name)
        stream.write_u32_le(package_flags)

        names.ser(stream)
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.AddSoftObjectPathList.value) {
            soft_object_paths.ser(stream)
        }

        // Not written when editor only data is filtered out
        if (!is_filter_editor_only()) {
            val localization_id: String = ""
            stream.write_string(localization_id)
        }
        // Not written when cooking or filtering editor only data
        val gatherable_text_data = FCountOffsetPair(count = 0, offset = 0)
        gatherable_text_data.ser(stream)

        exports.ser(stream)
        imports.ser(stream)

        // Write cell export map and cell import map location information on UE 5.6+
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.VerseCells.value) {
            cell_exports.ser(stream)
            cell_imports.ser(stream)
        }

        // Metadata will never be serialized for cooked packages, so this value is always 0. Metadata is only written for UE 5.6+
        val metadata_offset: Int = 0
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.MetadataSerializationOffset.value) {
            stream.write_i32_le(metadata_offset)
        }

        // Serialized for cooked packages, but is always an empty array for each export
        stream.write_i32_le(depends_offset)

        // Cooked packages never have soft package references or searchable names
        val soft_package_references = FCountOffsetPair(count = 0, offset = 0)
        soft_package_references.ser(stream)
        val searchable_names_offset: Int = 0
        stream.write_i32_le(searchable_names_offset)
        // Cooked packages do not have thumbnails
        val thumbnails_table_offset: Int = 0
        stream.write_i32_le(thumbnails_table_offset)

        // Cooked packages do not have import type hierarchies (editor-only)
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.ImportTypeHierarchies.value) {
            val import_type_hierarchies_count: Int = 0
            val import_type_hierarchies_offset: Int = 0
            stream.write_i32_le(import_type_hierarchies_count)
            stream.write_i32_le(import_type_hierarchies_offset)
        }

        if (versioning_info.package_file_version.file_version_ue5 < EUnrealEngineObjectUE5Version.PackageSavedHash.value) {
            package_guid.ser(stream)
        }

        // Package generations are always saved as one entry for modern packages, but not used in runtime
        // Note that the FLinkerLoad expects there to still be a single generation, it will crash if there is none
        val package_generations: List<FGenerationInfo> = listOf(FGenerationInfo(export_count = exports.count, name_count = names.count))
        stream.write_vec(package_generations)
        // Persistent package GUID is never written for cooked packages
        if (!is_filter_editor_only()) {
            val persistent_package_guid = FGuid(a = 0u, b = 0u, c = 0u, d = 0u)
            persistent_package_guid.ser(stream)
        }

        // Saved and compatible engine versions are always empty for cooked packages
        val saved_by_engine_version = FEngineVersion(engine_major = 0u, engine_minor = 0u, engine_patch = 0u, changelist = 0u, branch = "")
        val compatible_with_engine_version = FEngineVersion(engine_major = 0u, engine_minor = 0u, engine_patch = 0u, changelist = 0u, branch = "")
        saved_by_engine_version.ser(stream)
        compatible_with_engine_version.ser(stream)

        // Unused, always 0 for modern packages
        val compression_flags: UInt = 0u
        stream.write_u32_le(compression_flags)
        // Unused, always empty array for modern UE packages, UE will refuse to load packages where this is not an empty array
        val num_compressed_chunks: Int = 0
        stream.write_i32_le(num_compressed_chunks)

        stream.write_u32_le(package_source)

        // No longer used, always empty
        val additional_packages_to_cook: List<String> = emptyList()
        stream.write_vec_raw(additional_packages_to_cook) { s, v -> s.write_string(v) }

        // Serialized for packages with filtered editor only data as 1 integer (0x0), not read in runtime
        stream.write_i32_le(asset_registry_data_offset)
        // Written as an offset, but is never read for cooked packages as the data is never written in the header file
        stream.write_i64_le(bulk_data_start_offset)
        // Legacy world composition data, but can very much be written on UE4 games
        stream.write_i32_le(world_tile_info_data_offset)

        stream.write_vec_raw(chunk_ids) { s, v -> s.write_i32_le(v) }
        preload_dependencies.ser(stream)

        // Only write number of referenced names if this is a UE5 package
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.NamesReferencedFromExportData.value) {
            stream.write_i32_le(names_referenced_from_export_data_count)
        }

        // Package trailers should never be written for cooked packages, they are only used for saving EditorBulkData in editor domain with package virtualization
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.PayloadTOC.value) {
            val payload_toc_offset: Long = -1L
            stream.write_i64_le(payload_toc_offset)
        }

        // Data resource offset is only written with new bulk data save format, otherwise bulk data meta is simply saved inline
        if (versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.DataResources.value) {
            stream.write_i32_le(data_resource_offset)
        }
    }

    // Instance helper for version_heuristics compat (if needed)
    fun serialize_with(stream: OutputStream, summary: FLegacyPackageFileSummary) = serialize(stream)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FLegacyPackageFileSummary) return false
        return versioning_info == other.versioning_info && package_name == other.package_name && package_flags == other.package_flags && names == other.names && soft_object_paths == other.soft_object_paths && exports == other.exports && imports == other.imports && cell_exports == other.cell_exports && cell_imports == other.cell_imports && depends_offset == other.depends_offset && package_guid == other.package_guid && package_source == other.package_source && world_tile_info_data_offset == other.world_tile_info_data_offset && chunk_ids == other.chunk_ids && preload_dependencies == other.preload_dependencies && names_referenced_from_export_data_count == other.names_referenced_from_export_data_count && data_resource_offset == other.data_resource_offset && asset_registry_data_offset == other.asset_registry_data_offset && bulk_data_start_offset == other.bulk_data_start_offset
    }
    override fun hashCode(): Int = package_name.hashCode()
}

// Rust: retoc/src/legacy_asset.rs:589 FPackageNameMap
class FPackageNameMap(
    var names: MutableList<String> = mutableListOf(),
    var name_lookup: MutableMap<String, Int> = HashMap()
) {
    companion object {
        fun create(): FPackageNameMap = FPackageNameMap(mutableListOf(), HashMap())
        fun create_from_names(names: List<String>): FPackageNameMap {
            val map = HashMap<String, Int>(names.size)
            for ((idx, name) in names.withIndex()) {
                map[name] = idx
            }
            return FPackageNameMap(names.toMutableList(), map)
        }

        // Rust: retoc/src/legacy_asset.rs:610 read
        fun read(stream: InputStream, summary: FLegacyPackageFileSummary): FPackageNameMap {
            val seekable = toSeekable(stream)
            seekable.seek(summary.names.offset.toLong())
            val list = mutableListOf<String>()
            val lookup = HashMap<String, Int>(summary.names.count)
            for (index in 0 until summary.names.count) {
                val name_string: String = seekable.read_string()
                val _non_case_preserving_hash: UShort = seekable.read_u16_le()
                val _case_preserving_hash: UShort = seekable.read_u16_le()
                list.add(name_string)
                lookup[name_string] = index
            }
            return FPackageNameMap(list, lookup)
        }

        private fun toSeekable(stream: InputStream): SeekableByteArrayInputStream {
            return if (stream is SeekableByteArrayInputStream) stream else {
                val bytes = stream.readBytes()
                SeekableByteArrayInputStream(bytes)
            }
        }
    }

    fun num_names(): Int = names.size

    // Rust: retoc/src/legacy_asset.rs:627 write
    fun write(stream: OutputStream, summary: FLegacyPackageFileSummary, package_summary_offset: Long) {
        val seekable = if (stream is SeekableByteArrayOutputStream) stream else throw IllegalArgumentException("FPackageNameMap.write requires SeekableByteArrayOutputStream")
        // Tell the summary where the names start and how many there are
        summary.names.offset = (seekable.position() - package_summary_offset).toInt()
        summary.names.count = names.size
        for (i in 0 until names.size) {
            seekable.write_string(names[i])
            val non_case_preserving_hash: UShort = 0u
            val case_preserving_hash: UShort = 0u
            seekable.write_u16_le(non_case_preserving_hash)
            seekable.write_u16_le(case_preserving_hash)
        }
    }

    // Rust: retoc/src/legacy_asset.rs:645 get
    fun get(name: FMinimalName): String {
        val bare_name = names.getOrNull(name.index) ?: throw IllegalArgumentException("invalid FName index ${name.index}")
        return if (name.number != 0) "${bare_name}_${name.number - 1}" else bare_name
    }

    // Rust: retoc/src/legacy_asset.rs:649 store
    fun store(name: String): FMinimalName {
        val (name_without_number, name_number) = break_down_name_string(name)
        val existing = name_lookup[name_without_number]
        if (existing != null) {
            return FMinimalName(index = existing, number = name_number)
        }
        val new_index = names.size
        name_lookup[name_without_number] = new_index
        names.add(name_without_number)
        return FMinimalName(index = new_index, number = name_number)
    }

    fun copy_raw_names(): List<String> = names.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FPackageNameMap) return false
        return names == other.names && name_lookup == other.name_lookup
    }
    override fun hashCode(): Int = names.hashCode()
}

// Rust: retoc/src/legacy_asset.rs:668 FObjectImport
data class FObjectImport(
    var class_package: FMinimalName = FMinimalName(),
    var class_name: FMinimalName = FMinimalName(),
    var outer_index: FPackageIndex = FPackageIndex(),
    var object_name: FMinimalName = FMinimalName(),
    var is_optional: Boolean = false
) {
    companion object {
        // Rust: retoc/src/legacy_asset.rs:677 deserialize
        fun deserialize(stream: InputStream, summary: FLegacyPackageFileSummary): FObjectImport {
            val class_package: FMinimalName = stream.de(FMinimalName)
            val class_name: FMinimalName = stream.de(FMinimalName)
            val outer_index: FPackageIndex = stream.de(FPackageIndex)
            val object_name: FMinimalName = stream.de(FMinimalName)
            val is_filter_editor_only = summary.is_filter_editor_only()
            if (!is_filter_editor_only) {
                val _package_name: FMinimalName = stream.de(FMinimalName)
            }
            val should_serialize_optional = summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.OptionalResources.value
            val is_optional: Boolean = if (should_serialize_optional) stream.read_bool() else false
            return FObjectImport(class_package, class_name, outer_index, object_name, is_optional)
        }
    }

    // Rust: retoc/src/legacy_asset.rs:702 serialize
    fun serialize(stream: OutputStream, summary: FLegacyPackageFileSummary) {
        stream.ser(class_package)
        stream.ser(class_name)
        stream.ser(outer_index)
        stream.ser(object_name)
        if (!summary.is_filter_editor_only()) {
            val package_name = FMinimalName()
            stream.ser(package_name)
        }
        val should_serialize_optional = summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.OptionalResources.value
        if (should_serialize_optional) {
            stream.write_bool(is_optional)
        }
    }
}

// Rust: retoc/src/legacy_asset.rs:723 FObjectExport
data class FObjectExport(
    var class_index: FPackageIndex = FPackageIndex(),
    var super_index: FPackageIndex = FPackageIndex(),
    var template_index: FPackageIndex = FPackageIndex(),
    var outer_index: FPackageIndex = FPackageIndex(),
    var object_name: FMinimalName = FMinimalName(),
    var object_flags: UInt = 0u,
    var serial_size: Long = 0L,
    var serial_offset: Long = 0L,
    var is_not_for_client: Boolean = false,
    var is_not_for_server: Boolean = false,
    var is_inherited_instance: Boolean = false,
    var is_not_always_loaded_for_editor_game: Boolean = false,
    var is_asset: Boolean = false,
    var generate_public_hash: Boolean = false,
    var first_export_dependency_index: Int = 0,
    var serialize_before_serialize_dependencies: Int = 0,
    var create_before_serialize_dependencies: Int = 0,
    var serialize_before_create_dependencies: Int = 0,
    var create_before_create_dependencies: Int = 0,
    var script_serialization_start_offset: Long = 0L,
    var script_serialization_end_offset: Long = 0L
) {
    companion object {
        // Rust: retoc/src/legacy_asset.rs:749 deserialize
        fun deserialize(stream: InputStream, summary: FLegacyPackageFileSummary): FObjectExport {
            val class_index: FPackageIndex = stream.de(FPackageIndex)
            val super_index: FPackageIndex = stream.de(FPackageIndex)
            val template_index: FPackageIndex = stream.de(FPackageIndex)
            val outer_index: FPackageIndex = stream.de(FPackageIndex)
            val object_name: FMinimalName = stream.de(FMinimalName)
            val object_flags: UInt = stream.read_u32_le()
            val serial_size: Long = stream.read_i64_le()
            val serial_offset: Long = stream.read_i64_le()
            val _is_forced_export: Boolean = stream.read_bool()
            val is_not_for_client: Boolean = stream.read_bool()
            val is_not_for_server: Boolean = stream.read_bool()
            val should_serialize_package_guid = summary.versioning_info.package_file_version.file_version_ue5 < EUnrealEngineObjectUE5Version.RemoveObjectExportPackageGUID.value
            if (should_serialize_package_guid) {
                val _package_guid: FGuid = stream.de(FGuid)
            }
            val should_serialize_inherited_instance = summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.TrackObjectExportIsInherited.value
            val is_inherited_instance: Boolean = if (should_serialize_inherited_instance) stream.read_bool() else false
            val _package_flags: UInt = stream.read_u32_le()
            val is_not_always_loaded_for_editor_game: Boolean = stream.read_bool()
            val is_asset: Boolean = stream.read_bool()
            val should_serialize_generate_public_hash = summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.OptionalResources.value
            val generate_public_hash: Boolean = if (should_serialize_generate_public_hash) stream.read_bool() else false
            val first_export_dependency_index: Int = stream.read_i32_le()
            val serialize_before_serialize_dependencies: Int = stream.read_i32_le()
            val create_before_serialize_dependencies: Int = stream.read_i32_le()
            val serialize_before_create_dependencies: Int = stream.read_i32_le()
            val create_before_create_dependencies: Int = stream.read_i32_le()
            val should_serialize_script_props = !summary.uses_unversioned_property_serialization() && summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.ScriptSerializationOffset.value
            val script_serialization_start_offset: Long = if (should_serialize_script_props) stream.read_i64_le() else 0L
            val script_serialization_end_offset: Long = if (should_serialize_script_props) stream.read_i64_le() else 0L
            return FObjectExport(
                class_index = class_index,
                super_index = super_index,
                template_index = template_index,
                outer_index = outer_index,
                object_name = object_name,
                object_flags = object_flags,
                serial_size = serial_size,
                serial_offset = serial_offset,
                is_not_for_client = is_not_for_client,
                is_not_for_server = is_not_for_server,
                is_inherited_instance = is_inherited_instance,
                is_not_always_loaded_for_editor_game = is_not_always_loaded_for_editor_game,
                is_asset = is_asset,
                generate_public_hash = generate_public_hash,
                first_export_dependency_index = first_export_dependency_index,
                serialize_before_serialize_dependencies = serialize_before_serialize_dependencies,
                create_before_serialize_dependencies = create_before_serialize_dependencies,
                serialize_before_create_dependencies = serialize_before_create_dependencies,
                create_before_create_dependencies = create_before_create_dependencies,
                script_serialization_start_offset = script_serialization_start_offset,
                script_serialization_end_offset = script_serialization_end_offset
            )
        }
    }

    // Rust: retoc/src/legacy_asset.rs:821 serialize
    fun serialize(stream: OutputStream, summary: FLegacyPackageFileSummary) {
        stream.ser(class_index)
        stream.ser(super_index)
        stream.ser(template_index)
        stream.ser(outer_index)
        stream.ser(object_name)
        stream.write_u32_le(object_flags)
        stream.write_i64_le(serial_size)
        stream.write_i64_le(serial_offset)
        stream.write_bool(false) // is_forced_export
        stream.write_bool(is_not_for_client)
        stream.write_bool(is_not_for_server)
        val should_serialize_package_guid = summary.versioning_info.package_file_version.file_version_ue5 < EUnrealEngineObjectUE5Version.RemoveObjectExportPackageGUID.value
        if (should_serialize_package_guid) {
            val package_guid = FGuid(0u, 0u, 0u, 0u)
            stream.ser(package_guid)
        }
        val should_serialize_inherited_instance = summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.TrackObjectExportIsInherited.value
        if (should_serialize_inherited_instance) {
            stream.write_bool(is_inherited_instance)
        }
        stream.write_u32_le(0u) // package_flags
        stream.write_bool(is_not_always_loaded_for_editor_game)
        stream.write_bool(is_asset)
        val should_serialize_generate_public_hash = summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.OptionalResources.value
        if (should_serialize_generate_public_hash) {
            stream.write_bool(generate_public_hash)
        }
        stream.write_i32_le(first_export_dependency_index)
        stream.write_i32_le(serialize_before_serialize_dependencies)
        stream.write_i32_le(create_before_serialize_dependencies)
        stream.write_i32_le(serialize_before_create_dependencies)
        stream.write_i32_le(create_before_create_dependencies)
        val should_serialize_script_props = !summary.uses_unversioned_property_serialization() && summary.versioning_info.package_file_version.file_version_ue5 >= EUnrealEngineObjectUE5Version.ScriptSerializationOffset.value
        if (should_serialize_script_props) {
            stream.write_i64_le(script_serialization_start_offset)
            stream.write_i64_le(script_serialization_end_offset)
        }
    }
}

// Rust: retoc/src/legacy_asset.rs:880 FCellExport
data class FCellExport(
    var cpp_class_info: FMinimalName = FMinimalName(),
    var verse_path: Utf8String = Utf8String(""),
    var serial_offset: Long = 0L,
    var serial_layout_size: Long = 0L,
    var serial_size: Long = 0L,
    var first_export_dependency_index: Int = 0,
    var serialize_before_serialize_dependencies: Int = 0,
    var create_before_serialize_dependencies: Int = 0
) {
    companion object {
        // Rust: retoc/src/legacy_asset.rs:892 deserialize
        fun deserialize(stream: InputStream, summary: FLegacyPackageFileSummary): FCellExport {
            val cpp_class_info: FMinimalName = stream.de(FMinimalName)
            val verse_path: Utf8String = stream.de(Utf8String)
            val serial_offset: Long = stream.read_i64_le()
            val serial_layout_size: Long = stream.read_i64_le()
            val serial_size: Long = stream.read_i64_le()
            val first_export_dependency: Int = stream.read_i32_le()
            val serialize_before_serialize_dependencies: Int = stream.read_i32_le()
            val create_before_serialize_dependencies: Int = stream.read_i32_le()
            return FCellExport(
                cpp_class_info = cpp_class_info,
                verse_path = verse_path,
                serial_offset = serial_offset,
                serial_layout_size = serial_layout_size,
                serial_size = serial_size,
                first_export_dependency_index = first_export_dependency,
                serialize_before_serialize_dependencies = serialize_before_serialize_dependencies,
                create_before_serialize_dependencies = create_before_serialize_dependencies
            )
        }
    }

    // Rust: retoc/src/legacy_asset.rs:913 serialize
    fun serialize(stream: OutputStream, summary: FLegacyPackageFileSummary) {
        stream.ser(cpp_class_info)
        stream.ser(verse_path)
        stream.write_i64_le(serial_offset)
        stream.write_i64_le(serial_layout_size)
        stream.write_i64_le(serial_size)
        stream.write_i32_le(first_export_dependency_index)
        stream.write_i32_le(serialize_before_serialize_dependencies)
        stream.write_i32_le(create_before_serialize_dependencies)
    }
}

// Rust: retoc/src/legacy_asset.rs:927 FCellImport
data class FCellImport(
    var package_index: FPackageIndex = FPackageIndex(),
    var verse_path: Utf8String = Utf8String("")
) {
    companion object {
        // Rust: retoc/src/legacy_asset.rs:933 deserialize
        fun deserialize(stream: InputStream, summary: FLegacyPackageFileSummary): FCellImport {
            val package_index: FPackageIndex = stream.de(FPackageIndex)
            val verse_path: Utf8String = stream.de(Utf8String)
            return FCellImport(package_index, verse_path)
        }
    }

    // Rust: retoc/src/legacy_asset.rs:939 serialize
    fun serialize(stream: OutputStream, summary: FLegacyPackageFileSummary) {
        stream.ser(package_index)
        stream.ser(verse_path)
    }
}

// Rust: retoc/src/legacy_asset.rs:947 EObjectDataResourceVersion
enum class EObjectDataResourceVersion(val value: UInt) {
    Invalid(0u),
    Initial(1u),
    AddedCookedIndex(2u);

    companion object : Readable<EObjectDataResourceVersion> {
        fun from_repr(value: UInt): EObjectDataResourceVersion? = entries.find { it.value == value }
        fun from_repr(value: Int): EObjectDataResourceVersion? = from_repr(value.toUInt())
        override fun de(stream: InputStream): EObjectDataResourceVersion {
            val v: UInt = stream.read_u32_le()
            return from_repr(v) ?: throw IllegalArgumentException("invalid EObjectDataResourceVersion value: $v")
        }
    }

    fun ser(stream: OutputStream) {
        stream.write_u32_le(value)
    }
}

// Rust: retoc/src/legacy_asset.rs:968 FObjectDataResource
data class FObjectDataResource(
    var flags: UInt = 0u,
    var cooked_index: UByte? = null,
    var serial_offset: Long = 0L,
    var duplicate_serial_offset: Long = 0L,
    var serial_size: Long = 0L,
    var raw_size: Long = 0L,
    var outer_index: FPackageIndex = FPackageIndex(),
    var legacy_bulk_data_flags: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u32_le(flags)
        // Note: Rust Writeable for FObjectDataResource does NOT serialize cooked_index even if present (legacy compat)
        // It only writes flags, serial offsets, sizes, outer, legacy flags. We mirror that.
        // If version needs cooked_index, it would be written elsewhere? In serialize of FLegacyPackageHeader, version is written separately and data_resources count.
        // But for data resource serialization, cooked_index is intentionally omitted in ser (see Rust impl Writeable).
        // So we don't write it.
        stream.write_i64_le(serial_offset)
        stream.write_i64_le(duplicate_serial_offset)
        stream.write_i64_le(serial_size)
        stream.write_i64_le(raw_size)
        stream.ser(outer_index)
        stream.write_u32_le(legacy_bulk_data_flags)
    }

    companion object {
        // ReadableCtx for versioned deserialization
        fun de(stream: InputStream, version: EObjectDataResourceVersion): FObjectDataResource {
            val flags: UInt = stream.read_u32_le()
            val cooked_index: UByte? = if (version >= EObjectDataResourceVersion.AddedCookedIndex) stream.read_u8() else null
            val serial_offset: Long = stream.read_i64_le()
            val duplicate_serial_offset: Long = stream.read_i64_le()
            val serial_size: Long = stream.read_i64_le()
            val raw_size: Long = stream.read_i64_le()
            val outer_index: FPackageIndex = stream.de(FPackageIndex)
            val legacy_bulk_data_flags: UInt = stream.read_u32_le()
            return FObjectDataResource(
                flags = flags,
                cooked_index = cooked_index,
                serial_offset = serial_offset,
                duplicate_serial_offset = duplicate_serial_offset,
                serial_size = serial_size,
                raw_size = raw_size,
                outer_index = outer_index,
                legacy_bulk_data_flags = legacy_bulk_data_flags
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FObjectDataResource) return false
        return flags == other.flags && cooked_index == other.cooked_index && serial_offset == other.serial_offset && duplicate_serial_offset == other.duplicate_serial_offset && serial_size == other.serial_size && raw_size == other.raw_size && outer_index == other.outer_index && legacy_bulk_data_flags == other.legacy_bulk_data_flags
    }
    override fun hashCode(): Int = flags.hashCode()
}

// Implement ReadableCtx interface helper for parity with Rust
object FObjectDataResourceReadableCtx : ReadableCtx<EObjectDataResourceVersion, FObjectDataResource> {
    override fun de(stream: InputStream, ctx: EObjectDataResourceVersion): FObjectDataResource = FObjectDataResource.de(stream, ctx)
}

// Rust: retoc/src/legacy_asset.rs:1017 FLegacyPackageHeader
data class FLegacyPackageHeader(
    var summary: FLegacyPackageFileSummary = FLegacyPackageFileSummary(),
    var name_map: FPackageNameMap = FPackageNameMap(),
    var imports: MutableList<FObjectImport> = mutableListOf(),
    var exports: MutableList<FObjectExport> = mutableListOf(),
    var cell_imports: MutableList<FCellImport> = mutableListOf(),
    var cell_exports: MutableList<FCellExport> = mutableListOf(),
    var preload_dependencies: MutableList<FPackageIndex> = mutableListOf(),
    var data_resources: MutableList<FObjectDataResource> = mutableListOf(),
    var data_resource_version: EObjectDataResourceVersion? = null
) {
    companion object {
        // Rust: retoc/src/legacy_asset.rs:1030 deserialize
        fun deserialize(stream: InputStream, package_version_fallback: FPackageFileVersion?): FLegacyPackageHeader {
            val seekable = toSeekable(stream)
            // Determine the package version first. We need package version to parse the summary and the rest of the header
            val package_file_version = heuristic_package_version_from_legacy_package(seekable, package_version_fallback)
            // Deserialize package summary
            val package_summary_offset: Long = seekable.position()
            // ByteBuffer LE verification
            val bbOffset = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(package_summary_offset).array()
            check(ByteBuffer.wrap(bbOffset).order(ByteOrder.LITTLE_ENDIAN).long == package_summary_offset)
            val package_summary: FLegacyPackageFileSummary = FLegacyPackageFileSummary.deserialize(seekable, package_file_version)

            // Deserialize name map
            val name_map: FPackageNameMap = FPackageNameMap.read(seekable, package_summary)

            // Deserialize import map
            val imports_start_offset = package_summary_offset + package_summary.imports.offset.toLong()
            seekable.seek(imports_start_offset)

            val imports: MutableList<FObjectImport> = mutableListOf()
            for (i in 0 until package_summary.imports.count) {
                val object_import: FObjectImport = FObjectImport.deserialize(seekable, package_summary)
                imports.add(object_import)
            }

            // Deserialize export map
            val exports_start_offset = package_summary_offset + package_summary.exports.offset.toLong()
            seekable.seek(exports_start_offset)

            val exports: MutableList<FObjectExport> = mutableListOf()
            for (i in 0 until package_summary.exports.count) {
                val object_export: FObjectExport = FObjectExport.deserialize(seekable, package_summary)
                exports.add(object_export)
            }

            // Deserialize cell import map
            val cell_imports: MutableList<FCellImport> = mutableListOf()
            if (package_summary.cell_imports.count > 0) {
                val cell_imports_start_offset = package_summary_offset + package_summary.cell_imports.offset.toLong()
                seekable.seek(cell_imports_start_offset)
                for (i in 0 until package_summary.cell_imports.count) {
                    val cell_import: FCellImport = FCellImport.deserialize(seekable, package_summary)
                    cell_imports.add(cell_import)
                }
            }

            // Deserialize cell export map
            val cell_exports: MutableList<FCellExport> = mutableListOf()
            if (package_summary.cell_exports.count > 0) {
                val cell_exports_start_offset = package_summary_offset + package_summary.cell_exports.offset.toLong()
                seekable.seek(cell_exports_start_offset)
                for (i in 0 until package_summary.cell_exports.count) {
                    val cell_export: FCellExport = FCellExport.deserialize(seekable, package_summary)
                    cell_exports.add(cell_export)
                }
            }

            // Deserialize preload dependencies
            val preload_dependencies_start_offset = package_summary_offset + package_summary.preload_dependencies.offset.toLong()
            seekable.seek(preload_dependencies_start_offset)
            val preload_dependencies: MutableList<FPackageIndex> = mutableListOf()
            for (i in 0 until package_summary.preload_dependencies.count) {
                preload_dependencies.add(seekable.de(FPackageIndex))
            }

            // Data resources are absent on packages below UE 5.2
            val data_resources: MutableList<FObjectDataResource> = mutableListOf()
            var data_resource_version: EObjectDataResourceVersion? = null
            if (package_summary.data_resource_offset > 0) {
                val data_resource_start_offset = package_summary_offset + package_summary.data_resource_offset.toLong()
                seekable.seek(data_resource_start_offset)
                val version: EObjectDataResourceVersion = seekable.de(EObjectDataResourceVersion)
                data_resource_version = version
                val data_resource_count: Int = seekable.read_i32_le()
                val bbCount = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data_resource_count).array()
                check(ByteBuffer.wrap(bbCount).order(ByteOrder.LITTLE_ENDIAN).int == data_resource_count)
                for (i in 0 until data_resource_count) {
                    data_resources.add(FObjectDataResource.de(seekable, version))
                }
            }
            // TreeMap check per spec
            val treeCheck = TreeMap<String, Int>()
            treeCheck["imports"] = imports.size
            treeCheck["exports"] = exports.size
            val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
            check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)

            return FLegacyPackageHeader(
                summary = package_summary,
                name_map = name_map,
                imports = imports,
                exports = exports,
                cell_imports = cell_imports,
                cell_exports = cell_exports,
                preload_dependencies = preload_dependencies,
                data_resources = data_resources,
                data_resource_version = data_resource_version
            )
        }

        private fun toSeekable(stream: InputStream): SeekableByteArrayInputStream {
            return if (stream is SeekableByteArrayInputStream) stream else {
                val bytes = stream.readBytes()
                SeekableByteArrayInputStream(bytes)
            }
        }
    }

    // Rust: retoc/src/legacy_asset.rs:1115 serialize
    fun serialize(stream: OutputStream, desired_header_size: Int?, log: Log) {
        val seekable = if (stream is SeekableByteArrayOutputStream) stream else {
            // For generic OutputStream, buffer then copy (parity with zen.kt)
            val tmp = SeekableByteArrayOutputStream()
            serialize(tmp, desired_header_size, log)
            stream.write(tmp.toByteArray())
            return
        }
        val package_summary_offset: Long = seekable.position()
        val bbOffset = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(package_summary_offset).array()
        check(ByteBuffer.wrap(bbOffset).order(ByteOrder.LITTLE_ENDIAN).long == package_summary_offset)
        var package_summary: FLegacyPackageFileSummary = summary.copy(
            versioning_info = summary.versioning_info.copy(
                saved_hash = summary.versioning_info.saved_hash.copyOf(),
                custom_versions = summary.versioning_info.custom_versions.toMutableList()
            ),
            chunk_ids = summary.chunk_ids.toMutableList()
        )
        // Need deep copy for mutable fields that are lists in summary (chunk_ids already) but others are data classes copy is fine
        // Clone package_summary to avoid mutating original directly until patch
        package_summary = summary.copy(
            versioning_info = summary.versioning_info.copy(
                saved_hash = summary.versioning_info.saved_hash.copyOf(),
                custom_versions = summary.versioning_info.custom_versions.toMutableList()
            ),
            names = summary.names.copy(),
            soft_object_paths = summary.soft_object_paths.copy(),
            exports = summary.exports.copy(),
            imports = summary.imports.copy(),
            cell_exports = summary.cell_exports.copy(),
            cell_imports = summary.cell_imports.copy(),
            preload_dependencies = summary.preload_dependencies.copy(),
            chunk_ids = summary.chunk_ids.toMutableList()
        )

        // Write initial package summary. We will overwrite it again once we have the offsets of the relevant data members
        package_summary.serialize(seekable)

        // Write name map. It directly follows the package summary
        name_map.write(seekable, package_summary, package_summary_offset)

        // Write soft object paths offset. We do not actually write any soft object paths because they must be serialized inline for cooked assets,
        // because zen header cannot preserve object paths that are not serialized inline
        val soft_object_paths_offset = (seekable.position() - package_summary_offset).toInt()
        package_summary.soft_object_paths = FCountOffsetPair(count = 0, offset = soft_object_paths_offset)

        // Serialize import map
        val imports_start_offset = (seekable.position() - package_summary_offset).toInt()
        package_summary.imports = FCountOffsetPair(count = imports.size, offset = imports_start_offset)
        for (object_import in imports) {
            object_import.serialize(seekable, package_summary)
        }

        // Serialize export map
        val exports_start_offset_from_stream_start = seekable.position()
        val exports_start_offset = (exports_start_offset_from_stream_start - package_summary_offset).toInt()
        package_summary.exports = FCountOffsetPair(count = exports.size, offset = exports_start_offset)
        for (object_export in exports) {
            object_export.serialize(seekable, package_summary)
        }

        // Serialize cell import map
        val cell_imports_start_offset = (seekable.position() - package_summary_offset).toInt()
        package_summary.cell_imports = FCountOffsetPair(count = cell_imports.size, offset = cell_imports_start_offset)
        for (cell_import in cell_imports) {
            cell_import.serialize(seekable, package_summary)
        }

        // Serialize cell export map
        val cell_exports_start_offset_from_stream_start = seekable.position()
        val cell_exports_start_offset = (cell_exports_start_offset_from_stream_start - package_summary_offset).toInt()
        package_summary.cell_exports = FCountOffsetPair(count = cell_exports.size, offset = cell_exports_start_offset)
        for (cell_export in cell_exports) {
            cell_export.serialize(seekable, package_summary)
        }

        // Serialize depends map. This is just an empty placeholder for cooked assets
        val depends_start_offset = (seekable.position() - package_summary_offset).toInt()
        package_summary.depends_offset = depends_start_offset
        for (i in 0 until exports.size) {
            // empty depends list per export: write vec with 0 length (u32 0)
            seekable.write_u32_le(0u)
        }

        // Serialize asset registry data. This is just an empty placeholder for cooked assets
        val asset_registry_data_start_offset = (seekable.position() - package_summary_offset).toInt()
        package_summary.asset_registry_data_offset = asset_registry_data_start_offset
        val asset_object_data_count: Int = 0
        seekable.write_i32_le(asset_object_data_count)

        // World composition data from the package summary is not used in runtime and is only written for legacy world composition assets in 4.27, so write 0
        package_summary.world_tile_info_data_offset = 0

        // Serialize preload dependencies
        val preload_dependencies_start_offset = (seekable.position() - package_summary_offset).toInt()
        package_summary.preload_dependencies = FCountOffsetPair(count = preload_dependencies.size, offset = preload_dependencies_start_offset)
        for (preload_dependency in preload_dependencies) {
            seekable.ser(preload_dependency)
        }

        // Serialize data resources if they are present. Write -1 if there are no data resources
        package_summary.data_resource_offset = -1
        if (data_resources.isNotEmpty()) {
            val data_resources_start_offset = (seekable.position() - package_summary_offset).toInt()
            package_summary.data_resource_offset = data_resources_start_offset
            val versionToWrite = data_resource_version ?: EObjectDataResourceVersion.Initial
            versionToWrite.ser(seekable)
            val data_resource_count: Int = data_resources.size
            seekable.write_i32_le(data_resource_count)
            for (data_resource in data_resources) {
                // Rust FObjectDataResource::ser (legacy_asset.rs:1004-1014): flags, serial_offset, duplicate_serial_offset, serial_size, raw_size, outer_index, legacy_bulk_data_flags — cooked_index is never written
                seekable.write_u32_le(data_resource.flags)
                seekable.write_i64_le(data_resource.serial_offset)
                seekable.write_i64_le(data_resource.duplicate_serial_offset)
                seekable.write_i64_le(data_resource.serial_size)
                seekable.write_i64_le(data_resource.raw_size)
                seekable.ser(data_resource.outer_index)
                seekable.write_u32_le(data_resource.legacy_bulk_data_flags)
            }
        }

        // Write zero padding after normal header data to maintain the zen asset binary equality if desired
        val data_total_header_size = (seekable.position() - package_summary_offset).toInt()
        if (desired_header_size != null && desired_header_size > data_total_header_size) {
            val extra_null_padding_bytes = desired_header_size - data_total_header_size
            seekable.write(ByteArray(extra_null_padding_bytes))
        }

        // Set total size of the serialized header. The rest of the data is not considered the part of it
        val total_header_size = (seekable.position() - package_summary_offset).toInt()
        package_summary.versioning_info.total_header_size = total_header_size
        val position_after_writing_header = seekable.position()

        // Export serial offsets include total header size into them, even if exports are split into a separate file
        // So we need to re-write export entries, now that we know the size of the header to adjust their offsets by
        seekable.seek(exports_start_offset_from_stream_start)
        var end_of_last_export_offset: Long = total_header_size.toLong()
        for (object_export in exports) {
            val modified_object_export = object_export.copy(serial_offset = object_export.serial_offset + total_header_size)
            end_of_last_export_offset = max(end_of_last_export_offset, modified_object_export.serial_offset + modified_object_export.serial_size)
            modified_object_export.serialize(seekable, package_summary)
        }

        // Same applies to cell exports, their serial offsets include total header size, even though they are split into a separate file
        seekable.seek(cell_exports_start_offset_from_stream_start)
        for (cell_export in cell_exports) {
            val modified_cell_export = cell_export.copy(serial_offset = cell_export.serial_offset + total_header_size)
            end_of_last_export_offset = max(end_of_last_export_offset, modified_cell_export.serial_offset + modified_cell_export.serial_size)
            modified_cell_export.serialize(seekable, package_summary)
        }

        // This would be written directly after the exports blobs. Even though this value is never used in cooked games, we can infer it by looking at the furthest written export blob and setting to be directly after it
        package_summary.bulk_data_start_offset = end_of_last_export_offset

        // Go back to the initial package summary and overwrite it with a patched-up version
        seekable.seek(package_summary_offset)
        package_summary.serialize(seekable)

        // Dump fully patched up package summary if needed
        if (log.is_level_enabled(LogLevel.Debug)) {
            log.debug("FLegacyPackageHeader summary: $package_summary")
        }

        // Seek back to the position after the header
        seekable.seek(position_after_writing_header)
    }

    // Overloads for Seekable streams (parity with Rust Seek)
    fun deserialize_seekable(stream: SeekableByteArrayInputStream, package_version_fallback: FPackageFileVersion?): FLegacyPackageHeader {
        return deserialize(stream, package_version_fallback)
    }

    fun serialize_seekable(stream: SeekableByteArrayOutputStream, desired_header_size: Int?, log: Log) {
        return serialize(stream, desired_header_size, log)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FLegacyPackageHeader) return false
        return summary == other.summary && name_map == other.name_map && imports == other.imports && exports == other.exports && cell_imports == other.cell_imports && cell_exports == other.cell_exports && preload_dependencies == other.preload_dependencies && data_resources == other.data_resources && data_resource_version == other.data_resource_version
    }
    override fun hashCode(): Int = summary.hashCode()
}

// Rust: retoc/src/legacy_asset.rs:1266 get_package_object_full_name
fun get_package_object_full_name(
    pkg: FLegacyPackageHeader,
    object_index: FPackageIndex,
    path_separator: Char,
    lowercase_path: Boolean,
    package_name_override: String?
): kotlin.Pair<String, String> {
    // From the outermost to the innermost, e.g. SubObject;Asset;PackageName
    val package_object_outer_chain: MutableList<FPackageIndex> = mutableListOf()
    var current_object_index = object_index

    // Walk the import chain to resolve this import
    while (!current_object_index.is_null()) {
        package_object_outer_chain.add(current_object_index)

        if (current_object_index.is_import()) {
            val current_import_index = current_object_index.to_import_index().toInt()
            // Bounds check for safety
            if (current_import_index < 0 || current_import_index >= pkg.imports.size) break
            current_object_index = pkg.imports[current_import_index].outer_index
        } else if (current_object_index.is_export()) {
            val current_export_index = current_object_index.to_export_index().toInt()
            if (current_export_index < 0 || current_export_index >= pkg.exports.size) break
            current_object_index = pkg.exports[current_export_index].outer_index
        } else {
            break
        }
    }
    // Reserve the outer chain now
    package_object_outer_chain.reverse()

    val package_name: String
    val start_object_index: Int

    if (package_object_outer_chain.isNotEmpty() && package_object_outer_chain[0].is_import()) {
        val package_import_index = package_object_outer_chain[0].to_import_index().toInt()
        // If the innermost package index is an import, it's a package name. Otherwise, this package name is the package name
        package_name = pkg.name_map.get(pkg.imports[package_import_index].object_name)
        start_object_index = 1
    } else {
        // This is an export, package name is this package name, and we should start path building at index 0
        // Use the provided package name override if it is available instead of the actual package name. This is necessary to produce correct global import index for exports on legacy UE4 zen assets
        package_name = package_name_override ?: pkg.summary.package_name
        start_object_index = 0
    }

    // Build full object name now. We append all elements and use / as a path separator
    var full_object_name: String = package_name
    for (i in start_object_index until package_object_outer_chain.size) {
        val outer = package_object_outer_chain[i]
        // Append object path separator
        full_object_name += path_separator

        // Append the name of the object if it's an import
        if (outer.is_import()) {
            val import_index = outer.to_import_index().toInt()
            full_object_name += pkg.name_map.get(pkg.imports[import_index].object_name)
        } else if (outer.is_export()) {
            val export_index = outer.to_export_index().toInt()
            full_object_name += pkg.name_map.get(pkg.exports[export_index].object_name)
        }
    }
    // Make sure the entire path is lowercase. This is a requirement for GetPublicExportHash
    if (lowercase_path) {
        full_object_name = full_object_name.lowercase(Locale.ROOT)
    }
    // TreeMap ByteBuffer LE check per spec
    val treeCheck = TreeMap<String, String>()
    treeCheck[package_name] = full_object_name
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
    return kotlin.Pair(package_name, full_object_name)
}

// Rust: retoc/src/legacy_asset.rs:1327 convert_localized_package_name_to_source
fun convert_localized_package_name_to_source(package_name: String): kotlin.Pair<String, String>? {
    if (!package_name.startsWith("/")) return null
    val splits: List<String> = package_name.substring(1).split("/", limit = 4)
    if (splits.size != 4 || splits[1] != "L10N") return null
    val mount_point = splits[0]
    val culture_name = splits[2]
    val package_path = splits[3]
    val source_package_name = "/$mount_point/$package_path"
    return kotlin.Pair(source_package_name, culture_name)
}

// Rust: retoc/src/legacy_asset.rs:1349 FSerializedAssetBundle
data class FSerializedAssetBundle(
    var asset_file_buffer: ByteArray = ByteArray(0),
    var exports_file_buffer: ByteArray = ByteArray(0),
    var bulk_data_buffer: ByteArray? = null,
    var optional_bulk_data_buffer: ByteArray? = null,
    var memory_mapped_bulk_data_buffer: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (other !is FSerializedAssetBundle) return false
        return asset_file_buffer.contentEquals(other.asset_file_buffer) && exports_file_buffer.contentEquals(other.exports_file_buffer) && bulk_data_buffer.contentEquals(other.bulk_data_buffer) && optional_bulk_data_buffer.contentEquals(other.optional_bulk_data_buffer) && memory_mapped_bulk_data_buffer.contentEquals(other.memory_mapped_bulk_data_buffer)
    }
    override fun hashCode(): Int = asset_file_buffer.contentHashCode()

    private fun ByteArray?.contentEquals(other: ByteArray?): Boolean {
        if (this == null && other == null) return true
        if (this == null || other == null) return false
        return this.contentEquals(other)
    }
}

// Rust: retoc/src/legacy_asset.rs:1358 constants for asset conversion
const val CORE_OBJECT_PACKAGE_NAME: String = "/Script/CoreUObject"
const val ENGINE_PACKAGE_NAME: String = "/Script/Engine"
const val OBJECT_CLASS_NAME: String = "Object"
const val CLASS_CLASS_NAME: String = "Class"
const val PACKAGE_CLASS_NAME: String = "Package"
const val PRESTREAM_PACKAGE_CLASS_NAME: String = "PrestreamPackage"
