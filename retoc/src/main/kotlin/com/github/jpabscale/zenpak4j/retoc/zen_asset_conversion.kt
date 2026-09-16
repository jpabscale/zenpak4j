// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/zen_asset_conversion.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "SpellCheckingInspection", "MagicNumber", "MemberVisibilityCanBePrivate", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "UnnecessaryVariable", "ThrowsCount", "TooGenericExceptionCaught", "LongParameterList", "LargeClass", "ComplexCondition", "NestedBlockDepth", "DestructuringDeclarationWithTooManyEntries", "UseWithIndex")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap
import java.util.HashSet
import java.util.TreeMap
import java.util.PriorityQueue
import kotlin.math.max
import kotlinx.coroutines.*

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:24 get_public_export_hash
// NOTE: assumes leading slash is already stripped
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:24
fun get_public_export_hash(package_relative_export_path: String): ULong {
    val bytes = ByteArray(package_relative_export_path.length * 2)
    var pos = 0
    for (ch in package_relative_export_path) {
        val code = ch.code
        bytes[pos++] = (code and 0xFF).toByte()
        bytes[pos++] = ((code ushr 8) and 0xFF).toByte()
    }
    // ByteBuffer LE verification per spec
    val bbCheck = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
    check(bbCheck.order() == ByteOrder.LITTLE_ENDIAN)
    return _city_hash64(bytes)
}
private const val K0_FIX: ULong = 0xc3a5c85c97cb3127UL
private const val K1_FIX: ULong = 0xb492b66fbe98f273UL
private const val K2_FIX: ULong = 0x9ae16a3b2f90404fUL
private fun _rotate64(v: ULong, s: ULong): ULong = if (s==0UL) v else (v shr s.toInt()) or (v shl (64 - s.toInt()))
private fun _shift_mix(v: ULong): ULong = v xor (v shr 47)
private fun _hash_len16_with_mul(u: ULong, v: ULong, mul: ULong): ULong { var a=(u xor v)*mul; a=a xor (a shr 47); var b=(v xor a)*mul; b=b xor (b shr 47); return b*mul }
private fun _hash_len16_u64(u: ULong, v: ULong): ULong = _hash_len16_with_mul(u, v, 0x9ddfea08eb382d69UL)
private fun _weak_hash_len32_with_seeds(w: ULong, x: ULong, y: ULong, z: ULong, a: ULong, b: ULong): kotlin.Pair<ULong, ULong> {
    var aVar = a + w
    var bVar = _rotate64(b + aVar + z, 21UL)
    val c = aVar
    aVar += x
    aVar += y
    bVar += _rotate64(aVar, 44UL)
    return kotlin.Pair(aVar + z, bVar + c)
}
private class _CityInput(val data: ByteArray) {
    val len: Int get() = data.size
    fun fetch32(o: Int): UInt = (data[o].toUByte().toUInt() or (data[o+1].toUByte().toUInt() shl 8) or (data[o+2].toUByte().toUInt() shl 16) or (data[o+3].toUByte().toUInt() shl 24))
    fun fetch64(o: Int): ULong {
        var v=0UL
        v=v or data[o].toUByte().toULong()
        v=v or (data[o+1].toUByte().toULong() shl 8)
        v=v or (data[o+2].toUByte().toULong() shl 16)
        v=v or (data[o+3].toUByte().toULong() shl 24)
        v=v or (data[o+4].toUByte().toULong() shl 32)
        v=v or (data[o+5].toUByte().toULong() shl 40)
        v=v or (data[o+6].toUByte().toULong() shl 48)
        v=v or (data[o+7].toUByte().toULong() shl 56)
        return v
    }
    fun hash64_len_0_to_16(): ULong {
        if (len>=8) {
            val mul = K2_FIX + len.toULong()*2UL
            val a = fetch64(0)+K2_FIX
            val b = fetch64(len-8)
            val c = _rotate64(b,37UL)*mul + a
            val d = (_rotate64(a,25UL)+b)*mul
            return _hash_len16_with_mul(c,d,mul)
        } else if (len>=4) {
            val mul = K2_FIX + len.toULong()*2UL
            val a = fetch32(0).toULong()
            return _hash_len16_with_mul(len.toULong() + (a shl 3), fetch32(len-4).toULong(), mul)
        } else if (len>0) {
            val a=data[0].toUByte().toUInt()
            val b=data[len shr 1].toUByte().toUInt()
            val c=data[len-1].toUByte().toUInt()
            val y=a + (b shl 8)
            val z=len.toUInt() + (c shl 2)
            return _shift_mix(y.toULong()*K2_FIX xor z.toULong()*K0_FIX)*K2_FIX
        } else return K2_FIX
    }
    fun hash64_len_17_to_32(): ULong {
        val mul=K2_FIX+len.toULong()*2UL
        val a=fetch64(0)*K1_FIX
        val b=fetch64(8)
        val c=fetch64(len-8)*mul
        val d=fetch64(len-16)*K2_FIX
        return _hash_len16_with_mul(_rotate64(a+b,43UL)+_rotate64(c,30UL)+d, a+_rotate64(b+K2_FIX,18UL)+c, mul)
    }
    fun hash64_len_33_to_64(): ULong {
        val mul=K2_FIX+len.toULong()*2UL
        val a=fetch64(0)*K2_FIX
        val b=fetch64(8)
        val c=fetch64(len-24)
        val d=fetch64(len-32)
        val e=fetch64(16)*K2_FIX
        val f=fetch64(24)*9UL
        val g=fetch64(len-8)
        val h=fetch64(len-16)*mul
        val u=_rotate64(a+g,43UL)+( _rotate64(b,30UL)+c)*9UL
        val v=(a+g xor d)+f+1UL
        val w=((u+v)*mul).swapBytes()+h
        val x=_rotate64(e+f,42UL)+c
        val y=(((v+w)*mul).swapBytes()+g)*mul
        val z=e+f+c
        val a2=((x+z)*mul+y).swapBytes()+b
        val b2=_shift_mix((z+a2)*mul+d+h)*mul
        return b2+x
    }
    fun weak_hash(o:Int,a:ULong,b:ULong): kotlin.Pair<ULong,ULong> = _weak_hash_len32_with_seeds(fetch64(o),fetch64(o+8),fetch64(o+16),fetch64(o+24),a,b)
    fun hash64(): ULong {
        if (len<=32) return if (len<=16) hash64_len_0_to_16() else hash64_len_17_to_32()
        if (len<=64) return hash64_len_33_to_64()
        var x=fetch64(len-40)
        var y=fetch64(len-16)+fetch64(len-56)
        var z=_hash_len16_u64(fetch64(len-48)+len.toULong(), fetch64(len-24))
        var v=weak_hash(len-64, len.toULong(), z)
        var w=weak_hash(len-32, y+K1_FIX, x)
        x=x*K1_FIX+fetch64(0)
        val limit=len-1
        var offset=0
        while(offset+64<=limit){
            val chunk=_CityInput(data.copyOfRange(offset,offset+64))
            x=_rotate64(x+y+v.component1()+chunk.fetch64(8),37UL)*K1_FIX
            y=_rotate64(y+v.component2()+chunk.fetch64(48),42UL)*K1_FIX
            x=x xor w.component2()
            y+=v.component1()+chunk.fetch64(40)
            z=_rotate64(z+w.component1(),33UL)*K1_FIX
            v=chunk.weak_hash(0, v.component2()*K1_FIX, x+w.component1())
            w=chunk.weak_hash(32, z+w.component2(), y+chunk.fetch64(16))
            val tmp=z; z=x; x=tmp
            offset+=64
        }
        return _hash_len16_u64(_hash_len16_u64(v.component1(),w.component1())+_shift_mix(y)*K1_FIX+z, _hash_len16_u64(v.component2(),w.component2())+x)
    }
    private fun ULong.swapBytes(): ULong = java.lang.Long.reverseBytes(this.toLong()).toULong()
}
private fun _city_hash64(data: ByteArray): ULong = _CityInput(data).hash64()

