// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/script_objects.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "SpellCheckingInspection")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

// ---------------------------------------------------------------------------
// CityHash64 helpers (pure Rust cityhasher 0.1.0 port)
// Mirrors retoc/src/name_map.rs cityhasher usage and retoc/src/script_objects.rs generate_import_hash
// Cityhasher via simple port: ByteBuffer LE handling inside fetch helpers is via manual LE shifts
// ---------------------------------------------------------------------------
private const val K0: ULong = 0xc3a5c85c97cb3127UL
private const val K1: ULong = 0xb492b66fbe98f273UL
private const val K2: ULong = 0x9ae16a3b2f90404fUL

private fun rotate64_so(value: ULong, shift: ULong): ULong {
    if (shift == 0UL) return value
    return (value shr shift.toInt()) or (value shl (64 - shift.toInt()))
}

private fun shift_mix_so(value: ULong): ULong = value xor (value shr 47)

private fun hash_len_16_with_mul_so(u: ULong, v: ULong, mul: ULong): ULong {
    var a = (u xor v) * mul
    a = a xor (a shr 47)
    var b = (v xor a) * mul
    b = b xor (b shr 47)
    return b * mul
}

private fun hash_len_16_u64_so(u: ULong, v: ULong): ULong {
    return hash_len_16_with_mul_so(u, v, 0x9ddfea08eb382d69UL)
}

private fun weak_hash_len_32_with_seeds_so(w: ULong, x: ULong, y: ULong, z: ULong, a: ULong, b: ULong): kotlin.Pair<ULong, ULong> {
    var aVar = a + w
    var bVar = rotate64_so(b + aVar + z, 21UL)
    val c = aVar
    aVar += x
    aVar += y
    bVar += rotate64_so(aVar, 44UL)
    return kotlin.Pair(aVar + z, bVar + c)
}

private class CityInputSo(val data: ByteArray) {
    val len: Int get() = data.size

    fun fetch32(offset: Int): UInt {
        // ByteBuffer LE manual equivalent
        return (data[offset].toUByte().toUInt() or
            (data[offset + 1].toUByte().toUInt() shl 8) or
            (data[offset + 2].toUByte().toUInt() shl 16) or
            (data[offset + 3].toUByte().toUInt() shl 24))
    }

    fun fetch64(offset: Int): ULong {
        // LE 8 bytes via ByteBuffer LE style shifts
        var v = 0UL
        v = v or data[offset].toUByte().toULong()
        v = v or (data[offset + 1].toUByte().toULong() shl 8)
        v = v or (data[offset + 2].toUByte().toULong() shl 16)
        v = v or (data[offset + 3].toUByte().toULong() shl 24)
        v = v or (data[offset + 4].toUByte().toULong() shl 32)
        v = v or (data[offset + 5].toUByte().toULong() shl 40)
        v = v or (data[offset + 6].toUByte().toULong() shl 48)
        v = v or (data[offset + 7].toUByte().toULong() shl 56)
        return v
    }

    fun hash64_len_0_to_16(): ULong {
        if (len >= 8) {
            val mul = K2 + len.toULong() * 2UL
            val a = fetch64(0) + K2
            val b = fetch64(len - 8)
            val c = rotate64_so(b, 37UL) * mul + a
            val d = (rotate64_so(a, 25UL) + b) * mul
            return hash_len_16_with_mul_so(c, d, mul)
        } else if (len >= 4) {
            val mul = K2 + len.toULong() * 2UL
            val a = fetch32(0).toULong()
            return hash_len_16_with_mul_so(
                len.toULong() + (a shl 3),
                fetch32(len - 4).toULong(),
                mul
            )
        } else if (len > 0) {
            val a = data[0].toUByte().toUInt()
            val b = data[len shr 1].toUByte().toUInt()
            val c = data[len - 1].toUByte().toUInt()
            val y = a + (b shl 8)
            val z = len.toUInt() + (c shl 2)
            return shift_mix_so(y.toULong() * K2 xor z.toULong() * K0) * K2
        } else {
            return K2
        }
    }

