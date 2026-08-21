// Rust: retoc/src/ser.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "RedundantVisibilityModifier", "TooManyFunctions", "unused")

package com.github.jpabscale.zenpak4j.retoc

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// Rust: retoc/src/ser.rs:8 Readable
interface Readable<T> {
    fun de(stream: InputStream): T
    fun de_vec(len: Int, stream: InputStream): List<T> =
        read_array(len, stream) { de(it) }
    fun de_array(stream: InputStream, n: Int): List<T> {
        val buf = ArrayList<T>(n)
        for (i in 0 until n) {
            buf.add(de(stream))
        }
        return buf
    }
}

// Rust: retoc/src/ser.rs:29 Writeable
interface Writeable {
    fun ser(stream: OutputStream)
    // default companion helper for ser_array
    companion object {
        fun <T : Writeable> ser_array(values: List<T>, stream: OutputStream) {
            for (v in values) v.ser(stream)
        }
        fun <T : Writeable> ser_array(values: Array<T>, stream: OutputStream) {
            for (v in values) v.ser(stream)
        }
    }
}

// Rust: retoc/src/ser.rs:41 ReadableCtx
interface ReadableCtx<C, T> {
    fun de(stream: InputStream, ctx: C): T
}

// Rust: retoc/src/ser.rs:47 ReadExt
// blanket impl for InputStream -> extension functions
fun <T> InputStream.de(readable: Readable<T>): T = readable.de(this)
fun <C, T> InputStream.de_ctx(ctx: C, readable: ReadableCtx<C, T>): T = readable.de(this, ctx)

// Rust: retoc/src/ser.rs:64 WriteExt
fun OutputStream.ser(value: Writeable) = value.ser(this)
fun <T : Writeable> OutputStream.ser_no_length(values: List<T>) {
    for (v in values) v.ser(this)
}
fun <T : Writeable> OutputStream.ser_no_length(values: Array<T>) {
    for (v in values) v.ser(this)
}

// Rust: retoc/src/ser.rs:83 impl [T; N] Readable / Writeable helpers
// Fixed-size array helpers are provided via de_array / ser_array above. No direct Kotlin const-generic analogue;
// callers use List<T> with explicit length.

// ---------------------------------------------------------------------------
// Primitive LE helpers (byteorder LE)
// ---------------------------------------------------------------------------

// Rust: retoc/src/ser.rs:1 byteorder LE helpers (read_exact)
fun InputStream.read_exact(buf: ByteArray) {
    var offset = 0
    while (offset < buf.size) {
        val n = this.read(buf, offset, buf.size - offset)
        if (n == -1) throw EOFException("unexpected EOF reading ${buf.size} bytes, got $offset")
        offset += n
    }
}

// Rust: retoc/src/ser.rs:95 String (Readable)
fun InputStream.read_string(): String {
    val len = read_i32_le()
    return read_string_data(len, this)
}

fun OutputStream.write_string(value: String) {
    write_string(this, value)
}

// Rust: retoc/src/ser.rs:105 &str Writeable -> same as String
fun OutputStream.write_str(value: String) = write_string(value)

// Rust: retoc/src/ser.rs:111 Vec<T> Readable
fun <T> InputStream.read_vec(parser: (InputStream) -> T): List<T> {
    val len = read_u32_le().toInt()
    return read_array(len, this, parser)
}

// Rust: retoc/src/ser.rs:116 Vec<T> ReadableCtx<usize>
fun <T> InputStream.read_vec_ctx(len: Int, parser: (InputStream) -> T): List<T> {
    return read_array(len, this, parser)
}

// Rust: retoc/src/ser.rs:121 Vec<T> Writeable
fun <T : Writeable> OutputStream.write_vec(value: List<T>) {
    write_u32_le(value.size.toUInt())
    for (v in value) v.ser(this)
}
fun <T> OutputStream.write_vec_raw(value: List<T>, writer: (OutputStream, T) -> Unit) {
    write_u32_le(value.size.toUInt())
    for (v in value) writer(this, v)
}

