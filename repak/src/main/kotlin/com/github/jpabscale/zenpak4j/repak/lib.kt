// Rust: repak/src/lib.rs:1
package com.github.jpabscale.zenpak4j.repak

import javax.crypto.spec.SecretKeySpec

// Rust: repak/src/lib.rs:11 MAGIC
const val MAGIC: UInt = 0x5A6F12E1u

// Rust: repak/src/lib.rs:26 Version
@Suppress("EnumEntryName", "EnumNaming", "FunctionName", "PropertyName", "VariableNaming")
enum class Version {
    V0,
    V1,
    V2,
    V3,
    V4,
    V5,
    V6,
    V7,
    V8A,
    V8B,
    V9,
    V10,
    V11;

    // strum::Display parity
    override fun toString(): String = name

    // Rust: repak/src/lib.rs:64 Version::iter
    // Preserve reverse order (Rev<VersionIter>)
    companion object {
        fun iter(): List<Version> = entries.reversed()

        fun from_repr(value: Int): Version? = entries.getOrNull(value)

        fun from_string(value: String): Version? =
            entries.find { it.name == value }
    }

    // Rust: repak/src/lib.rs:68 Version::size
    fun size(): Long {
        // (magic + version): u32 + (offset + size): u64 + hash: [u8; 20]
        var size = 4L + 4L + 8L + 8L + 20L
        if (version_major() >= VersionMajor.EncryptionKeyGuid) {
            // encryption uuid: u128
            size += 16
        }
        if (version_major() >= VersionMajor.IndexEncryption) {
            // encrypted: bool
            size += 1
        }
        if (version_major() == VersionMajor.FrozenIndex) {
            // frozen index: bool
            size += 1
        }
        if (this >= V8A) {
            // compression names: [[u8; 32]; 4]
            size += 32 * 4
        }
        if (this >= V8B) {
            // additional compression name
            size += 32
        }
        return size
    }

    // Rust: repak/src/lib.rs:95 Version::version_major
    fun version_major(): VersionMajor =
        when (this) {
            V0 -> VersionMajor.Unknown
            V1 -> VersionMajor.Initial
            V2 -> VersionMajor.NoTimestamps
            V3 -> VersionMajor.CompressionEncryption
            V4 -> VersionMajor.IndexEncryption
            V5 -> VersionMajor.RelativeChunkOffsets
            V6 -> VersionMajor.DeleteRecords
            V7 -> VersionMajor.EncryptionKeyGuid
            V8A -> VersionMajor.FNameBasedCompression
            V8B -> VersionMajor.FNameBasedCompression
            V9 -> VersionMajor.FrozenIndex
            V10 -> VersionMajor.PathHashIndex
            V11 -> VersionMajor.Fnv64BugFix
        }
}

// Rust: repak/src/lib.rs:47 VersionMajor
@Suppress("EnumEntryName", "EnumNaming", "FunctionName", "PropertyName", "VariableNaming")
enum class VersionMajor {
    Unknown, // v0 unknown (mostly just for padding)
    Initial, // v1 initial specification
    NoTimestamps, // v2 timestamps removed
    CompressionEncryption, // v3 compression and encryption support
    IndexEncryption, // v4 index encryption support
    RelativeChunkOffsets, // v5 offsets are relative to header
    DeleteRecords, // v6 record deletion support
    EncryptionKeyGuid, // v7 include key GUID
    FNameBasedCompression, // v8 compression names included
    FrozenIndex, // v9 frozen index byte included
    PathHashIndex, // v10
    Fnv64BugFix, // v11
    ;

    override fun toString(): String = name

    companion object {
        fun from_repr(value: Int): VersionMajor? = entries.getOrNull(value)
        fun from_repr(value: UInt): VersionMajor? = entries.getOrNull(value.toInt())
    }
}

// Rust: repak/src/lib.rs:117 Compression
@Suppress("EnumEntryName", "EnumNaming", "FunctionName", "PropertyName", "VariableNaming")
enum class Compression {
    Zlib,
    Gzip,
    Oodle,
    Zstd,
    LZ4;

    override fun toString(): String = name

    companion object {
        fun from_string(value: String): Compression? =
            entries.find { it.name == value }
    }
}

// Rust: repak/src/lib.rs:127 Key
@Suppress("ClassName", "FunctionName", "PropertyName", "VariableNaming")
sealed class Key {
    // Rust: repak/src/lib.rs:129 Key::Some (encryption)
    data class Some(val key: SecretKeySpec?) : Key()

    // Rust: repak/src/lib.rs:131 Key::None
    object None : Key()

    companion object {
        val Default: Key = None
    }
}