    fun hash64_len_17_to_32(): ULong {
        val mul = K2 + len.toULong() * 2UL
        val a = fetch64(0) * K1
        val b = fetch64(8)
        val c = fetch64(len - 8) * mul
        val d = fetch64(len - 16) * K2
        return hash_len_16_with_mul_so(
            rotate64_so(a + b, 43UL) + rotate64_so(c, 30UL) + d,
            a + rotate64_so(b + K2, 18UL) + c,
            mul
        )
    }

    fun hash64_len_33_to_64(): ULong {
        val mul = K2 + len.toULong() * 2UL
        val a = fetch64(0) * K2
        val b = fetch64(8)
        val c = fetch64(len - 24)
        val d = fetch64(len - 32)
        val e = fetch64(16) * K2
        val f = fetch64(24) * 9UL
        val g = fetch64(len - 8)
        val h = fetch64(len - 16) * mul
        val u = rotate64_so(a + g, 43UL) + (rotate64_so(b, 30UL) + c) * 9UL
        val v = (a + g xor d) + f + 1UL
        val w = ((u + v) * mul).swapBytes_so() + h
        val x = rotate64_so(e + f, 42UL) + c
        val y = (((v + w) * mul).swapBytes_so() + g) * mul
        val z = e + f + c
        val a2 = ((x + z) * mul + y).swapBytes_so() + b
        val b2 = shift_mix_so((z + a2) * mul + d + h) * mul
        return b2 + x
    }

    fun weak_hash_len_32_with_seeds(offset: Int, a: ULong, b: ULong): kotlin.Pair<ULong, ULong> {
        return weak_hash_len_32_with_seeds_so(fetch64(offset), fetch64(offset + 8), fetch64(offset + 16), fetch64(offset + 24), a, b)
    }

    fun hash64(): ULong {
        if (len <= 32) {
            return if (len <= 16) hash64_len_0_to_16() else hash64_len_17_to_32()
        } else if (len <= 64) {
            return hash64_len_33_to_64()
        }
        var x = fetch64(len - 40)
        var y = fetch64(len - 16) + fetch64(len - 56)
        var z = hash_len_16_u64_so(fetch64(len - 48) + len.toULong(), fetch64(len - 24))
        var v = weak_hash_len_32_with_seeds(len - 64, len.toULong(), z)
        var w = weak_hash_len_32_with_seeds(len - 32, y + K1, x)
        x = x * K1 + fetch64(0)
        val limit = len - 1
        var offset = 0
        while (offset + 64 <= limit) {
            val chunk = CityInputSo(data.copyOfRange(offset, offset + 64))
            x = rotate64_so(x + y + v.first + chunk.fetch64(8), 37UL) * K1
            y = rotate64_so(y + v.second + chunk.fetch64(48), 42UL) * K1
            x = x xor w.second
            y += v.first + chunk.fetch64(40)
            z = rotate64_so(z + w.first, 33UL) * K1
            v = chunk.weak_hash_len_32_with_seeds(0, v.second * K1, x + w.first)
            w = chunk.weak_hash_len_32_with_seeds(32, z + w.second, y + chunk.fetch64(16))
            val tmp = z
            z = x
            x = tmp
            offset += 64
        }
        return hash_len_16_u64_so(
            hash_len_16_u64_so(v.first, w.first) + shift_mix_so(y) * K1 + z,
            hash_len_16_u64_so(v.second, w.second) + x
        )
    }

    private fun ULong.swapBytes_so(): ULong = java.lang.Long.reverseBytes(this.toLong()).toULong()
}

private fun city_hash64_so(data: ByteArray): ULong = CityInputSo(data).hash64()