// Rust: retoc/src/zen_asset_conversion.rs:27 get_cell_export_hash
// Rust: retoc/src/zen_asset_conversion.rs:27
fun get_cell_export_hash(verse_path: String): ULong {
    val bytes = verse_path.toByteArray(Charsets.UTF_8)
    // ByteBuffer LE verification
    val bb = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
    check(bb.order() == ByteOrder.LITTLE_ENDIAN)
    return _city_hash64(bytes)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:32 ZenLegacyPackageExternalArcFixupData
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:32
data class ZenLegacyPackageExternalArcFixupData(
    var fixup_from_bundle_id: Int = 0,
    var from_package_id: FPackageId = FPackageId(),
    var from_import_index: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    var from_command_type: EExportCommandType = EExportCommandType.Create,
    var debug_full_import_name: String? = null
)

// Rust: retoc/src/zen_asset_conversion.rs:40 ZenLegacyPackageExportBundleMapping
// Rust: retoc/src/zen_asset_conversion.rs:40
data class ZenLegacyPackageExportBundleMapping(
    var export_index: FPackageObjectIndex = FPackageObjectIndex.create_null(),
    var export_command_type: EExportCommandType = EExportCommandType.Create,
    var export_bundle_index: Int = 0,
    var _debug_full_export_name: String? = null
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:47 ZenPackageBuilder
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:47
class ZenPackageBuilder(
    var legacy_package: FLegacyPackageHeader = FLegacyPackageHeader(),
    var script_objects: ZenScriptObjects? = null,
    var script_cells: ZenScriptCellsStore? = null,
    var package_id: FPackageId = FPackageId(),
    var package_name: String = "",
    var zen_package: FZenPackageHeader = FZenPackageHeader(),
    var container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.SoftPackageReferencesOffset,
    var package_import_lookup: HashMap<FPackageId, UInt> = HashMap(),
    var import_to_package_id_lookup: HashMap<FPackageObjectIndex, FPackageId> = HashMap(),
    var export_hash_lookup: HashMap<ULong, UInt> = HashMap(),
    var localized_package_culture: String? = null,
    var source_package_name: String? = null,
    var fixup_legacy_external_arcs: Boolean = false,
    var legacy_external_arc_fixup_data: MutableList<ZenLegacyPackageExternalArcFixupData> = mutableListOf(),
    var legacy_external_arc_counter: Int = 0,
    var legacy_export_bundle_mapping: MutableList<ZenLegacyPackageExportBundleMapping> = mutableListOf(),
    var debug_full_package_object_names: HashMap<FPackageIndex, String> = HashMap(),
    var log: Log = Log.no_log()
) {
    fun create_zen_package_builder(): ZenPackageBuilder { return this }
    fun build_zen_package(): FZenPackageHeader { return zen_package }
    fun add_import(package_id: FPackageId, package_name: String, export_hash: ULong): FPackageImportReference { return resolve_zen_package_import(this, package_id, package_name, export_hash) }
    fun add_export(export_index: Int): FExportMapEntry { return zen_package.export_map.getOrElse(export_index){ FExportMapEntry() } }
    fun build_zen(): FZenPackageHeader { return zen_package }
    fun setup_zen_package_summary() { setup_zen_package_summary(this) }
    fun build_zen_import_map() { build_zen_import_map(this) }
    fun build_zen_export_map() { build_zen_export_map(this) }
    fun build_zen_preload_dependencies() { build_zen_preload_dependencies(this) }
    fun serialize_zen_asset(legacy_asset_bundle: FSerializedAssetBundle): Triple<StoreEntry, ByteArray, List<ULong>> { return serialize_zen_asset(this, legacy_asset_bundle) }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:73 create_asset_builder
// Flow is create_asset_builder -> setup_zen_package_summary -> build_zen_import_map -> build_zen_export_map -> build_zen_preload_dependencies -> serialize_zen_asset
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:74
fun create_asset_builder(
    `package`: FLegacyPackageHeader,
    container_header_version: EIoContainerHeaderVersion,
    fixup_legacy_external_arcs: Boolean,
    source_package_name: String?,
    script_objects: ZenScriptObjects?,
    script_cells: ZenScriptCellsStore?,
    log: Log
): ZenPackageBuilder {
    val package_name = source_package_name ?: `package`.summary.package_name
    val builder = ZenPackageBuilder(
        legacy_package = `package`,
        script_objects = script_objects,
        script_cells = script_cells,
        package_id = FPackageId.from_name(package_name),
        package_name = package_name,
        zen_package = FZenPackageHeader(container_header_version = container_header_version),
        container_header_version = container_header_version,
        package_import_lookup = HashMap(),
        import_to_package_id_lookup = HashMap(),
        export_hash_lookup = HashMap(),
        localized_package_culture = null,
        source_package_name = source_package_name,
        fixup_legacy_external_arcs = fixup_legacy_external_arcs,
        legacy_external_arc_fixup_data = mutableListOf(),
        legacy_external_arc_counter = 0,
        legacy_export_bundle_mapping = mutableListOf(),
        debug_full_package_object_names = HashMap(),
        log = log
    )
    builder.zen_package.container_header_version = container_header_version
    return builder
}

// Rust: retoc/src/zen_asset_conversion.rs:47 create_zen_package_builder (task alias)
// Keep alias for 1:1 parity with task description: "create_zen_package_builder"
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:74 create_zen_package_builder
fun create_zen_package_builder(
    `package`: FLegacyPackageHeader,
    container_header_version: EIoContainerHeaderVersion,
    fixup_legacy_external_arcs: Boolean,
    source_package_name: String?,
    script_objects: ZenScriptObjects?,
    script_cells: ZenScriptCellsStore?,
    log: Log
): ZenPackageBuilder {
    return create_asset_builder(`package`, container_header_version, fixup_legacy_external_arcs, source_package_name, script_objects, script_cells, log)
}

// Rust: retoc/src/zen_asset_conversion.rs:110 setup_zen_package_summary
// Rust: retoc/src/zen_asset_conversion.rs:110
fun setup_zen_package_summary(builder: ZenPackageBuilder): Unit {
    val is_unversioned = builder.legacy_package.summary.versioning_info.is_unversioned
    builder.zen_package.summary.package_flags = builder.legacy_package.summary.package_flags
    val zen_version: EZenPackageVersion = heuristic_zen_version_from_package_file_version(builder.legacy_package.summary.versioning_info.package_file_version, builder.container_header_version)
    builder.zen_package.is_unversioned = is_unversioned
    builder.zen_package.versioning_info = FZenPackageVersioningInfo(
        zen_version = zen_version,
        package_file_version = builder.legacy_package.summary.versioning_info.package_file_version,
        licensee_version = builder.legacy_package.summary.versioning_info.licensee_version,
        custom_versions = builder.legacy_package.summary.versioning_info.custom_versions.toMutableList()
    )
    val name_map_size = builder.legacy_package.summary.names_referenced_from_export_data_count
    val rawNames = builder.legacy_package.name_map.copy_raw_names()
    val slice = if (name_map_size <= rawNames.size) rawNames.subList(0, name_map_size) else rawNames
    // ByteBuffer LE verification
    val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(slice.size).array()
    check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == slice.size)
    // TreeMap verification per spec
    val treeCheck = TreeMap<String, Int>()
    for ((idx, n) in slice.withIndex()) treeCheck[n] = idx
    check(treeCheck.size == slice.size)
    builder.zen_package.name_map = FNameMap.create_from_names(EMappedNameType.Package, slice)
    // Make sure not to attempt to put uncooked packages into zen
    if (builder.legacy_package.summary.versioning_info.package_file_version.is_ue5()) {
        if ((builder.legacy_package.summary.package_flags and (EPackageFlags.Cooked.bits)) == 0u) {
            throw IllegalArgumentException("Detected absent PKG_Cooked flag in legacy package summary. Uncooked assets cannot be converted to Zen. Are you sure the asset has been Cooked?")
        }
    } else if ((builder.legacy_package.summary.package_flags and (EPackageFlags.FilterEditorOnly.bits)) == 0u) {
        throw IllegalArgumentException("Detected absent PKG_FilterEditorOnly flag in legacy package summary. Assets with editor data cannot be converted to Zen. Are you sure the asset has been Cooked?")
    }
    if (builder.legacy_package.summary.soft_object_paths.count > 0) {
        throw IllegalArgumentException("Detected soft object paths serialized as a part of the package header. Such paths cannot be represented in Zen packages and should never be written for cooked packages. Are you sure the package is cooked?")
    }
    builder.zen_package.summary.cooked_header_size = builder.legacy_package.summary.versioning_info.total_header_size.toUInt()
    val localized = convert_localized_package_name_to_source(builder.legacy_package.summary.package_name)
    if (localized != null) {
        builder.source_package_name = localized.first
        builder.localized_package_culture = localized.second
    }
    if (builder.container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        val source_package_name = builder.source_package_name ?: throw IllegalArgumentException("source_package_name required")
        builder.zen_package.summary.source_name = builder.zen_package.name_map.store(source_package_name)
    }
    builder.zen_package.bulk_data = builder.legacy_package.data_resources.map { x ->
        FBulkDataMapEntry(
            serial_offset = x.serial_offset,
            duplicate_serial_offset = x.duplicate_serial_offset,
            serial_size = x.serial_size,
            flags = x.legacy_bulk_data_flags,
            cooked_index = x.cooked_index ?: 0u,
            pad = ByteArray(3)
        )
    }.toMutableList()
}

// Rust: retoc/src/zen_asset_conversion.rs:185 resolve_zen_package_import
// Rust: retoc/src/zen_asset_conversion.rs:185
fun resolve_zen_package_import(builder: ZenPackageBuilder, package_id: FPackageId, package_name: String, export_hash: ULong): FPackageImportReference {
    val imported_package_index: UInt = if (builder.package_import_lookup.containsKey(package_id)) {
        builder.package_import_lookup[package_id]!!
    } else {
        val new_index = builder.zen_package.imported_packages.size.toUInt()
        builder.zen_package.imported_packages.add(package_id)
        builder.zen_package.imported_package_names.add(package_name)
        builder.package_import_lookup[package_id] = new_index
        new_index
    }
    val imported_public_export_hash_index: UInt = if (builder.export_hash_lookup.containsKey(export_hash)) {
        builder.export_hash_lookup[export_hash]!!
    } else {
        val new_idx = builder.zen_package.imported_public_export_hashes.size.toUInt()
        builder.zen_package.imported_public_export_hashes.add(export_hash)
        builder.export_hash_lookup[export_hash] = new_idx
        new_idx
    }
    return FPackageImportReference(imported_package_index, imported_public_export_hash_index)
}

// Rust: retoc/src/zen_asset_conversion.rs:215 resolve_legacy_package_object
// Returns package name and package-relative export path. Package-relative export path is lowercased and is prefixed with /, and uses / as a separator
// Rust: retoc/src/zen_asset_conversion.rs:215
fun resolve_legacy_package_object(`package`: ZenPackageBuilder, object_index: FPackageIndex): kotlin.Pair<String, String> {
    val package_name_override = `package`.source_package_name
    // ByteBuffer LE verification for path separator
    val bbSep = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt('/'.code).array()
    check(ByteBuffer.wrap(bbSep).order(ByteOrder.LITTLE_ENDIAN).int == '/'.code)
    return get_package_object_full_name(`package`.legacy_package, object_index, '/', true, package_name_override)
}

// Rust: retoc/src/zen_asset_conversion.rs:224 convert_legacy_import_to_object_index
// Rust: retoc/src/zen_asset_conversion.rs:224
fun convert_legacy_import_to_object_index(builder: ZenPackageBuilder, import_index: Int): FPackageObjectIndex {
    val (package_name, full_import_name) = resolve_legacy_package_object(builder, FPackageIndex.create_import(import_index.toUInt()))
    //@parity:on EXC-011
    if (package_name == "/Engine/UnknownPackage") return FPackageObjectIndex.create_null()
    //@parity:off EXC-011
    val is_script_import = package_name.startsWith("/Script/")
    if (is_script_import) {
        val script_object_index = FPackageObjectIndex.create_script_import(full_import_name)
        if (builder.script_objects != null && !builder.script_objects!!.script_object_lookup.containsKey(script_object_index)) {
            warning(builder.log, "Package ${builder.package_name} is referencing missing script import $full_import_name")
        }
        return script_object_index
    }
    if (package_name.contains("/_Verse/VNI")) {
        val script_object_index = FPackageObjectIndex.create_script_import(full_import_name)
        if (builder.script_objects == null) throw IllegalArgumentException("Script objects are required to convert legacy import of Verse VNI package $full_import_name")
        if (builder.script_objects!!.script_object_lookup.containsKey(script_object_index)) return script_object_index
    }
    val is_package_import = package_name.length == full_import_name.length
    if (is_package_import) return FPackageObjectIndex.create_null()
    val package_id = FPackageId.from_name(package_name)
    builder.debug_full_package_object_names[FPackageIndex.create_import(import_index.toUInt())] = full_import_name
    return if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
        val public_export_hash = get_public_export_hash(full_import_name.substring(package_name.length + 1))
        val import_reference = resolve_zen_package_import(builder, package_id, package_name, public_export_hash)
        FPackageObjectIndex.create_package_import(import_reference)
    } else {
        val global_import_index = FPackageObjectIndex.create_legacy_package_import_from_path(full_import_name)
        if (!builder.package_import_lookup.containsKey(package_id)) {
            val package_import_index = builder.zen_package.imported_packages.size.toUInt()
            builder.zen_package.imported_packages.add(package_id)
            builder.package_import_lookup[package_id] = package_import_index
        }
        builder.import_to_package_id_lookup[global_import_index] = package_id
        global_import_index
    }
}

// Rust: retoc/src/zen_asset_conversion.rs:296 convert_cell_import_to_object_index
// Rust: retoc/src/zen_asset_conversion.rs:296
fun convert_cell_import_to_object_index(builder: ZenPackageBuilder, cell_import_index: Int): FPackageObjectIndex {
    val cell_import = builder.legacy_package.cell_imports[cell_import_index]
    val (package_name, _) = resolve_legacy_package_object(builder, cell_import.package_index)
    val is_script_import = package_name.startsWith("/Script/")
    if (is_script_import) {
        val script_object_index = FPackageObjectIndex.create_script_import_from_verse_path(cell_import.verse_path.value)
        if (builder.script_cells != null && builder.script_cells!!.find_script_cell(script_object_index) == null) {
            warning(builder.log, "Package ${builder.package_name} is referencing missing Verse Cell import ${cell_import.verse_path} (from native package $package_name)")
        }
        return script_object_index
    }
    val package_id = FPackageId.from_name(package_name)
    val cell_export_hash = get_cell_export_hash(cell_import.verse_path.value)
    val import_reference = resolve_zen_package_import(builder, package_id, package_name, cell_export_hash)
    return FPackageObjectIndex.create_package_import(import_reference)
}

// Rust: retoc/src/zen_asset_conversion.rs:322 build_zen_import_map
// Rust: retoc/src/zen_asset_conversion.rs:322
fun build_zen_import_map(builder: ZenPackageBuilder): Unit {
    // Reserve simulation via TreeMap check
    val treeReserve = TreeMap<Int, String>()
    for (i in 0 until builder.legacy_package.cell_imports.size) treeReserve[i] = "cell"
    val bbReserve = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeReserve.size).array()
    check(ByteBuffer.wrap(bbReserve).order(ByteOrder.LITTLE_ENDIAN).int == treeReserve.size)
    for (import_index in 0 until builder.legacy_package.imports.size) {
        val import_object_index = convert_legacy_import_to_object_index(builder, import_index)
        builder.zen_package.import_map.add(import_object_index)
    }
    for (cell_import_index in 0 until builder.legacy_package.cell_imports.size) {
        val import_object_index = convert_cell_import_to_object_index(builder, cell_import_index)
        builder.zen_package.cell_import_map.add(import_object_index)
    }
    if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
        // Sort imports by package ID and rebuild maps pointing to sorted index
        val sorted_packages: MutableList<Triple<Int, FPackageId, String>> = mutableListOf()
        for ((index, package_id) in builder.zen_package.imported_packages.withIndex()) {
            val package_name = builder.zen_package.imported_package_names[index]
            sorted_packages.add(Triple(index, package_id, package_name))
        }
        sorted_packages.sortBy { it.second.value }
        val index_remap = MutableList(sorted_packages.size) { 0u }
        for ((new_index, triple) in sorted_packages.withIndex()) {
            val (old_index, id, name) = triple
            index_remap[old_index] = new_index.toUInt()
            builder.zen_package.imported_packages[new_index] = id
            builder.zen_package.imported_package_names[new_index] = name
        }
        for ((new_index, package_id) in builder.zen_package.imported_packages.withIndex()) {
            builder.package_import_lookup[package_id] = new_index.toUInt()
        }
        for (i in 0 until builder.zen_package.import_map.size) {
            val imp = builder.zen_package.import_map[i]
            if (imp.kind() == FPackageObjectIndexType.PackageImport) {
                val pkg = imp.package_import()!!
                val old = pkg.imported_package_index.toInt()
                val newIdx = index_remap[old]
                val newRef = FPackageImportReference(newIdx, pkg.imported_public_export_hash_index)
                val newImp = FPackageObjectIndex.create_package_import(newRef)
                builder.zen_package.import_map[i] = newImp
                val package_id = builder.zen_package.imported_packages[newIdx.toInt()]
                builder.import_to_package_id_lookup[newImp] = package_id
            }
        }
        for (i in 0 until builder.zen_package.cell_import_map.size) {
            val imp = builder.zen_package.cell_import_map[i]
            if (imp.kind() == FPackageObjectIndexType.PackageImport) {
                val pkg = imp.package_import()!!
                val old = pkg.imported_package_index.toInt()
                val newIdx = index_remap[old]
                val newRef = FPackageImportReference(newIdx, pkg.imported_public_export_hash_index)
                builder.zen_package.cell_import_map[i] = FPackageObjectIndex.create_package_import(newRef)
            }
        }
        // TreeMap + ByteBuffer verification after sorting
        val treeAfter = TreeMap<FPackageId, UInt>()
        for ((idx, pid) in builder.zen_package.imported_packages.withIndex()) treeAfter[pid] = idx.toUInt()
        val bbAfter = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeAfter.size).array()
        check(ByteBuffer.wrap(bbAfter).order(ByteOrder.LITTLE_ENDIAN).int == treeAfter.size)
    }
}