// Rust: retoc/src/ser.rs:128 Readable for bool (u32 != 0)
fun InputStream.read_bool(): Boolean {
    return read_u32_le() != 0u
}

// Rust: retoc/src/ser.rs:133 Writeable for bool
fun OutputStream.write_bool(value: Boolean) {
    write_u32_le(if (value) 1u else 0u)
}

// Rust: retoc/src/ser.rs:138 Readable for u8
fun InputStream.read_u8(): UByte {
    val b = this.read()
    if (b == -1) throw EOFException("unexpected EOF reading u8")
    return b.toUByte()
}

fun InputStream.read_u8_vec(len: Int): List<UByte> {
    // optimized de_vec: read_exact into ByteArray
    val buf = ByteArray(len)
    read_exact(buf)
    return buf.map { it.toUByte() }
}

fun InputStream.read_u8_array(n: Int): List<UByte> {
    val buf = ByteArray(n)
    read_exact(buf)
    return buf.map { it.toUByte() }
}

// Rust: retoc/src/ser.rs:159 Writeable for u8
fun OutputStream.write_u8(value: UByte) {
    this.write(value.toInt())
}

fun OutputStream.write_u8_array(values: ByteArray) {
    this.write(values)
}

fun OutputStream.write_u8_list(values: List<UByte>) {
    // optimized ser_array: write_all
    val arr = ByteArray(values.size) { values[it].toByte() }
    this.write(arr)
}

// Rust: retoc/src/ser.rs:170 Readable for i8
fun InputStream.read_i8(): Byte {
    val b = this.read()
    if (b == -1) throw EOFException("unexpected EOF reading i8")
    return b.toByte()
}

// Rust: retoc/src/ser.rs:175 Writeable for i8
fun OutputStream.write_i8(value: Byte) {
    this.write(value.toInt() and 0xFF)
}

// Rust: retoc/src/ser.rs:180 Readable for u16
fun InputStream.read_u16_le(): UShort {
    val buf = ByteArray(2)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short.toUShort()
}

