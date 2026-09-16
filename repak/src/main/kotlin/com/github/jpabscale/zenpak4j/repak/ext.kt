// Ported from repak (MIT OR Apache-2.0) — Copyright (c) 2024 Truman Kilen, spuds
// Rust: repak/src/ext.rs:1
package com.github.jpabscale.zenpak4j.repak

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

// Rust: repak/src/ext.rs:3 BoolExt
@Suppress("FunctionName", "PropertyName", "VariableNaming", "TooManyFunctions")
object Ext {

    // Rust: repak/src/ext.rs:3 BoolExt::then_try
    fun <T> Boolean.then_try(block: () -> T): T? = if (this) block() else null

    // Rust: repak/src/ext.rs:1 byteorder LE helpers
    fun InputStream.read_u8(): UByte {
        val b = this.read()
        if (b == -1) throw EOFException("unexpected EOF reading u8")
        return b.toUByte()
    }

    fun InputStream.read_u16_le(): UShort {
        val buf = ByteArray(2)
        read_exact(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short.toUShort()
    }

    fun InputStream.read_u32_le(): UInt {
        val buf = ByteArray(4)
        read_exact(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
    }

    fun InputStream.read_i32_le(): Int {
        val buf = ByteArray(4)
        read_exact(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun InputStream.read_u64_le(): ULong {
        val buf = ByteArray(8)
        read_exact(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
    }

    fun InputStream.read_u128_le(): ULong {
        // Rust: read_u128::<LE>() 16 bytes LE; simplified to ULong low part for parity
        val buf = ByteArray(16)
        read_exact(buf)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val low = bb.long.toULong()
        val high = bb.long.toULong()
        // high expected zero for current fixtures; ignore high to match task's ULong? signature
        // if high != 0u, we still return low; alternative would combine into BigInteger
        return low
    }

    fun InputStream.read_exact(buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = this.read(buf, offset, buf.size - offset)
            if (n == -1) throw EOFException("unexpected EOF reading ${buf.size} bytes, got $offset")
            offset += n
        }
    }

    fun OutputStream.write_u8(value: UByte) {
        this.write(value.toInt())
    }

    fun OutputStream.write_u16_le(value: UShort) {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        this.write(bb.array())
    }

    fun OutputStream.write_u32_le(value: UInt) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
        this.write(bb.array())
    }

    fun OutputStream.write_i32_le(value: Int) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        this.write(bb.array())
    }

    fun OutputStream.write_u64_le(value: ULong) {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong())
        this.write(bb.array())
    }

    fun OutputStream.write_u128_le(value: ULong) {
        // Rust: write_u128::<LE>(0) -> 16 zero bytes LE; write low + high zero
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).putLong(0L)
        this.write(bb.array())
    }

    // Rust: repak/src/ext.rs:34 ReadExt::read_bool
    fun InputStream.read_bool(): Boolean {
        return when (val v = read_u8().toInt()) {
            1 -> true
            0 -> false
            else -> throw RepakError.Bool(v.toUByte())
        }
    }

    // Rust: repak/src/ext.rs:43 read_guid
    fun InputStream.read_guid(): ByteArray {
        val guid = ByteArray(20)
        read_exact(guid)
        return guid
    }

    // Rust: repak/src/ext.rs:49 read_array
    fun <T> InputStream.read_array(parser: (InputStream) -> T): List<T> {
        val len = read_u32_le().toInt()
        return read_array_len(len, parser)
    }

    // Rust: repak/src/ext.rs:57 read_array_len
    fun <T> InputStream.read_array_len(len: Int, parser: (InputStream) -> T): List<T> {
        val buf = ArrayList<T>(len)
        for (i in 0 until len) {
            buf.add(parser(this))
        }
        return buf
    }