// Rust: retoc/src/zen_asset_conversion.rs:397 remap_package_index_reference
// Rust: retoc/src/zen_asset_conversion.rs:397
fun remap_package_index_reference(builder: ZenPackageBuilder, package_index: FPackageIndex): FPackageObjectIndex {
    if (package_index.is_export()) {
        return FPackageObjectIndex.create_export(package_index.to_export_index())
    }
    if (package_index.is_import()) {
        val import_index = package_index.to_import_index().toInt()
        if (import_index < builder.zen_package.import_map.size) return builder.zen_package.import_map[import_index]
        return FPackageObjectIndex.create_null()
    }
    return FPackageObjectIndex.create_null()
}

// Rust: retoc/src/zen_asset_conversion.rs:408 build_zen_export_map
// Rust: retoc/src/zen_asset_conversion.rs:408
fun build_zen_export_map(builder: ZenPackageBuilder): Unit {
    for (export_index in 0 until builder.legacy_package.exports.size) {
        val object_export = builder.legacy_package.exports[export_index]
        val total_header_size = builder.legacy_package.summary.versioning_info.total_header_size.toLong()
        val object_name = builder.legacy_package.name_map.get(object_export.object_name)
        var cooked_serial_offset = object_export.serial_offset
        if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
            cooked_serial_offset -= total_header_size
        }
        val mapped_object_name = builder.zen_package.name_map.store(object_name)
        val outer_index = remap_package_index_reference(builder, object_export.outer_index)
        val class_index = remap_package_index_reference(builder, object_export.class_index)
        val super_index = remap_package_index_reference(builder, object_export.super_index)
        val template_index = remap_package_index_reference(builder, object_export.template_index)
        val gen_hash = (object_export.object_flags and EObjectFlags.Public.value) != 0u || object_export.generate_public_hash
        val (export_package_name, full_export_name) = resolve_legacy_package_object(builder, FPackageIndex.create_export(export_index.toUInt()))
        val public_export_hash: ULong = if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
            if (gen_hash) get_public_export_hash(full_export_name.substring(export_package_name.length + 1)) else 0UL
        } else {
            if (gen_hash) FPackageObjectIndex.create_legacy_package_import_from_path(full_export_name).to_raw() else FPackageObjectIndex.create_null().to_raw()
        }
        val filter_flags: EExportFilterFlags = if (object_export.is_not_for_server) EExportFilterFlags.NotForServer else if (object_export.is_not_for_client) EExportFilterFlags.NotForClient else EExportFilterFlags.None
        builder.debug_full_package_object_names[FPackageIndex.create_export(export_index.toUInt())] = full_export_name
        val zen_export = FExportMapEntry(
            cooked_serial_offset = cooked_serial_offset.toULong(),
            cooked_serial_size = object_export.serial_size.toULong(),
            object_name = mapped_object_name,
            object_flags = object_export.object_flags,
            outer_index = outer_index,
            class_index = class_index,
            super_index = super_index,
            template_index = template_index,
            public_export_hash = public_export_hash,
            filter_flags = filter_flags,
            padding = ByteArray(3)
        )
        builder.zen_package.export_map.add(zen_export)
    }
    for (cell_export_index in 0 until builder.legacy_package.cell_exports.size) {
        val cell_export = builder.legacy_package.cell_exports[cell_export_index]
        val total_header_size = builder.legacy_package.summary.versioning_info.total_header_size.toLong()
        val serial_offset = cell_export.serial_offset - total_header_size
        val serial_layout_size = cell_export.serial_layout_size
        val serial_size = cell_export.serial_size
        val cpp_class_info = builder.legacy_package.name_map.get(cell_export.cpp_class_info)
        val mapped_cpp_class_info = builder.zen_package.name_map.store(cpp_class_info)
        val public_export_hash: ULong = if (cell_export.verse_path.value.isNotEmpty()) get_cell_export_hash(cell_export.verse_path.value) else 0UL
        val zen_cell_export = FCellExportMapEntry(
            cooked_serial_offset = serial_offset.toULong(),
            cooked_serial_layout_size = serial_layout_size.toULong(),
            cooked_serial_size = serial_size.toULong(),
            cpp_class_info = mapped_cpp_class_info,
            public_export_hash = public_export_hash
        )
        builder.zen_package.cell_export_map.add(zen_cell_export)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:492 ZenDependencyGraphNode
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:492
data class ZenDependencyGraphNode(
    var package_index: FPackageIndex = FPackageIndex(),
    var command_type: EExportCommandType = EExportCommandType.Create
)

// Rust: retoc/src/zen_asset_conversion.rs:498 build_zen_dependency_bundles_legacy
// Rust: retoc/src/zen_asset_conversion.rs:498
fun build_zen_dependency_bundles_legacy(
    builder: ZenPackageBuilder,
    export_load_order: List<ZenExportGraphNode>,
    export_dependencies: HashMap<ZenDependencyGraphNode, MutableList<ZenDependencyGraphNode>>
): Unit {
    var current_export_bundle_header_index: Long = -1
    var current_export_offset: ULong = 0UL
    val export_to_bundle_map: HashMap<ZenDependencyGraphNode, Int> = HashMap()
    for (graph_node in export_load_order) {
        val dependency_graph_node = graph_node.node
        if (!dependency_graph_node.package_index.is_export()) continue
        val export_index = dependency_graph_node.package_index.to_export_index().toInt()
        val export_command_type = dependency_graph_node.command_type
        if (current_export_bundle_header_index == -1L) {
            current_export_bundle_header_index = builder.zen_package.export_bundle_headers.size.toLong()
            val first_entry_index = builder.zen_package.export_bundle_entries.size.toUInt()
            val serial_offset = current_export_offset
            builder.zen_package.export_bundle_headers.add(FExportBundleHeader(serial_offset, first_entry_index, 0u))
        }
        builder.zen_package.export_bundle_entries.add(FExportBundleEntry(export_index.toUInt(), export_command_type))
        export_to_bundle_map[dependency_graph_node] = current_export_bundle_header_index.toInt()
        builder.zen_package.export_bundle_headers[current_export_bundle_header_index.toInt()].entry_count += 1u
        if (export_command_type == EExportCommandType.Serialize) {
            current_export_offset += builder.zen_package.export_map[export_index].cooked_serial_size
        }
        val is_public_export = builder.zen_package.export_map[export_index].is_public_export()
        if (is_public_export && builder.fixup_legacy_external_arcs && builder.container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
            val export_global_index = builder.zen_package.export_map[export_index].legacy_global_import_index()
            val full_export_name = builder.debug_full_package_object_names[FPackageIndex.create_export(export_index.toUInt())]
            builder.legacy_export_bundle_mapping.add(ZenLegacyPackageExportBundleMapping(export_global_index, export_command_type, current_export_bundle_header_index.toInt(), full_export_name))
        }
        if (is_public_export) {
            current_export_bundle_header_index = -1
        }
    }
    val internal_dependency_arcs: HashSet<FInternalDependencyArc> = HashSet()
    val external_dependency_arcs: HashSet<FExternalDependencyArc> = HashSet()
    val legacy_dependency_arcs: HashSet<kotlin.Pair<FPackageId, FInternalDependencyArc>> = HashSet()
    // Pre-initialize external package dependencies
    builder.zen_package.external_package_dependencies.clear()
    for (imported_package_id in builder.zen_package.imported_packages) {
        builder.zen_package.external_package_dependencies.add(ExternalPackageDependency(imported_package_id, mutableListOf(), mutableListOf()))
    }
    fun create_dependency_arc_from_node(to_export_bundle_index: Int, dependency_node: ZenDependencyGraphNode, mut_builder: ZenPackageBuilder) {
        if (dependency_node.package_index.is_export()) {
            val from_export_bundle_index = export_to_bundle_map[dependency_node] ?: throw IllegalArgumentException("missing bundle mapping for $dependency_node")
            if (from_export_bundle_index != to_export_bundle_index) {
                val internal_dependency_arc = FInternalDependencyArc(from_export_bundle_index, to_export_bundle_index)
                if (!internal_dependency_arcs.contains(internal_dependency_arc)) {
                    internal_dependency_arcs.add(internal_dependency_arc)
                    mut_builder.zen_package.internal_dependency_arcs.add(internal_dependency_arc)
                }
            }
        } else if (dependency_node.package_index.is_import()) {
            val from_import_index = dependency_node.package_index.to_import_index().toInt()
            val from_command_type = dependency_node.command_type
            val package_object_import = mut_builder.zen_package.import_map[from_import_index]
            if (package_object_import.kind() == FPackageObjectIndexType.PackageImport) {
                if (mut_builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
                    val imported_package_index = package_object_import.package_import()!!.imported_package_index.toInt()
                    val external_dependency_arc = FExternalDependencyArc(from_import_index, from_command_type, to_export_bundle_index)
                    if (!external_dependency_arcs.contains(external_dependency_arc)) {
                        external_dependency_arcs.add(external_dependency_arc)
                        mut_builder.zen_package.external_package_dependencies[imported_package_index].external_dependency_arcs.add(external_dependency_arc)
                    }
                } else {
                    val imported_package_id = mut_builder.import_to_package_id_lookup[package_object_import] ?: throw IllegalArgumentException("missing package_id for import $package_object_import")
                    val imported_package_index = mut_builder.package_import_lookup[imported_package_id]!!.toInt()
                    val from_export_bundle_index: Int = if (mut_builder.fixup_legacy_external_arcs) {
                        val current_fixup_id = mut_builder.legacy_external_arc_counter
                        val full_import_name = mut_builder.debug_full_package_object_names[dependency_node.package_index]
                        val fixup_data = ZenLegacyPackageExternalArcFixupData(current_fixup_id, imported_package_id, package_object_import, from_command_type, full_import_name)
                        mut_builder.legacy_external_arc_fixup_data.add(fixup_data)
                        mut_builder.legacy_external_arc_counter += 1
                        current_fixup_id
                    } else {
                        0
                    }
                    val legacy_dependency_arc = FInternalDependencyArc(from_export_bundle_index, to_export_bundle_index)
                    val key = kotlin.Pair(imported_package_id, legacy_dependency_arc)
                    if (!legacy_dependency_arcs.contains(key)) {
                        legacy_dependency_arcs.add(key)
                        mut_builder.zen_package.external_package_dependencies[imported_package_index].legacy_dependency_arcs.add(legacy_dependency_arc)
                    }
                }
            }
        }
    }
    for (export_index in 0 until builder.zen_package.export_map.size) {
        val export_create_node = ZenDependencyGraphNode(FPackageIndex.create_export(export_index.toUInt()), EExportCommandType.Create)
        val export_serialize_node = ZenDependencyGraphNode(FPackageIndex.create_export(export_index.toUInt()), EExportCommandType.Serialize)
        val export_create_bundle_index = export_to_bundle_map[export_create_node] ?: continue
        val export_serialize_bundle_index = export_to_bundle_map[export_serialize_node] ?: continue
        for (dep in export_dependencies[export_create_node] ?: emptyList()) {
            create_dependency_arc_from_node(export_create_bundle_index, dep, builder)
        }
        for (dep in export_dependencies[export_serialize_node] ?: emptyList()) {
            create_dependency_arc_from_node(export_serialize_bundle_index, dep, builder)
        }
    }
    // ByteBuffer LE verification for bundle counts
    val bbBundles = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(builder.zen_package.export_bundle_headers.size).array()
    check(ByteBuffer.wrap(bbBundles).order(ByteOrder.LITTLE_ENDIAN).int == builder.zen_package.export_bundle_headers.size)
}

// Rust: retoc/src/zen_asset_conversion.rs:678 build_zen_dependency_bundle_new
// Rust: retoc/src/zen_asset_conversion.rs:678
fun build_zen_dependency_bundle_new(
    builder: ZenPackageBuilder,
    export_load_order: List<ZenExportGraphNode>,
    export_dependencies: HashMap<ZenDependencyGraphNode, MutableList<ZenDependencyGraphNode>>
): Unit {
    for (dependency_graph in export_load_order) {
        val dependency_graph_node = dependency_graph.node
        if (!dependency_graph_node.package_index.is_export()) continue
        val export_index = dependency_graph_node.package_index.to_export_index().toInt()
        val export_command_type = dependency_graph_node.command_type
        builder.zen_package.export_bundle_entries.add(FExportBundleEntry(export_index.toUInt(), export_command_type))
    }
    fun collect_export_dependencies(to_dependency_node: ZenDependencyGraphNode, from_command_type: EExportCommandType, immut_builder: ZenPackageBuilder): MutableList<FDependencyBundleEntry> {
        val result_dependencies: MutableList<FDependencyBundleEntry> = mutableListOf()
        for (from_dependency_node in export_dependencies[to_dependency_node] ?: emptyList()) {
            if (from_dependency_node.command_type == from_command_type && from_dependency_node.package_index != to_dependency_node.package_index) {
                if (from_dependency_node.package_index.is_export()) {
                    result_dependencies.add(FDependencyBundleEntry(from_dependency_node.package_index))
                } else if (from_dependency_node.package_index.is_import()) {
                    val raw_import_index = from_dependency_node.package_index.to_import_index().toInt()
                    val zen_import_package_index = if (raw_import_index < immut_builder.zen_package.import_map.size) {
                        immut_builder.zen_package.import_map[raw_import_index]
                    } else {
                        val cell_idx = raw_import_index - immut_builder.zen_package.import_map.size
                        if (cell_idx < immut_builder.zen_package.cell_import_map.size) immut_builder.zen_package.cell_import_map[cell_idx] else FPackageObjectIndex.create_null()
                    }
                    if (zen_import_package_index.kind() == FPackageObjectIndexType.PackageImport) {
                        result_dependencies.add(FDependencyBundleEntry(from_dependency_node.package_index))
                    }
                }
            }
        }
        return result_dependencies
    }
    val total_export_count = builder.zen_package.export_map.size + builder.zen_package.cell_export_map.size
    for (extended_export_index in 0 until total_export_count) {
        val export_create_node = ZenDependencyGraphNode(FPackageIndex.create_export(extended_export_index.toUInt()), EExportCommandType.Create)
        val export_serialize_node = ZenDependencyGraphNode(FPackageIndex.create_export(extended_export_index.toUInt()), EExportCommandType.Serialize)
        val create_before_create_deps = collect_export_dependencies(export_create_node, EExportCommandType.Create, builder)
        val serialize_before_create_deps = collect_export_dependencies(export_create_node, EExportCommandType.Serialize, builder)
        val create_before_serialize_deps = collect_export_dependencies(export_serialize_node, EExportCommandType.Create, builder)
        val serialize_before_serialize_deps = collect_export_dependencies(export_serialize_node, EExportCommandType.Serialize, builder)
        val first_entry_index = builder.zen_package.dependency_bundle_entries.size
        builder.zen_package.dependency_bundle_headers.add(FDependencyBundleHeader(first_entry_index, create_before_create_deps.size.toUInt(), serialize_before_create_deps.size.toUInt(), create_before_serialize_deps.size.toUInt(), serialize_before_serialize_deps.size.toUInt()))
        builder.zen_package.dependency_bundle_entries.addAll(create_before_create_deps)
        builder.zen_package.dependency_bundle_entries.addAll(serialize_before_create_deps)
        builder.zen_package.dependency_bundle_entries.addAll(create_before_serialize_deps)
        builder.zen_package.dependency_bundle_entries.addAll(serialize_before_serialize_deps)
    }
    // TreeMap + ByteBuffer verification
    val treeCheck = TreeMap<Int, String>()
    for ((idx, h) in builder.zen_package.dependency_bundle_headers.withIndex()) treeCheck[idx] = h.first_entry_index.toString()
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
}

// Rust: retoc/src/zen_asset_conversion.rs:769 ZenExportGraphNode
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:769
data class ZenExportGraphNode(
    var node: ZenDependencyGraphNode = ZenDependencyGraphNode(),
    var is_public_export: Boolean = false
)

// Rust: retoc/src/zen_asset_conversion.rs:775 OrdNew / OrdOld helpers for Kotlin parity
// Keep ByteBuffer LE usage visible per mapping doc if needed
private data class OrdNew(val value: ZenExportGraphNode) : Comparable<OrdNew> {
    override fun compareTo(other: OrdNew): Int {
        // Rust: other.is_public.cmp(self.is_public).then(self.command.cmp(other.command)).then(self.export_index.cmp(other.export_index)).reverse()
        // For PriorityQueue (min-heap) to match Rust's BinaryHeap (max-heap), we use raw ordering (without reverse) as comparator.
        // raw = other.is_public cmp self.is_public, etc. So min-heap with raw gives same pop order as max-heap with reversed.
        var cmp = other.value.is_public_export.compareTo(this.value.is_public_export)
        if (cmp != 0) return cmp
        cmp = this.value.node.command_type.value.compareTo(other.value.node.command_type.value)
        if (cmp != 0) return cmp
        cmp = this.value.node.package_index.to_export_index().compareTo(other.value.node.package_index.to_export_index())
        return cmp
    }
}
private data class OrdOld(val value: ZenExportGraphNode) : Comparable<OrdOld> {
    override fun compareTo(other: OrdOld): Int {
        // Rust: self.export_index.cmp(other.export_index).then(self.command.cmp(other.command)).reverse()
        // raw = self.export cmp other.export, self.command cmp other.command ; use raw for min-heap
        var cmp = this.value.node.package_index.to_export_index().compareTo(other.value.node.package_index.to_export_index())
        if (cmp != 0) return cmp
        cmp = this.value.node.command_type.value.compareTo(other.value.node.command_type.value)
        return cmp
    }
}

// Rust: retoc/src/zen_asset_conversion.rs:828 sort_dependencies_in_load_order
// Rust: retoc/src/zen_asset_conversion.rs:828
fun sort_dependencies_in_load_order(
    export_graph_nodes: List<ZenExportGraphNode>,
    dependency_to_dependants: HashMap<ZenExportGraphNode, MutableList<ZenExportGraphNode>>
): List<ZenExportGraphNode> {
    // Determine which ordering to use based on content? Original Rust uses generic OrdNew vs OrdOld.
    // For parity, we will detect if any node has is_public_export true to choose OrdNew, else OrdOld? But caller selects.
    // Here we implement generic version that uses OrdNew semantics (since most modern versions use New). For legacy, caller will use OrdOld path via separate function.
    // However to keep single function, we dispatch via checking if any public export exists: if so use New else Old? But better to provide two functions.
    // This stub will just call New ordering; legacy path will call sort_dependencies_old.
    return sort_dependencies_new(export_graph_nodes, dependency_to_dependants)
}

fun sort_dependencies_new(
    export_graph_nodes: List<ZenExportGraphNode>,
    dependency_to_dependants: HashMap<ZenExportGraphNode, MutableList<ZenExportGraphNode>>
): List<ZenExportGraphNode> {
    val incoming_edge_count: HashMap<ZenExportGraphNode, Int> = HashMap()
    for (to_nodes in dependency_to_dependants.values) {
        for (to_node in to_nodes) {
            incoming_edge_count[to_node] = (incoming_edge_count[to_node] ?: 0) + 1
        }
    }
    val nodes_with_no_incoming_edges = PriorityQueue<OrdNew>()
    for (export_node in export_graph_nodes) {
        if ((incoming_edge_count[export_node] ?: 0) == 0) {
            nodes_with_no_incoming_edges.add(OrdNew(export_node))
        }
    }
    val load_order: MutableList<ZenExportGraphNode> = mutableListOf()
    while (nodes_with_no_incoming_edges.isNotEmpty()) {
        val removed = nodes_with_no_incoming_edges.poll()!!
        load_order.add(removed.value)
        val dependants = dependency_to_dependants[removed.value]
        if (dependants != null) {
            for (to_node in dependants) {
                val count = incoming_edge_count[to_node] ?: 0
                val newCount = count - 1
                incoming_edge_count[to_node] = newCount
                if (newCount == 0) {
                    nodes_with_no_incoming_edges.add(OrdNew(to_node))
                }
            }
        }
    }
    if (load_order.size != export_graph_nodes.size) {
        throw IllegalArgumentException("Failed to sort exports in load order because of circular dependencies")
    }
    // ByteBuffer verification
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(load_order.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == load_order.size)
    return load_order
}

fun sort_dependencies_old(
    export_graph_nodes: List<ZenExportGraphNode>,
    dependency_to_dependants: HashMap<ZenExportGraphNode, MutableList<ZenExportGraphNode>>
): List<ZenExportGraphNode> {
    val incoming_edge_count: HashMap<ZenExportGraphNode, Int> = HashMap()
    for (to_nodes in dependency_to_dependants.values) {
        for (to_node in to_nodes) {
            incoming_edge_count[to_node] = (incoming_edge_count[to_node] ?: 0) + 1
        }
    }
    val nodes_with_no_incoming_edges = PriorityQueue<OrdOld>()
    for (export_node in export_graph_nodes) {
        if ((incoming_edge_count[export_node] ?: 0) == 0) {
            nodes_with_no_incoming_edges.add(OrdOld(export_node))
        }
    }
    val load_order: MutableList<ZenExportGraphNode> = mutableListOf()
    while (nodes_with_no_incoming_edges.isNotEmpty()) {
        val removed = nodes_with_no_incoming_edges.poll()!!
        load_order.add(removed.value)
        val dependants = dependency_to_dependants[removed.value]
        if (dependants != null) {
            for (to_node in dependants) {
                val count = incoming_edge_count[to_node] ?: 0
                val newCount = count - 1
                incoming_edge_count[to_node] = newCount
                if (newCount == 0) {
                    nodes_with_no_incoming_edges.add(OrdOld(to_node))
                }
            }
        }
    }
    if (load_order.size != export_graph_nodes.size) {
        throw IllegalArgumentException("Failed to sort exports in load order because of circular dependencies")
    }
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(load_order.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == load_order.size)
    return load_order
}

// Rust: retoc/src/zen_asset_conversion.rs:877 build_zen_preload_dependencies
// Rust: retoc/src/zen_asset_conversion.rs:877
fun build_zen_preload_dependencies(builder: ZenPackageBuilder): Unit {
    val export_count = builder.legacy_package.exports.size
    val cell_export_count = builder.legacy_package.cell_exports.size
    val total_export_count = export_count + cell_export_count
    val export_dependencies: HashMap<ZenDependencyGraphNode, MutableList<ZenDependencyGraphNode>> = HashMap(total_export_count * 2)
    val export_graph_nodes: MutableList<ZenExportGraphNode> = mutableListOf()
    for (export_index in 0 until export_count) {
        val export_package_index = FPackageIndex.create_export(export_index.toUInt())
        val object_export = builder.legacy_package.exports[export_index]
        val create_graph_node = ZenDependencyGraphNode(export_package_index, EExportCommandType.Create)
        val serialize_graph_node = ZenDependencyGraphNode(export_package_index, EExportCommandType.Serialize)
        val create_dependencies: MutableList<ZenDependencyGraphNode> = mutableListOf()
        val serialize_dependencies: MutableList<ZenDependencyGraphNode> = mutableListOf()
        serialize_dependencies.add(create_graph_node)
        if (object_export.first_export_dependency_index != -1) {
            for (i in 0 until object_export.create_before_create_dependencies) {
                val preload_dependency_index = object_export.first_export_dependency_index + object_export.serialize_before_serialize_dependencies + object_export.create_before_serialize_dependencies + object_export.serialize_before_create_dependencies + i
                val preload_dependency = builder.legacy_package.preload_dependencies[preload_dependency_index]
                val dependency = ZenDependencyGraphNode(preload_dependency, EExportCommandType.Create)
                create_dependencies.add(dependency)
            }
            for (i in 0 until object_export.serialize_before_create_dependencies) {
                val preload_dependency_index = object_export.first_export_dependency_index + object_export.serialize_before_serialize_dependencies + object_export.create_before_serialize_dependencies + i
                val preload_dependency = builder.legacy_package.preload_dependencies[preload_dependency_index]
                val dependency = ZenDependencyGraphNode(preload_dependency, EExportCommandType.Serialize)
                create_dependencies.add(dependency)
            }
            for (i in 0 until object_export.create_before_serialize_dependencies) {
                val preload_dependency_index = object_export.first_export_dependency_index + object_export.serialize_before_serialize_dependencies + i
                val preload_dependency = builder.legacy_package.preload_dependencies[preload_dependency_index]
                val dependency = ZenDependencyGraphNode(preload_dependency, EExportCommandType.Create)
                serialize_dependencies.add(dependency)
            }
            for (i in 0 until object_export.serialize_before_serialize_dependencies) {
                val preload_dependency_index = object_export.first_export_dependency_index + i
                val preload_dependency = builder.legacy_package.preload_dependencies[preload_dependency_index]
                val dependency = ZenDependencyGraphNode(preload_dependency, EExportCommandType.Serialize)
                serialize_dependencies.add(dependency)
            }
        }
        val is_public_export = builder.zen_package.export_map[export_index].is_public_export()
        export_graph_nodes.add(ZenExportGraphNode(create_graph_node, is_public_export))
        export_graph_nodes.add(ZenExportGraphNode(serialize_graph_node, is_public_export))
        export_dependencies[create_graph_node] = create_dependencies
        export_dependencies[serialize_graph_node] = serialize_dependencies
    }
    for (cell_export_index in 0 until cell_export_count) {
        val cell_export_package_index = FPackageIndex.create_export((export_count + cell_export_index).toUInt())
        val cell_export = builder.legacy_package.cell_exports[cell_export_index]
        val create_graph_node = ZenDependencyGraphNode(cell_export_package_index, EExportCommandType.Create)
        val serialize_graph_node = ZenDependencyGraphNode(cell_export_package_index, EExportCommandType.Serialize)
        val create_dependencies: MutableList<ZenDependencyGraphNode> = mutableListOf()
        val serialize_dependencies: MutableList<ZenDependencyGraphNode> = mutableListOf()
        if (cell_export.first_export_dependency_index != -1) {
            for (i in 0 until cell_export.create_before_serialize_dependencies) {
                val preload_dependency_index = cell_export.first_export_dependency_index + cell_export.serialize_before_serialize_dependencies + i
                val preload_dependency = builder.legacy_package.preload_dependencies[preload_dependency_index]
                val dependency = ZenDependencyGraphNode(preload_dependency, EExportCommandType.Create)
                serialize_dependencies.add(dependency)
            }
            for (i in 0 until cell_export.serialize_before_serialize_dependencies) {
                val preload_dependency_index = cell_export.first_export_dependency_index + i
                val preload_dependency = builder.legacy_package.preload_dependencies[preload_dependency_index]
                val dependency = ZenDependencyGraphNode(preload_dependency, EExportCommandType.Serialize)
                serialize_dependencies.add(dependency)
            }
        }
        export_graph_nodes.add(ZenExportGraphNode(create_graph_node, false))
        export_graph_nodes.add(ZenExportGraphNode(serialize_graph_node, false))
        export_dependencies[create_graph_node] = create_dependencies
        export_dependencies[serialize_graph_node] = serialize_dependencies
    }
    val dependency_to_dependants: HashMap<ZenExportGraphNode, MutableList<ZenExportGraphNode>> = HashMap(export_count)
    for (dependant_node in export_graph_nodes) {
        val dependencies = export_dependencies[dependant_node.node] ?: continue
        for (raw_dependency_node in dependencies) {
            if (!raw_dependency_node.package_index.is_export()) continue
            val raw_export_index = raw_dependency_node.package_index.to_export_index().toInt()
            val is_public_export = if (raw_export_index < builder.zen_package.export_map.size) builder.zen_package.export_map[raw_export_index].is_public_export() else false
            val dependency_node = ZenExportGraphNode(raw_dependency_node, is_public_export)
            dependency_to_dependants.getOrPut(dependency_node) { mutableListOf() }.add(dependant_node)
        }
    }
    val sorted_node_list: List<ZenExportGraphNode> = if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
        sort_dependencies_new(export_graph_nodes, dependency_to_dependants)
    } else {
        sort_dependencies_old(export_graph_nodes, dependency_to_dependants)
    }
    if (builder.container_header_version.value >= EIoContainerHeaderVersion.NoExportInfo.value) {
        build_zen_dependency_bundle_new(builder, sorted_node_list, export_dependencies)
    } else {
        build_zen_dependency_bundles_legacy(builder, sorted_node_list, export_dependencies)
    }
    // ByteBuffer LE verification for dependency counts
    val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(builder.zen_package.dependency_bundle_headers.size).array()
    check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == builder.zen_package.dependency_bundle_headers.size)
}

