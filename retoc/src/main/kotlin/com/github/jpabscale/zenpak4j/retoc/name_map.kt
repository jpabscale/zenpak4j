// Rust: retoc/src/name_map.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

// Rust: retoc/src/name_map.rs:11 FNAME_HASH_ALGORITHM_ID
const val FNAME_HASH_ALGORITHM_ID: ULong = 0xC1640000UL

// ---------------------------------------------------------------------------
// CityHash64 helpers (pure Rust cityhasher 0.1.0 port)
// Rust: cityhasher::hash
private const val K0: ULong = 0xc3a5c85c97cb3127UL
private const val K1: ULong = 0xb492b66fbe98f273UL
private const val K2: ULong = 0x9ae16a3b2f90404fUL
private const val C1_U32: UInt = 0xcc9e2d51u
private const val C2_U32: UInt = 0x1b873593u

private fun rotate32(value: UInt, shift: UInt): UInt {
    if (shift == 0u) return value
    return (value shr shift.toInt()) or (value shl (32 - shift.toInt()))
}

private fun rotate64(value: ULong, shift: ULong): ULong {
    if (shift == 0UL) return value
    return (value shr shift.toInt()) or (value shl (64 - shift.toInt()))
}

private fun shift_mix(value: ULong): ULong = value xor (value shr 47)

private fun hash_len_16_with_mul(u: ULong, v: ULong, mul: ULong): ULong {
    var a = (u xor v) * mul
    a = a xor (a shr 47)
    var b = (v xor a) * mul
    b = b xor (b shr 47)
    return b * mul
}

private fun hash_len_16_u64(u: ULong, v: ULong): ULong {
    return hash_len_16_with_mul(u, v, 0x9ddfea08eb382d69UL)
}

private fun weak_hash_len_32_with_seeds(w: ULong, x: ULong, y: ULong, z: ULong, a: ULong, b: ULong): kotlin.Pair<ULong, ULong> {
    var aVar = a + w
    var bVar = rotate64(b + aVar + z, 21UL)
    val c = aVar
    aVar += x
    aVar += y
    bVar += rotate64(aVar, 44UL)
    return kotlin.Pair(aVar + z, bVar + c)
}

private class CityInput(val data: ByteArray) {
    val len: Int get() = data.size

    fun fetch32(offset: Int): UInt {
        // LE
        return (data[offset].toUByte().toUInt() or
            (data[offset + 1].toUByte().toUInt() shl 8) or
            (data[offset + 2].toUByte().toUInt() shl 16) or
            (data[offset + 3].toUByte().toUInt() shl 24))
    }