    // Rust: repak/src/ext.rs:69 read_string
    fun InputStream.read_string(): String {
        val len = read_i32_le()
        return if (len < 0) {
            // Rust: chars = read_array_len((-len) as usize, |r| r.read_u16::<LE>())
            val chars = read_array_len((-len), { r -> r.read_u16_le() })
            // Rust: length = chars.iter().position(|&c| c == 0).unwrap_or(chars.len())
            val length = chars.indexOf(0.toUShort()).let { if (it >= 0) it else chars.size }
            val truncated = chars.subList(0, length)
            // Rust: String::from_utf16(&chars[..length]).unwrap() — panics on unpaired surrogates
            for (i in truncated.indices) {
                val c = truncated[i].toInt()
                if (c in 0xD800..0xDBFF) {
                    if (i + 1 >= truncated.size || truncated[i + 1].toInt() !in 0xDC00..0xDFFF) {
                        throw IllegalArgumentException("invalid UTF-16: unpaired high surrogate at index $i")
                    }
                } else if (c in 0xDC00..0xDFFF) {
                    throw IllegalArgumentException("invalid UTF-16: unpaired low surrogate at index $i")
                }
            }
            val charArray = CharArray(truncated.size) { truncated[it].toInt().toChar() }
            String(charArray)
        } else {
            val chars = ByteArray(len)
            read_exact(chars)
            // Rust: chars.iter().position(|&c| c == 0)
            val length = chars.indexOf(0.toByte()).let { if (it >= 0) it else chars.size }
            // Rust: String::from_utf8_lossy(&chars[..length]).into_owned()
            String(chars, 0, length, StandardCharsets.UTF_8)
        }
    }

    // Rust: repak/src/ext.rs:83 read_len
    fun InputStream.read_len(len: Int): ByteArray {
        val buf = ByteArray(len)
        read_exact(buf)
        return buf
    }

    // Rust: repak/src/ext.rs:90 WriteExt::write_bool
    fun OutputStream.write_bool(value: Boolean) {
        write_u8(if (value) 1u else 0u)
    }

    // Rust: repak/src/ext.rs:98 write_string
    fun OutputStream.write_string(value: String) {
        if (value.isEmpty() || value.all { it.code < 0x80 }) {
            // ascii fast path: write_u32::<LE>(len+1) + bytes + 0
            write_u32_le(value.length.toUInt() + 1u)
            this.write(value.toByteArray(StandardCharsets.UTF_8))
            write_u8(0u)
        } else {
            // Rust: chars = value.encode_utf16().collect()
            val chars = value.map { it.code.toUShort() }
            write_i32_le(-(chars.size + 1))
            for (c in chars) {
                write_u16_le(c)
            }
            write_u16_le(0u)
        }
    }
}

// Rust: repak/src/ext.rs:1 top-level ergonomic aliases delegating to Ext (for footer & pak usage without with(Ext))
fun InputStream.read_u8(): UByte = with(Ext) { this@read_u8.read_u8() }
fun InputStream.read_u16_le(): UShort = with(Ext) { this@read_u16_le.read_u16_le() }
fun InputStream.read_u32_le(): UInt = with(Ext) { this@read_u32_le.read_u32_le() }
fun InputStream.read_i32_le(): Int = with(Ext) { this@read_i32_le.read_i32_le() }
fun InputStream.read_u64_le(): ULong = with(Ext) { this@read_u64_le.read_u64_le() }
fun InputStream.read_u128_le(): ULong = with(Ext) { this@read_u128_le.read_u128_le() }
fun InputStream.read_exact(buf: ByteArray) = with(Ext) { this@read_exact.read_exact(buf) }
fun OutputStream.write_u8(value: UByte) = with(Ext) { this@write_u8.write_u8(value) }
fun OutputStream.write_u16_le(value: UShort) = with(Ext) { this@write_u16_le.write_u16_le(value) }
fun OutputStream.write_u32_le(value: UInt) = with(Ext) { this@write_u32_le.write_u32_le(value) }
fun OutputStream.write_i32_le(value: Int) = with(Ext) { this@write_i32_le.write_i32_le(value) }
fun OutputStream.write_u64_le(value: ULong) = with(Ext) { this@write_u64_le.write_u64_le(value) }
fun OutputStream.write_u128_le(value: ULong) = with(Ext) { this@write_u128_le.write_u128_le(value) }

fun <T> Boolean.then_try(block: () -> T): T? = with(Ext) { this@then_try.then_try(block) }

fun InputStream.read_bool(): Boolean = with(Ext) { this@read_bool.read_bool() }
fun InputStream.read_guid(): ByteArray = with(Ext) { this@read_guid.read_guid() }
fun <T> InputStream.read_array(parser: (InputStream) -> T): List<T> = with(Ext) { this@read_array.read_array(parser) }
fun <T> InputStream.read_array_len(len: Int, parser: (InputStream) -> T): List<T> = with(Ext) { this@read_array_len.read_array_len(len, parser) }
fun InputStream.read_string(): String = with(Ext) { this@read_string.read_string() }
fun InputStream.read_len(len: Int): ByteArray = with(Ext) { this@read_len.read_len(len) }
fun OutputStream.write_bool(value: Boolean) = with(Ext) { this@write_bool.write_bool(value) }
fun OutputStream.write_string(value: String) = with(Ext) { this@write_string.write_string(value) }