// Rust: retoc/src/ser.rs:185 Writeable for u16
fun OutputStream.write_u16_le(value: UShort) {
    val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:190 Readable for i16
fun InputStream.read_i16_le(): Short {
    val buf = ByteArray(2)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short
}

// Rust: retoc/src/ser.rs:195 Writeable for i16
fun OutputStream.write_i16_le(value: Short) {
    val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value)
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:200 Readable for u32
fun InputStream.read_u32_le(): UInt {
    val buf = ByteArray(4)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
}

// Rust: retoc/src/ser.rs:205 Writeable for u32
fun OutputStream.write_u32_le(value: UInt) {
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:210 Readable for i32
fun InputStream.read_i32_le(): Int {
    val buf = ByteArray(4)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
}

// Rust: retoc/src/ser.rs:215 Writeable for i32
fun OutputStream.write_i32_le(value: Int) {
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:220 Readable for u64
fun InputStream.read_u64_le(): ULong {
    val buf = ByteArray(8)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
}

// Rust: retoc/src/ser.rs:225 Writeable for u64
fun OutputStream.write_u64_le(value: ULong) {
    val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong())
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:230 Readable for i64
fun InputStream.read_i64_le(): Long {
    val buf = ByteArray(8)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
}

// Rust: retoc/src/ser.rs:235 Writeable for i64
fun OutputStream.write_i64_le(value: Long) {
    val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:240 Readable for f32
fun InputStream.read_f32_le(): Float {
    val buf = ByteArray(4)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).float
}

// Rust: retoc/src/ser.rs:245 Writeable for f32
fun OutputStream.write_f32_le(value: Float) {
    val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value)
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:250 Readable for f64
fun InputStream.read_f64_le(): Double {
    val buf = ByteArray(8)
    read_exact(buf)
    return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).double
}

// Rust: retoc/src/ser.rs:255 Writeable for f64
fun OutputStream.write_f64_le(value: Double) {
    val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value)
    this.write(bb.array())
}

// Rust: retoc/src/ser.rs:261 read_array
// tracing::instrument(skip_all) -> no-op
fun <T> read_array(len: Int, stream: InputStream, f: (InputStream) -> T): MutableList<T> {
    val array = ArrayList<T>(len)
    for (i in 0 until len) {
        array.add(f(stream))
    }
    return array
}

// Rust: retoc/src/ser.rs:273 read_string_data
// tracing::instrument(skip_all) -> no-op
fun read_string_data(len: Int, stream: InputStream): String {
    return if (len < 0) {
        val chars = read_array((-len), stream) { r -> r.read_u16_le() }
        val length = chars.indexOf(0.toUShort()).let { if (it >= 0) it else chars.size }
        val truncated = chars.subList(0, length)
        val charArray = CharArray(truncated.size) { truncated[it].toInt().toChar() }
        String(charArray)
    } else {
        val chars = ByteArray(len)
        stream.read_exact(chars)
        val length = chars.indexOf(0.toByte()).let { if (it >= 0) it else chars.size }
        // Rust: String::from_utf8_lossy(&chars[..length]).into_owned()
        // Kotlin: decode with replacement for invalid UTF-8
        val bytes = if (length == chars.size) chars else chars.copyOf(length)
        // Use UTF-8 with replacement; StandardCharsets decoder with replacement is default via String constructor
        String(bytes, StandardCharsets.UTF_8)
    }
}

// Rust: retoc/src/ser.rs:287 write_string_data
fun write_string_data(stream: OutputStream, value: String) {
    if (value.isEmpty()) {
    } else if (value.all { it.code < 0x80 }) {
        stream.write(value.toByteArray(StandardCharsets.US_ASCII))
        stream.write_u8(0u)
    } else {
        val chars: List<UShort> = value.map { it.code.toUShort() }
        for (c in chars) {
            stream.write_u16_le(c)
        }
        stream.write_u16_le(0u)
    }
}

// Rust: retoc/src/ser.rs:303 serialized_string_len
fun serialized_string_len(value: String): Int {
    return if (value.isEmpty()) {
        0
    } else if (value.all { it.code < 0x80 }) {
        value.length + 1
    } else {
        -(value.map { it.code.toUShort() }.size + 1)
    }
}

// Rust: retoc/src/ser.rs:313 write_string
@JvmName("write_string_impl")
fun write_string(stream: OutputStream, value: String) {
    if (value.isEmpty()) {
        stream.write_u32_le(0u)
    } else if (value.all { it.code < 0x80 }) {
        stream.write_u32_le(value.length.toUInt() + 1u)
        stream.write(value.toByteArray(StandardCharsets.US_ASCII))
        stream.write_u8(0u)
    } else {
        val chars: List<UShort> = value.map { it.code.toUShort() }
        stream.write_i32_le(-(chars.size + 1))
        for (c in chars) {
            stream.write_u16_le(c)
        }
        stream.write_u16_le(0u)
    }
}

// Rust: retoc/src/ser.rs:331 read_utf8_string
// tracing::instrument(skip_all) -> no-op
fun InputStream.read_utf8_string(): String {
    val len: Int = read_i32_le()
    val chars = ByteArray(len)
    read_exact(chars)
    // Rust: String::from_utf8(chars).unwrap() — strict decode, panics on invalid UTF-8
    return StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(chars))
        .toString()
}

@JvmName("read_utf8_string_impl")
fun read_utf8_string(stream: InputStream): String = stream.read_utf8_string()

// Rust: retoc/src/ser.rs:339 write_utf8_string
fun OutputStream.write_utf8_string(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val len: Int = bytes.size
    write_i32_le(len)
    this.write(bytes)
}