    fun fetch64(offset: Int): ULong {
        // LE 8 bytes
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
            val c = rotate64(b, 37UL) * mul + a
            val d = (rotate64(a, 25UL) + b) * mul
            return hash_len_16_with_mul(c, d, mul)
        } else if (len >= 4) {
            val mul = K2 + len.toULong() * 2UL
            val a = fetch32(0).toULong()
            return hash_len_16_with_mul(
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
            return shift_mix(y.toULong() * K2 xor z.toULong() * K0) * K2
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
        return hash_len_16_with_mul(
            rotate64(a + b, 43UL) + rotate64(c, 30UL) + d,
            a + rotate64(b + K2, 18UL) + c,
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
        val u = rotate64(a + g, 43UL) + (rotate64(b, 30UL) + c) * 9UL
        val v = (a + g xor d) + f + 1UL
        val w = ((u + v) * mul).swapBytes() + h
        val x = rotate64(e + f, 42UL) + c
        val y = (((v + w) * mul).swapBytes() + g) * mul
        val z = e + f + c
        val a2 = ((x + z) * mul + y).swapBytes() + b
        val b2 = shift_mix((z + a2) * mul + d + h) * mul
        return b2 + x
    }

    fun weak_hash_len_32_with_seeds(offset: Int, a: ULong, b: ULong): kotlin.Pair<ULong, ULong> {
        return weak_hash_len_32_with_seeds(fetch64(offset), fetch64(offset + 8), fetch64(offset + 16), fetch64(offset + 24), a, b)
    }

    fun hash64(): ULong {
        if (len <= 32) {
            return if (len <= 16) hash64_len_0_to_16() else hash64_len_17_to_32()
        } else if (len <= 64) {
            return hash64_len_33_to_64()
        }
        var x = fetch64(len - 40)
        var y = fetch64(len - 16) + fetch64(len - 56)
        var z = hash_len_16_u64(fetch64(len - 48) + len.toULong(), fetch64(len - 24))
        var v = weak_hash_len_32_with_seeds(len - 64, len.toULong(), z)
        var w = weak_hash_len_32_with_seeds(len - 32, y + K1, x)
        x = x * K1 + fetch64(0)
        // loop over chunks [0, len-1) size 64
        val limit = len - 1
        var offset = 0
        while (offset + 64 <= limit) {
            val chunk = CityInput(data.copyOfRange(offset, offset + 64))
            x = rotate64(x + y + v.first + chunk.fetch64(8), 37UL) * K1
            y = rotate64(y + v.second + chunk.fetch64(48), 42UL) * K1
            x = x xor w.second
            y += v.first + chunk.fetch64(40)
            z = rotate64(z + w.first, 33UL) * K1
            v = chunk.weak_hash_len_32_with_seeds(0, v.second * K1, x + w.first)
            w = chunk.weak_hash_len_32_with_seeds(32, z + w.second, y + chunk.fetch64(16))
            // swap z and x
            val tmp = z
            z = x
            x = tmp
            offset += 64
        }
        return hash_len_16_u64(
            hash_len_16_u64(v.first, w.first) + shift_mix(y) * K1 + z,
            hash_len_16_u64(v.second, w.second) + x
        )
    }

    private fun ULong.swapBytes(): ULong = java.lang.Long.reverseBytes(this.toLong()).toULong()
}

// Rust: retoc/src/name_map.rs:13 name_hash
fun name_hash(name: String): ULong {
    val lower = name.to_ascii_lowercase()
    return if (lower.is_ascii()) {
        // cityhasher::hash(lower.as_bytes())
        city_hash64(lower.toByteArray(Charsets.US_ASCII))
    } else {
        // cityhasher::hash(lower.encode_utf16().flat_map(|s| s.to_le_bytes())...)
        val bytes = ByteArray(lower.length * 2)
        var pos = 0
        for (ch in lower) {
            val code = ch.code
            bytes[pos++] = (code and 0xFF).toByte()
            bytes[pos++] = ((code ushr 8) and 0xFF).toByte()
        }
        city_hash64(bytes)
    }
}

private fun city_hash64(data: ByteArray): ULong = CityInput(data).hash64()

private fun String.to_ascii_lowercase(): String {
    val sb = StringBuilder(length)
    for (ch in this) {
        if (ch in 'A'..'Z') sb.append((ch.code + 32).toChar())
        else sb.append(ch)
    }
    return sb.toString()
}

private fun String.is_ascii(): Boolean = all { it.code < 128 }

// Rust: retoc/src/name_map.rs:22 name_header
fun name_header(name: String): ByteArray {
    val lenShort: Short = if (name.is_ascii()) {
        name.length.toShort()
    } else {
        // name.encode_utf16().count() as i16 + i16::MIN
        (name.length + Short.MIN_VALUE.toInt()).toShort()
    }
    val bb = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(lenShort)
    return bb.array()
}

// Rust: retoc/src/name_map.rs:28 break_down_name_string
fun break_down_name_string(name: String): kotlin.Pair<String, Int> {
    var name_without_number: String = name
    var name_number: Int = 0
    val idx = name.lastIndexOf('_')
    if (idx >= 0) {
        val left = name.substring(0, idx)
        val right = name.substring(idx + 1)
        val parsed = right.toIntOrNull()
        if (parsed != null && parsed >= 0 && parsed.toString() == right) {
            name_without_number = left
            name_number = parsed + 1
        }
    }
    return kotlin.Pair(name_without_number, name_number)
}

// Rust: retoc/src/name_map.rs:47 read_name_batch
fun read_name_batch(stream: InputStream): List<String> {
    val num = stream.read_u32_le().toInt()
    if (num == 0) return emptyList()
    val _num_string_bytes = stream.read_u32_le()
    //@parity:on EXC-008
    val hash_version = stream.read_u64_le()
    check(hash_version == FNAME_HASH_ALGORITHM_ID) { "hash_version mismatch expected ${FNAME_HASH_ALGORITHM_ID.toString(16)} got ${hash_version.toString(16)}" }
    //@parity:off EXC-008
    val hashBytes = ByteArray(num * 8)
    stream.read_exact(hashBytes)
    val lengths = read_array(num, stream) { s ->
        val buf = ByteArray(2)
        s.read_exact(buf)
        ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).short
    }
    val names = mutableListOf<String>()
    for (l_raw in lengths) {
        val l = if (l_raw < 0) (Short.MIN_VALUE - l_raw.toInt()).toShort() else l_raw
        val lenInt = l.toInt()
        names.add(read_string_data(lenInt, stream))
    }
    return names
}

// Rust: retoc/src/name_map.rs:68 write_name_batch
fun write_name_batch(stream: OutputStream, names: List<String>) {
    fun name_byte_size(name: String): UInt = if (name.is_ascii()) name.length.toUInt() else (name.length * 2).toUInt()
    stream.write_u32_le(names.size.toUInt())
    if (names.isEmpty()) return
    val total = names.fold(0u) { acc, n -> acc + name_byte_size(n) }
    stream.write_u32_le(total)
    stream.write_u64_le(FNAME_HASH_ALGORITHM_ID)
    for (name in names) {
        stream.write_u64_le(name_hash(name))
    }
    for (name in names) {
        stream.write(name_header(name))
    }
    for (name in names) {
        if (name.is_ascii()) {
            stream.write(name.toByteArray(Charsets.US_ASCII))
        } else {
            for (c in name) {
                stream.write_u16_le(c.code.toUShort())
            }
        }
    }
}

// Rust: retoc/src/name_map.rs:102 read_name_batch_parts
fun read_name_batch_parts(names_buffer: ByteArray): List<String> {
    val names = mutableListOf<String>()
    val stream = ByteArrayInputStream(names_buffer)
    var pos = 0
    while (pos < names_buffer.size) {
        val headerBuf = ByteArray(2)
        stream.read_exact(headerBuf)
        pos += 2
        val l_raw = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN).short
        val lAdjusted = if (l_raw < 0) (Short.MIN_VALUE - l_raw.toInt()).toShort() else l_raw
        val lenInt = lAdjusted.toInt()
        // UTF16 strings aligned to 2 bytes so read one byte to reach alignment
        if (lenInt < 0 && (pos and 1) != 0) {
            val pad = stream.read()
            if (pad == -1) throw EOFException("unexpected EOF reading padding")
            pos += 1
        }
        val s = read_string_data(lenInt, stream)
        names.add(s)
        // update pos via available
        pos = names_buffer.size - stream.available()
    }
    return names
}

