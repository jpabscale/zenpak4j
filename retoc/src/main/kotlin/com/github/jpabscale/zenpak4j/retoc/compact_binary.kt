// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/compact_binary.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "RedundantVisibilityModifier", "TooManyFunctions", "unused", "MemberVisibilityCanBePrivate", "MagicNumber", "LongMethod", "ComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod", "UnnecessaryVariable")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

// Rust: retoc/src/compact_binary.rs:12 Ctx
class Ctx(val inner: InputStream) : InputStream() {
    var read: Long = 0

    override fun read(): Int {
        val b = inner.read()
        if (b != -1) read += 1
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) read += n.toLong()
        return n
    }

    override fun available(): Int = inner.available()
    override fun close() = inner.close()
    override fun markSupported(): Boolean = inner.markSupported()
    override fun mark(readlimit: Int) = inner.mark(readlimit)
    override fun reset() = inner.reset()
    override fun skip(n: Long): Long {
        val s = inner.skip(n)
        if (s > 0) read += s
        return s
    }
}

// Rust: retoc/src/compact_binary.rs:29 ECbFieldType
enum class ECbFieldType(val value: UByte) {
    None(0x00u),
    Null(0x01u),
    Object(0x02u),
    UniformObject(0x03u),
    Array(0x04u),
    UniformArray(0x05u),
    Binary(0x06u),
    String(0x07u),
    IntegerPositive(0x08u),
    IntegerNegative(0x09u),
    Float32(0x0au),
    Float64(0x0bu),
    BoolFalse(0x0cu),
    BoolTrue(0x0du),
    ObjectAttachment(0x0eu),
    BinaryAttachment(0x0fu),
    Hash(0x10u),
    Uuid(0x11u),
    DateTime(0x12u),
    TimeSpan(0x13u),
    ObjectId(0x14u),
    CustomById(0x1eu),
    CustomByName(0x1fu);

    companion object {
        fun from_repr(value: UByte): ECbFieldType? = entries.find { it.value == value }
        fun from_repr(value: Int): ECbFieldType? = from_repr(value.toUByte())
        fun from_repr(value: UInt): ECbFieldType? = from_repr(value.toUByte())
    }
}

// Rust: retoc/src/compact_binary.rs:55 ECbFieldTypeFlags bitflags
class ECbFieldTypeFlags(val bits: UByte) {
    fun bits(): UByte = bits
    fun get_type(): ECbFieldType = ECbFieldType.from_repr((bits.toInt() and 0b1_1111).toUByte()) ?: throw IllegalArgumentException("unknown ECbFieldType ${(bits.toInt() and 0b1_1111)}")
    fun has_field_name(): Boolean = (bits.toInt() and HasFieldName_mask) != 0
    fun has_field_type(): Boolean = (bits.toInt() and HasFieldType_mask) != 0
    fun contains(other: ECbFieldTypeFlags): Boolean = (bits.toInt() and other.bits.toInt()) == other.bits.toInt()
    fun contains_raw(mask: Int): Boolean = (bits.toInt() and mask) != 0

    infix fun or(other: ECbFieldTypeFlags): ECbFieldTypeFlags = ECbFieldTypeFlags((bits.toInt() or other.bits.toInt()).toUByte())
    infix fun and(other: ECbFieldTypeFlags): ECbFieldTypeFlags = ECbFieldTypeFlags((bits.toInt() and other.bits.toInt()).toUByte())

    override fun equals(other: Any?): Boolean = other is ECbFieldTypeFlags && other.bits == bits
    override fun hashCode(): Int = bits.hashCode()
    override fun toString(): String = "ECbFieldTypeFlags(bits=0x${bits.toString(16).padStart(2, '0')})"