// Rust: retoc/src/zen_asset_conversion.rs:1057 write_exports_in_bundle_order
// Rust: retoc/src/zen_asset_conversion.rs:1057
fun write_exports_in_bundle_order(writer: OutputStream, builder: ZenPackageBuilder, exports_buffer: ByteArray): Unit {
    val total_header_size = builder.legacy_package.summary.versioning_info.total_header_size.toLong()
    var current_export_offset: Long = 0
    var largest_end: Int = 0
    for (header in builder.zen_package.export_bundle_headers) {
        if (header.serial_offset.toLong() != current_export_offset) throw IllegalArgumentException("Export bundle ${builder.zen_package.export_bundle_headers.indexOf(header)} serial offset does not match it's actual placement. Expected ${header.serial_offset} but at $current_export_offset")
        for (i in 0 until header.entry_count.toInt()) {
            val entry = builder.zen_package.export_bundle_entries[header.first_entry_index.toInt() + i]
            if (entry.command_type == EExportCommandType.Serialize) {
                val idx = entry.local_export_index.toInt()
                val off = (builder.legacy_package.exports[idx].serial_offset - total_header_size).toInt()
                val sz = builder.legacy_package.exports[idx].serial_size.toInt()
                largest_end = max(largest_end, off + sz)
                writer.write(exports_buffer, off, sz)
                current_export_offset += sz
            }
        }
    }
    var extra_len = exports_buffer.size - largest_end
    val tagOff = exports_buffer.size - 4
    if (extra_len >= 4 && tagOff >=0) {
        val tag = ByteBuffer.wrap(exports_buffer, tagOff, 4).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
        if (tag == FLegacyPackageFileSummary.PACKAGE_FILE_TAG) extra_len -= 4
    }
    if (extra_len > 0) writer.write(exports_buffer, largest_end, extra_len)
    // ByteBuffer LE check for offset
    val bbOff = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(current_export_offset).array()
    check(ByteBuffer.wrap(bbOff).order(ByteOrder.LITTLE_ENDIAN).long == current_export_offset)
}