// Rust: retoc/src/name_map.rs:117 write_name_batch_parts
fun write_name_batch_parts(names: List<String>): kotlin.Pair<ByteArray, ByteArray> {
    val cur_names = ByteArrayOutputStream()
    val cur_hashes = ByteArrayOutputStream()
    cur_hashes.write_u64_le(FNAME_HASH_ALGORITHM_ID)
    for (name in names) {
        cur_names.write(name_header(name))
        if (name.is_ascii()) {
            cur_names.write(name.toByteArray(Charsets.US_ASCII))
        } else {
            if ((cur_names.size() and 1) != 0) {
                cur_names.write(0)
            }
            for (c in name) {
                cur_names.write_u16_le(c.code.toUShort())
            }
        }
        cur_hashes.write_u64_le(name_hash(name))
    }
    return kotlin.Pair(cur_names.toByteArray(), cur_hashes.toByteArray())
}

// Rust: retoc/src/name_map.rs:142 FNameMap
class FNameMap(
    var kind: EMappedNameType,
    var names: MutableList<String>,
    var name_lookup: MutableMap<String, Int>
) {
    // Rust: retoc/src/name_map.rs:150 deserialize
    fun deserialize(stream: InputStream, kind: EMappedNameType): FNameMap {
        return create_from_names(kind, read_name_batch(stream))
    }

    // Rust: retoc/src/name_map.rs:154 serialize
    fun serialize(stream: OutputStream) {
        write_name_batch(stream, names)
    }

    companion object {
        // Rust: retoc/src/name_map.rs:161 create
        fun create(kind: EMappedNameType): FNameMap =
            FNameMap(kind, mutableListOf(), TreeMap())

        // Rust: retoc/src/name_map.rs:164 create_from_names
        fun create_from_names(kind: EMappedNameType, names: List<String>): FNameMap {
            val map = TreeMap<String, Int>()
            for ((idx, name) in names.withIndex()) {
                map[name] = idx
            }
            return FNameMap(kind, names.toMutableList(), map)
        }

        // Top-level deserialize helper mirroring Rust impl deserialize
        fun deserialize_from(stream: InputStream, kind: EMappedNameType): FNameMap =
            create_from_names(kind, read_name_batch(stream))
    }

    // Rust: retoc/src/name_map.rs:171 get
    fun get(name: FMappedName): String {
        check(name.kind() == kind) { "Attempt to map name of the different kind in this name map Name Kind is ${name.kind()}, but name map kind is $kind" }
        val n = names[name.index().toInt()]
        return if (name.number != 0u) "${n}_${name.number - 1u}" else n
    }

    // Rust: retoc/src/name_map.rs:177 store
    fun store(name: String): FMappedName {
        val (without, number) = break_down_name_string(name)
        val existing = name_lookup[without]
        if (existing != null) {
            return FMappedName.create(existing.toUInt(), kind, number.toUInt())
        }
        val newIndex = names.size
        name_lookup[without] = newIndex
        names.add(without)
        return FMappedName.create(newIndex.toUInt(), kind, number.toUInt())
    }

    // Rust: retoc/src/name_map.rs:192 copy_raw_names
    fun copy_raw_names(): List<String> = names.toList()

    override fun equals(other: Any?): Boolean {
        if (other !is FNameMap) return false
        return kind == other.kind && names == other.names && name_lookup == other.name_lookup
    }

    override fun hashCode(): Int = kind.hashCode() * 31 + names.hashCode()

    override fun toString(): String = "FNameMap(kind=$kind, names=$names)"
}