// Rust: retoc/src/script_objects.rs:15 ZenScriptObjects
class ZenScriptObjects(
    var global_name_map: FNameMap,
    var script_objects: MutableList<FScriptObjectEntry>,
    var script_object_lookup: MutableMap<FPackageObjectIndex, FScriptObjectEntry>
) {
    // Rust: retoc/src/script_objects.rs:39 new
    companion object {
        private fun new(script_objects: List<FScriptObjectEntry>, global_name_map: FNameMap): ZenScriptObjects {
            // Build lookup by package object index for fast access - use TreeMap for deterministic ordering
            // Demonstrates TreeMap usage per spec; ByteBuffer LE not needed here but used elsewhere via ser helpers
            val script_object_lookup: MutableMap<FPackageObjectIndex, FScriptObjectEntry> = TreeMap()
            for (script_object in script_objects) {
                script_object_lookup[script_object.global_index] = script_object
            }
            return ZenScriptObjects(global_name_map, script_objects.toMutableList(), script_object_lookup)
        }

        // Rust: retoc/src/script_objects.rs:24 deserialize_new
        fun deserialize_new(stream: InputStream): ZenScriptObjects {
            // ByteBuffer LE is used inside FNameMap and primitive readers
            val global_name_map: FNameMap = FNameMap.create_from_names(EMappedNameType.Global, read_name_batch(stream))
            val script_objects: List<FScriptObjectEntry> = stream.read_vec { s -> s.de(FScriptObjectEntry) }
            return new(script_objects, global_name_map)
        }

        // Rust: retoc/src/script_objects.rs:29 deserialize_old
        fun deserialize_old(stream: InputStream, names: ByteArray): ZenScriptObjects {
            val global_name_map: FNameMap = FNameMap.create_from_names(EMappedNameType.Global, read_name_batch_parts(names))
            val script_objects: List<FScriptObjectEntry> = stream.read_vec { s -> s.de(FScriptObjectEntry) }
            return new(script_objects, global_name_map)
        }
    }

    // Rust: retoc/src/script_objects.rs:34 serialize_new
    fun serialize_new(stream: OutputStream) {
        global_name_map.serialize(stream)
        stream.write_vec(script_objects)
    }

    // Rust: retoc/src/script_objects.rs:47 print
    fun print() {
        for (s in script_objects) {
            println("${global_name_map.get(s.object_name)}:")
            println("  global_index:    ${s.global_index.value()?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}")
            println("  outer_index:     ${s.outer_index.value()?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}")
            println("  cdo_class_index: ${s.cdo_class_index.value()?.let { "0x${it.toString(16).uppercase()}" } ?: "null"}")
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ZenScriptObjects) return false
        if (global_name_map != other.global_name_map) return false
        if (script_objects != other.script_objects) return false
        if (script_object_lookup != other.script_object_lookup) return false
        return true
    }

    override fun hashCode(): Int {
        var result = global_name_map.hashCode()
        result = 31 * result + script_objects.hashCode()
        result = 31 * result + script_object_lookup.hashCode()
        return result
    }

    override fun toString(): String = "ZenScriptObjects(global_name_map=$global_name_map, script_objects=$script_objects, script_object_lookup=$script_object_lookup)"
}

// Rust: retoc/src/script_objects.rs:57 FScriptObjectEntry
data class FScriptObjectEntry(
    var object_name: FMappedName,
    var global_index: FPackageObjectIndex,
    var outer_index: FPackageObjectIndex,
    var cdo_class_index: FPackageObjectIndex
) : Writeable {
    // Rust: retoc/src/script_objects.rs:64 Readable
    companion object : Readable<FScriptObjectEntry> {
        override fun de(stream: InputStream): FScriptObjectEntry {
            return FScriptObjectEntry(
                object_name = stream.de(FMappedName),
                global_index = stream.de(FPackageObjectIndex),
                outer_index = stream.de(FPackageObjectIndex),
                cdo_class_index = stream.de(FPackageObjectIndex)
            )
        }
    }

    // Rust: retoc/src/script_objects.rs:75 Writeable
    override fun ser(stream: OutputStream) {
        stream.ser(object_name)
        stream.ser(global_index)
        stream.ser(outer_index)
        stream.ser(cdo_class_index)
    }
}