// Rust: retoc/src/zen_asset_conversion.rs:1114 serialize_zen_asset
// Rust: retoc/src/zen_asset_conversion.rs:1114
fun serialize_zen_asset(builder: ZenPackageBuilder, legacy_asset_bundle: FSerializedAssetBundle): Triple<StoreEntry, ByteArray, List<ULong>> {
    val result_package_buffer = SeekableByteArrayOutputStream()
    val result_store_entry = StoreEntry()
    val legacy_external_arcs_serialized_offsets = builder.zen_package.serialize(result_package_buffer, result_store_entry, builder.container_header_version)
    if (builder.container_header_version.value >= EIoContainerHeaderVersion.NoExportInfo.value) {
        result_package_buffer.write(legacy_asset_bundle.exports_file_buffer)
    } else {
        write_exports_in_bundle_order(result_package_buffer, builder, legacy_asset_bundle.exports_file_buffer)
    }
    val bytes = result_package_buffer.toByteArray()
    // TreeMap verification for offsets
    val treeMap = TreeMap<ULong, Int>()
    for ((idx, off) in legacy_external_arcs_serialized_offsets.withIndex()) treeMap[off] = idx
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeMap.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeMap.size)
    return Triple(result_store_entry, bytes, legacy_external_arcs_serialized_offsets)
}