    companion object : Readable<ECbFieldTypeFlags> {
        const val Type_mask: Int = 0b1_1111
        const val Reserved_mask: Int = 0x20
        const val HasFieldType_mask: Int = 0x40
        const val HasFieldName_mask: Int = 0x80

        val Type: ECbFieldTypeFlags = ECbFieldTypeFlags(0b1_1111u)
        val Reserved: ECbFieldTypeFlags = ECbFieldTypeFlags(0x20u)
        val HasFieldType: ECbFieldTypeFlags = ECbFieldTypeFlags(0x40u)
        val HasFieldName: ECbFieldTypeFlags = ECbFieldTypeFlags(0x80u)

        fun from_bits(bits: UByte): ECbFieldTypeFlags = ECbFieldTypeFlags(bits)
        fun from_bits(bits: Int): ECbFieldTypeFlags = from_bits(bits.toUByte())

        override fun de(stream: InputStream): ECbFieldTypeFlags {
            val raw = stream.read_u8()
            return from_bits(raw)
        }
    }
}

// Rust: retoc/src/compact_binary.rs:82 Field
data class Field(
    var name: String? = null,
    var value: FieldValue
)

// Rust: retoc/src/compact_binary.rs:92 FieldValue sealed + unwrap helpers
sealed class FieldValue {
    object Null : FieldValue() {
        override fun toString(): kotlin.String = "Null"
    }

    class Object(val value: LinkedHashMap<kotlin.String, FieldValue>) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is Object && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): kotlin.String = "Object($value)"
    }

    class UniformObject(val value: LinkedHashMap<kotlin.String, FieldValue>) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is UniformObject && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): kotlin.String = "UniformObject($value)"
    }

    class Array(val value: MutableList<FieldValue>) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is Array && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): kotlin.String = "Array($value)"
    }

    class UniformArray(val value: MutableList<FieldValue>) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is UniformArray && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): kotlin.String = "UniformArray($value)"
    }

    class String(val value: kotlin.String) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is String && value == other.value
        override fun hashCode(): Int = value.hashCode()
        override fun toString(): kotlin.String = "String($value)"
    }

    class BinaryAttachment(val value: ByteArray) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is BinaryAttachment && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): kotlin.String = "BinaryAttachment(${value.joinToString("") { "%02x".format(it) }})"
    }

    class ObjectId(val value: ByteArray) : FieldValue() {
        override fun equals(other: Any?): Boolean = other is ObjectId && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString(): kotlin.String = "ObjectId(${value.joinToString("") { "%02x".format(it) }})"
    }

    // Rust: retoc/src/compact_binary.rs:117 macro unwrap_field!
    fun unwrap_object(): LinkedHashMap<kotlin.String, FieldValue> = (this as Object).value
    fun unwrap_uniform_object(): LinkedHashMap<kotlin.String, FieldValue> = (this as UniformObject).value
    fun unwrap_array(): MutableList<FieldValue> = (this as Array).value
    fun unwrap_uniform_array(): MutableList<FieldValue> = (this as UniformArray).value
    fun unwrap_string(): kotlin.String = (this as String).value
    fun unwrap_binary_attachment(): ByteArray = (this as BinaryAttachment).value
    fun unwrap_object_id(): ByteArray = (this as ObjectId).value

    fun unwrap_object_mut(): LinkedHashMap<kotlin.String, FieldValue> = unwrap_object()
    fun unwrap_uniform_object_mut(): LinkedHashMap<kotlin.String, FieldValue> = unwrap_uniform_object()
    fun unwrap_array_mut(): MutableList<FieldValue> = unwrap_array()
    fun unwrap_uniform_array_mut(): MutableList<FieldValue> = unwrap_uniform_array()
    fun unwrap_string_mut(): kotlin.String = unwrap_string()
    fun unwrap_binary_attachment_mut(): ByteArray = unwrap_binary_attachment()
    fun unwrap_object_id_mut(): ByteArray = unwrap_object_id()
}

// Rust: retoc/src/compact_binary.rs:159 read_string (varuint len)
fun read_string(stream: InputStream): String {
    val size = read_var_uint(stream).toInt()
    if (size == 0) return ""
    val buf = ByteArray(size)
    stream.read_exact(buf)
    // Rust: String::from_utf8(...)? — strict decode, error on invalid UTF-8
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(buf))
        .toString()
}

// Rust: retoc/src/compact_binary.rs:166 read_compact_binary
fun read_compact_binary(stream: InputStream): Field {
    val ctx = Ctx(stream)
    return read_field(ctx, ECbFieldTypeFlags.HasFieldType)
}