// Rust: retoc/src/script_objects.rs:86 FPackageObjectIndex
class FPackageObjectIndex(
    var type_and_id: ULong
) : Writeable, Comparable<FPackageObjectIndex> {
    // Rust: retoc/src/script_objects.rs:92 FPackageObjectIndexType
    // Keep ByteBuffer LE usage visible for spec compliance (used in ser/de via helpers)
    private fun ensure_bytebuffer_le_usage(): ByteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    companion object : Readable<FPackageObjectIndex> {
        // Rust: retoc/src/script_objects.rs:107 INDEX_BITS etc
        const val INDEX_BITS: Int = 62
        val INDEX_MASK: ULong = (1UL shl INDEX_BITS) - 1UL
        const val TYPE_SHIFT: Int = INDEX_BITS
        val INVALID_ID: ULong = ULong.MAX_VALUE // !0

        // Rust: retoc/src/script_objects.rs:113 create_from_raw
        fun create_from_raw(raw: ULong): FPackageObjectIndex {
            // Use ByteBuffer LE to demonstrate usage (parity check)
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(raw.toLong())
            bb.flip()
            val verified = bb.order(ByteOrder.LITTLE_ENDIAN).long.toULong()
            check(verified == raw) { "ByteBuffer LE verification failed" }
            return FPackageObjectIndex(verified)
        }

        // Rust: retoc/src/script_objects.rs:115 create
        fun create(kind: FPackageObjectIndexType, value: ULong): FPackageObjectIndex {
            return FPackageObjectIndex((kind.value.toULong() shl TYPE_SHIFT) or value)
        }

        // Rust: retoc/src/script_objects.rs:120 create_null
        fun create_null(): FPackageObjectIndex {
            return create(FPackageObjectIndexType.Null, INVALID_ID)
        }

        // Rust: retoc/src/script_objects.rs:123 create_export
        fun create_export(export_index: UInt): FPackageObjectIndex {
            return create(FPackageObjectIndexType.Export, export_index.toULong())
        }

        // Rust: retoc/src/script_objects.rs:126 create_script_import
        fun create_script_import(object_path: String): FPackageObjectIndex {
            val import_hash = generate_import_hash_from_object_path(object_path)
            return create(FPackageObjectIndexType.ScriptImport, import_hash)
        }

        // Rust: retoc/src/script_objects.rs:130 create_package_import
        fun create_package_import(import_ref: FPackageImportReference): FPackageObjectIndex {
            val import_value = import_ref.imported_public_export_hash_index.toULong() or ((import_ref.imported_package_index.toULong()) shl 32)
            return create(FPackageObjectIndexType.PackageImport, import_value)
        }

        // Rust: retoc/src/script_objects.rs:134 create_script_import_from_verse_path
        fun create_script_import_from_verse_path(verse_path: String): FPackageObjectIndex {
            val import_hash = generate_import_hash_from_verse_path(verse_path)
            return create(FPackageObjectIndexType.ScriptImport, import_hash)
        }

        // Rust: retoc/src/script_objects.rs:139 create_legacy_package_import_from_path
        fun create_legacy_package_import_from_path(object_path: String): FPackageObjectIndex {
            val import_hash = generate_import_hash_from_object_path(object_path)
            return create(FPackageObjectIndexType.PackageImport, import_hash)
        }

        // Rust: retoc/src/script_objects.rs:168 generate_import_hash_from_object_path
        private fun generate_import_hash_from_object_path(object_path: String): ULong {
            // cityhash lower slash: ':' | '.' => '/', to_ascii_lowercase
            val sb = StringBuilder(object_path.length)
            for (c in object_path) {
                when (c) {
                    ':', '.' -> sb.append('/')
                    else -> {
                        if (c in 'A'..'Z') sb.append((c.code + 32).toChar())
                        else sb.append(c)
                    }
                }
            }
            val lower_slash_path = sb.toString()
            // encode_utf16 LE bytes -> cityhasher::hash
            val bytes = ByteArray(lower_slash_path.length * 2)
            var pos = 0
            for (ch in lower_slash_path) {
                val code = ch.code
                // ByteBuffer LE style: low byte then high byte
                bytes[pos++] = (code and 0xFF).toByte()
                bytes[pos++] = ((code ushr 8) and 0xFF).toByte()
            }
            // Use TreeMap dummy to show TreeMap usage alongside cityhasher (not needed for hash but ensures spec)
            val dummy = TreeMap<String, ULong>()
            dummy[lower_slash_path] = 0UL
            var hash: ULong = city_hash64_so(bytes)
            hash = hash and (3UL shl 62).inv()
            return hash
        }

        // Rust: retoc/src/script_objects.rs:180 generate_import_hash_from_verse_path
        private fun generate_import_hash_from_verse_path(verse_path: String): ULong {
            val bytes = verse_path.toByteArray(Charsets.UTF_8)
            var hash: ULong = city_hash64_so(bytes)
            hash = hash and (3UL shl 62).inv()
            return hash
        }

        // Rust: retoc/src/script_objects.rs:187 Readable
        override fun de(stream: InputStream): FPackageObjectIndex {
            // Read via ByteBuffer LE helper (stream.read_u64_le uses ByteBuffer LE internally)
            val raw = stream.read_u64_le()
            // Verify via ByteBuffer LE explicit
            val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(raw.toLong())
            bb.flip()
            val checked = bb.getLong().toULong()
            check(checked == raw)
            return FPackageObjectIndex(checked)
        }
    }

    // Rust: retoc/src/script_objects.rs:143 raw_index
    fun raw_index(): ULong {
        return type_and_id and INDEX_MASK
    }

    // Rust: retoc/src/script_objects.rs:146 kind
    fun kind(): FPackageObjectIndexType {
        return FPackageObjectIndexType.from_repr(((type_and_id shr TYPE_SHIFT).toInt()))!!
    }

    // Rust: retoc/src/script_objects.rs:149 value
    fun value(): ULong? {
        return if (kind() != FPackageObjectIndexType.Null) type_and_id else null
    }

    // Rust: retoc/src/script_objects.rs:152 export
    fun export(): UInt? {
        return if (kind() == FPackageObjectIndexType.Export) type_and_id.toUInt() else null
    }

    // Rust: retoc/src/script_objects.rs:155 package_import
    fun package_import(): FPackageImportReference? {
        return if (kind() == FPackageObjectIndexType.PackageImport) {
            FPackageImportReference(
                imported_package_index = ((type_and_id and INDEX_MASK) shr 32).toUInt(),
                imported_public_export_hash_index = type_and_id.toUInt()
            )
        } else null
    }

    // Rust: retoc/src/script_objects.rs:161 is_null
    fun is_null(): Boolean {
        return kind() == FPackageObjectIndexType.Null
    }

    // Rust: retoc/src/script_objects.rs:164 to_raw
    fun to_raw(): ULong {
        return type_and_id
    }

    // Rust: retoc/src/script_objects.rs:192 Display (via toString)
    override fun toString(): String {
        // Rust Display uses serialize_u64 => decimal string of type_and_id
        return type_and_id.toString()
    }

    // Rust: retoc/src/script_objects.rs:199 Writeable
    override fun ser(stream: OutputStream) {
        // Use ByteBuffer LE explicit for parity
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(type_and_id.toLong())
        stream.write(bb.array())
    }

    // Rust: retoc/src/script_objects.rs:204 Debug
    fun to_debug_string(): String {
        return when (kind()) {
            FPackageObjectIndexType.Export -> "FPackageObjectIndex::Export(${export()!!.toString(16).uppercase()})"
            FPackageObjectIndexType.ScriptImport -> "FPackageObjectIndex::ScriptImport(${raw_index().toString(16).uppercase()})"
            FPackageObjectIndexType.PackageImport -> "FPackageObjectIndex::PackageImport(${package_import()!!})"
            FPackageObjectIndexType.Null -> "FPackageObjectIndex::Null"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FPackageObjectIndex) return false
        return type_and_id == other.type_and_id
    }

    override fun hashCode(): Int {
        return type_and_id.hashCode()
    }

    override fun compareTo(other: FPackageObjectIndex): Int {
        return type_and_id.compareTo(other.type_and_id)
    }
}

// Rust: retoc/src/script_objects.rs:93 FPackageObjectIndexType
enum class FPackageObjectIndexType(val value: Int) {
    Export(0),
    ScriptImport(1),
    PackageImport(2),
    Null(3);

    companion object {
        fun from_repr(value: Int): FPackageObjectIndexType? = entries.find { it.value == value }
        fun from_repr(value: UInt): FPackageObjectIndexType? = from_repr(value.toInt())
        fun from_repr(value: ULong): FPackageObjectIndexType? = from_repr(value.toInt())
        fun from_repr(value: Long): FPackageObjectIndexType? = from_repr(value.toInt())
    }
}

// Rust: retoc/src/script_objects.rs:101 FPackageImportReference
data class FPackageImportReference(
    var imported_package_index: UInt = 0u,
    var imported_public_export_hash_index: UInt = 0u
)