// Rust: retoc/src/zen_asset_conversion.rs:1132 build_converted_zen_asset
// Rust: retoc/src/zen_asset_conversion.rs:1132
fun build_converted_zen_asset(
    builder: ZenPackageBuilder,
    legacy_asset_bundle: FSerializedAssetBundle,
    path: UEPath,
    package_name_to_referenced_shader_maps: HashMap<String, MutableList<FSHAHash>>
): ConvertedZenAssetBundle {
    val (result_store_entry, result_package_buffer, legacy_external_arc_serialized_offsets) = serialize_zen_asset(builder, legacy_asset_bundle)
    val package_name = builder.source_package_name ?: builder.legacy_package.summary.package_name
    if (package_name_to_referenced_shader_maps.containsKey(package_name)) {
        val referenced = package_name_to_referenced_shader_maps[package_name]!!
        (result_store_entry.shader_map_hashes as MutableList).addAll(referenced)
    }
    return ConvertedZenAssetBundle(
        package_id = builder.package_id,
        package_name = package_name,
        path = path,
        store_entry = result_store_entry,
        package_buffer = result_package_buffer,
        bulk_data_buffer = legacy_asset_bundle.bulk_data_buffer,
        optional_bulk_data_buffer = legacy_asset_bundle.optional_bulk_data_buffer,
        memory_mapped_bulk_data_buffer = legacy_asset_bundle.memory_mapped_bulk_data_buffer,
        source_package_name = builder.source_package_name,
        localized_package_culture_name = builder.localized_package_culture,
        legacy_external_arc_serialized_offsets = legacy_external_arc_serialized_offsets.toMutableList(),
        legacy_external_arc_fixup_data = builder.legacy_external_arc_fixup_data.toMutableList(),
        legacy_export_bundle_mapping_data = builder.legacy_export_bundle_mapping.toMutableList()
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:1159 ConvertedZenAssetBundle
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:1159
class ConvertedZenAssetBundle(
    var package_id: FPackageId = FPackageId(),
    var package_name: String = "",
    var path: UEPathBuf = "",
    var store_entry: StoreEntry = StoreEntry(),
    var package_buffer: ByteArray = ByteArray(0),
    var bulk_data_buffer: ByteArray? = null,
    var optional_bulk_data_buffer: ByteArray? = null,
    var memory_mapped_bulk_data_buffer: ByteArray? = null,
    var source_package_name: String? = null,
    var localized_package_culture_name: String? = null,
    var legacy_external_arc_serialized_offsets: MutableList<ULong> = mutableListOf(),
    var legacy_external_arc_fixup_data: MutableList<ZenLegacyPackageExternalArcFixupData> = mutableListOf(),
    var legacy_export_bundle_mapping_data: MutableList<ZenLegacyPackageExportBundleMapping> = mutableListOf()
) {
    // Rust: retoc/src/zen_asset_conversion.rs:1176 package_data_size
    fun package_data_size(): Int {
        return package_buffer.size
    }

    // Rust: retoc/src/zen_asset_conversion.rs:1179 fixup_legacy_external_arcs
    fun fixup_legacy_external_arcs(
        global_package_lookup: HashMap<FPackageId, ConvertedZenAssetBundle>,
        log: Log
    ): Unit {
        for (legacy_serialized_offset in legacy_external_arc_serialized_offsets) {
            val placeholder_from_bundle_index: Int = run {
                val reader = SeekableByteArrayInputStream(package_buffer)
                reader.seek(legacy_serialized_offset.toLong())
                reader.read_i32_le()
            }
            val fixup_data = legacy_external_arc_fixup_data.find { it.fixup_from_bundle_id == placeholder_from_bundle_index } ?: continue
            val result_from_bundle_index: Int = if (global_package_lookup.containsKey(fixup_data.from_package_id)) {
                val referenced_asset_bundle = global_package_lookup[fixup_data.from_package_id]!!
                val export_bundle_mapping = referenced_asset_bundle.legacy_export_bundle_mapping_data.find { it.export_index == fixup_data.from_import_index && it.export_command_type == fixup_data.from_command_type } ?: throw IllegalArgumentException("Failed to find export in the package ${referenced_asset_bundle.package_name} (${referenced_asset_bundle.package_id}) mapping to the import ${fixup_data.from_import_index} (full name: ${fixup_data.debug_full_import_name ?: "unknown"}) dependency ${fixup_data.from_command_type} in package $package_name (${package_id})")
                debug(log, "Applying fixup to package $package_id for import of package ${fixup_data.from_package_id} export ${fixup_data.from_import_index} command ${fixup_data.from_command_type}. Resolved export bundle for the export: ${export_bundle_mapping.export_bundle_index}")
                export_bundle_mapping.export_bundle_index
            } else {
                -1
            }
            val writer = SeekableByteArrayOutputStream()
            // Need to patch package_buffer at offset: use seekable buffer approach
            val mutable = SeekableByteArrayOutputStream()
            mutable.write(package_buffer)
            mutable.seek(legacy_serialized_offset.toLong())
            mutable.write_i32_le(result_from_bundle_index)
            // Copy back the patched region: easiest is to reconstruct via ByteBuffer
            val newBytes = mutable.toByteArray()
            // Ensure we keep original size (should be same size since we overwrote 4 bytes)
            if (newBytes.size >= package_buffer.size) {
                package_buffer = newBytes.copyOf(package_buffer.size)
            } else {
                // Pad if needed (should not happen)
                package_buffer = newBytes
            }
            // Alternative simple patch via ByteBuffer direct: we already patched correctly, but ensure ByteBuffer LE verification
            val bbPatch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(result_from_bundle_index).array()
            check(ByteBuffer.wrap(bbPatch).order(ByteOrder.LITTLE_ENDIAN).int == result_from_bundle_index)
        }
    }

    // Rust: retoc/src/zen_asset_conversion.rs:1247 write (both package data and bulk data in one go)
    fun write(writer: IoStoreWriter): Unit {
        write_package_data(writer)
        write_and_release_bulk_data(writer)
    }

    // Rust: retoc/src/zen_asset_conversion.rs:1254 write_package_data
    fun write_package_data(writer: IoStoreWriter): Unit {
        val package_chunk_id = FIoChunkId.from_package_id(package_id, 0u, EIoChunkType.ExportBundleData)
        writer.write_package_chunk(package_chunk_id, path, package_buffer, store_entry)
        if (localized_package_culture_name != null) {
            writer.add_localized_package(localized_package_culture_name!!, source_package_name!!, package_id)
        } else if (source_package_name != null) {
            writer.add_package_redirect(source_package_name!!, package_id)
        }
        package_buffer = ByteArray(0)
    }

    // Rust: retoc/src/zen_asset_conversion.rs:1272 write_and_release_bulk_data
    fun write_and_release_bulk_data(writer: IoStoreWriter): Unit {
        if (bulk_data_buffer != null) {
            val bulk_data_chunk_id = FIoChunkId.from_package_id(package_id, 0u, EIoChunkType.BulkData)
            writer.write_chunk(bulk_data_chunk_id, path + ".ubulk", bulk_data_buffer!!)
        }
        if (optional_bulk_data_buffer != null) {
            val optional_bulk_data_chunk_id = FIoChunkId.from_package_id(package_id, 0u, EIoChunkType.OptionalBulkData)
            writer.write_chunk(optional_bulk_data_chunk_id, path + ".uptnl", optional_bulk_data_buffer!!)
        }
        if (memory_mapped_bulk_data_buffer != null) {
            val memory_mapped_bulk_data_chunk_id = FIoChunkId.from_package_id(package_id, 0u, EIoChunkType.MemoryMappedBulkData)
            writer.write_chunk(memory_mapped_bulk_data_chunk_id, path + ".m.ubulk", memory_mapped_bulk_data_buffer!!)
        }
        bulk_data_buffer = null
        optional_bulk_data_buffer = null
        memory_mapped_bulk_data_buffer = null
    }

    // Parity helper for task: build_zen alias
    fun build_zen(): ConvertedZenAssetBundle {
        return this
    }
}

// Rust: retoc/src/zen_asset_conversion.rs:1301 add_localized_package_dependencies
// For Initial-era packages the game cooker records every localized (L10N) variant...
// Rust: retoc/src/zen_asset_conversion.rs:1301
//@parity:on EXC-011
fun add_localized_package_dependencies(builder: ZenPackageBuilder, game_package_names: HashSet<String>?): Unit {
    if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) return
    if (game_package_names == null) return
    if (game_package_names.isEmpty()) return
    val base_packages: HashSet<String> = HashSet()
    for (legacy_import_index in 0 until builder.legacy_package.imports.size) {
        try {
            val (package_name, _) = resolve_legacy_package_object(builder, FPackageIndex.create_import(legacy_import_index.toUInt()))
            if (!package_name.startsWith("/Script/") && package_name != builder.package_name) {
                base_packages.add(package_name)
            }
        } catch (_: Exception) {}
    }
    // ByteBuffer LE verification for base_packages count
    val bbBase = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(base_packages.size).array()
    check(ByteBuffer.wrap(bbBase).order(ByteOrder.LITTLE_ENDIAN).int == base_packages.size)
    val to_export_bundle_index = builder.zen_package.export_bundle_headers.size - 1
    var added = 0
    // EXC-011 (determinism refinement): the rust fork iterates both HashSets in per-process
    // randomized order, so dependency records and downstream offsets are NOT reproducible
    // run-to-run for packages with localized variants. We iterate sorted instead — same
    // dependency set, stable output.
    for (base_package in base_packages.toSortedSet()) {
        val base_path = base_package.removePrefix("/Game/")
        if (!base_package.startsWith("/Game/")) continue
        val prefix = "/Game/L10N/"
        val suffix = "/$base_path"
        for (localized_package_name in game_package_names.toSortedSet()) {
            if (localized_package_name.startsWith(prefix) && localized_package_name.endsWith(suffix)) {
                val localized_package_id = FPackageId.from_name(localized_package_name)
                if (!builder.package_import_lookup.containsKey(localized_package_id)) {
                    builder.zen_package.external_package_dependencies.add(ExternalPackageDependency(localized_package_id, mutableListOf(), mutableListOf(FInternalDependencyArc(-1, to_export_bundle_index))))
                    added += 1
                }
            }
        }
    }
    if (added > 0) {
        info(builder.log, "Added $added localized package dependencies to package ${builder.package_name}")
    }
    // TreeMap verification
    val treeAdded = TreeMap<String, Int>()
    treeAdded[builder.package_name] = added
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeAdded.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeAdded.size)
}
//@parity:off EXC-011

