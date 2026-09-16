// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/asset_registry.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "SpellCheckingInspection", "MemberVisibilityCanBePrivate", "MagicNumber", "ThrowsCount", "TooGenericExceptionCaught", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "UnnecessaryVariable", "MaxLineLength")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:11 FAssetRegistryHeader
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:11
data class FAssetRegistryHeader(
    var filter_editor_only_data: Boolean = false
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_bool(filter_editor_only_data)
    }

    companion object : Readable<FAssetRegistryHeader> {
        override fun de(stream: InputStream): FAssetRegistryHeader {
            return FAssetRegistryHeader(filter_editor_only_data = stream.read_bool())
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:29 FAssetRegistryVersion
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:29
enum class FAssetRegistryVersion(val value: UInt) : Writeable {
    PreVersioning(0u),
    HardSoftDependencies(1u),
    AddAssetRegistryState(2u),
    ChangedAssetData(3u),
    RemovedMD5Hash(4u),
    AddedHardManage(5u),
    AddedCookedMD5Hash(6u),
    AddedDependencyFlags(7u),
    FixedTags(8u),
    WorkspaceDomain(9u),
    PackageImportedClasses(10u),
    PackageFileSummaryVersionChange(11u),
    ObjectResourceOptionalVersionChange(12u),
    AddedChunkHashes(13u),
    ClassPaths(14u),
    RemoveAssetPathFNames(15u),
    AddedHeader(16u),
    AssetPackageDataHasExtension(17u),
    AssetPackageDataHasPackageLocation(18u),
    MarshalledTextAsUTF8String(19u),
    PackageSavedHash(20u),
    ExternalActorToWorldIsEditorOnly(21u);

    override fun ser(stream: OutputStream) {
        stream.write_u32_le(value)
    }

    companion object : Readable<FAssetRegistryVersion> {
        fun from_repr(value: UInt): FAssetRegistryVersion? = entries.find { it.value == value }
        fun from_repr(value: Int): FAssetRegistryVersion? = from_repr(value.toUInt())
        fun from_repr(value: Long): FAssetRegistryVersion? = from_repr(value.toUInt())
        fun from_repr(value: ULong): FAssetRegistryVersion? = from_repr(value.toUInt())

        override fun de(stream: InputStream): FAssetRegistryVersion {
            val v = stream.read_u32_le()
            return from_repr(v) ?: throw IllegalArgumentException("invalid FAssetRegistryVersion: $v")
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:103 read_fname
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:103
fun read_fname(stream: InputStream, names: List<String>): String {
    val n = stream.read_u32_le()
    val index = (n and 0x7FFFFFFFu).toInt()
    val name = names.getOrNull(index) ?: throw IllegalArgumentException("Invalid name index: $index")
    return if ((n and 0x80000000u) != 0u) {
        val number = stream.read_u32_le()
        "${name}_${number - 1u}"
    } else {
        name
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:116 read_fname_pre_fixed_tags
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:116
fun read_fname_pre_fixed_tags(stream: InputStream, names: List<String>): String {
    val index = stream.read_i32_le()
    val number = stream.read_i32_le()
    if (index < 0 || index >= names.size) {
        throw IllegalArgumentException("Invalid name index: $index (total names: ${names.size})")
    }
    val name = names[index]
    return if (number > 0) "${name}_${number - 1}" else name
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:129 write_fname
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:129
fun write_fname(stream: OutputStream, value: String, name_map: LinkedHashSet<String>) {
    val pair: kotlin.Pair<String, Int> = break_down_name_string(value)
    val base_name = pair.first
    val number = pair.second
    val index = name_map.indexOf(base_name).takeIf { it >= 0 } ?: throw IllegalArgumentException("name should already be in name map: $base_name")
    if (number != 0) {
        stream.write_u32_le(index.toUInt() or 0x80000000u)
        stream.write_i32_le(number)
    } else {
        stream.write_u32_le(index.toUInt())
    }
}

// overload for IndexSet alias
typealias IndexSet<T> = LinkedHashSet<T>
fun <T> IndexSet<T>.get_index_of(value: T): Int? = this.indexOf(value).takeIf { it >= 0 }
fun write_fname_indexset(stream: OutputStream, value: String, name_map: IndexSet<String>) = write_fname(stream, value, name_map)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:143 FTopLevelAssetPath
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:143
data class FTopLevelAssetPath(
    var package_name: String = "",
    var asset_name: String = ""
) : Writeable {
    override fun ser(stream: OutputStream) {
        // Rust: retoc/src/asset_registry.rs:143 FTopLevelAssetPath has no direct ser; use write_fname with name_map for parity
        throw UnsupportedOperationException("FTopLevelAssetPath ser requires name_map; use write_fname (retoc/src/asset_registry.rs:143)")
    }

    fun write_fname(stream: OutputStream, name_map: LinkedHashSet<String>) {
        write_fname(stream, package_name, name_map)
        write_fname(stream, asset_name, name_map)
    }

    companion object : Readable<FTopLevelAssetPath> {
        override fun de(stream: InputStream): FTopLevelAssetPath {
            // Rust: retoc/src/asset_registry.rs:143 FTopLevelAssetPath has no direct de; use read_fname with names
            throw UnsupportedOperationException("FTopLevelAssetPath de requires names; use read_fname (retoc/src/asset_registry.rs:143)")
        }

        fun read_fname(stream: InputStream, names: List<String>): FTopLevelAssetPath {
            return FTopLevelAssetPath(
                package_name = com.github.jpabscale.zenpak4j.retoc.read_fname(stream, names),
                asset_name = com.github.jpabscale.zenpak4j.retoc.read_fname(stream, names)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:165 FAssetBundleEntry
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:165
data class FAssetBundleEntry(
    var bundle_name: String = "",
    var asset_paths: MutableList<FTopLevelAssetPath> = mutableListOf()
) : Writeable {
    override fun ser(stream: OutputStream) {
        // Rust: retoc/src/asset_registry.rs:165 FAssetBundleEntry has no direct ser; use write_fname with name_map
        throw UnsupportedOperationException("FAssetBundleEntry ser requires name_map; use write_fname (retoc/src/asset_registry.rs:165)")
    }

    fun write_fname(stream: OutputStream, name_map: LinkedHashSet<String>) {
        write_fname(stream, bundle_name, name_map)
        stream.write_i32_le(asset_paths.size)
        for (path in asset_paths) {
            path.write_fname(stream, name_map)
            stream.write_string("")
        }
    }

    companion object : Readable<FAssetBundleEntry> {
        override fun de(stream: InputStream): FAssetBundleEntry {
            // Rust: retoc/src/asset_registry.rs:165 FAssetBundleEntry has no direct de; use read_fname with names
            throw UnsupportedOperationException("FAssetBundleEntry de requires names; use read_fname (retoc/src/asset_registry.rs:165)")
        }

        fun read_fname(stream: InputStream, names: List<String>): FAssetBundleEntry {
            val bundle_name = com.github.jpabscale.zenpak4j.retoc.read_fname(stream, names)
            val num_paths = stream.read_i32_le()
            val asset_paths = read_array(num_paths, stream) { s ->
                val path = FTopLevelAssetPath.read_fname(s, names)
                val _sub_path: String = s.read_string()
                path
            }.toMutableList()
            return FAssetBundleEntry(bundle_name = bundle_name, asset_paths = asset_paths)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:198 AssetClassPath
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:198
sealed class AssetClassPath {
    data class TopLevelAssetPath(val value: FTopLevelAssetPath) : AssetClassPath()
    data class LegacyClassName(val value: String) : AssetClassPath()

    fun to_path_string(): String {
        return when (this) {
            is TopLevelAssetPath -> {
                if (value.package_name.isEmpty() && value.asset_name.isEmpty()) "" else "${value.package_name}.${value.asset_name}"
            }
            is LegacyClassName -> value
        }
    }

    fun package_name(): String {
        return when (this) {
            is TopLevelAssetPath -> value.package_name
            is LegacyClassName -> ""
        }
    }

    fun asset_name(): String {
        return when (this) {
            is TopLevelAssetPath -> value.asset_name
            is LegacyClassName -> value
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:239 ExportPath
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:239
data class ExportPath(
    var object_path: String = "",
    var package_path: String = "",
    var asset_class: AssetClassPath = AssetClassPath.LegacyClassName("")
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:246 TagType
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:246
enum class TagType(val value: UInt) : Writeable {
    AnsiString(0u),
    WideString(1u),
    NumberlessName(2u),
    Name(3u),
    NumberlessExportPath(4u),
    ExportPath(5u),
    LocalizedText(6u);

    override fun ser(stream: OutputStream) {
        stream.write_u32_le(value)
    }

    companion object : Readable<TagType> {
        fun from_repr(value: UInt): TagType? = entries.find { it.value == value }
        fun from_repr(value: Int): TagType? = from_repr(value.toUInt())
        override fun de(stream: InputStream): TagType {
            val v = stream.read_u32_le()
            return from_repr(v) ?: throw IllegalArgumentException("invalid AssetRegistry TagType: $v")
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:271 Pair
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:271
data class Pair(
    var name: String = "",
    var type_: TagType = TagType.AnsiString,
    var index: UInt = 0u
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:278 MapHandle
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:278
data class MapHandle(
    var has_numberless_keys: Boolean = false,
    var num: UShort = 0u,
    var pair_begin: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        val packed = ((if (has_numberless_keys) 1UL else 0UL) shl 63) or ((num.toULong()) shl 32) or (pair_begin.toULong())
        stream.write_u64_le(packed)
    }

    companion object : Readable<MapHandle> {
        override fun de(stream: InputStream): MapHandle {
            val packed = stream.read_u64_le()
            return MapHandle(
                has_numberless_keys = (packed shr 63) != 0UL,
                num = ((packed shr 32) and 0xFFFFUL).toUShort(),
                pair_begin = (packed and 0xFFFFFFFFUL).toUInt()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:304 AssetData
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:304
data class AssetData(
    var object_path: String = "",
    var package_path: String = "",
    var asset_class: String = "",
    var package_name: String = "",
    var asset_name: String = "",
    var tags: MapHandle = MapHandle(),
    var legacy_tags: MutableList<kotlin.Pair<String, String>> = mutableListOf(),
    var bundles: MutableList<FAssetBundleEntry> = mutableListOf(),
    var chunk_ids: MutableList<UInt> = mutableListOf(),
    var flags: UInt = 0u
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:318 Dependencies
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:318
data class Dependencies(
    var dependencies_size: ULong = 0UL,
    var dependencies: MutableList<UInt> = mutableListOf(),
    var package_data_buffer_size: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u64_le(dependencies_size)
        stream.write_u32_le(dependencies.size.toUInt())
        for (v in dependencies) stream.write_u32_le(v)
        stream.write_u32_le(package_data_buffer_size)
    }

    companion object : Readable<Dependencies> {
        override fun de(stream: InputStream): Dependencies {
            val dependencies_size = stream.read_u64_le()
            val deps = stream.read_vec { s -> s.read_u32_le() }.toMutableList()
            val package_data_buffer_size = stream.read_u32_le()
            return Dependencies(dependencies_size, deps, package_data_buffer_size)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:344 Store
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:344
const val MAGIC_START: UInt = 0x12345679u
const val MAGIC_END: UInt = 0x87654321u

// Rust: retoc/src/asset_registry.rs:347
data class Store(
    var pair_count: UInt = 0u,
    var texts: MutableList<String> = mutableListOf(),
    var nbl_names: MutableList<String> = mutableListOf(),
    var names: MutableList<String> = mutableListOf(),
    var nbl_export_paths: MutableList<ExportPath> = mutableListOf(),
    var export_paths: MutableList<ExportPath> = mutableListOf(),
    var ansi_strings: MutableList<String> = mutableListOf(),
    var wide_strings: MutableList<String> = mutableListOf(),
    var pairs: MutableList<Pair> = mutableListOf()
) {
    // Rust: retoc/src/asset_registry.rs:361 read_with_names
    fun read_with_names(stream: InputStream, names: List<String>, registry_version: FAssetRegistryVersion): Store {
        return read_with_names_internal(stream, names, registry_version)
    }

    // Rust: retoc/src/asset_registry.rs:491 write_with_name_map
    fun write_with_name_map(stream: OutputStream, name_map: LinkedHashSet<String>, registry_version: FAssetRegistryVersion) {
        write_with_name_map_internal(stream, name_map, registry_version)
    }

    companion object {
        // Rust: retoc/src/asset_registry.rs:361 static
        fun read_with_names_static(stream: InputStream, names: List<String>, registry_version: FAssetRegistryVersion): Store {
            return Store().read_with_names_internal(stream, names, registry_version)
        }
    }

    private fun read_with_names_internal(stream: InputStream, names: List<String>, registry_version: FAssetRegistryVersion): Store {
        val magic = stream.read_u32_le()
        check(magic == MAGIC_START) { "Invalid Store magic: expected ${MAGIC_START.toString(16)}, got ${magic.toString(16)}" }

        val nbl_names_count = stream.read_u32_le()
        val names_count = stream.read_u32_le()
        val nbl_export_path_count = stream.read_u32_le()
        val export_path_count = stream.read_u32_le()
        val texts_count = stream.read_u32_le()
        val ansi_strings_count = stream.read_u32_le()
        val wide_strings_count = stream.read_u32_le()
        val _ansi_string_bytes = stream.read_u32_le()
        val _wide_string_bytes = stream.read_u32_le()
        val nbl_pair_count = stream.read_u32_le()
        val pair_count_val = stream.read_u32_le()

        val _text_bytes = stream.read_u32_le()
        val texts_list: MutableList<String> = if (registry_version.value >= FAssetRegistryVersion.MarshalledTextAsUTF8String.value) {
            read_array(texts_count.toInt(), stream) { s ->
                val len = s.read_u32_le().toInt()
                val chars = ByteArray(len)
                if (len > 0) s.read_exact(chars)
                String(chars, StandardCharsets.UTF_8)
            }
        } else {
            read_array(texts_count.toInt(), stream) { s ->
                val len = s.read_u32_le().toInt()
                val actualLen = if (len > 0) len - 1 else 0
                val chars = ByteArray(actualLen)
                if (actualLen > 0) s.read_exact(chars)
                if (len > 0) s.read_u8()
                String(chars, StandardCharsets.UTF_8)
            }
        }

        val nbl_names_list: MutableList<String> = read_array(nbl_names_count.toInt(), stream) { s -> read_fname(s, names) }
        val store_names_list: MutableList<String> = read_array(names_count.toInt(), stream) { s -> read_fname(s, names) }

        val nbl_export_paths_list: MutableList<ExportPath> = if (registry_version.value >= FAssetRegistryVersion.ClassPaths.value) {
            read_array(nbl_export_path_count.toInt(), stream) { s ->
                ExportPath(
                    asset_class = AssetClassPath.TopLevelAssetPath(FTopLevelAssetPath.read_fname(s, names)),
                    object_path = read_fname(s, names),
                    package_path = read_fname(s, names)
                )
            }
        } else {
            read_array(nbl_export_path_count.toInt(), stream) { s ->
                ExportPath(
                    asset_class = AssetClassPath.LegacyClassName(read_fname(s, names)),
                    object_path = read_fname(s, names),
                    package_path = read_fname(s, names)
                )
            }
        }

        val export_paths_list: MutableList<ExportPath> = if (registry_version.value >= FAssetRegistryVersion.ClassPaths.value) {
            read_array(export_path_count.toInt(), stream) { s ->
                ExportPath(
                    asset_class = AssetClassPath.TopLevelAssetPath(FTopLevelAssetPath.read_fname(s, names)),
                    object_path = read_fname(s, names),
                    package_path = read_fname(s, names)
                )
            }
        } else {
            read_array(export_path_count.toInt(), stream) { s ->
                ExportPath(
                    asset_class = AssetClassPath.LegacyClassName(read_fname(s, names)),
                    object_path = read_fname(s, names),
                    package_path = read_fname(s, names)
                )
            }
        }

        // Read string offset tables (not used for reading, but required for file format)
        val _ansi_offsets = read_array(ansi_strings_count.toInt(), stream) { s -> s.read_u32_le() }
        val _wide_offsets = read_array(wide_strings_count.toInt(), stream) { s -> s.read_u32_le() }

        val ansi_strings_list = read_array(ansi_strings_count.toInt(), stream) { s ->
            val bytes = mutableListOf<Byte>()
            while (true) {
                val b = s.read_u8().toInt()
                if (b == 0) break
                bytes.add(b.toByte())
            }
            String(bytes.toByteArray(), StandardCharsets.UTF_8)
        }

        val wide_strings_list = read_array(wide_strings_count.toInt(), stream) { s ->
            val chars = mutableListOf<Char>()
            while (true) {
                val w = s.read_u16_le().toInt()
                if (w == 0) break
                chars.add(w.toChar())
            }
            String(chars.toCharArray())
        }

        val pairs_list: MutableList<Pair> = read_array(nbl_pair_count.toInt(), stream) { s ->
            val name_idx = s.read_u32_le().toInt()
            val name = names.getOrNull(name_idx) ?: throw IllegalArgumentException("Invalid pair name index: $name_idx")
            val packed = s.read_u32_le()
            val typeVal = packed and 0x7u
            val type_ = TagType.from_repr(typeVal) ?: throw IllegalArgumentException("Invalid pair TagType: $typeVal")
            val index = packed shr 3
            Pair(name = name, type_ = type_, index = index)
        }

        val magic_end = stream.read_u32_le()
        check(magic_end == MAGIC_END) { "Invalid Store end magic: expected ${MAGIC_END.toString(16)}, got ${magic_end.toString(16)}" }

        return Store(
            pair_count = pair_count_val,
            texts = texts_list,
            nbl_names = nbl_names_list,
            names = store_names_list,
            nbl_export_paths = nbl_export_paths_list,
            export_paths = export_paths_list,
            ansi_strings = ansi_strings_list,
            wide_strings = wide_strings_list,
            pairs = pairs_list
        )
    }

    private fun write_with_name_map_internal(stream: OutputStream, name_map: LinkedHashSet<String>, registry_version: FAssetRegistryVersion) {
        // Build index map for O(1) lookups (LinkedHashSet.indexOf is O(n))
        val nameToIndex = HashMap<String, Int>(name_map.size * 2)
        var idxTmp = 0
        for (n in name_map) {
            nameToIndex[n] = idxTmp++
        }
        fun write_fname_fast(value: String) {
            val pr = break_down_name_string(value)
            val base = pr.first
            val number = pr.second
            val index = nameToIndex[base] ?: throw IllegalArgumentException("name should already be in name map: $base")
            if (number != 0) {
                stream.write_u32_le(index.toUInt() or 0x80000000u)
                stream.write_i32_le(number)
            } else {
                stream.write_u32_le(index.toUInt())
            }
        }
        fun write_top_level_fast(tl: FTopLevelAssetPath) {
            write_fname_fast(tl.package_name)
            write_fname_fast(tl.asset_name)
        }

        stream.write_u32_le(MAGIC_START)

        stream.write_u32_le(nbl_names.size.toUInt())
        stream.write_u32_le(names.size.toUInt())
        stream.write_u32_le(nbl_export_paths.size.toUInt())
        stream.write_u32_le(export_paths.size.toUInt())
        stream.write_u32_le(texts.size.toUInt())
        stream.write_u32_le(ansi_strings.size.toUInt())
        stream.write_u32_le(wide_strings.size.toUInt())

        val ansi_bytes: UInt = ansi_strings.fold(0u) { acc, s -> acc + s.toByteArray(StandardCharsets.UTF_8).size.toUInt() + 1u }
        val wide_bytes: UInt = wide_strings.fold(0u) { acc, s -> acc + s.length.toUInt() + 1u }
        stream.write_u32_le(ansi_bytes)
        stream.write_u32_le(wide_bytes)

        stream.write_u32_le(pairs.size.toUInt())
        stream.write_u32_le(pair_count)

        if (registry_version.value >= FAssetRegistryVersion.MarshalledTextAsUTF8String.value) {
            val text_bytes: UInt = texts.fold(0u) { acc, s -> acc + s.toByteArray(StandardCharsets.UTF_8).size.toUInt() + 4u }
            stream.write_u32_le(text_bytes)
            for (text in texts) {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                stream.write_u32_le(bytes.size.toUInt())
                stream.write(bytes)
            }
        } else {
            val text_bytes: UInt = texts.fold(0u) { acc, s -> acc + s.toByteArray(StandardCharsets.UTF_8).size.toUInt() + 1u + 4u }
            stream.write_u32_le(text_bytes)
            for (text in texts) {
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                stream.write_u32_le((bytes.size + 1).toUInt())
                stream.write(bytes)
                stream.write_u8(0u)
            }
        }

        for (name in nbl_names) {
            write_fname_fast(name)
        }
        for (name in names) {
            write_fname_fast(name)
        }

        if (registry_version.value >= FAssetRegistryVersion.ClassPaths.value) {
            for (path in nbl_export_paths) {
                when (val ac = path.asset_class) {
                    is AssetClassPath.TopLevelAssetPath -> write_top_level_fast(ac.value)
                    is AssetClassPath.LegacyClassName -> error("unreachable LegacyClassName for ClassPaths version")
                }
                write_fname_fast(path.object_path)
                write_fname_fast(path.package_path)
            }
            for (path in export_paths) {
                when (val ac = path.asset_class) {
                    is AssetClassPath.TopLevelAssetPath -> write_top_level_fast(ac.value)
                    is AssetClassPath.LegacyClassName -> error("unreachable")
                }
                write_fname_fast(path.object_path)
                write_fname_fast(path.package_path)
            }
        } else {
            for (path in nbl_export_paths) {
                when (val ac = path.asset_class) {
                    is AssetClassPath.LegacyClassName -> write_fname_fast(ac.value)
                    is AssetClassPath.TopLevelAssetPath -> error("unreachable TopLevel for legacy")
                }
                write_fname_fast(path.object_path)
                write_fname_fast(path.package_path)
            }
            for (path in export_paths) {
                when (val ac = path.asset_class) {
                    is AssetClassPath.LegacyClassName -> write_fname_fast(ac.value)
                    is AssetClassPath.TopLevelAssetPath -> error("unreachable")
                }
                write_fname_fast(path.object_path)
                write_fname_fast(path.package_path)
            }
        }

        var off = 0u
        for (s in ansi_strings) {
            stream.write_u32_le(off)
            off += s.toByteArray(StandardCharsets.UTF_8).size.toUInt() + 1u
        }
        off = 0u
        for (s in wide_strings) {
            stream.write_u32_le(off)
            off += s.length.toUInt() + 1u
        }

        for (s in ansi_strings) {
            stream.write(s.toByteArray(StandardCharsets.UTF_8))
            stream.write_u8(0u)
        }
        for (s in wide_strings) {
            for (c in s) {
                stream.write_u16_le(c.code.toUShort())
            }
            stream.write_u16_le(0u)
        }

        for (pair in pairs) {
            val name_idx = nameToIndex[pair.name] ?: throw IllegalArgumentException("name should already be in name map: ${pair.name}")
            stream.write_u32_le(name_idx.toUInt())
            val packed = (pair.type_.value) or (pair.index shl 3)
            stream.write_u32_le(packed)
        }

        stream.write_u32_le(MAGIC_END)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:629 AssetRegistry
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:629
data class AssetRegistry(
    var version: FGuid = FGuid(),
    var registry_version: FAssetRegistryVersion = FAssetRegistryVersion.PreVersioning,
    var header: FAssetRegistryHeader = FAssetRegistryHeader(),
    var store: Store = Store(),
    var asset_data: MutableList<AssetData> = mutableListOf(),
    var dependencies: Dependencies = Dependencies()
) {
    // Rust: retoc/src/asset_registry.rs:640 new
    fun add_asset(asset_data_item: AssetData) {
        asset_data.add(asset_data_item)
    }

    // Rust: retoc/src/asset_registry.rs:648 deserialize
    fun deserialize(stream: InputStream): AssetRegistry {
        // buffer to seekable for pre-FixedTags handling
        val seekable = toSeekable(stream)
        return deserialize_internal(seekable)
    }

    // Rust: retoc/src/asset_registry.rs:703 serialize
    fun serialize(stream: OutputStream) {
        // Always buffer via SeekableByteArrayOutputStream to allow patching for pre-FixedTags
        val tmp = SeekableByteArrayOutputStream()
        serialize_internal(tmp)
        stream.write(tmp.toByteArray())
    }

    private fun deserialize_internal(stream: SeekableByteArrayInputStream): AssetRegistry {
        val versionVal: FGuid = stream.de(FGuid)
        val registry_versionVal: FAssetRegistryVersion = stream.de(FAssetRegistryVersion)

        val headerVal = if (registry_versionVal.value >= FAssetRegistryVersion.AddedHeader.value) {
            stream.de(FAssetRegistryHeader)
        } else {
            FAssetRegistryHeader()
        }

        if (registry_versionVal.value < FAssetRegistryVersion.FixedTags.value) {
            return de_pre_fixed_tags_internal(stream, versionVal, registry_versionVal, headerVal)
        }

        val names = read_name_batch(stream)
        val storeVal = Store.read_with_names_static(stream, names, registry_versionVal)

        val asset_count = stream.read_u32_le().toInt()
        val asset_data_list = read_array(asset_count, stream) { s ->
            val object_path = read_fname(s, names)
            val package_path = read_fname(s, names)
            val asset_class = read_fname(s, names)
            val package_name = read_fname(s, names)
            val asset_name = read_fname(s, names)
            val tags: MapHandle = s.de(MapHandle)
            val bundle_count = s.read_u32_le().toInt()
            val bundles = read_array(bundle_count, s) { ss -> FAssetBundleEntry.read_fname(ss, names) }.toMutableList()
            val chunk_ids: MutableList<UInt> = s.read_vec { it.read_u32_le() }.toMutableList()
            val flags = s.read_u32_le()
            AssetData(
                object_path = object_path,
                package_path = package_path,
                asset_class = asset_class,
                package_name = package_name,
                asset_name = asset_name,
                tags = tags,
                legacy_tags = mutableListOf(),
                bundles = bundles,
                chunk_ids = chunk_ids,
                flags = flags
            )
        }

        val dependenciesVal: Dependencies = stream.de(Dependencies)

        return AssetRegistry(
            version = versionVal,
            registry_version = registry_versionVal,
            header = headerVal,
            store = storeVal,
            asset_data = asset_data_list.toMutableList(),
            dependencies = dependenciesVal
        )
    }

    private fun serialize_internal(stream: SeekableByteArrayOutputStream) {
        stream.ser(version)
        stream.ser(registry_version)

        if (registry_version.value >= FAssetRegistryVersion.AddedHeader.value) {
            stream.ser(header)
        }

        if (registry_version.value < FAssetRegistryVersion.FixedTags.value) {
            ser_pre_fixed_tags_internal(this, stream)
            return
        }

        val name_map = LinkedHashSet<String>()

        fun extract_base(name: String): String = break_down_name_string(name).first

        for (asset in asset_data) {
            name_map.add(extract_base(asset.object_path))
            name_map.add(extract_base(asset.package_path))
            name_map.add(extract_base(asset.asset_class))
            name_map.add(extract_base(asset.package_name))
            name_map.add(extract_base(asset.asset_name))

            for (bundle in asset.bundles) {
                name_map.add(extract_base(bundle.bundle_name))
                for (path in bundle.asset_paths) {
                    name_map.add(extract_base(path.package_name))
                    name_map.add(extract_base(path.asset_name))
                }
            }
        }

        for (name in store.nbl_names) {
            name_map.add(extract_base(name))
        }
        for (name in store.names) {
            name_map.add(extract_base(name))
        }
        if (registry_version.value >= FAssetRegistryVersion.ClassPaths.value) {
            for (path in store.nbl_export_paths) {
                name_map.add(extract_base(path.asset_class.package_name()))
                name_map.add(extract_base(path.asset_class.asset_name()))
                name_map.add(extract_base(path.object_path))
                name_map.add(extract_base(path.package_path))
            }
            for (path in store.export_paths) {
                name_map.add(extract_base(path.asset_class.package_name()))
                name_map.add(extract_base(path.asset_class.asset_name()))
                name_map.add(extract_base(path.object_path))
                name_map.add(extract_base(path.package_path))
            }
        } else {
            for (path in store.nbl_export_paths) {
                when (val ac = path.asset_class) {
                    is AssetClassPath.LegacyClassName -> name_map.add(extract_base(ac.value))
                    is AssetClassPath.TopLevelAssetPath -> {
                        name_map.add(extract_base(ac.value.package_name))
                        name_map.add(extract_base(ac.value.asset_name))
                    }
                }
                name_map.add(extract_base(path.object_path))
                name_map.add(extract_base(path.package_path))
            }
            for (path in store.export_paths) {
                when (val ac = path.asset_class) {
                    is AssetClassPath.LegacyClassName -> name_map.add(extract_base(ac.value))
                    is AssetClassPath.TopLevelAssetPath -> {
                        name_map.add(extract_base(ac.value.package_name))
                        name_map.add(extract_base(ac.value.asset_name))
                    }
                }
                name_map.add(extract_base(path.object_path))
                name_map.add(extract_base(path.package_path))
            }
        }
        for (pair in store.pairs) {
            // pair name is already base; insert directly
            name_map.add(pair.name)
        }

        val namesList: List<String> = name_map.toList()
        write_name_batch(stream, namesList)

        store.write_with_name_map(stream, name_map, registry_version)

        // Build fast lookup for asset writing
        val nameToIndex = HashMap<String, Int>(name_map.size * 2)
        var idxT = 0
        for (n in name_map) nameToIndex[n] = idxT++
        fun write_fname_fast_asset(value: String) {
            val pr = break_down_name_string(value)
            val base = pr.first
            val number = pr.second
            val idx = nameToIndex[base] ?: throw IllegalArgumentException("name should already be in name map: $base")
            if (number != 0) {
                stream.write_u32_le(idx.toUInt() or 0x80000000u)
                stream.write_i32_le(number)
            } else {
                stream.write_u32_le(idx.toUInt())
            }
        }

        stream.write_u32_le(asset_data.size.toUInt())
        for (asset in asset_data) {
            write_fname_fast_asset(asset.object_path)
            write_fname_fast_asset(asset.package_path)
            write_fname_fast_asset(asset.asset_class)
            write_fname_fast_asset(asset.package_name)
            write_fname_fast_asset(asset.asset_name)
            stream.ser(asset.tags)
            stream.write_u32_le(asset.bundles.size.toUInt())
            for (bundle in asset.bundles) {
                // bundle write using fast map
                val bpr = break_down_name_string(bundle.bundle_name)
                val bbase = bpr.first
                val bnum = bpr.second
                val bidx = nameToIndex[bbase] ?: throw IllegalArgumentException("name should already be in name map: $bbase")
                if (bnum != 0) {
                    stream.write_u32_le(bidx.toUInt() or 0x80000000u)
                    stream.write_i32_le(bnum)
                } else {
                    stream.write_u32_le(bidx.toUInt())
                }
                stream.write_i32_le(bundle.asset_paths.size)
                for (path in bundle.asset_paths) {
                    val p1 = break_down_name_string(path.package_name)
                    val pb1 = p1.first
                    val pn1 = p1.second
                    val pi1 = nameToIndex[pb1] ?: throw IllegalArgumentException("name should already be in name map: $pb1")
                    if (pn1 != 0) { stream.write_u32_le(pi1.toUInt() or 0x80000000u); stream.write_i32_le(pn1) } else stream.write_u32_le(pi1.toUInt())
                    val p2 = break_down_name_string(path.asset_name)
                    val pb2 = p2.first
                    val pn2 = p2.second
                    val pi2 = nameToIndex[pb2] ?: throw IllegalArgumentException("name should already be in name map: $pb2")
                    if (pn2 != 0) { stream.write_u32_le(pi2.toUInt() or 0x80000000u); stream.write_i32_le(pn2) } else stream.write_u32_le(pi2.toUInt())
                    stream.write_string("")
                }
            }
            // chunk_ids
            stream.write_u32_le(asset.chunk_ids.size.toUInt())
            for (cid in asset.chunk_ids) stream.write_u32_le(cid)
            stream.write_u32_le(asset.flags)
        }

        stream.ser(dependencies)
    }

    private fun toSeekable(stream: InputStream): SeekableByteArrayInputStream {
        if (stream is SeekableByteArrayInputStream) return stream
        val bytes = stream.readBytes()
        return SeekableByteArrayInputStream(bytes)
    }

    companion object {
        // Rust: retoc/src/asset_registry.rs:640 new
        fun new(registry_version: FAssetRegistryVersion): AssetRegistry {
            return AssetRegistry(registry_version = registry_version)
        }

        // Rust: retoc/src/asset_registry.rs:648 deserialize static
        fun deserialize_static(stream: InputStream): AssetRegistry {
            return AssetRegistry().deserialize(stream)
        }

        // Rust: retoc/src/asset_registry.rs:816 de_pre_fixed_tags
        fun de_pre_fixed_tags(stream: InputStream, version: FGuid, registry_version: FAssetRegistryVersion, header: FAssetRegistryHeader): AssetRegistry {
            val seekable = if (stream is SeekableByteArrayInputStream) stream else SeekableByteArrayInputStream(stream.readBytes())
            return AssetRegistry().de_pre_fixed_tags_internal(seekable, version, registry_version, header)
        }

        private fun AssetRegistry.de_pre_fixed_tags_internal(stream: SeekableByteArrayInputStream, version: FGuid, registry_version: FAssetRegistryVersion, header: FAssetRegistryHeader): AssetRegistry {
            val name_offset = stream.read_i64_le()
            val data_start = stream.position()
            val names: List<String> = if (name_offset > 0 && data_start > 0) {
                stream.seek(name_offset)
                val name_count = stream.read_i32_le()
                check(name_count >= 0) { "Invalid name count: $name_count" }
                val list = mutableListOf<String>()
                repeat(name_count) {
                    val name: String = stream.read_string()
                    val _hashA = stream.read_u16_le()
                    val _hashB = stream.read_u16_le()
                    list.add(name)
                }
                stream.seek(data_start)
                list
            } else {
                emptyList()
            }

            val asset_count = stream.read_u32_le().toInt()
            val asset_data_list = read_array(asset_count, stream) { s ->
                AssetData(
                    object_path = read_fname_pre_fixed_tags(s, names),
                    package_path = read_fname_pre_fixed_tags(s, names),
                    asset_class = read_fname_pre_fixed_tags(s, names),
                    package_name = read_fname_pre_fixed_tags(s, names),
                    asset_name = read_fname_pre_fixed_tags(s, names),
                    tags = MapHandle(has_numberless_keys = false, num = 0u, pair_begin = 0u),
                    legacy_tags = read_simple_tags_internal(s, names),
                    bundles = mutableListOf(),
                    chunk_ids = s.read_vec { it.read_u32_le() }.toMutableList(),
                    flags = s.read_u32_le()
                )
            }

            return AssetRegistry(
                version = version,
                registry_version = registry_version,
                header = header,
                store = Store(),
                asset_data = asset_data_list.toMutableList(),
                dependencies = Dependencies()
            )
        }

        // Rust: retoc/src/asset_registry.rs:868 read_simple_tags
        fun read_simple_tags(stream: InputStream, names: List<String>): MutableList<kotlin.Pair<String, String>> {
            return read_simple_tags_internal(stream, names)
        }

        private fun read_simple_tags_internal(stream: InputStream, names: List<String>): MutableList<kotlin.Pair<String, String>> {
            val tag_count = stream.read_i32_le()
            val tags = mutableListOf<kotlin.Pair<String, String>>()
            repeat(tag_count) {
                val key = read_fname_pre_fixed_tags(stream, names)
                val value: String = stream.read_string()
                tags.add(kotlin.Pair(key, value))
            }
            return tags
        }

        // Rust: retoc/src/asset_registry.rs:881 ser_pre_fixed_tags
        fun ser_pre_fixed_tags(registry: AssetRegistry, stream: OutputStream) {
            val tmp = if (stream is SeekableByteArrayOutputStream) stream else SeekableByteArrayOutputStream().also { ser_pre_fixed_tags_internal(registry, it); stream.write(it.toByteArray()); return }
            ser_pre_fixed_tags_internal(registry, tmp)
        }

        private fun ser_pre_fixed_tags_internal(registry: AssetRegistry, stream: SeekableByteArrayOutputStream) {
            val name_map = LinkedHashSet<String>()
            for (asset in registry.asset_data) {
                name_map.add(break_down_name_string(asset.object_path).first)
                name_map.add(break_down_name_string(asset.package_path).first)
                name_map.add(break_down_name_string(asset.asset_class).first)
                name_map.add(break_down_name_string(asset.package_name).first)
                name_map.add(break_down_name_string(asset.asset_name).first)
                for ((key, _) in asset.legacy_tags) {
                    name_map.add(break_down_name_string(key).first)
                }
            }

            val name_offset_pos = stream.position()
            stream.write_i64_le(0L)

            stream.write_u32_le(registry.asset_data.size.toUInt())
            for (asset in registry.asset_data) {
                write_fname_pre_fixed_tags(stream, asset.object_path, name_map)
                write_fname_pre_fixed_tags(stream, asset.package_path, name_map)
                write_fname_pre_fixed_tags(stream, asset.asset_class, name_map)
                write_fname_pre_fixed_tags(stream, asset.package_name, name_map)
                write_fname_pre_fixed_tags(stream, asset.asset_name, name_map)

                stream.write_i32_le(asset.legacy_tags.size)
                for ((key, value) in asset.legacy_tags) {
                    write_fname_pre_fixed_tags(stream, key, name_map)
                    stream.write_string(value)
                }

                stream.write_u32_le(asset.chunk_ids.size.toUInt())
                for (cid in asset.chunk_ids) stream.write_u32_le(cid)

                stream.write_u32_le(asset.flags)
            }

            stream.write_u64_le(0UL)

            val name_table_offset = stream.position()
            stream.write_i32_le(name_map.size)

            for (name in name_map) {
                stream.write_string(name)
                val (a, b) = generate_name_hash(name)
                stream.write_u16_le(a)
                stream.write_u16_le(b)
            }

            val end_pos = stream.position()
            stream.seek(name_offset_pos)
            stream.write_i64_le(name_table_offset)
            stream.seek(end_pos)
        }

        // Rust: retoc/src/asset_registry.rs:946 write_fname_pre_fixed_tags
        fun write_fname_pre_fixed_tags(stream: OutputStream, value: String, name_map: LinkedHashSet<String>) {
            val pair: kotlin.Pair<String, Int> = break_down_name_string(value)
            val base_name = pair.first
            val number = pair.second
            val index = name_map.indexOf(base_name).takeIf { it >= 0 } ?: throw IllegalArgumentException("name should already be in name map: $base_name")
            stream.write_i32_le(index)
            stream.write_i32_le(number)
        }
    }

    // instance helpers delegating to companion for parity
    fun de_pre_fixed_tags(stream: InputStream, version: FGuid, registry_version: FAssetRegistryVersion, header: FAssetRegistryHeader): AssetRegistry {
        return Companion.de_pre_fixed_tags(stream, version, registry_version, header)
    }

    fun read_simple_tags(stream: InputStream, names: List<String>): MutableList<kotlin.Pair<String, String>> {
        return Companion.read_simple_tags(stream, names)
    }

    fun ser_pre_fixed_tags(stream: OutputStream) {
        Companion.ser_pre_fixed_tags(this, stream)
    }

    fun write_fname_pre_fixed_tags(stream: OutputStream, value: String, name_map: LinkedHashSet<String>) {
        Companion.write_fname_pre_fixed_tags(stream, value, name_map)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:955 pub mod dbg
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_registry.rs:955
object dbg {
    // Rust: retoc/src/asset_registry.rs:961 Dbg<'reg,'data,D>
    class Dbg<D>(val reg: AssetRegistry, val data: D) {
        companion object {
            fun <D> new(reg: AssetRegistry, data: D): Dbg<D> = Dbg(reg, data)
        }

        override fun toString(): String {
            return "Dbg(data=$data)"
        }

        // Rust: retoc/src/asset_registry.rs:972 Debug for Dbg<AssetData>
        fun debug_asset_data(): String {
            if (data is AssetData) {
                val d = data as AssetData
                return "AssetData(object_path=${d.object_path}, package_path=${d.package_path}, asset_class=${d.asset_class}, package_name=${d.package_name}, asset_name=${d.asset_name}, tags=${Dbg(reg, d.tags)}, legacy_tags=${d.legacy_tags}, bundles=${d.bundles}, chunk_ids=${d.chunk_ids}, flags=${d.flags})"
            }
            return toString()
        }

        // Rust: retoc/src/asset_registry.rs:989 Debug for Dbg<MapHandle>
        fun debug_map_handle(): String {
            if (data is MapHandle) {
                val mh = data as MapHandle
                val start = mh.pair_begin.toInt()
                val end = start + mh.num.toInt()
                val sb = StringBuilder("[")
                for (i in start until end) {
                    val pair = reg.store.pairs.getOrNull(i)
                    if (pair != null) {
                        sb.append(Dbg(reg, pair).debug_pair()).append(", ")
                    }
                }
                sb.append("]")
                return sb.toString()
            }
            return toString()
        }

        // Rust: retoc/src/asset_registry.rs:1003 Debug for Dbg<Pair>
        fun debug_pair(): String {
            if (data is Pair) {
                val p = data as Pair
                val store = reg.store
                val idx = p.index.toInt()
                val valueStr = when (p.type_) {
                    TagType.AnsiString -> store.ansi_strings.getOrNull(idx)?.let { "value=$it" } ?: ""
                    TagType.WideString -> store.wide_strings.getOrNull(idx)?.let { "value=$it" } ?: ""
                    TagType.NumberlessName -> store.nbl_names.getOrNull(idx)?.let { "value=$it" } ?: ""
                    TagType.Name -> store.names.getOrNull(idx)?.let { "value=$it" } ?: ""
                    TagType.NumberlessExportPath -> store.nbl_export_paths.getOrNull(idx)?.let { "value=$it" } ?: ""
                    TagType.ExportPath -> store.export_paths.getOrNull(idx)?.let { "value=$it" } ?: ""
                    TagType.LocalizedText -> store.texts.getOrNull(idx)?.let { "value=$it" } ?: ""
                }
                return "Pair(name=${p.name}, type=${p.type_}, $valueStr)"
            }
            return toString()
        }
    }

    // convenience top-level helper mirroring Rust Dbg::new
    fun <D> new_dbg(reg: AssetRegistry, data: D): Dbg<D> = Dbg.new(reg, data)
}

// ---------------------------------------------------------------------------
// Additional helper: read_fname_pre_fixed_tags write helpers already defined above
// Keep parity stubs for Store full logic (not required for BUILD SUCCESSFUL)
// ---------------------------------------------------------------------------