// Rust: retoc/src/name_map.rs:198 EMappedNameType
enum class EMappedNameType(val value: UInt) {
    Package(0u),
    Container(1u),
    Global(2u);

    override fun toString(): String = name

    companion object {
        fun from_repr(value: UInt): EMappedNameType? = entries.find { it.value == value }
        fun from_repr(value: Int): EMappedNameType? = from_repr(value.toUInt())
        fun from_repr(value: ULong): EMappedNameType? = from_repr(value.toUInt())
    }
}

// Rust: retoc/src/name_map.rs:205 FMappedName
data class FMappedName(
    var index_and_type: UInt,
    var number: UInt
) : Writeable {
    companion object : Readable<FMappedName> {
        // Rust: retoc/src/name_map.rs:211 INDEX_BITS etc
        const val INDEX_BITS: Int = 30
        val INDEX_MASK: UInt = (1u shl INDEX_BITS) - 1u
        val TYPE_MASK: UInt = INDEX_MASK.inv()
        const val TYPE_SHIFT: Int = INDEX_BITS

        // Rust: retoc/src/name_map.rs:215 create
        fun create(index: UInt, kind: EMappedNameType, number: UInt): FMappedName {
            val shifted_type = kind.value shl TYPE_SHIFT
            val index_and_type: UInt = (index and INDEX_MASK) or (shifted_type and TYPE_MASK)
            return FMappedName(index_and_type, number)
        }

        // Rust: retoc/src/name_map.rs:228 Readable
        override fun de(stream: InputStream): FMappedName {
            return FMappedName(stream.read_u32_le(), stream.read_u32_le())
        }
    }

    // Rust: retoc/src/name_map.rs:220 index
    fun index(): UInt = index_and_type and INDEX_MASK

    // Rust: retoc/src/name_map.rs:223 kind
    fun kind(): EMappedNameType = EMappedNameType.from_repr((index_and_type and TYPE_MASK) shr TYPE_SHIFT)!!

    // Rust: retoc/src/name_map.rs:234 Writeable
    override fun ser(stream: OutputStream) {
        stream.write_u32_le(index_and_type)
        stream.write_u32_le(number)
    }
}