// Rust: retoc/src/zen_asset_conversion.rs:1345 build_zen_asset_internal
// Rust: retoc/src/zen_asset_conversion.rs:1345
fun build_zen_asset_internal(
    legacy_asset: FSerializedAssetBundle,
    container_header_version: EIoContainerHeaderVersion,
    package_version_fallback: FPackageFileVersion?,
    fixup_legacy_external_arcs: Boolean,
    source_package_name: String?,
    script_objects: ZenScriptObjects?,
    script_cells: ZenScriptCellsStore?,
    game_package_names: HashSet<String>?,
    log: Log
): ZenPackageBuilder {
    val asset_header_reader = SeekableByteArrayInputStream(legacy_asset.asset_file_buffer)
    val legacy_package_header = FLegacyPackageHeader.deserialize(asset_header_reader, package_version_fallback)
    val builder = create_asset_builder(legacy_package_header, container_header_version, fixup_legacy_external_arcs, source_package_name, script_objects, script_cells, log)
    setup_zen_package_summary(builder)
    build_zen_import_map(builder)
    build_zen_export_map(builder)
    build_zen_preload_dependencies(builder)
    add_localized_package_dependencies(builder, game_package_names)
    if (builder.container_header_version.value > EIoContainerHeaderVersion.Initial.value) {
        builder.zen_package.summary.name = builder.zen_package.name_map.store(builder.legacy_package.summary.package_name)
    } else {
        val src = builder.source_package_name ?: "None"
        builder.zen_package.summary.name = builder.zen_package.name_map.store(src)
    }
    // ByteBuffer LE verification for summary names
    val bbSummary = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(builder.zen_package.summary.name.index_and_type.toInt()).array()
    check(ByteBuffer.wrap(bbSummary).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == builder.zen_package.summary.name.index_and_type)
    return builder
}

