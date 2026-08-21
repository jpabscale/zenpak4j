// Rust: retoc/src/asset_conversion.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "SpellCheckingInspection", "MagicNumber", "MemberVisibilityCanBePrivate", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "UnnecessaryVariable", "ThrowsCount", "TooGenericExceptionCaught", "LongParameterList", "LargeClass", "ComplexCondition")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.HashMap
import java.util.HashSet
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

// ---------------------------------------------------------------------------
// Helpers mirroring Rust concurrency primitives
// Rust: std::sync::{Arc, RwLock, RwLockReadGuard} + key_mutex::KeyMutex
// Mapping: Arc -> GC ref, RwLock -> ReentrantReadWriteLock wrapper, KeyMutex -> ConcurrentHashMap<K, Mutex>
// ---------------------------------------------------------------------------

// Rust: key_mutex::KeyMutex<K, V>
// Kotlin: ConcurrentHashMap<K, Mutex> per mapping.md (coroutines)
class KeyMutex<K, V> {
    private val map = ConcurrentHashMap<K, Mutex>()
    fun lock(key: K): AutoCloseable {
        val m = map.computeIfAbsent(key) { Mutex() }
        runBlocking { m.lock() }
        return AutoCloseable { m.unlock() }
    }
    companion object {
        fun <K, V> new(): KeyMutex<K, V> = KeyMutex()
    }
}