@JvmName("write_utf8_string_impl")
fun write_utf8_string(stream: OutputStream, value: String) = stream.write_utf8_string(value)

// Rust: retoc/src/ser.rs:346 Utf8String
class Utf8String @JvmOverloads constructor(var value: String = "") : Writeable {
    // Rust: retoc/src/ser.rs:348 Display
    override fun toString(): String = value

    // Rust: retoc/src/ser.rs:353 AsRef<str>
    fun as_ref(): String = value

    // Rust: retoc/src/ser.rs:358 Deref
    fun deref(): String = value

    // Rust: retoc/src/ser.rs:364 DerefMut via var

    // Rust: retoc/src/ser.rs:374 Writeable for Utf8String
    override fun ser(stream: OutputStream) {
        write_utf8_string(stream, value)
    }

    // Equality / hash parity with Rust derive
    override fun equals(other: Any?): Boolean = other is Utf8String && other.value == value
    override fun hashCode(): Int = value.hashCode()
    fun clone(): Utf8String = Utf8String(value)

    companion object : Readable<Utf8String> {
        // Rust: retoc/src/ser.rs:369 Readable for Utf8String
        override fun de(stream: InputStream): Utf8String {
            return Utf8String(read_utf8_string(stream))
        }
    }
}

// Convenience helpers for companion-style Readable usage as described in task:
// "Keep Readable/Writeable as interfaces with fun de(stream): T etc., but Kotlin can use companion object with fun de"
// Example usage: StringReadable.de(stream) etc.
object StringReadable : Readable<String> {
    override fun de(stream: InputStream): String = stream.read_string()
    // de_vec delegates to read_array automatically
}
object StringWriteableHelper {
    fun ser(value: String, stream: OutputStream) = stream.write_string(value)
}
object BoolReadable : Readable<Boolean> {
    override fun de(stream: InputStream): Boolean = stream.read_bool()
}
object BoolWriteable {
    fun ser(value: Boolean, stream: OutputStream) = stream.write_bool(value)
}
object U8Readable : Readable<UByte> {
    override fun de(stream: InputStream): UByte = stream.read_u8()
    override fun de_vec(len: Int, stream: InputStream): List<UByte> = stream.read_u8_vec(len)
    override fun de_array(stream: InputStream, n: Int): List<UByte> = stream.read_u8_array(n)
}
object I8Readable : Readable<Byte> {
    override fun de(stream: InputStream): Byte = stream.read_i8()
}
object U16Readable : Readable<UShort> {
    override fun de(stream: InputStream): UShort = stream.read_u16_le()
}
object I16Readable : Readable<Short> {
    override fun de(stream: InputStream): Short = stream.read_i16_le()
}
object U32Readable : Readable<UInt> {
    override fun de(stream: InputStream): UInt = stream.read_u32_le()
}
object I32Readable : Readable<Int> {
    override fun de(stream: InputStream): Int = stream.read_i32_le()
}
object U64Readable : Readable<ULong> {
    override fun de(stream: InputStream): ULong = stream.read_u64_le()
}
object I64Readable : Readable<Long> {
    override fun de(stream: InputStream): Long = stream.read_i64_le()
}
object F32Readable : Readable<Float> {
    override fun de(stream: InputStream): Float = stream.read_f32_le()
}
object F64Readable : Readable<Double> {
    override fun de(stream: InputStream): Double = stream.read_f64_le()
}

// Generic Vec helpers as objects for parity with Rust impls
object VecReadable {
    fun <T> de(stream: InputStream, parser: (InputStream) -> T): List<T> = stream.read_vec(parser)
    fun <T> de_ctx(stream: InputStream, len: Int, parser: (InputStream) -> T): List<T> = stream.read_vec_ctx(len, parser)
}
object VecWriteable {
    fun <T : Writeable> ser(values: List<T>, stream: OutputStream) = stream.write_vec(values)
}
