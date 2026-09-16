// Ported from repak (MIT OR Apache-2.0) — Copyright (c) 2024 Truman Kilen, spuds
// Rust: repak/src/footer.rs:1
package com.github.jpabscale.zenpak4j.repak

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

// Rust: repak/src/pak.rs:12 Hash
@Suppress("FunctionName", "PropertyName", "VariableNaming")
class Hash(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Hash && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "Hash(${bytes.joinToString("") { "%02x".format(it) }})"
    companion object
}

// Rust: repak/src/footer.rs:11 Footer
@Suppress("FunctionName", "PropertyName", "VariableNaming")
data class Footer(
    var encryption_uuid: ULong?,
    var encrypted: Boolean,
    var magic: UInt,
    var version: Version,
    var version_major: VersionMajor,
    var index_offset: Long,
    var index_size: Long,
    var hash: Hash,
    var frozen: Boolean,
    var compression: List<Compression?>
) {
    // Rust: repak/src/footer.rs:86 write
    fun write(writer: OutputStream) {
        if (version_major >= VersionMajor.EncryptionKeyGuid) {
            writer.write_u128_le(0u)
        }
        if (version_major >= VersionMajor.IndexEncryption) {
            writer.write_bool(encrypted)
        }
        writer.write_u32_le(magic)
        writer.write_u32_le(version_major.ordinal.toUInt())
        writer.write_u64_le(index_offset.toULong())
        writer.write_u64_le(index_size.toULong())
        writer.write(hash.bytes)
        if (version_major == VersionMajor.FrozenIndex) {
            writer.write_bool(frozen)
        }
        // Rust: repak/src/footer.rs:101 algo_size
        val algo_size = when {
            version < Version.V8A -> 0
            version < Version.V8B -> 4
            else -> 5
        }
        // TODO: handle if compression.len() > algo_size
        for (i in 0 until algo_size) {
            val name = ByteArray(32) { 0 }
            val algo = compression.getOrNull(i)?.let { it } // flatten Option
            if (algo != null) {
                val bytes = algo.toString().toByteArray(StandardCharsets.UTF_8)
                for ((idx, b) in bytes.withIndex()) {
                    if (idx < 32) name[idx] = b
                }
            }
            writer.write(name)
        }
    }

    companion object {
        // Rust: repak/src/footer.rs:25 read
        fun read(reader: InputStream, version: Version): Footer {
            // Rust: encryption_uuid = (version.version_major() >= EncryptionKeyGuid).then_try(|| reader.read_u128::<LE>())?;
            val encryption_uuid: ULong? = (version.version_major() >= VersionMajor.EncryptionKeyGuid).then_try { reader.read_u128_le() }
            // Rust: encrypted = version.version_major() >= IndexEncryption && reader.read_bool()?;
            val encrypted: Boolean = version.version_major() >= VersionMajor.IndexEncryption && reader.read_bool()
            val magic: UInt = reader.read_u32_le()
            // Rust: version_major = VersionMajor::from_repr(reader.read_u32::<LE>()?).unwrap_or(version.version_major());
            val version_major: VersionMajor = VersionMajor.from_repr(reader.read_u32_le()) ?: version.version_major()
            val index_offset: Long = reader.read_u64_le().toLong()
            val index_size: Long = reader.read_u64_le().toLong()
            val hash = Hash(reader.read_guid())
            // Rust: frozen = version.version_major() == FrozenIndex && reader.read_bool()?;
            val frozen: Boolean = version.version_major() == VersionMajor.FrozenIndex && reader.read_bool()
            // Rust: compression Vec capacity 0/4/5 + FNameBasedCompression defaults
            val compression: MutableList<Compression?> = mutableListOf()
            val capacity = when {
                version < Version.V8A -> 0
                version < Version.V8B -> 4
                else -> 5
            }
            for (idx in 0 until capacity) {
                val raw = reader.read_len(32)
                // Rust: filter_map(|&ch| (ch != 0).then_some(ch as char)).collect::<String>()
                val str = raw.filter { it != 0.toByte() }.map { (it.toInt() and 0xFF).toChar() }.joinToString("")
                compression.add(Compression.from_string(str))
            }
            if (version.version_major() < VersionMajor.FNameBasedCompression) {
                compression.add(Compression.Zlib)
                compression.add(Compression.Gzip)
                compression.add(Compression.Oodle)
            }
            if (MAGIC != magic) {
                throw RepakError.Magic(magic)
            }
            if (version.version_major() != version_major) {
                throw RepakError.Version(used = version.version_major(), version = version_major)
            }
            return Footer(
                encryption_uuid = encryption_uuid,
                encrypted = encrypted,
                magic = magic,
                version = version,
                version_major = version_major,
                index_offset = index_offset,
                index_size = index_size,
                hash = hash,
                frozen = frozen,
                compression = compression,
            )
        }
    }
}