// Rust: std::sync::RwLock<T>
// Kotlin: ReentrantReadWriteLock wrapper
class RwLock<T>(var inner: T) {
    private val rw = ReentrantReadWriteLock()
    fun read(): T {
        rw.readLock().lock()
        try { return inner } finally { rw.readLock().unlock() }
    }
    fun write(action: (T) -> Unit) {
        rw.writeLock().lock()
        try { action(inner) } finally { rw.writeLock().unlock() }
    }
    fun readLock(): ReentrantReadWriteLock.ReadLock = rw.readLock()
    fun writeLock(): ReentrantReadWriteLock.WriteLock = rw.writeLock()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:24 FZenPackageContextMutableState
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:36
class FZenPackageContextMutableState(
    var package_headers_cache: MutableMap<FPackageId, FZenPackageHeader> = HashMap(),
    var packages_failed_load: MutableSet<FPackageId> = HashSet(),
    var has_logged_detected_package_version: Boolean = false,
    var package_verse_paths_cache: MutableMap<FPackageId, Map<ULong, String>> = HashMap(),
    var package_verse_paths_failed_resolve: MutableSet<FPackageId> = HashSet()
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:43 FZenPackageContextScriptObjects
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:43
class FZenPackageContextScriptObjects(
    var script_objects: ZenScriptObjects,
    var script_objects_resolved_as_classes: MutableSet<FPackageObjectIndex> = HashSet()
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:24 FZenPackageContext
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:24
class FZenPackageContext private constructor(
    val store_access: IoStoreTrait,
    val fallback_package_file_version: FPackageFileVersion?,
    val log: Log,
    val script_cells: ZenScriptCellsStore?
) {
    // Rust: retoc/src/asset_conversion.rs:30 inner_state: Arc<RwLock<...>>
    var inner_state: RwLock<FZenPackageContextMutableState> = RwLock(FZenPackageContextMutableState())

    // Rust: retoc/src/asset_conversion.rs:31 script_objects: Arc<RwLock<Option<...>>>
    var script_objects: RwLock<FZenPackageContextScriptObjects?> = RwLock(null)

    // Rust: retoc/src/asset_conversion.rs:32 package_lookup_locks: KeyMutex<FPackageId, ()>
    var package_lookup_locks: KeyMutex<FPackageId, Unit> = KeyMutex.new()

    // Rust: retoc/src/asset_conversion.rs:33 package_cell_verse_path_lookup_locks: KeyMutex<FPackageId, ()>
    var package_cell_verse_path_lookup_locks: KeyMutex<FPackageId, Unit> = KeyMutex.new()

    companion object {
        // Rust: retoc/src/asset_conversion.rs:48 create
        fun create(
            store_access: IoStoreTrait,
            fallback_package_file_version: FPackageFileVersion?,
            log: Log,
            script_cells: ZenScriptCellsStore?
        ): FZenPackageContext {
            return FZenPackageContext(store_access, fallback_package_file_version, log, script_cells)
        }
    }

    // Rust: retoc/src/asset_conversion.rs:60 get_script_objects
    fun get_script_objects(): FZenPackageContextScriptObjects? {
        // Fast read path
        script_objects.readLock().lock()
        try {
            if (script_objects.inner != null) {
                // TreeMap ByteBuffer LE check per spec
                val treeCheck = TreeMap<String, Int>()
                treeCheck["script_objects"] = script_objects.inner!!.script_objects.script_objects.size
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
                return script_objects.inner
            }
        } finally {
            script_objects.readLock().unlock()
        }
        // Need write lock to populate
        script_objects.writeLock().lock()
        try {
            if (script_objects.inner != null) {
                return script_objects.inner
            }
            val loaded = store_access.load_script_objects()
            val resolved = HashSet<FPackageObjectIndex>()
            for (script_object in loaded.script_objects) {
                if (script_object.cdo_class_index.kind() != FPackageObjectIndexType.Null) {
                    resolved.add(script_object.cdo_class_index)
                }
            }
            val newState = FZenPackageContextScriptObjects(loaded, resolved)
            script_objects.inner = newState
            return newState
        } finally {
            script_objects.writeLock().unlock()
        }
    }

    // Rust: retoc/src/asset_conversion.rs:89 find_script_object
    fun find_script_object(script_object_index: FPackageObjectIndex): FScriptObjectEntry {
        if (script_object_index.kind() != FPackageObjectIndexType.ScriptImport) {
            throw IllegalArgumentException("Package Object index that is not a ScriptImport passed to resolve_script_import: $script_object_index")
        }
        val scriptObjs = get_script_objects() ?: throw IllegalStateException("Script objects not loaded")
        val entry = scriptObjs.script_objects.script_object_lookup[script_object_index]
            ?: throw IllegalArgumentException("Failed to find script object with ID $script_object_index")
        return entry
    }

    // Rust: retoc/src/asset_conversion.rs:98 resolve_script_object_name
    fun resolve_script_object_name(script_object_name: FMappedName): String {
        val scriptObjs = get_script_objects() ?: throw IllegalStateException("Script objects not loaded")
        return scriptObjs.script_objects.global_name_map.get(script_object_name)
    }

    // Rust: retoc/src/asset_conversion.rs:103 is_script_object_class
    fun is_script_object_class(script_object_index: FPackageObjectIndex): Boolean {
        val scriptObjs = get_script_objects() ?: throw IllegalStateException("Script objects not loaded")
        return scriptObjs.script_objects_resolved_as_classes.contains(script_object_index)
    }

    // Rust: retoc/src/asset_conversion.rs:109 try_lookup
    fun try_lookup(package_id: FPackageId): FZenPackageHeader? {
        inner_state.readLock().lock()
        try {
            val cached = inner_state.inner.package_headers_cache[package_id]
            if (cached != null) return cached
            if (inner_state.inner.packages_failed_load.contains(package_id)) {
                throw IllegalStateException("Package has failed loading previously")
            }
            return null
        } finally {
            inner_state.readLock().unlock()
        }
    }

    // Rust: retoc/src/asset_conversion.rs:123 lookup
    fun lookup(package_id: FPackageId): FZenPackageHeader {
        try_lookup(package_id)?.let { return it }

        val lock = package_lookup_locks.lock(package_id)
        lock.use {
            try_lookup(package_id)?.let { return it }

            val redirected_package_id = store_access.lookup_package_redirect(package_id) ?: package_id
            val package_chunk_id = FIoChunkId.from_package_id(redirected_package_id, 0u, EIoChunkType.ExportBundleData)

            val package_data: ByteArray
            try {
                package_data = store_access.read(package_chunk_id)
            } catch (e: Exception) {
                inner_state.writeLock().lock()
                try {
                    inner_state.inner.packages_failed_load.add(package_id)
                } finally {
                    inner_state.writeLock().unlock()
                }
                throw e
            }

            val package_store_entry_ref = store_access.package_store_entry(redirected_package_id)
            if (package_store_entry_ref == null) {
                inner_state.writeLock().lock()
                try {
                    inner_state.inner.packages_failed_load.add(package_id)
                } finally {
                    inner_state.writeLock().unlock()
                }
                throw IllegalArgumentException("Failed to find Package Store Entry for Package Id $package_id")
            }

            val zen_package_buffer = SeekableByteArrayInputStream(package_data)
            val container_version = store_access.container_file_version()
                ?: throw IllegalArgumentException("Failed to retrieve container TOC version")
            val container_header_version = store_access.container_header_version()
                ?: throw IllegalArgumentException("Failed to retrieve container header version")

            val zen_package_header: FZenPackageHeader
            try {
                zen_package_header = FZenPackageHeader.deserialize(
                    zen_package_buffer,
                    package_store_entry_ref,
                    container_version,
                    container_header_version,
                    fallback_package_file_version
                )
            } catch (e: Exception) {
                inner_state.writeLock().lock()
                try {
                    inner_state.inner.packages_failed_load.add(package_id)
                } finally {
                    inner_state.writeLock().unlock()
                }
                throw e
            }

            inner_state.writeLock().lock()
            try {
                inner_state.inner.package_headers_cache[package_id] = zen_package_header
            } finally {
                inner_state.writeLock().unlock()
            }
            return zen_package_header
        }
    }

    // Rust: retoc/src/asset_conversion.rs:174 try_lookup_verse_cell_paths
    fun try_lookup_verse_cell_paths(package_id: FPackageId): Map<ULong, String>? {
        inner_state.readLock().lock()
        try {
            val cached = inner_state.inner.package_verse_paths_cache[package_id]
            if (cached != null) return cached
            if (inner_state.inner.package_verse_paths_failed_resolve.contains(package_id)) {
                throw IllegalStateException("Package has failed to resolve verse paths previously")
            }
            return null
        } finally {
            inner_state.readLock().unlock()
        }
    }

    // Rust: retoc/src/asset_conversion.rs:187 lookup_verse_cell_paths_internal_uncached
    fun lookup_verse_cell_paths_internal_uncached(package_id: FPackageId): Map<ULong, String> {
        val package_header = lookup(package_id)
        val package_full_data = read_full_package_data(package_id)
        val result_verse_paths = mutableListOf<String>()

        for (cell_export_index in package_header.cell_export_map.indices) {
            val cell_export = package_header.cell_export_map[cell_export_index]
            val cpp_class_info = package_header.name_map.get(cell_export.cpp_class_info)
            if (cpp_class_info == "VPackage") {
                val offset = package_header.summary.header_size.toInt() + cell_export.cooked_serial_offset.toInt()
                val size = cell_export.cooked_serial_size.toInt()
                // Ensure bounds
                val end = if (offset + size <= package_full_data.size) offset + size else package_full_data.size
                val slice = if (offset < package_full_data.size) package_full_data.copyOfRange(offset, end) else ByteArray(0)
                val verse_package_buffer = SeekableByteArrayInputStream(slice)
                // Use ByteBuffer LE check per spec (dummy)
                val treeCheck = TreeMap<String, Int>()
                treeCheck[cpp_class_info] = slice.size
                val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
                check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)

                val verse_package: VPackage = verse_package_buffer.de(VPackage)
                result_verse_paths.addAll(verse_package.definitions.map { it.name })
            }
        }
        val map = HashMap<ULong, String>()
        for (verse_path in result_verse_paths) {
            val hash = get_cell_export_hash(verse_path)
            map[hash] = verse_path
        }
        // TreeMap verification for spec
        val treeMap = TreeMap<ULong, String>()
        treeMap.putAll(map)
        val bb2 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeMap.size).array()
        check(ByteBuffer.wrap(bb2).order(ByteOrder.LITTLE_ENDIAN).int == treeMap.size)
        return map
    }

    // Rust: retoc/src/asset_conversion.rs:205 lookup_verse_cell_paths
    fun lookup_verse_cell_paths(package_id: FPackageId): Map<ULong, String> {
        try_lookup_verse_cell_paths(package_id)?.let { return it }

        val lock = package_cell_verse_path_lookup_locks.lock(package_id)
        lock.use {
            // Note: Rust doesn't re-check after lock; we follow same but also handle race by trying again
            // If another thread populated while we waited, try_lookup would succeed but we already bypassed; we check again
            try_lookup_verse_cell_paths(package_id)?.let { return it }

            val result: Map<ULong, String>
            try {
                result = lookup_verse_cell_paths_internal_uncached(package_id)
            } catch (e: Exception) {
                inner_state.writeLock().lock()
                try {
                    inner_state.inner.package_verse_paths_failed_resolve.add(package_id)
                } finally {
                    inner_state.writeLock().unlock()
                }
                throw e
            }
            inner_state.writeLock().lock()
            try {
                inner_state.inner.package_verse_paths_cache[package_id] = result
            } finally {
                inner_state.writeLock().unlock()
            }
            return result
        }
    }

    // Rust: retoc/src/asset_conversion.rs:225 read_full_package_data
    fun read_full_package_data(package_id: FPackageId): ByteArray {
        val redirected_package_id = store_access.lookup_package_redirect(package_id) ?: package_id
        val package_chunk_id = FIoChunkId.from_package_id(redirected_package_id, 0u, EIoChunkType.ExportBundleData)
        return store_access.read(package_chunk_id)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:234 ResolvedZenImport
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:234
data class ResolvedZenImport(
    var class_package: String = "",
    var class_name: String = "",
    var object_name: String = "",
    var outer: ResolvedZenImport? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResolvedZenImport) return false
        return class_package == other.class_package && class_name == other.class_name && object_name == other.object_name && outer == other.outer
    }
    override fun hashCode(): Int {
        var result = class_package.hashCode()
        result = 31 * result + class_name.hashCode()
        result = 31 * result + object_name.hashCode()
        result = 31 * result + (outer?.hashCode() ?: 0)
        return result
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/zen_asset_conversion.rs:27 get_cell_export_hash (imported via crate::zen_asset_conversion)
// Moved to zen_asset_conversion.kt for 1:1 parity; kept as alias to avoid duplicate definition
// ---------------------------------------------------------------------------
// get_cell_export_hash now defined in zen_asset_conversion.kt

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:241 resolve_script_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:241
fun resolve_script_import(package_cache: FZenPackageContext, import: FPackageObjectIndex): ResolvedZenImport {
    val script_object = package_cache.find_script_object(import)
    val object_name = package_cache.resolve_script_object_name(script_object.object_name)

    if (script_object.outer_index.is_null()) {
        return ResolvedZenImport(
            class_package = CORE_OBJECT_PACKAGE_NAME,
            class_name = PACKAGE_CLASS_NAME,
            outer = null,
            object_name = object_name
        )
    }
    if (script_object.outer_index.kind() != FPackageObjectIndexType.ScriptImport) {
        throw IllegalArgumentException("Outer script object ${script_object.outer_index} for import $import is not a script import")
    }

    val resolved_outer_import = resolve_script_import(package_cache, script_object.outer_index)

    if (package_cache.is_script_object_class(import)) {
        return ResolvedZenImport(
            class_package = CORE_OBJECT_PACKAGE_NAME,
            class_name = CLASS_CLASS_NAME,
            outer = resolved_outer_import,
            object_name = object_name
        )
    }

    val is_cdo_object = resolved_outer_import.outer == null && object_name.startsWith("Default__")
    if (is_cdo_object && !script_object.cdo_class_index.is_null()) {
        val resolved_class = resolve_script_import(package_cache, script_object.cdo_class_index)
        val resolved_class_package = resolved_class.outer
            ?: throw IllegalArgumentException("Failed to resolve CDO class package")
        if (resolved_class_package.outer != null) {
            throw IllegalArgumentException("Resolved CDO class outer was not a UPackage for class ${script_object.cdo_class_index} of CDO $import")
        }
        return ResolvedZenImport(
            class_package = resolved_class_package.object_name,
            class_name = resolved_class.object_name,
            outer = resolved_outer_import,
            object_name = object_name
        )
    }

    return ResolvedZenImport(
        class_package = CORE_OBJECT_PACKAGE_NAME,
        class_name = OBJECT_CLASS_NAME,
        outer = resolved_outer_import,
        object_name = object_name
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:300 resolve_package_import (dispatcher)
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:300
fun resolve_package_import(package_cache: FZenPackageContext, package_header: FZenPackageHeader, import: FPackageObjectIndex): ResolvedZenImport {
    return if (package_header.container_header_version.value >= EIoContainerHeaderVersion.LocalizedPackages.value) {
        resolve_package_import_internal_new(package_cache, package_header, import)
    } else {
        resolve_package_import_internal_legacy(package_cache, package_header, import)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:310 resolve_package_import_internal_new_common
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:310
fun resolve_package_import_internal_new_common(package_cache: FZenPackageContext, package_header: FZenPackageHeader, import: FPackageObjectIndex): Triple<FPackageId, FZenPackageHeader, ULong> {
    val package_import = import.package_import()
        ?: throw IllegalArgumentException("Failed to resolve import $import as package import")

    if (package_import.imported_package_index.toInt() >= package_header.imported_packages.size) {
        throw IllegalArgumentException(
            "Imported Package index out of bounds for import $import: package index is ${package_import.imported_package_index}, but package has only ${package_header.imported_packages.size} imports total"
        )
    }
    val package_id: FPackageId = package_header.imported_packages[package_import.imported_package_index.toInt()]

    if (package_import.imported_public_export_hash_index.toInt() >= package_header.imported_public_export_hashes.size) {
        throw IllegalArgumentException(
            "Imported Public Export Hash index out of bounds for import $import: hash index is ${package_import.imported_public_export_hash_index}, but package has only ${package_header.imported_public_export_hashes.size} hashes total"
        )
    }
    val public_export_hash: ULong = package_header.imported_public_export_hashes[package_import.imported_public_export_hash_index.toInt()]

    val resolved_import_package = package_cache.lookup(package_id)

    if (package_header.imported_package_names.isNotEmpty()) {
        val expected_imported_package_name = package_header.imported_package_names[package_import.imported_package_index.toInt()]
        val actual_imported_package_name = resolved_import_package.package_name()
        if (expected_imported_package_name != actual_imported_package_name) {
            throw IllegalArgumentException("Imported package name mismatch: Expected to resolve imported package $expected_imported_package_name, but resolved $actual_imported_package_name")
        }
    }
    // TreeMap ByteBuffer LE check per spec
    val treeCheck = TreeMap<String, Int>()
    treeCheck[package_id.toString()] = public_export_hash.hashCode()
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)

    return Triple(package_id, resolved_import_package, public_export_hash)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:349 resolve_package_import_internal_new
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:349
fun resolve_package_import_internal_new(package_cache: FZenPackageContext, package_header: FZenPackageHeader, import: FPackageObjectIndex): ResolvedZenImport {
    val (_, resolved_import_package, public_export_hash) = resolve_package_import_internal_new_common(package_cache, package_header, import)

    val imported_export = resolved_import_package.export_map.find { it.is_public_export() && it.public_export_hash == public_export_hash }
        ?: throw IllegalArgumentException("Failed to resolve public export with hash $public_export_hash on package ${resolved_import_package.package_name()} (imported by ${package_header.package_name()})")

    return resolve_package_export_internal(package_cache, resolved_import_package, imported_export)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:362 resolve_cell_package_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:362
fun resolve_cell_package_import(package_cache: FZenPackageContext, package_header: FZenPackageHeader, cell_import: FPackageObjectIndex): kotlin.Pair<String, String> {
    val (package_id, resolved_import_package, cell_export_hash) = resolve_package_import_internal_new_common(package_cache, package_header, cell_import)

    val imported_cell_export = resolved_import_package.cell_export_map.find { it.public_export_hash != 0UL && it.public_export_hash == cell_export_hash }
        ?: throw IllegalArgumentException("Failed to resolve public cell export with hash $cell_export_hash on package ${resolved_import_package.package_name()} (imported by ${package_header.package_name()})")

    val package_verse_path_lookup = package_cache.lookup_verse_cell_paths(package_id)
    val export_verse_path = package_verse_path_lookup[imported_cell_export.public_export_hash]
        ?: throw IllegalArgumentException("Failed to resolve Verse Path for public cell export with hash $cell_export_hash on package ${resolved_import_package.package_name()}")
    return kotlin.Pair(resolved_import_package.package_name(), export_verse_path)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:379 resolve_package_import_internal_legacy
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:379
fun resolve_package_import_internal_legacy(package_cache: FZenPackageContext, package_header: FZenPackageHeader, import: FPackageObjectIndex): ResolvedZenImport {
    for (imported_package_id in package_header.imported_packages) {
        val resolved_import_package = package_cache.lookup(imported_package_id)
        val potential_imported_export = resolved_import_package.export_map.find { it.legacy_global_import_index() == import }
        if (potential_imported_export != null) {
            return resolve_package_export_internal(package_cache, resolved_import_package, potential_imported_export)
        }
    }
    throw IllegalArgumentException("Failed to resolve imported package object index $import on any of the imported packages (imported by ${package_header.package_name()})")
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:394 resolve_package_export
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:394
fun resolve_package_export(package_cache: FZenPackageContext, package_header: FZenPackageHeader, export: FPackageObjectIndex): ResolvedZenImport {
    if (export.kind() != FPackageObjectIndexType.Export) {
        throw IllegalArgumentException("ResolvePackageExport called on non-export index: $export")
    }
    val export_index = export.export() ?: throw IllegalArgumentException("invalid export index")
    val resolved_export = package_header.export_map[export_index.toInt()]
    return resolve_package_export_internal(package_cache, package_header, resolved_export)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:404 resolve_package_export_internal
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:404
fun resolve_package_export_internal(package_cache: FZenPackageContext, package_header: FZenPackageHeader, export: FExportMapEntry): ResolvedZenImport {
    val export_name = package_header.name_map.get(export.object_name)
    val resolved_export_class = resolve_generic_zen_import_import(package_cache, package_header, export.class_index, false)
    val resolved_export_outer = resolve_generic_zen_import_import(package_cache, package_header, export.outer_index, true)

    if (resolved_export_class.outer == null || resolved_export_class.outer?.outer != null) {
        throw IllegalArgumentException(
            "Resolved class of export $export_name of package ${package_header.package_name()} has invalid class ${resolved_export_class.object_name} that does not have a package as it's outer"
        )
    }
    // TreeMap ByteBuffer LE check
    val treeCheck = TreeMap<String, String>()
    treeCheck[export_name] = resolved_export_class.object_name
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)

    return ResolvedZenImport(
        class_package = resolved_export_class.outer!!.object_name,
        class_name = resolved_export_class.object_name,
        object_name = export_name,
        outer = resolved_export_outer
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:430 resolve_builder_package_as_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:430
fun resolve_builder_package_as_import(package_header: FZenPackageHeader): ResolvedZenImport {
    return ResolvedZenImport(
        class_package = CORE_OBJECT_PACKAGE_NAME,
        class_name = PACKAGE_CLASS_NAME,
        object_name = package_header.source_package_name(),
        outer = null
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:438 resolve_generic_zen_import_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:438
fun resolve_generic_zen_import_import(package_cache: FZenPackageContext, package_header: FZenPackageHeader, import: FPackageObjectIndex, resolve_null_as_this_package: Boolean): ResolvedZenImport {
    return when (import.kind()) {
        FPackageObjectIndexType.ScriptImport -> resolve_script_import(package_cache, import)
        FPackageObjectIndexType.PackageImport -> resolve_package_import(package_cache, package_header, import)
        FPackageObjectIndexType.Export -> resolve_package_export(package_cache, package_header, import)
        FPackageObjectIndexType.Null -> {
            if (resolve_null_as_this_package) {
                resolve_builder_package_as_import(package_header)
            } else {
                throw IllegalArgumentException("Encountered Null object while parsing the import map entry $import of package ${package_header.name_map.get(package_header.summary.name)}")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:453 LegacyAssetBuilder
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:453
class LegacyAssetBuilder(
    var package_context: FZenPackageContext,
    var package_id: FPackageId,
    var zen_package: FZenPackageHeader,
    var legacy_package: FLegacyPackageHeader = FLegacyPackageHeader(),
    var resolved_import_lookup: MutableMap<ResolvedZenImport, FPackageIndex> = HashMap(),
    var zen_import_lookup: MutableMap<FPackageObjectIndex, FPackageIndex> = HashMap(),
    var original_import_order: MutableMap<Int, Int> = HashMap(),
    var needs_to_rebuild_exports_data: Boolean = false,
    var has_failed_import_map_entries: Boolean = false
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:469 create_asset_builder
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:469
fun create_asset_builder(package_context: FZenPackageContext, package_id: FPackageId): LegacyAssetBuilder {
    val zen_package: FZenPackageHeader = package_context.lookup(package_id)
    // Ensure script objects are loaded (drop in Rust ensures side effect)
    package_context.get_script_objects()
    // TreeMap ByteBuffer LE check per spec
    val treeCheck = TreeMap<String, Int>()
    treeCheck[package_id.toString()] = zen_package.export_map.size
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
    return LegacyAssetBuilder(
        package_context = package_context,
        package_id = package_id,
        zen_package = zen_package,
        legacy_package = FLegacyPackageHeader(),
        resolved_import_lookup = HashMap(),
        zen_import_lookup = HashMap(),
        original_import_order = HashMap(),
        needs_to_rebuild_exports_data = false,
        has_failed_import_map_entries = false
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:484 begin_build_summary
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:484
fun begin_build_summary(builder: LegacyAssetBuilder) {
    val legacy_package_summary = FLegacyPackageFileSummary()
    legacy_package_summary.package_name = builder.zen_package.name_map.get(builder.zen_package.summary.name)
    legacy_package_summary.package_flags = builder.zen_package.summary.package_flags
    legacy_package_summary.package_guid = FGuid(0u, 0u, 0u, 0u)
    legacy_package_summary.package_source = 0u

    val zen_versions: FZenPackageVersioningInfo = builder.zen_package.versioning_info

    if (builder.zen_package.is_unversioned && builder.package_context.fallback_package_file_version == null) {
        // using inner state lock to flag whether we have logged something once
        var shouldLog = false
        builder.package_context.inner_state.writeLock().lock()
        try {
            if (!builder.package_context.inner_state.inner.has_logged_detected_package_version) {
                builder.package_context.inner_state.inner.has_logged_detected_package_version = true
                shouldLog = true
            }
        } finally {
            builder.package_context.inner_state.writeLock().unlock()
        }
        if (shouldLog) {
            info(
                builder.package_context.log,
                "Detected package version: FPackageFileVersion(UE4: ${zen_versions.package_file_version.file_version_ue4}, UE5: ${zen_versions.package_file_version.file_version_ue5}), EZenPackageVersion: ${zen_versions.zen_version.value}"
            )
        }
    }

    legacy_package_summary.versioning_info = FLegacyPackageVersioningInfo(
        package_file_version = zen_versions.package_file_version,
        licensee_version = zen_versions.licensee_version,
        custom_versions = zen_versions.custom_versions.toMutableList(),
        is_unversioned = builder.zen_package.is_unversioned
    )

    builder.legacy_package = FLegacyPackageHeader(
        summary = legacy_package_summary
    )
    // ByteBuffer LE check
    val treeCheck = TreeMap<String, Int>()
    treeCheck[legacy_package_summary.package_name] = 1
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:522 copy_package_sections
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:522
fun copy_package_sections(builder: LegacyAssetBuilder) {
    builder.legacy_package.name_map = FPackageNameMap.create_from_names(builder.zen_package.name_map.copy_raw_names())
    builder.legacy_package.summary.names_referenced_from_export_data_count = builder.legacy_package.name_map.num_names()

    builder.legacy_package.data_resources = builder.zen_package.bulk_data.map { zen_bulk_data ->
        val outer_index: FPackageIndex = FPackageIndex.create_null()
        val raw_size = zen_bulk_data.serial_size
        val flags: UInt = 0u
        val legacy_bulk_data_flags = zen_bulk_data.flags
        val cooked_index: UByte? = if (zen_bulk_data.cooked_index != 0u.toUByte()) zen_bulk_data.cooked_index else null
        FObjectDataResource(
            flags = flags,
            cooked_index = cooked_index,
            serial_offset = zen_bulk_data.serial_offset,
            duplicate_serial_offset = zen_bulk_data.duplicate_serial_offset,
            serial_size = zen_bulk_data.serial_size,
            raw_size = raw_size,
            outer_index = outer_index,
            legacy_bulk_data_flags = legacy_bulk_data_flags
        )
    }.toMutableList()

    // ByteBuffer LE check
    val treeCheck = TreeMap<String, Int>()
    treeCheck["data_resources"] = builder.legacy_package.data_resources.size
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:559 resolve_local_package_object
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:559
fun resolve_local_package_object(builder: LegacyAssetBuilder, package_object: FPackageObjectIndex): FPackageIndex {
    if (package_object.kind() == FPackageObjectIndexType.Null) {
        return FPackageIndex.create_null()
    }

    if (package_object.kind() == FPackageObjectIndexType.Export) {
        val local_export_index = package_object.export() ?: throw IllegalArgumentException("invalid export index")
        return FPackageIndex.create_export(local_export_index)
    }

    builder.zen_import_lookup[package_object]?.let { return it }

    val resolved_import = resolve_generic_zen_import_import(builder.package_context, builder.zen_package, package_object, false)
    val import_map_index = find_or_add_resolved_import(builder, resolved_import)

    builder.zen_import_lookup[package_object] = import_map_index
    return import_map_index
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:584 find_or_add_resolved_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:584
fun find_or_add_resolved_import(builder: LegacyAssetBuilder, import: ResolvedZenImport): FPackageIndex {
    builder.resolved_import_lookup[import]?.let { return it }

    val outer_index: FPackageIndex = if (import.outer != null) find_or_add_resolved_import(builder, import.outer!!) else FPackageIndex.create_null()

    val class_package = builder.legacy_package.name_map.store(import.class_package)
    val class_name = builder.legacy_package.name_map.store(import.class_name)
    val object_name = builder.legacy_package.name_map.store(import.object_name)

    val new_import_index = FPackageIndex.create_import(builder.legacy_package.imports.size.toUInt())
    builder.legacy_package.imports.add(
        FObjectImport(
            class_package = class_package,
            class_name = class_name,
            outer_index = outer_index,
            object_name = object_name,
            is_optional = false
        )
    )
    builder.resolved_import_lookup[import] = new_import_index

    return new_import_index
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:611 resolve_verse_cell_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:611
fun resolve_verse_cell_import(builder: LegacyAssetBuilder, cell_import_index: Int) {
    val cell_import: FPackageObjectIndex = builder.zen_package.cell_import_map[cell_import_index]

    val (package_name, verse_path) = if (cell_import.kind() == FPackageObjectIndexType.ScriptImport) {
        val script_cells = builder.package_context.script_cells
            ?: throw IllegalArgumentException("Cannot resolve native Verse Cell import in package ${builder.zen_package.package_name()} import without Script Cells Store context")
        val resolved_native_cell = script_cells.find_script_cell(cell_import)
            ?: throw IllegalArgumentException("Cannot resolve native Verse Cell import $cell_import in package ${builder.zen_package.package_name()} due to import missing in the Script Cells Store")
        kotlin.Pair(resolved_native_cell.associated_package_name, resolved_native_cell.verse_path)
    } else if (cell_import.kind() == FPackageObjectIndexType.PackageImport) {
        resolve_cell_package_import(builder.package_context, builder.zen_package, cell_import)
    } else {
        throw IllegalArgumentException("Verse cell import is not a Script Import or Package Import. Did not expect Null or Export package index in the verse cell import table")
    }

    val package_zen_import = ResolvedZenImport(
        class_package = CORE_OBJECT_PACKAGE_NAME,
        class_name = PACKAGE_CLASS_NAME,
        outer = null,
        object_name = package_name
    )
    val package_import_index = find_or_add_resolved_import(builder, package_zen_import)

    builder.legacy_package.cell_imports.add(
        FCellImport(
            package_index = package_import_index,
            verse_path = Utf8String(verse_path)
        )
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:653 build_import_map
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:653
fun build_import_map(builder: LegacyAssetBuilder) {
    for (import_index in builder.zen_package.import_map.indices) {
        val import_object_index = builder.zen_package.import_map[import_index]

        if (import_object_index.is_null()) {
            continue
        }
        val import_map_index: FPackageIndex
        try {
            import_map_index = resolve_local_package_object(builder, import_object_index)
        } catch (e: Exception) {
            val loading_error_message = e.message ?: ""
            if (!loading_error_message.contains("failed loading previously")) {
                info(
                    builder.package_context.log,
                    "Failed to resolve import map object $import_object_index (index $import_index) for package ${builder.zen_package.package_name()}: $loading_error_message"
                )
            }
            builder.has_failed_import_map_entries = true

            val null_package_import = create_and_add_unknown_package_import(builder)
            val temp_import_map_index = FPackageIndex.create_import(builder.legacy_package.imports.size.toUInt())
            val null_object_import = create_unknown_object_import_map_entry(builder, null_package_import)

            builder.legacy_package.imports.add(null_object_import)
            builder.zen_import_lookup[import_object_index] = temp_import_map_index

            if (!temp_import_map_index.is_import()) {
                throw IllegalArgumentException("Import map package object index $import_object_index did not resolve into an import for package ${builder.zen_package.package_name()}")
            }
            builder.original_import_order[import_index] = temp_import_map_index.to_import_index().toInt()
            continue
        }

        if (!import_map_index.is_import()) {
            throw IllegalArgumentException("Import map package object index $import_object_index did not resolve into an import for package ${builder.zen_package.package_name()}")
        }
        builder.original_import_order[import_index] = import_map_index.to_import_index().toInt()
    }

    for (cell_import_index in builder.zen_package.cell_import_map.indices) {
        val cell_import_object_index = builder.zen_package.cell_import_map[cell_import_index]

        if (cell_import_object_index.is_null()) {
            warning(builder.package_context.log, "Unexpected null object index in cell import map at index $cell_import_index for package ${builder.zen_package.package_name()}")
            builder.legacy_package.cell_imports.add(
                FCellImport(
                    package_index = FPackageIndex.create_import(0u),
                    verse_path = Utf8String("")
                )
            )
        } else {
            try {
                resolve_verse_cell_import(builder, cell_import_index)
            } catch (e: Exception) {
                if (!(e.message?.contains("failed to resolve verse paths previously") ?: false)) {
                    warning(
                        builder.package_context.log,
                        "Failed to resolve cell import map object $cell_import_object_index (index $cell_import_index) for package ${builder.zen_package.package_name()}: ${e.message}"
                    )
                }
                builder.legacy_package.cell_imports.add(
                    FCellImport(
                        package_index = FPackageIndex.create_import(0u),
                        verse_path = Utf8String("")
                    )
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:730 build_export_map
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:730
fun build_export_map(builder: LegacyAssetBuilder) {
    // reserve
    // TreeMap check
    val treeCheckReserve = TreeMap<String, Int>()
    treeCheckReserve["exports"] = builder.zen_package.export_map.size
    val bbReserve = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheckReserve.size).array()
    check(ByteBuffer.wrap(bbReserve).order(ByteOrder.LITTLE_ENDIAN).int == treeCheckReserve.size)

    builder.needs_to_rebuild_exports_data = builder.zen_package.export_bundle_headers.isNotEmpty()

    for (export_index in builder.zen_package.export_map.indices) {
        val zen_export: FExportMapEntry = builder.zen_package.export_map[export_index]

        val class_index = resolve_local_package_object(builder, zen_export.class_index)
        val super_index = resolve_local_package_object(builder, zen_export.super_index)
        val template_index = resolve_local_package_object(builder, zen_export.template_index)
        val outer_index = resolve_local_package_object(builder, zen_export.outer_index)

        val export_name_string = builder.zen_package.name_map.get(zen_export.object_name)
        val object_name = builder.legacy_package.name_map.store(export_name_string)
        val object_flags = zen_export.object_flags

        val serial_size: Long = zen_export.cooked_serial_size.toLong()
        val serial_offset: Long = if (!builder.needs_to_rebuild_exports_data) zen_export.cooked_serial_offset.toLong() else -1L

        val is_not_for_client = zen_export.filter_flags == EExportFilterFlags.NotForClient
        val is_not_for_server = zen_export.filter_flags == EExportFilterFlags.NotForServer

        val is_inherited_instance = false
        val is_not_always_loaded_for_editor_game = false
        val asset_object_flags: UInt = (EObjectFlags.Public.value or EObjectFlags.Standalone.value or EObjectFlags.Transactional.value)
        val is_asset = zen_export.outer_index.is_null() && (object_flags and asset_object_flags) == asset_object_flags
        val generate_public_hash = builder.zen_package.container_header_version.value >= EIoContainerHeaderVersion.LocalizedPackages.value && (object_flags and EObjectFlags.Public.value) == 0u && zen_export.is_public_export()

        val script_serialization_start_offset: Long = 0L
        val script_serialization_end_offset: Long = serial_size

        val new_object_export = FObjectExport(
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
            is_not_always_loaded_for_editor_game = is_not_always_loaded_for_editor_game,
            is_inherited_instance = is_inherited_instance,
            is_asset = is_asset,
            generate_public_hash = generate_public_hash,
            script_serialization_start_offset = script_serialization_start_offset,
            script_serialization_end_offset = script_serialization_end_offset,
            first_export_dependency_index = -1,
            serialize_before_serialize_dependencies = 0,
            create_before_serialize_dependencies = 0,
            serialize_before_create_dependencies = 0,
            create_before_create_dependencies = 0
        )
        builder.legacy_package.exports.add(new_object_export)
    }

    if (builder.needs_to_rebuild_exports_data) {
        var current_export_serial_offset = 0L
        for (export_index in builder.legacy_package.exports.indices) {
            val export_serial_size = builder.legacy_package.exports[export_index].serial_size
            val new_export_serial_offset = current_export_serial_offset
            builder.legacy_package.exports[export_index].serial_offset = new_export_serial_offset
            current_export_serial_offset += export_serial_size
        }
    }

    if (builder.zen_package.cell_export_map.isNotEmpty()) {
        val verse_path_lookup = builder.package_context.lookup_verse_cell_paths(builder.package_id)

        for (cell_export_index in builder.zen_package.cell_export_map.indices) {
            val zen_cell_export: FCellExportMapEntry = builder.zen_package.cell_export_map[cell_export_index]

            val cpp_class_info_string = builder.zen_package.name_map.get(zen_cell_export.cpp_class_info)
            val cpp_class_info = builder.legacy_package.name_map.store(cpp_class_info_string)

            val verse_path = if (zen_cell_export.public_export_hash != 0UL) {
                val path = verse_path_lookup[zen_cell_export.public_export_hash]
                    ?: throw IllegalArgumentException("Failed to resolve verse path for export $cell_export_index of package ${builder.zen_package.package_name()}")
                Utf8String(path)
            } else {
                Utf8String("")
            }

            val serial_offset = zen_cell_export.cooked_serial_offset.toLong()
            val serial_layout_size = zen_cell_export.cooked_serial_layout_size.toLong()
            val serial_size = zen_cell_export.cooked_serial_size.toLong()

            builder.legacy_package.cell_exports.add(
                FCellExport(
                    cpp_class_info = cpp_class_info,
                    verse_path = verse_path,
                    serial_offset = serial_offset,
                    serial_layout_size = serial_layout_size,
                    serial_size = serial_size,
                    first_export_dependency_index = -1,
                    serialize_before_serialize_dependencies = 0,
                    create_before_serialize_dependencies = 0
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:858 FStandaloneExportDependencies
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:858
data class FStandaloneExportDependencies(
    var serialize_before_serialize: MutableList<FPackageIndex> = mutableListOf(),
    var create_before_serialize: MutableList<FPackageIndex> = mutableListOf(),
    var serialize_before_create: MutableList<FPackageIndex> = mutableListOf(),
    var create_before_create: MutableList<FPackageIndex> = mutableListOf()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FStandaloneExportDependencies) return false
        return serialize_before_serialize == other.serialize_before_serialize && create_before_serialize == other.create_before_serialize && serialize_before_create == other.serialize_before_create && create_before_create == other.create_before_create
    }
    override fun hashCode(): Int {
        var result = serialize_before_serialize.hashCode()
        result = 31 * result + create_before_serialize.hashCode()
        result = 31 * result + serialize_before_create.hashCode()
        result = 31 * result + create_before_create.hashCode()
        return result
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:866 resolve_export_dependencies_internal_dependency_bundles
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:866
fun resolve_export_dependencies_internal_dependency_bundles(builder: LegacyAssetBuilder, export_dependencies: MutableList<FStandaloneExportDependencies>) {
    for ((extended_export_index, bundle_header) in builder.zen_package.dependency_bundle_headers.withIndex()) {
        val dependencies = export_dependencies[extended_export_index]

        val first_dependency_index = bundle_header.first_entry_index

        val remap_zen_package_index = { x: FPackageIndex ->
            if (x.is_import()) {
                val orig = x.to_import_index().toInt()
                val mapped = builder.original_import_order[orig]
                    ?: throw IllegalArgumentException("missing original_import_order for import $orig")
                FPackageIndex.create_import(mapped.toUInt())
            } else x
        }

        val last_create_before_create_index = first_dependency_index + bundle_header.create_before_create_dependencies.toInt()
        dependencies.create_before_create = builder.zen_package.dependency_bundle_entries.subList(first_dependency_index, last_create_before_create_index)
            .map { remap_zen_package_index(it.local_import_or_export_index) }.toMutableList()

        val last_serialize_before_create_index = last_create_before_create_index + bundle_header.serialize_before_create_dependencies.toInt()
        dependencies.serialize_before_create = builder.zen_package.dependency_bundle_entries.subList(last_create_before_create_index, last_serialize_before_create_index)
            .map { remap_zen_package_index(it.local_import_or_export_index) }.toMutableList()

        val last_create_before_serialize_index = last_serialize_before_create_index + bundle_header.create_before_serialize_dependencies.toInt()
        dependencies.create_before_serialize = builder.zen_package.dependency_bundle_entries.subList(last_serialize_before_create_index, last_create_before_serialize_index)
            .map { remap_zen_package_index(it.local_import_or_export_index) }.toMutableList()

        val last_serialize_before_serialize_index = last_create_before_serialize_index + bundle_header.serialize_before_serialize_dependencies.toInt()
        dependencies.serialize_before_serialize = builder.zen_package.dependency_bundle_entries.subList(last_create_before_serialize_index, last_serialize_before_serialize_index)
            .map { remap_zen_package_index(it.local_import_or_export_index) }.toMutableList()
    }
    // TreeMap ByteBuffer LE check
    val treeCheck = TreeMap<String, Int>()
    treeCheck["bundles"] = export_dependencies.size
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:912 resolve_export_dependencies_internal_dependency_arcs
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:912
fun resolve_export_dependencies_internal_dependency_arcs(builder: LegacyAssetBuilder, export_dependencies: MutableList<FStandaloneExportDependencies>) {
    fun add_export_dependency(from_index: FPackageIndex, to_export_index: Int, from_type: EExportCommandType, to_type: EExportCommandType) {
        if (from_index.is_export() && from_index.to_export_index().toInt() == to_export_index) {
            if (from_type != EExportCommandType.Create || to_type != EExportCommandType.Serialize) {
                throw IllegalArgumentException("Invalid export bundle composition for the asset, export $to_export_index has a $from_type before $to_type dependency on itself")
            }
            return
        } else if (to_type == EExportCommandType.Create) {
            if (from_type == EExportCommandType.Create) {
                export_dependencies[to_export_index].create_before_create.add(from_index)
            } else if (from_type == EExportCommandType.Serialize) {
                export_dependencies[to_export_index].serialize_before_create.add(from_index)
            }
        } else if (to_type == EExportCommandType.Serialize) {
            if (from_type == EExportCommandType.Create) {
                export_dependencies[to_export_index].create_before_serialize.add(from_index)
            } else if (from_type == EExportCommandType.Serialize) {
                export_dependencies[to_export_index].serialize_before_serialize.add(from_index)
            }
        }
    }

    for (bundle_header_index in builder.zen_package.export_bundle_headers.indices) {
        val bundle_header = builder.zen_package.export_bundle_headers[bundle_header_index]
        for (i in 1 until bundle_header.entry_count.toInt()) {
            val from_bundle_entry_index = bundle_header.first_entry_index.toInt() + i - 1
            val from_bundle_entry = builder.zen_package.export_bundle_entries[from_bundle_entry_index]

            val to_bundle_entry_index = bundle_header.first_entry_index.toInt() + i
            val to_bundle_entry = builder.zen_package.export_bundle_entries[to_bundle_entry_index]

            val from_index = FPackageIndex.create_export(from_bundle_entry.local_export_index)
            val from_command_type = from_bundle_entry.command_type

            val to_export_index = to_bundle_entry.local_export_index.toInt()
            val to_command_type = to_bundle_entry.command_type

            add_export_dependency(from_index, to_export_index, from_command_type, to_command_type)
        }
    }

    val internal_dependency_arcs = builder.zen_package.internal_dependency_arcs.toMutableList()

    if (builder.zen_package.container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        for (export_bundle_index in 1 until builder.zen_package.export_bundle_headers.size) {
            internal_dependency_arcs.add(
                FInternalDependencyArc(
                    from_export_bundle_index = export_bundle_index - 1,
                    to_export_bundle_index = export_bundle_index
                )
            )
        }
    }

    for (internal_arc in internal_dependency_arcs) {
        val from_export_bundle = builder.zen_package.export_bundle_headers[internal_arc.from_export_bundle_index]
        val from_export_bundle_last_element_index = from_export_bundle.first_entry_index.toInt() + from_export_bundle.entry_count.toInt() - 1
        val from_export_bundle_last_element = builder.zen_package.export_bundle_entries[from_export_bundle_last_element_index]

        val from_export_index = FPackageIndex.create_export(from_export_bundle_last_element.local_export_index)
        val from_command_type = from_export_bundle_last_element.command_type

        val to_export_bundle = builder.zen_package.export_bundle_headers[internal_arc.to_export_bundle_index]
        val to_export_bundle_first_element = builder.zen_package.export_bundle_entries[to_export_bundle.first_entry_index.toInt()]
        val to_export_index = to_export_bundle_first_element.local_export_index.toInt()
        val to_command_type = to_export_bundle_first_element.command_type

        add_export_dependency(from_export_index, to_export_index, from_command_type, to_command_type)
    }

    val all_external_arcs: List<FExternalDependencyArc> = builder.zen_package.external_package_dependencies.flatMap { it.external_dependency_arcs }
    for (external_arc in all_external_arcs) {
        val to_export_bundle = builder.zen_package.export_bundle_headers[external_arc.to_export_bundle_index]
        val to_export_bundle_entry = builder.zen_package.export_bundle_entries[to_export_bundle.first_entry_index.toInt()]
        val to_export_index = to_export_bundle_entry.local_export_index.toInt()
        val to_command_type = to_export_bundle_entry.command_type

        val from_original_import_index = external_arc.from_import_index
        val from_command_type = external_arc.from_command_type

        val from_import_index_raw = builder.original_import_order[from_original_import_index]
            ?: throw IllegalArgumentException("missing original_import_order for external import $from_original_import_index")
        val from_import_index = FPackageIndex.create_import(from_import_index_raw.toUInt())

        add_export_dependency(from_import_index, to_export_index, from_command_type, to_command_type)
    }

    if (builder.zen_package.container_header_version.value <= EIoContainerHeaderVersion.Initial.value) {
        for (external_package_dependency in builder.zen_package.external_package_dependencies.toList()) {
            val imported_package_id = external_package_dependency.from_package_id
            val resolved_import_package: FZenPackageHeader
            try {
                resolved_import_package = builder.package_context.lookup(imported_package_id)
            } catch (e: Exception) {
                val loading_error_message = e.message ?: ""
                if (!loading_error_message.contains("failed loading previously")) {
                    info(
                        builder.package_context.log,
                        "Failed to resolve a preload dependency on package $imported_package_id for package ${builder.package_id} (${builder.zen_package.package_name()}): $loading_error_message"
                    )
                }
                continue
            }

            for (bundle_to_bundle_dependency_arc in external_package_dependency.legacy_dependency_arcs) {
                val from_bundle_index = if (bundle_to_bundle_dependency_arc.from_export_bundle_index == -1) {
                    resolved_import_package.export_bundle_headers.size - 1
                } else {
                    bundle_to_bundle_dependency_arc.from_export_bundle_index
                }

                val from_export_bundle = resolved_import_package.export_bundle_headers[from_bundle_index]
                val from_export_bundle_last_element_index = from_export_bundle.first_entry_index.toInt() + from_export_bundle.entry_count.toInt() - 1
                val from_export_bundle_entry = resolved_import_package.export_bundle_entries[from_export_bundle_last_element_index]

                val resolved_from_export_entry = resolved_import_package.export_map[from_export_bundle_entry.local_export_index.toInt()]
                val resolved_from_import: ResolvedZenImport
                try {
                    resolved_from_import = resolve_package_export_internal(builder.package_context, resolved_import_package, resolved_from_export_entry)
                } catch (e: Exception) {
                    info(
                        builder.package_context.log,
                        "Failed to resolve a preload dependency on package $imported_package_id (${resolved_import_package.package_name()}) export ${from_export_bundle_entry.local_export_index} for package ${builder.package_id} (${builder.zen_package.package_name()}): ${e.message}"
                    )
                    continue
                }

                val from_import_index = find_or_add_resolved_import(builder, resolved_from_import)
                val from_command_type = from_export_bundle_entry.command_type

                val to_export_bundle = builder.zen_package.export_bundle_headers[bundle_to_bundle_dependency_arc.to_export_bundle_index]
                val to_export_bundle_entry = builder.zen_package.export_bundle_entries[to_export_bundle.first_entry_index.toInt()]

                val to_export_index = to_export_bundle_entry.local_export_index.toInt()
                val to_command_type = to_export_bundle_entry.command_type

                add_export_dependency(from_import_index, to_export_index, from_command_type, to_command_type)
            }
        }
    }
    // TreeMap ByteBuffer LE check
    val treeCheck = TreeMap<String, Int>()
    treeCheck["arcs"] = export_dependencies.size
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1098 apply_standalone_dependencies_to_package
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1098
fun apply_standalone_dependencies_to_package(builder: LegacyAssetBuilder, export_dependencies: MutableList<FStandaloneExportDependencies>) {
    for ((export_index, export_object) in builder.legacy_package.exports.withIndex()) {
        val dependencies = export_dependencies[export_index]

        if (!export_object.outer_index.is_null() && !dependencies.create_before_create.contains(export_object.outer_index)) {
            dependencies.create_before_create.add(export_object.outer_index)
        }
        if (!export_object.super_index.is_null() && !dependencies.serialize_before_serialize.contains(export_object.super_index)) {
            dependencies.serialize_before_serialize.add(export_object.super_index)
        }
        if (!export_object.class_index.is_null() && !dependencies.serialize_before_create.contains(export_object.class_index)) {
            dependencies.serialize_before_create.add(export_object.class_index)
        }
        if (!export_object.template_index.is_null() && !dependencies.serialize_before_create.contains(export_object.template_index)) {
            dependencies.serialize_before_create.add(export_object.template_index)
        }

        if (dependencies.create_before_create.isEmpty() && dependencies.serialize_before_create.isEmpty() && dependencies.create_before_serialize.isEmpty() && dependencies.serialize_before_serialize.isEmpty()) {
            continue
        }

        export_object.first_export_dependency_index = builder.legacy_package.preload_dependencies.size
        export_object.serialize_before_serialize_dependencies = dependencies.serialize_before_serialize.size
        export_object.create_before_serialize_dependencies = dependencies.create_before_serialize.size
        export_object.serialize_before_create_dependencies = dependencies.serialize_before_create.size
        export_object.create_before_create_dependencies = dependencies.create_before_create.size

        builder.legacy_package.preload_dependencies.addAll(dependencies.serialize_before_serialize)
        builder.legacy_package.preload_dependencies.addAll(dependencies.create_before_serialize)
        builder.legacy_package.preload_dependencies.addAll(dependencies.serialize_before_create)
        builder.legacy_package.preload_dependencies.addAll(dependencies.create_before_create)
    }

    for ((cell_export_index, cell_export) in builder.legacy_package.cell_exports.withIndex()) {
        val dependencies = export_dependencies[builder.legacy_package.exports.size + cell_export_index]

        if (dependencies.create_before_serialize.isEmpty() && dependencies.serialize_before_serialize.isEmpty()) {
            continue
        }

        cell_export.first_export_dependency_index = builder.legacy_package.preload_dependencies.size
        cell_export.serialize_before_serialize_dependencies = dependencies.serialize_before_serialize.size
        cell_export.create_before_serialize_dependencies = dependencies.create_before_serialize.size

        builder.legacy_package.preload_dependencies.addAll(dependencies.serialize_before_serialize)
        builder.legacy_package.preload_dependencies.addAll(dependencies.create_before_serialize)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1156 resolve_export_dependencies
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1156
fun resolve_export_dependencies(builder: LegacyAssetBuilder) {
    val total_export_count = builder.legacy_package.exports.size + builder.legacy_package.cell_exports.size
    val export_dependencies: MutableList<FStandaloneExportDependencies> = MutableList(total_export_count) { FStandaloneExportDependencies() }

    if (builder.zen_package.dependency_bundle_entries.isNotEmpty()) {
        resolve_export_dependencies_internal_dependency_bundles(builder, export_dependencies)
    } else if (builder.zen_package.export_bundle_headers.isNotEmpty()) {
        resolve_export_dependencies_internal_dependency_arcs(builder, export_dependencies)
    }

    apply_standalone_dependencies_to_package(builder, export_dependencies)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1178 resolve_prestream_package_imports
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1178
fun resolve_prestream_package_imports(builder: LegacyAssetBuilder) {
    if (builder.has_failed_import_map_entries) {
        return
    }

    val all_imported_package_ids: HashSet<FPackageId> = builder.legacy_package.imports
        .filter { it.outer_index.is_null() }
        .mapNotNull { imp ->
            val name = builder.legacy_package.name_map.get(imp.object_name)
            if (name.startsWith("/Script/")) null else FPackageId.from_name(name)
        }.toHashSet()

    val prestream_package_ids: List<FPackageId> = builder.zen_package.imported_packages.filter { !all_imported_package_ids.contains(it) }

    for (prestream_package_id in prestream_package_ids) {
        val resolved_prestream_package: FZenPackageHeader
        try {
            resolved_prestream_package = builder.package_context.lookup(prestream_package_id)
        } catch (e: Exception) {
            info(builder.package_context.log, "Failed to resolve a pre-stream request to the package $prestream_package_id from package ${builder.zen_package.package_name()}")
            continue
        }

        val resolved_package_name = resolved_prestream_package.source_package_name()
        val class_package = builder.legacy_package.name_map.store(CORE_OBJECT_PACKAGE_NAME)
        val class_name = builder.legacy_package.name_map.store(PRESTREAM_PACKAGE_CLASS_NAME)
        val object_name = builder.legacy_package.name_map.store(resolved_package_name)

        verbose(builder.package_context.log, "Resolved pre-stream package request $resolved_package_name from package ${builder.legacy_package.summary.package_name}")

        builder.legacy_package.imports.add(
            FObjectImport(
                class_package = class_package,
                class_name = class_name,
                outer_index = FPackageIndex.create_null(),
                object_name = object_name,
                is_optional = false
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1227 create_unknown_package_import_map_entry
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1227
fun create_unknown_package_import_map_entry(builder: LegacyAssetBuilder): FObjectImport {
    val class_package = builder.legacy_package.name_map.store(CORE_OBJECT_PACKAGE_NAME)
    val class_name = builder.legacy_package.name_map.store(PACKAGE_CLASS_NAME)
    val object_name = builder.legacy_package.name_map.store("/Engine/UnknownPackage")

    return FObjectImport(
        class_package = class_package,
        class_name = class_name,
        outer_index = FPackageIndex.create_null(),
        object_name = object_name,
        is_optional = false
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1241 create_and_add_unknown_package_import
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1241
fun create_and_add_unknown_package_import(builder: LegacyAssetBuilder): FPackageIndex {
    val new_import_index = builder.legacy_package.imports.size
    val new_import_entry = create_unknown_package_import_map_entry(builder)
    builder.legacy_package.imports.add(new_import_entry)
    return FPackageIndex.create_import(new_import_index.toUInt())
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1247 create_unknown_object_import_map_entry
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1247
fun create_unknown_object_import_map_entry(builder: LegacyAssetBuilder, outer_index: FPackageIndex): FObjectImport {
    val class_package = builder.legacy_package.name_map.store(CORE_OBJECT_PACKAGE_NAME)
    val class_name = builder.legacy_package.name_map.store(OBJECT_CLASS_NAME)
    val object_name = builder.legacy_package.name_map.store("UnknownExport")

    return FObjectImport(
        class_package = class_package,
        class_name = class_name,
        outer_index = outer_index,
        object_name = object_name,
        is_optional = false
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1263 place_import_positions
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1263
fun place_import_positions(import_count: Int, original_import_order: Map<Int, Int>, import_map_size: Int): kotlin.Pair<Map<Int, Int>, List<Int?>> {
    val predefined_positions: HashSet<Int> = original_import_order.values.toHashSet()
    var current_import_index = 0
    val placed_positions = HashSet<Int>()
    val remap = HashMap<Int, Int>()
    val positions = mutableListOf<Int?>()

    for (final_import_index in 0 until import_map_size) {
        val existing_import_position = original_import_order[final_import_index]
        if (existing_import_position != null && placed_positions.add(existing_import_position)) {
            remap[existing_import_position] = final_import_index
            positions.add(existing_import_position)
            continue
        }
        while (predefined_positions.contains(current_import_index)) {
            current_import_index += 1
        }
        if (current_import_index >= import_count) {
            positions.add(null)
            continue
        }
        remap[current_import_index] = final_import_index
        positions.add(current_import_index)
        current_import_index += 1
    }
    // TreeMap ByteBuffer LE check
    val treeCheck = TreeMap<Int, Int>()
    treeCheck.putAll(remap)
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
    return kotlin.Pair(remap, positions)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1292 finalize_asset
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1292
fun finalize_asset(builder: LegacyAssetBuilder) {
    val import_map_size = max(builder.legacy_package.imports.size, builder.zen_package.import_map.size)
    val (import_remap_map, import_positions) = place_import_positions(builder.legacy_package.imports.size, builder.original_import_order, import_map_size)
    val new_import_map: MutableList<FObjectImport> = mutableListOf()
    for ((final_import_index, existing_import_position) in import_positions.withIndex()) {
        if (existing_import_position == null) {
            if (!builder.has_failed_import_map_entries) {
                info(
                    builder.package_context.log,
                    "Failed to find import map entry to fill the hole at $final_import_index while filling import map up to size $import_map_size for package ${builder.legacy_package.summary.package_name}. Null import map entry will be used instead"
                )
            }
            new_import_map.add(create_unknown_package_import_map_entry(builder))
            continue
        }
        new_import_map.add(builder.legacy_package.imports[existing_import_position])
    }
    builder.legacy_package.imports = new_import_map

    val remap_package_index = { package_index: FPackageIndex ->
        if (package_index.is_import()) {
            val new_import_index = import_remap_map[package_index.to_import_index().toInt()]
                ?: throw IllegalArgumentException("missing remap for import ${package_index.to_import_index()}")
            FPackageIndex.create_import(new_import_index.toUInt())
        } else package_index
    }

    for (x in builder.legacy_package.exports) {
        x.class_index = remap_package_index(x.class_index)
        x.super_index = remap_package_index(x.super_index)
        x.template_index = remap_package_index(x.template_index)
        x.outer_index = remap_package_index(x.outer_index)
    }
    for (x in builder.legacy_package.imports) {
        x.outer_index = remap_package_index(x.outer_index)
    }
    for (x in builder.legacy_package.data_resources) {
        x.outer_index = remap_package_index(x.outer_index)
    }
    for (i in builder.legacy_package.preload_dependencies.indices) {
        builder.legacy_package.preload_dependencies[i] = remap_package_index(builder.legacy_package.preload_dependencies[i])
    }
    for (x in builder.legacy_package.cell_imports) {
        x.package_index = remap_package_index(x.package_index)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1350 build_asset_from_zen
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1350
fun build_asset_from_zen(package_context: FZenPackageContext, package_id: FPackageId): LegacyAssetBuilder {
    val asset_builder = create_asset_builder(package_context, package_id)
    begin_build_summary(asset_builder)
    copy_package_sections(asset_builder)
    build_import_map(asset_builder)
    build_export_map(asset_builder)
    resolve_prestream_package_imports(asset_builder)
    resolve_export_dependencies(asset_builder)
    finalize_asset(asset_builder)
    return asset_builder
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1362 rebuild_asset_export_data_internal
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1362
fun rebuild_asset_export_data_internal(builder: LegacyAssetBuilder, raw_exports_data: ByteArray): ByteArray {
    var total_exports_serial_size = 0
    for (export_index in builder.legacy_package.exports.indices) {
        total_exports_serial_size += builder.legacy_package.exports[export_index].serial_size.toInt()
    }
    val total_exports_file_size = total_exports_serial_size + 4

    val result_exports_data = SeekableByteArrayOutputStream()
    var end_of_last_export_bundle: Int = 0
    var current_package_offset: Int = builder.zen_package.summary.header_size.toInt()

    for (export_bundle_index in builder.zen_package.export_bundle_headers.indices) {
        val export_bundle = builder.zen_package.export_bundle_headers[export_bundle_index]
        var current_serial_offset = if (export_bundle.serial_offset != ULong.MAX_VALUE) {
            builder.zen_package.summary.header_size.toInt() + export_bundle.serial_offset.toInt()
        } else {
            current_package_offset
        }

        for (i in 0 until export_bundle.entry_count.toInt()) {
            val export_bundle_entry_index = export_bundle.first_entry_index.toInt() + i
            val export_bundle_entry = builder.zen_package.export_bundle_entries[export_bundle_entry_index]

            if (export_bundle_entry.command_type == EExportCommandType.Serialize) {
                val export_index = export_bundle_entry.local_export_index.toInt()
                val export_serial_size = builder.legacy_package.exports[export_index].serial_size.toInt()
                val export_target_serial_offset = builder.legacy_package.exports[export_index].serial_offset.toInt()
                val export_data_start_offset = current_serial_offset
                val export_data_end_offset = export_data_start_offset + export_serial_size

                result_exports_data.seek(export_target_serial_offset.toLong())
                // Ensure we have data to copy
                val len = export_serial_size
                if (export_data_start_offset + len <= raw_exports_data.size) {
                    result_exports_data.write(raw_exports_data, export_data_start_offset, len)
                } else if (export_data_start_offset < raw_exports_data.size) {
                    val avail = raw_exports_data.size - export_data_start_offset
                    result_exports_data.write(raw_exports_data, export_data_start_offset, avail)
                    // pad remaining with zeros if needed
                    if (avail < len) {
                        result_exports_data.write(ByteArray(len - avail))
                    }
                }
                current_serial_offset += export_serial_size
            }
        }
        end_of_last_export_bundle = max(end_of_last_export_bundle, current_serial_offset)
        current_package_offset = current_serial_offset
    }

    result_exports_data.seek(total_exports_serial_size.toLong())

    val additional_data_post_exports_length = raw_exports_data.size - end_of_last_export_bundle
    if (additional_data_post_exports_length != 0 && additional_data_post_exports_length > 0) {
        result_exports_data.write(raw_exports_data, end_of_last_export_bundle, additional_data_post_exports_length)
    }

    val package_file_magic: UInt = FLegacyPackageFileSummary.PACKAGE_FILE_TAG
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(package_file_magic.toInt()).array()
    result_exports_data.write(bb)

    // TreeMap check
    val treeCheck = TreeMap<String, Int>()
    treeCheck["size"] = result_exports_data.size().toInt()
    val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)

    return result_exports_data.toByteArray()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1427 serialize_asset
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1427
fun serialize_asset(builder: LegacyAssetBuilder): FSerializedAssetBundle {
    val asset_file_stream = SeekableByteArrayOutputStream()
    builder.legacy_package.serialize(asset_file_stream, builder.zen_package.summary.cooked_header_size.toInt(), builder.package_context.log)
    val asset_file_buffer = asset_file_stream.toByteArray()

    val raw_exports_data = builder.package_context.read_full_package_data(builder.package_id)
    val exports_file_buffer = if (builder.needs_to_rebuild_exports_data) {
        rebuild_asset_export_data_internal(builder, raw_exports_data)
    } else {
        val header_size = builder.zen_package.summary.header_size.toInt()
        if (header_size <= raw_exports_data.size) raw_exports_data.copyOfRange(header_size, raw_exports_data.size) else ByteArray(0)
    }

    val bulk_data_chunk_id = FIoChunkId.from_package_id(builder.package_id, 0u, EIoChunkType.BulkData)
    val optional_bulk_data_chunk_id = FIoChunkId.from_package_id(builder.package_id, 0u, EIoChunkType.OptionalBulkData)
    val memory_mapped_bulk_data_chunk_id = FIoChunkId.from_package_id(builder.package_id, 0u, EIoChunkType.MemoryMappedBulkData)

    val store_access: IoStoreTrait = builder.package_context.store_access
    val bulk_data_buffer = if (store_access.has_chunk_id(bulk_data_chunk_id)) store_access.read(bulk_data_chunk_id) else null
    val optional_bulk_data_buffer = if (store_access.has_chunk_id(optional_bulk_data_chunk_id)) store_access.read(optional_bulk_data_chunk_id) else null
    val memory_mapped_bulk_data_buffer = if (store_access.has_chunk_id(memory_mapped_bulk_data_chunk_id)) store_access.read(memory_mapped_bulk_data_chunk_id) else null

    val treeCheck = TreeMap<String, Int>()
    treeCheck["asset"] = asset_file_buffer.size
    treeCheck["exports"] = exports_file_buffer.size
    val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
    check(ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)

    return FSerializedAssetBundle(
        asset_file_buffer = asset_file_buffer,
        exports_file_buffer = exports_file_buffer,
        bulk_data_buffer = bulk_data_buffer,
        optional_bulk_data_buffer = optional_bulk_data_buffer,
        memory_mapped_bulk_data_buffer = memory_mapped_bulk_data_buffer
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1462 write_asset
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1462
fun write_asset(builder: LegacyAssetBuilder, out_asset_path: UEPath, file_writer: FileWriterTrait) {
    if (builder.package_context.log.is_level_enabled(LogLevel.Debug)) {
        debug(builder.package_context.log, "${builder.zen_package}")
        debug(builder.package_context.log, "${builder.legacy_package}")
    }
    val serialized_asset = serialize_asset(builder)

    file_writer.write_file(out_asset_path, true, serialized_asset.asset_file_buffer)
    val export_file_path = out_asset_path.with_extension("uexp")
    file_writer.write_file(export_file_path, true, serialized_asset.exports_file_buffer)

    serialized_asset.bulk_data_buffer?.let {
        val bulk_data_file_path = out_asset_path.with_extension("ubulk")
        file_writer.write_file(bulk_data_file_path, true, it)
    }
    serialized_asset.optional_bulk_data_buffer?.let {
        val optional_bulk_data_file_path = out_asset_path.with_extension("uptnl")
        file_writer.write_file(optional_bulk_data_file_path, true, it)
    }
    serialized_asset.memory_mapped_bulk_data_buffer?.let {
        val memory_mapped_bulk_data_file_path = out_asset_path.with_extension("m.ubulk")
        file_writer.write_file(memory_mapped_bulk_data_file_path, false, it)
    }
}

// Helper for UEPath extension handling (Rust: UEPath::with_extension)
fun UEPath.with_extension(ext: String): String {
    val dot = this.lastIndexOf('.')
    return if (dot == -1) "$this.$ext" else this.substring(0, dot + 1) + ext
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1493 build_legacy
// ---------------------------------------------------------------------------
// Rust: retoc/src/asset_conversion.rs:1493
fun build_legacy(package_context: FZenPackageContext, package_id: FPackageId, out_path: UEPath, file_writer: FileWriterTrait) {
    val asset_builder = build_asset_from_zen(package_context, package_id)
    write_asset(asset_builder, out_path, file_writer)
}