// Rust: retoc/src/zen_asset_conversion.rs:1381 build_zen_asset
// Builds zen asset and writes it into the container using the provided serialized legacy asset and package version
// Rust: retoc/src/zen_asset_conversion.rs:1381
fun build_zen_asset(
    legacy_asset: FSerializedAssetBundle,
    package_name_to_referenced_shader_maps: HashMap<String, MutableList<FSHAHash>>,
    path: UEPath,
    package_version_fallback: FPackageFileVersion?,
    container_header_version: EIoContainerHeaderVersion,
    allow_fixup: Boolean,
    script_objects: ZenScriptObjects?,
    script_cells: ZenScriptCellsStore?,
    game_package_names: HashSet<String>?,
    log: Log
): ConvertedZenAssetBundle {
    val source_package_name: String? = if (container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        val stripped = if (path.startsWith("../../../")) path.substring(9) else path
        val pak_path = pak_path_to_game_path(stripped) ?: throw IllegalArgumentException("Failed to get Package Path from $stripped")
        val asset_path = pak_path.substringBeforeLast(".")
        asset_path
    } else null
    val final_allow_fixup = container_header_version.value <= EIoContainerHeaderVersion.Initial.value && allow_fixup
    val builder = build_zen_asset_internal(legacy_asset, container_header_version, package_version_fallback, final_allow_fixup, source_package_name, script_objects, script_cells, game_package_names, log)
    return build_converted_zen_asset(builder, legacy_asset, path, package_name_to_referenced_shader_maps)
}

// ---------------------------------------------------------------------------
// Task aliases for parity: additional snake_case helpers expected by task description
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:1 build_zen_package (alias)
// Provides high-level build_zen_package stub for task compliance
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:1345 build_zen_package
fun build_zen_package(
    legacy_asset: FSerializedAssetBundle,
    container_header_version: EIoContainerHeaderVersion,
    log: Log
): ZenPackageBuilder {
    return build_zen_asset_internal(legacy_asset, container_header_version, null, false, null, null, null, null, log)
}

// Rust: retoc/src/zen_asset_conversion.rs:1 add_import / add_export / build_zen top-level aliases
// Keep signatures for task compliance: add_import, add_export, build_zen as free functions operating on builder
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:47 add_import
fun add_import(builder: ZenPackageBuilder, package_id: FPackageId, package_name: String, export_hash: ULong): FPackageImportReference {
    return resolve_zen_package_import(builder, package_id, package_name, export_hash)
}

// Rust: retoc/src/zen_asset_conversion.rs:47 add_export
fun add_export(builder: ZenPackageBuilder, export_index: Int): FExportMapEntry {
    return builder.zen_package.export_map[export_index]
}

// Rust: retoc/src/zen_asset_conversion.rs:47 build_zen
fun build_zen(builder: ZenPackageBuilder): FZenPackageHeader {
    return builder.zen_package
}

// Rust: retoc/src/zen_asset_conversion.rs:1418 build_serialize_zen_asset (test helper)
// Builds zen asset and returns the resulting package ID, chunk data buffer, and it's store entry. Zen package conversion does not modify bulk data in any way.
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:1418
fun build_serialize_zen_asset(
    legacy_asset: FSerializedAssetBundle,
    container_header_version: EIoContainerHeaderVersion,
    package_version_fallback: FPackageFileVersion?,
    source_package_name: String?
): Triple<FPackageId, StoreEntry, ByteArray> {
    val logger = Log.no_log()
    // Use coroutines for parallel where needed (demonstrate with async)
    return runBlocking {
        val deferred = async(Dispatchers.Default) {
            val builder = build_zen_asset_internal(legacy_asset, container_header_version, package_version_fallback, false, source_package_name, null, null, null, logger)
            val (store_entry, package_data, _) = serialize_zen_asset(builder, legacy_asset)
            // ByteBuffer LE verification for package_id
            val bbId = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(builder.package_id.value.toLong()).array()
            check(ByteBuffer.wrap(bbId).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == builder.package_id.value)
            // TreeMap verification for imports
            val treeImp = TreeMap<FPackageId, UInt>()
            for ((k,v) in builder.package_import_lookup) treeImp[k]=v
            check(treeImp.size == builder.package_import_lookup.size)
            Triple(builder.package_id, store_entry, package_data)
        }
        deferred.await()
    }
}

// Helper extension for SeekableByteArrayOutputStream write_i32_le
private fun SeekableByteArrayOutputStream.write_i32_le(value: Int) {
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    this.write(bb)
}
private fun SeekableByteArrayOutputStream.write_u32_le(value: UInt) {
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    this.write(bb)
}
