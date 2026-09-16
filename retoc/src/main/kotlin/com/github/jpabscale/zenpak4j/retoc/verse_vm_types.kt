// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/verse_vm_types.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "EnumEntryName")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.io.OutputStream

// Rust: retoc/src/zen.rs:559 FPackageIndex moved to zen.kt for 1:1 parity
// FPackageIndex now defined in zen.kt

// Rust: retoc/src/verse_vm_types.rs:7 EVerseEncodedValueType
enum class EVerseEncodedValueType(val value: UByte) : Writeable {
    None(0u),
    Cell(1u),
    Object(2u),
    Char(3u),
    Char32(4u),
    Float(5u),
    Int(6u);

    // Rust: retoc/src/verse_vm_types.rs:18 Writeable for EVerseEncodedValueType
    override fun ser(stream: OutputStream) {
        stream.write_u8(value)
    }

    companion object : Readable<EVerseEncodedValueType> {
        fun from_repr(value: UByte): EVerseEncodedValueType? = entries.find { it.value == value }
        fun from_repr(value: Int): EVerseEncodedValueType? = from_repr(value.toUByte())
        fun from_repr(value: UInt): EVerseEncodedValueType? = from_repr(value.toUByte())

        // Rust: retoc/src/verse_vm_types.rs:24 Readable for EVerseEncodedValueType
        override fun de(stream: InputStream): EVerseEncodedValueType {
            val raw = stream.read_u8()
            return from_repr(raw) ?: throw IllegalArgumentException("Unknown encoded verse value type: $raw")
        }
    }
}

// Rust: retoc/src/verse_vm_types.rs:30 VValue
sealed class VValue : Writeable {
    // Rust: retoc/src/verse_vm_types.rs:32 VValue::None
    data object None : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.None)
        }
    }

    // Rust: retoc/src/verse_vm_types.rs:33 VValue::Cell
    data class Cell(val value: FPackageIndex) : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.Cell)
            stream.ser(value)
        }
    }

    // Rust: retoc/src/verse_vm_types.rs:34 VValue::Object
    data class Object(val value: FPackageIndex) : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.Object)
            stream.ser(value)
        }
    }

    // Rust: retoc/src/verse_vm_types.rs:35 VValue::Char
    data class Char(val value: UByte) : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.Char)
            stream.write_u8(value)
        }
    }

    // Rust: retoc/src/verse_vm_types.rs:36 VValue::Char32
    data class Char32(val value: UInt) : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.Char32)
            stream.write_u32_le(value)
        }
    }

    // Rust: retoc/src/verse_vm_types.rs:37 VValue::Float
    data class Float(val value: Double) : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.Float)
            stream.write_f64_le(value)
        }
    }

    // Rust: retoc/src/verse_vm_types.rs:38 VValue::Int
    data class Int(val value: kotlin.Int) : VValue() {
        override fun ser(stream: OutputStream) {
            stream.ser(EVerseEncodedValueType.Int)
            stream.write_i32_le(value)
        }
    }

    companion object : Readable<VValue> {
        // Rust: retoc/src/verse_vm_types.rs:80 Readable for VValue
        override fun de(stream: InputStream): VValue {
            val encoded_value_type: EVerseEncodedValueType = stream.de(EVerseEncodedValueType)
            return when (encoded_value_type) {
                EVerseEncodedValueType.None -> None
                EVerseEncodedValueType.Cell -> {
                    val cell_package_index: FPackageIndex = stream.de(FPackageIndex)
                    Cell(cell_package_index)
                }
                EVerseEncodedValueType.Object -> {
                    val object_package_index: FPackageIndex = stream.de(FPackageIndex)
                    Object(object_package_index)
                }
                EVerseEncodedValueType.Char -> {
                    val char_value: UByte = stream.read_u8()
                    Char(char_value)
                }
                EVerseEncodedValueType.Char32 -> {
                    val char32_value: UInt = stream.read_u32_le()
                    Char32(char32_value)
                }
                EVerseEncodedValueType.Float -> {
                    val float_value: Double = stream.read_f64_le()
                    Float(float_value)
                }
                EVerseEncodedValueType.Int -> {
                    val int_value: kotlin.Int = stream.read_i32_le()
                    Int(int_value)
                }
            }
        }
    }
}

// Rust: retoc/src/verse_vm_types.rs:113 VNameValueMapEntry
data class VNameValueMapEntry(
    var name: String,
    var value: VValue
) : Writeable {
    // Rust: retoc/src/verse_vm_types.rs:118 Writeable for VNameValueMapEntry
    override fun ser(stream: OutputStream) {
        stream.write_utf8_string(name)
        stream.ser(value)
    }

    companion object : Readable<VNameValueMapEntry> {
        // Rust: retoc/src/verse_vm_types.rs:125 Readable for VNameValueMapEntry
        override fun de(stream: InputStream): VNameValueMapEntry {
            val name: String = stream.read_utf8_string()
            val value: VValue = stream.de(VValue)
            return VNameValueMapEntry(name, value)
        }
    }
}

// Rust: retoc/src/verse_vm_types.rs:131 VPackage
data class VPackage(
    var name: FPackageIndex,
    var root_path: FPackageIndex,
    var definitions: List<VNameValueMapEntry>,
    var associated_u_package: VValue
) : Writeable {
    // Rust: retoc/src/verse_vm_types.rs:138 Writeable for VPackage
    override fun ser(stream: OutputStream) {
        stream.ser(name)
        stream.ser(root_path)
        stream.write_vec(definitions)
        stream.ser(associated_u_package)
    }

    companion object : Readable<VPackage> {
        // Rust: retoc/src/verse_vm_types.rs:147 Readable for VPackage
        override fun de(stream: InputStream): VPackage {
            val name: FPackageIndex = stream.de(FPackageIndex)
            val root_path: FPackageIndex = stream.de(FPackageIndex)
            val definitions: List<VNameValueMapEntry> = stream.read_vec { s -> s.de(VNameValueMapEntry) }
            val associated_u_package: VValue = stream.de(VValue)
            return VPackage(name, root_path, definitions, associated_u_package)
        }
    }
}