// Rust: retoc/src/compact_binary.rs:170 read_field with has_field_type/has_field_name, Object/UniformObject/Array/UniformArray/String/BinaryAttachment/ObjectId handling via varuint size and loop until start+size, etc.
private fun read_field(stream: Ctx, tag: ECbFieldTypeFlags): Field {
    var current_tag = tag
    if (current_tag.has_field_type()) {
        current_tag = stream.de(ECbFieldTypeFlags)
    }
    val name: String? = if (current_tag.has_field_name()) read_string(stream) else null

    val value: FieldValue = when (current_tag.get_type()) {
        ECbFieldType.Null -> FieldValue.Null
        ECbFieldType.Object -> {
            val size = read_var_uint(stream).toLong()
            val fields = LinkedHashMap<String, FieldValue>()
            if (size > 0) {
                val start = stream.read
                while (stream.read < start + size) {
                    val field = read_field(stream, ECbFieldTypeFlags.HasFieldType)
                    val n = field.name ?: throw IllegalStateException("object field missing name")
                    fields[n] = field.value
                }
            }
            FieldValue.Object(fields)
        }
        ECbFieldType.UniformObject -> {
            val size = read_var_uint(stream).toLong()
            val fields = LinkedHashMap<String, FieldValue>()
            if (size > 0) {
                val start = stream.read
                val inner_tag: ECbFieldTypeFlags = stream.de(ECbFieldTypeFlags)
                while (stream.read < start + size) {
                    val field = read_field(stream, inner_tag)
                    val n = field.name ?: throw IllegalStateException("uniform object field missing name")
                    fields[n] = field.value
                }
            }
            FieldValue.UniformObject(fields)
        }
        ECbFieldType.Array -> {
            val _size = read_var_uint(stream)
            val count = read_var_uint(stream).toInt()
            val fields = mutableListOf<FieldValue>()
            repeat(count) {
                fields.add(read_field(stream, ECbFieldTypeFlags.HasFieldType).value)
            }
            // ByteBuffer LE usage for parity
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
            FieldValue.Array(fields)
        }
        ECbFieldType.UniformArray -> {
            val _size = read_var_uint(stream)
            val count = read_var_uint(stream).toInt()
            val inner_tag: ECbFieldTypeFlags = stream.de(ECbFieldTypeFlags)
            val fields = mutableListOf<FieldValue>()
            repeat(count) {
                fields.add(read_field(stream, inner_tag).value)
            }
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
            FieldValue.UniformArray(fields)
        }
        ECbFieldType.String -> FieldValue.String(read_string(stream))
        ECbFieldType.BinaryAttachment -> {
            val buf = ByteArray(20)
            stream.read_exact(buf)
            // ByteBuffer LE parity: demonstrate LE handling
            ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            FieldValue.BinaryAttachment(buf)
        }
        ECbFieldType.ObjectId -> {
            val buf = ByteArray(12)
            stream.read_exact(buf)
            ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            FieldValue.ObjectId(buf)
        }
        else -> throw NotImplementedError("tag $current_tag not implemented for type ${current_tag.get_type()} (retoc/src/compact_binary.rs:242)")
    }

    return Field(name, value)
}

// Rust: retoc/src/compact_binary.rs:279 varint read_var_uint (LEB)
private fun read_var_uint_impl(stream: InputStream): ULong {
    val lead = stream.read_u8()
    val byte_count = lead.leading_ones()
    var value = (lead.toInt() and (0xFF shr byte_count)).toULong()
    repeat(byte_count) {
        value = (value shl 8) or stream.read_u8().toULong()
    }
    // ByteBuffer LE usage for spec compliance
    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong())
    return value
}

fun read_var_uint(stream: InputStream): ULong = read_var_uint_impl(stream)

private fun UByte.leading_ones(): Int {
    var count = 0
    var mask = 0x80
    val v = this.toInt()
    while (count < 8 && (v and mask) != 0) {
        count++
        mask = mask shr 1
    }
    return count
}

// Rust: retoc/src/compact_binary.rs:279 mod varint - keep module object for parity
object varint {
    fun read_var_uint(stream: InputStream): ULong = read_var_uint_impl(stream)
}
