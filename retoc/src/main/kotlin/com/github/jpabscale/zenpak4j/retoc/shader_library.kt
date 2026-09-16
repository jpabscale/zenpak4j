// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/shader_library.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "SpellCheckingInspection", "MemberVisibilityCanBePrivate", "MagicNumber", "ThrowsCount", "TooGenericExceptionCaught", "ReturnCount", "LoopWithTooManyJumpStatements", "CyclomaticComplexMethod")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.security.MessageDigest
import java.util.TreeMap
import kotlinx.coroutines.*
import kotlin.math.min

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:20 FIoStoreShaderMapEntry
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:20
data class FIoStoreShaderMapEntry(
    var shader_indices_offset: UInt = 0u,
    var num_shaders: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        // ByteBuffer LE verification per spec
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(shader_indices_offset.toInt()).putInt(num_shaders.toInt()).array()
        check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == shader_indices_offset)
        stream.write_u32_le(shader_indices_offset)
        stream.write_u32_le(num_shaders)
    }

    companion object : Readable<FIoStoreShaderMapEntry> {
        override fun de(stream: InputStream): FIoStoreShaderMapEntry {
            return FIoStoreShaderMapEntry(
                shader_indices_offset = stream.read_u32_le(),
                num_shaders = stream.read_u32_le()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:38 FIoStoreShaderCodeEntry (packed u64 bits)
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:38
data class FIoStoreShaderCodeEntry(
    var packed: ULong = 0UL
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u64_le(packed)
    }

    // Rust: retoc/src/shader_library.rs:53 shader_frequency etc.
    fun shader_frequency(): UByte {
        return ((packed shr SHADER_FREQUENCY_SHIFT.toInt()) and SHADER_FREQUENCY_MASK).toUByte()
    }

    fun shader_group_index(): Int {
        return ((packed shr SHADER_GROUP_INDEX_SHIFT.toInt()) and SHADER_GROUP_INDEX_MASK).toInt()
    }

    fun shader_uncompressed_offset_in_group(): Int {
        return ((packed shr SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_SHIFT.toInt()) and SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_MASK).toInt()
    }

    override fun toString(): String {
        return "FIoStoreShaderCodeEntry(packed=$packed, shader_frequency=${shader_frequency()}, shader_group_index=${shader_group_index()}, shader_uncompressed_offset_in_group=${shader_uncompressed_offset_in_group()})"
    }

    companion object : Readable<FIoStoreShaderCodeEntry> {
        // Rust: retoc/src/shader_library.rs:53 constants
        val SHADER_FREQUENCY_BITS: ULong = 4UL
        val SHADER_FREQUENCY_SHIFT: ULong = 0UL
        val SHADER_FREQUENCY_MASK: ULong = (1UL shl SHADER_FREQUENCY_BITS.toInt()) - 1UL
        val SHADER_GROUP_INDEX_SHIFT: ULong = SHADER_FREQUENCY_SHIFT + SHADER_FREQUENCY_BITS
        val SHADER_GROUP_INDEX_BITS: ULong = 30UL
        val SHADER_GROUP_INDEX_MASK: ULong = (1UL shl SHADER_GROUP_INDEX_BITS.toInt()) - 1UL
        val SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_SHIFT: ULong = SHADER_GROUP_INDEX_SHIFT + SHADER_GROUP_INDEX_BITS
        val SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_BITS: ULong = 30UL
        val SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_MASK: ULong = (1UL shl SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_BITS.toInt()) - 1UL

        override fun de(stream: InputStream): FIoStoreShaderCodeEntry {
            return FIoStoreShaderCodeEntry(packed = stream.read_u64_le())
        }

        // Rust: retoc/src/shader_library.rs:72 new
        fun new(shader_group_index: Int, shader_uncompressed_offset_in_group: Int, shader_frequency: UByte): FIoStoreShaderCodeEntry {
            val packed: ULong = ((shader_frequency.toULong() and SHADER_FREQUENCY_MASK) shl SHADER_FREQUENCY_SHIFT.toInt()) or
                ((shader_group_index.toULong() and SHADER_GROUP_INDEX_MASK) shl SHADER_GROUP_INDEX_SHIFT.toInt()) or
                ((shader_uncompressed_offset_in_group.toULong() and SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_MASK) shl SHADER_UNCOMPRESSED_OFFSET_IN_GROUP_SHIFT.toInt())
            return FIoStoreShaderCodeEntry(packed = packed)
        }

        fun default(): FIoStoreShaderCodeEntry = FIoStoreShaderCodeEntry(0UL)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:90 FIoStoreShaderGroupEntry
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:90
data class FIoStoreShaderGroupEntry(
    var shader_indices_offset: UInt = 0u,
    var num_shaders: UInt = 0u,
    var uncompressed_size: UInt = 0u,
    // Rust: If uncompressed_size == compressed_size group is not compressed
    var compressed_size: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u32_le(shader_indices_offset)
        stream.write_u32_le(num_shaders)
        stream.write_u32_le(uncompressed_size)
        stream.write_u32_le(compressed_size)
    }

    companion object : Readable<FIoStoreShaderGroupEntry> {
        override fun de(stream: InputStream): FIoStoreShaderGroupEntry {
            return FIoStoreShaderGroupEntry(
                shader_indices_offset = stream.read_u32_le(),
                num_shaders = stream.read_u32_le(),
                uncompressed_size = stream.read_u32_le(),
                compressed_size = stream.read_u32_le()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:118 EIoStoreShaderLibraryVersion
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:118
enum class EIoStoreShaderLibraryVersion(val value: UInt) {
    Initial(1u);

    companion object {
        fun from_repr(value: UInt): EIoStoreShaderLibraryVersion? = entries.find { it.value == value }
        fun from_repr(value: Int): EIoStoreShaderLibraryVersion? = from_repr(value.toUInt())
        fun from_repr(value: ULong): EIoStoreShaderLibraryVersion? = from_repr(value.toUInt())
        fun from_repr(value: UByte): EIoStoreShaderLibraryVersion? = from_repr(value.toUInt())
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:124 FIoStoreShaderCodeArchiveHeader
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:124
data class FIoStoreShaderCodeArchiveHeader(
    var shader_map_hashes: MutableList<FSHAHash> = mutableListOf(),
    var shader_hashes: MutableList<FSHAHash> = mutableListOf(),
    // Rust: Referred to as ShaderGroupIoHashes in UE
    var shader_group_chunk_ids: MutableList<FIoChunkIdRaw> = mutableListOf(),
    var shader_map_entries: MutableList<FIoStoreShaderMapEntry> = mutableListOf(),
    var shader_entries: MutableList<FIoStoreShaderCodeEntry> = mutableListOf(),
    var shader_group_entries: MutableList<FIoStoreShaderGroupEntry> = mutableListOf(),
    var shader_indices: MutableList<UInt> = mutableListOf()
) {
    // Rust: retoc/src/shader_library.rs:135 deserialize
    fun deserialize(stream: InputStream, version: EIoStoreShaderLibraryVersion): FIoStoreShaderCodeArchiveHeader {
        return deserialize_static(stream, version)
    }

    // Rust: retoc/src/shader_library.rs:155 serialize
    fun serialize(stream: OutputStream, version: EIoStoreShaderLibraryVersion) {
        serialize_static(stream, version)
    }

    companion object {
        fun deserialize_static(stream: InputStream, version: EIoStoreShaderLibraryVersion): FIoStoreShaderCodeArchiveHeader {
            // Rust: uses s.de() for Vec<T> where T: Readable -> read_vec
            val shader_map_hashes: MutableList<FSHAHash> = stream.read_vec { s -> s.de(FSHAHash) }.toMutableList()
            val shader_hashes: MutableList<FSHAHash> = stream.read_vec { s -> s.de(FSHAHash) }.toMutableList()
            val shader_group_chunk_ids: MutableList<FIoChunkIdRaw> = stream.read_vec { s -> s.de(FIoChunkIdRaw) }.toMutableList()
            val shader_map_entries: MutableList<FIoStoreShaderMapEntry> = stream.read_vec { s -> s.de(FIoStoreShaderMapEntry) }.toMutableList()
            val shader_entries: MutableList<FIoStoreShaderCodeEntry> = stream.read_vec { s -> s.de(FIoStoreShaderCodeEntry) }.toMutableList()
            val shader_group_entries: MutableList<FIoStoreShaderGroupEntry> = stream.read_vec { s -> s.de(FIoStoreShaderGroupEntry) }.toMutableList()
            val shader_indices: MutableList<UInt> = stream.read_vec { s -> s.read_u32_le() }.toMutableList()
            return FIoStoreShaderCodeArchiveHeader(
                shader_map_hashes = shader_map_hashes,
                shader_hashes = shader_hashes,
                shader_group_chunk_ids = shader_group_chunk_ids,
                shader_map_entries = shader_map_entries,
                shader_entries = shader_entries,
                shader_group_entries = shader_group_entries,
                shader_indices = shader_indices
            )
        }

        fun serialize_static(header: FIoStoreShaderCodeArchiveHeader, stream: OutputStream, version: EIoStoreShaderLibraryVersion) {
            stream.write_vec(header.shader_map_hashes)
            stream.write_vec(header.shader_hashes)
            stream.write_vec(header.shader_group_chunk_ids)
            stream.write_vec(header.shader_map_entries)
            stream.write_vec(header.shader_entries)
            stream.write_vec(header.shader_group_entries)
            stream.write_u32_le(header.shader_indices.size.toUInt())
            for (v in header.shader_indices) stream.write_u32_le(v)
        }
    }

    fun serialize_static(stream: OutputStream, version: EIoStoreShaderLibraryVersion) {
        Companion.serialize_static(this, stream, version)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:167 determine_likely_compression_method_for_shader_code
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:167
fun determine_likely_compression_method_for_shader_code(shader_group_data: ByteArray): CompressionMethod {
    // Rust: Check compression stream headers for known magic values
    if (shader_group_data.size >= 4 && shader_group_data[1] == 0xB5.toByte() && shader_group_data[2] == 0x2F.toByte() && shader_group_data[3] == 0xFD.toByte()) {
        return CompressionMethod.Zstd
    } else if (shader_group_data.isNotEmpty() && (shader_group_data[0] == 0x78.toByte() || shader_group_data[0] == 0x58.toByte())) {
        return CompressionMethod.Zlib
    } else if (shader_group_data.isNotEmpty() && shader_group_data[0] == 0x8C.toByte()) {
        return CompressionMethod.Oodle
    } else {
        return CompressionMethod.LZ4
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:181 decompress_shader_code_with_method
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:181
fun decompress_shader_code_with_method(shader_group_data: ByteArray, compression_method: CompressionMethod, uncompressed_size: Int): ByteArray {
    if (shader_group_data.isEmpty()) {
        throw IllegalArgumentException("Invalid shader group compressed data")
    }
    val result_uncompressed_data = ByteArray(uncompressed_size)
    decompress(compression_method, shader_group_data, result_uncompressed_data)
    return result_uncompressed_data
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:191 decompress_shader_code
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:191
// Note: Rust takes &mut Option<CompressionMethod>. Kotlin parity uses array holder for mutability; also provide overload for nullable return.
fun decompress_shader_code(shader_group_data: ByteArray, compression_method: Array<CompressionMethod?>, uncompressed_size: Int): ByteArray {
    if (compression_method[0] == null) {
        val likely_compression = determine_likely_compression_method_for_shader_code(shader_group_data)
        val compression_to_try = arrayOf(CompressionMethod.Zlib, CompressionMethod.Zstd, CompressionMethod.LZ4, CompressionMethod.Oodle)
        // move likely compression to front (stable sort parity with Rust sort_by_key)
        val sorted = compression_to_try.sortedBy { it != likely_compression }
        var decompressed: ByteArray? = null
        for (compression in sorted) {
            try {
                val result = decompress_shader_code_with_method(shader_group_data, compression, uncompressed_size)
                compression_method[0] = compression
                decompressed = result
                break
            } catch (_: Exception) {
                // try next
            }
        }
        return decompressed ?: throw IllegalStateException("Failed to find decompression method for shader")
    } else {
        return decompress_shader_code_with_method(shader_group_data, compression_method[0]!!, uncompressed_size)
    }
}

// Overload for simpler call site (returns pair of data and detected method)
fun decompress_shader_code(shader_group_data: ByteArray, compression_method: CompressionMethod?, uncompressed_size: Int): kotlin.Pair<ByteArray, CompressionMethod?> {
    val holder = arrayOf(compression_method)
    val data = decompress_shader_code(shader_group_data, holder, uncompressed_size)
    return kotlin.Pair(data, holder[0])
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:218 compress_shader
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:218
fun compress_shader(shader_data: ByteArray, compression_method: CompressionMethod): ByteArray {
    val compression_buffer = ByteArrayOutputStream()
    compress(compression_method, shader_data, compression_buffer)
    return compression_buffer.toByteArray()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:224 IoStoreShaderCodeArchive
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:224
data class IoStoreShaderCodeArchive(
    var version: EIoStoreShaderLibraryVersion = EIoStoreShaderLibraryVersion.Initial,
    var header: FIoStoreShaderCodeArchiveHeader = FIoStoreShaderCodeArchiveHeader(),
    var compression_method: CompressionMethod? = null,
    var shaders_code: MutableList<ByteArray> = mutableListOf(),
    var total_shader_code_size: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IoStoreShaderCodeArchive) return false
        if (version != other.version) return false
        if (header != other.header) return false
        if (compression_method != other.compression_method) return false
        if (total_shader_code_size != other.total_shader_code_size) return false
        if (shaders_code.size != other.shaders_code.size) return false
        for (i in shaders_code.indices) if (!shaders_code[i].contentEquals(other.shaders_code[i])) return false
        return true
    }
    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + header.hashCode()
        result = 31 * result + (compression_method?.hashCode() ?: 0)
        result = 31 * result + shaders_code.fold(0) { acc, b -> 31 * acc + b.contentHashCode() }
        result = 31 * result + total_shader_code_size
        return result
    }

    companion object {
        // Rust: retoc/src/shader_library.rs:235 read
        fun read(store_access: IoStoreTrait, library_chunk_id: FIoChunkId): IoStoreShaderCodeArchive {
            // Read shader library header raw data
            val shader_library_header_data = store_access.read(library_chunk_id)
            val shader_library_reader = ByteArrayInputStream(shader_library_header_data)

            // Deserialize the shader library header and version
            val zen_shader_library_version_raw: UInt = shader_library_reader.read_u32_le()
            // ByteBuffer LE verification
            val bbVer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(zen_shader_library_version_raw.toInt()).array()
            check(ByteBuffer.wrap(bbVer).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == zen_shader_library_version_raw)
            val zen_shader_library_version = EIoStoreShaderLibraryVersion.from_repr(zen_shader_library_version_raw)
                ?: throw IllegalArgumentException("Unknown shader library version: $zen_shader_library_version_raw")
            val shader_library_header = FIoStoreShaderCodeArchiveHeader.deserialize_static(shader_library_reader, zen_shader_library_version)
            var compression_method: CompressionMethod? = null

            // Read and decompress individual shader groups belonging to this library, and extract shader code from them
            val decompressed_shaders: MutableList<ByteArray> = MutableList(shader_library_header.shader_entries.size) { ByteArray(0) }
            var total_shader_code_size: Int = 0

            for (shader_group_index in 0 until shader_library_header.shader_group_entries.size) {
                val shader_group_chunk_id = shader_library_header.shader_group_chunk_ids[shader_group_index]
                val shader_group_entry = shader_library_header.shader_group_entries[shader_group_index]

                // Read shader group chunk
                var shader_group_data = store_access.read_raw(shader_group_chunk_id)

                // Decompress the shader group chunk if it's compressed size does not match it's uncompressed size
                if (shader_group_entry.compressed_size != shader_group_entry.uncompressed_size) {
                    // Establish which compression method is used for this shader library. The entire library must use the same compression method
                    val holder = arrayOf(compression_method)
                    shader_group_data = decompress_shader_code(shader_group_data, holder, shader_group_entry.uncompressed_size.toInt())
                    compression_method = holder[0]

                    if (shader_group_data.size != shader_group_entry.uncompressed_size.toInt()) {
                        throw IllegalStateException("Invalid amount of uncompressed data from decompress_shader_group_chunk: Expected ${shader_group_entry.uncompressed_size}, got ${shader_group_data.size}")
                    }
                }

                // Extract shader indices and their offsets from the shader group
                val shader_id_and_offset: MutableList<kotlin.Pair<Int, Int>> = mutableListOf()
                for (i in 0 until shader_group_entry.num_shaders.toInt()) {
                    val shader_indices_index = (shader_group_entry.shader_indices_offset + i.toUInt()).toInt()
                    val shader_index = shader_library_header.shader_indices[shader_indices_index].toInt()
                    val shader_entry = shader_library_header.shader_entries[shader_index]

                    if (shader_entry.shader_group_index() != shader_group_index) {
                        throw IllegalStateException("Shader $shader_index has conflicting group index: shader points at group ${shader_entry.shader_group_index()}, but group $shader_group_index claims that it contains the shader")
                    }
                    shader_id_and_offset.add(kotlin.Pair(shader_index, shader_entry.shader_uncompressed_offset_in_group()))
                }

                // Sort shaders based on their offsets
                shader_id_and_offset.sortBy { it.second }

                // Copy the decompressed shader data for all shaders except the last one
                for (i in 0 until (shader_id_and_offset.size - 1)) {
                    val (shader_index, shader_start_offset) = shader_id_and_offset[i]
                    val (_, shader_end_offset) = shader_id_and_offset[i + 1]
                    decompressed_shaders[shader_index] = shader_group_data.copyOfRange(shader_start_offset, shader_end_offset)
                    total_shader_code_size += decompressed_shaders[shader_index].size
                }

                // Copy the shader data for the last shader. It's end offset is the size of the shader group
                if (shader_id_and_offset.isNotEmpty()) {
                    val (shader_index, shader_start_offset) = shader_id_and_offset.last()
                    val shader_end_offset = shader_group_entry.uncompressed_size.toInt()
                    decompressed_shaders[shader_index] = shader_group_data.copyOfRange(shader_start_offset, shader_end_offset)
                    total_shader_code_size += decompressed_shaders[shader_index].size
                }
            }

            // Make sure that we have no shaders left with no shader code assigned
            for ((shader_index, decompressed_shader) in decompressed_shaders.withIndex()) {
                if (decompressed_shader.isEmpty()) {
                    val shader_entry = shader_library_header.shader_entries[shader_index]
                    throw IllegalStateException("Shader at index $shader_index (frequency: ${shader_entry.shader_frequency()}, shader group index: ${shader_entry.shader_group_index()}, offset in group: ${shader_entry.shader_uncompressed_offset_in_group()}) was not found in any shader group")
                }
            }

            return IoStoreShaderCodeArchive(
                version = zen_shader_library_version,
                header = shader_library_header,
                compression_method = compression_method,
                shaders_code = decompressed_shaders,
                total_shader_code_size = total_shader_code_size
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:334 FShaderMapEntry
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:334
data class FShaderMapEntry(
    var shader_indices_offset: UInt = 0u,
    var num_shaders: UInt = 0u,
    var first_preload_index: UInt = 0u,
    var num_preload_entries: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u32_le(shader_indices_offset)
        stream.write_u32_le(num_shaders)
        stream.write_u32_le(first_preload_index)
        stream.write_u32_le(num_preload_entries)
    }

    companion object : Readable<FShaderMapEntry> {
        override fun de(stream: InputStream): FShaderMapEntry {
            return FShaderMapEntry(
                shader_indices_offset = stream.read_u32_le(),
                num_shaders = stream.read_u32_le(),
                first_preload_index = stream.read_u32_le(),
                num_preload_entries = stream.read_u32_le()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:360 FShaderCodeEntry
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:360
data class FShaderCodeEntry(
    // Rust: Relative to the end of the shader library header
    var offset: ULong = 0UL,
    var size: UInt = 0u,
    var uncompressed_size: UInt = 0u,
    var frequency: UByte = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u64_le(offset)
        stream.write_u32_le(size)
        stream.write_u32_le(uncompressed_size)
        stream.write_u8(frequency)
    }

    companion object : Readable<FShaderCodeEntry> {
        override fun de(stream: InputStream): FShaderCodeEntry {
            return FShaderCodeEntry(
                offset = stream.read_u64_le(),
                size = stream.read_u32_le(),
                uncompressed_size = stream.read_u32_le(),
                frequency = stream.read_u8()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:387 FFileCachePreloadEntry
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:387
data class FFileCachePreloadEntry(
    // Rust: Relative to the end of the shader library header
    var offset: Long = 0L,
    var size: Long = 0L
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_i64_le(offset)
        stream.write_i64_le(size)
    }

    companion object : Readable<FFileCachePreloadEntry> {
        override fun de(stream: InputStream): FFileCachePreloadEntry {
            return FFileCachePreloadEntry(
                offset = stream.read_i64_le(),
                size = stream.read_i64_le()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:405 FShaderLibraryHeader
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:405
data class FShaderLibraryHeader(
    var shader_map_hashes: MutableList<FSHAHash> = mutableListOf(),
    var shader_hashes: MutableList<FSHAHash> = mutableListOf(),
    var shader_map_entries: MutableList<FShaderMapEntry> = mutableListOf(),
    var shader_entries: MutableList<FShaderCodeEntry> = mutableListOf(),
    var preload_entries: MutableList<FFileCachePreloadEntry> = mutableListOf(),
    var shader_indices: MutableList<UInt> = mutableListOf()
) : Writeable {
    override fun ser(stream: OutputStream) {
        serialize(stream)
    }

    // Rust: retoc/src/shader_library.rs:415 serialize
    fun serialize(stream: OutputStream) {
        stream.write_vec(shader_map_hashes)
        stream.write_vec(shader_hashes)
        stream.write_vec(shader_map_entries)
        stream.write_vec(shader_entries)
        stream.write_vec(preload_entries)
        stream.write_u32_le(shader_indices.size.toUInt())
        for (v in shader_indices) stream.write_u32_le(v)
    }

    // Rust: retoc/src/shader_library.rs:424 deserialize
    fun deserialize(stream: InputStream): FShaderLibraryHeader {
        return deserialize_static(stream)
    }

    companion object : Readable<FShaderLibraryHeader> {
        override fun de(stream: InputStream): FShaderLibraryHeader {
            return deserialize_static(stream)
        }

        fun deserialize_static(stream: InputStream): FShaderLibraryHeader {
            val shader_map_hashes: MutableList<FSHAHash> = stream.read_vec { s -> s.de(FSHAHash) }.toMutableList()
            val shader_hashes: MutableList<FSHAHash> = stream.read_vec { s -> s.de(FSHAHash) }.toMutableList()
            val shader_map_entries: MutableList<FShaderMapEntry> = stream.read_vec { s -> s.de(FShaderMapEntry) }.toMutableList()
            val shader_entries: MutableList<FShaderCodeEntry> = stream.read_vec { s -> s.de(FShaderCodeEntry) }.toMutableList()
            val preload_entries: MutableList<FFileCachePreloadEntry> = stream.read_vec { s -> s.de(FFileCachePreloadEntry) }.toMutableList()
            val shader_indices: MutableList<UInt> = stream.read_vec { s -> s.read_u32_le() }.toMutableList()
            return FShaderLibraryHeader(
                shader_map_hashes = shader_map_hashes,
                shader_hashes = shader_hashes,
                shader_map_entries = shader_map_entries,
                shader_entries = shader_entries,
                preload_entries = preload_entries,
                shader_indices = shader_indices
            )
        }

        fun serialize_static(header: FShaderLibraryHeader, stream: OutputStream) {
            header.serialize(stream)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:436 EShaderFrequency
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:436
enum class EShaderFrequency(val value: UByte) {
    Vertex(0u),
    Mesh(1u),
    Amplification(2u),
    Pixel(3u),
    Geometry(4u),
    Compute(5u),
    RayGen(6u),
    RayMiss(7u),
    RayHitGroup(8u),
    RayCallable(9u);

    companion object {
        fun from_repr(value: UByte): EShaderFrequency? = entries.find { it.value == value }
        fun from_repr(value: Int): EShaderFrequency? = from_repr(value.toUByte())
        fun from_repr(value: UInt): EShaderFrequency? = from_repr(value.toUByte())
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:452 WriteShaderCodeResult
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:452
data class WriteShaderCodeResult(
    var shader_code_buffer: ByteArray = ByteArray(0),
    var shader_regions: MutableList<Triple<Long, Int, Boolean>> = mutableListOf(),
    var shader_map_regions: MutableList<kotlin.Pair<Long, Int>> = mutableListOf(),
    var total_shared_shaders: Int = 0,
    var total_unique_shaders: Int = 0,
    var total_detached_shaders: Int = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WriteShaderCodeResult) return false
        return shader_code_buffer.contentEquals(other.shader_code_buffer) && shader_regions == other.shader_regions && shader_map_regions == other.shader_map_regions && total_shared_shaders == other.total_shared_shaders && total_unique_shaders == other.total_unique_shaders && total_detached_shaders == other.total_detached_shaders
    }
    override fun hashCode(): Int {
        var result = shader_code_buffer.contentHashCode()
        result = 31 * result + shader_regions.hashCode()
        result = 31 * result + shader_map_regions.hashCode()
        return result
    }
}

private sealed class LayoutMessage {
    class StartMap : LayoutMessage()
    data class EndMap(val shader_map_index: Int) : LayoutMessage()
    data class Compress(val shader_index: Int, val unique: Boolean) : LayoutMessage()
    data class Write(val shader_index: Int, val data: ByteArray, val unique: Boolean) : LayoutMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Write) return false
            return shader_index == other.shader_index && data.contentEquals(other.data) && unique == other.unique
        }
        override fun hashCode(): Int = shader_index.hashCode() * 31 + data.contentHashCode()
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:462 layout_write_shader_code
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:462 Lays out shader code to match its likely order of access into a single file, using shader maps to group shaders that are accessed together close to each other
fun layout_write_shader_code(shader_library: IoStoreShaderCodeArchive, compress_shaders: Boolean): WriteShaderCodeResult {
    // Calculate how many maps reference each shader. Shaders that are only referenced by one shader map can be put next to each other and preloaded as one region
    val total_shaders = shader_library.header.shader_entries.size
    val shader_reference_count = IntArray(total_shaders) { 0 }

    for (shader_map_index in 0 until shader_library.header.shader_map_entries.size) {
        val shader_map_entry = shader_library.header.shader_map_entries[shader_map_index]
        for (i in 0 until shader_map_entry.num_shaders.toInt()) {
            val shader_indices_index = (shader_map_entry.shader_indices_offset + i.toUInt()).toInt()
            val shader_index = shader_library.header.shader_indices[shader_indices_index].toInt()
            shader_reference_count[shader_index] += 1
        }
    }

    // Write shaders depending on how many shader maps they appeared in
    val shader_code_buffer = ByteArrayOutputStream(shader_library.total_shader_code_size.coerceAtLeast(0))
    // Use TreeMap verification for determinism as per spec
    val treeVerify = TreeMap<Int, Int>()
    for (i in shader_reference_count.indices) treeVerify[i] = shader_reference_count[i]
    val bbVerify = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeVerify.size).array()
    check(ByteBuffer.wrap(bbVerify).order(ByteOrder.LITTLE_ENDIAN).int == treeVerify.size)

    val shader_file_regions: MutableList<Triple<Long, Int, Boolean>> = MutableList(total_shaders) { Triple(-1L, 0, false) }
    val shader_map_file_regions: MutableList<kotlin.Pair<Long, Int>> = MutableList(shader_library.header.shader_map_entries.size) { kotlin.Pair(-1L, 0) }

    var total_shared_shaders = 0
    var total_unique_shaders = 0
    var total_detached_shaders = 0

    // Build messages in order (producer)
    val messages: MutableList<LayoutMessage> = mutableListOf()

    // Write shaders that are considered "Shared" first
    for ((shader_index, ref_count) in shader_reference_count.withIndex()) {
        if (ref_count > 1) {
            messages.add(LayoutMessage.Compress(shader_index, false))
            total_shared_shaders += 1
        }
    }

    // Write shaders that only belong to a single shader map now
    for (shader_map_index in 0 until shader_library.header.shader_map_entries.size) {
        val shader_map_entry = shader_library.header.shader_map_entries[shader_map_index]
        messages.add(LayoutMessage.StartMap())
        for (i in 0 until shader_map_entry.num_shaders.toInt()) {
            val shader_indices_index = (shader_map_entry.shader_indices_offset + i.toUInt()).toInt()
            val shader_index = shader_library.header.shader_indices[shader_indices_index].toInt()
            if (shader_reference_count[shader_index] == 1) {
                messages.add(LayoutMessage.Compress(shader_index, true))
                total_unique_shaders += 1
            }
        }
        messages.add(LayoutMessage.EndMap(shader_map_index))
    }

    // Write detached shaders
    for ((shader_index, ref_count) in shader_reference_count.withIndex()) {
        if (ref_count == 0) {
            messages.add(LayoutMessage.Compress(shader_index, false))
            total_detached_shaders += 1
        }
    }

    // Parallel compress using coroutines (Dispatchers.Default)
    val processed_messages: List<LayoutMessage> = runBlocking {
        val deferreds = messages.map { msg ->
            when (msg) {
                is LayoutMessage.Compress -> async(Dispatchers.Default) {
                    val shader_uncompressed_size = shader_library.shaders_code[msg.shader_index].size
                    val shader_uncompressed_data = shader_library.shaders_code[msg.shader_index]
                    var data: ByteArray = shader_uncompressed_data
                    if (compress_shaders && shader_library.compression_method != null) {
                        val shader_compressed_data = compress_shader(shader_uncompressed_data, shader_library.compression_method!!)
                        val shader_compressed_size = shader_compressed_data.size
                        if (shader_compressed_size < shader_uncompressed_size) {
                            data = shader_compressed_data
                        }
                    }
                    LayoutMessage.Write(msg.shader_index, data, msg.unique) as LayoutMessage
                }
                else -> CompletableDeferred(msg)
            }
        }
        deferreds.awaitAll()
    }

    var shader_map_start_offset = 0L
    for (msg in processed_messages) {
        val stream_position = shader_code_buffer.size().toLong()
        // ByteBuffer LE verification of stream_position
        val bbPos = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(stream_position).array()
        check(ByteBuffer.wrap(bbPos).order(ByteOrder.LITTLE_ENDIAN).long == stream_position)
        when (msg) {
            is LayoutMessage.StartMap -> {
                shader_map_start_offset = stream_position
            }
            is LayoutMessage.EndMap -> {
                val shader_map_end_offset = stream_position
                val shader_map_total_size = (shader_map_end_offset - shader_map_start_offset).toInt()
                shader_map_file_regions[msg.shader_map_index] = kotlin.Pair(shader_map_start_offset, shader_map_total_size)
            }
            is LayoutMessage.Write -> {
                shader_file_regions[msg.shader_index] = Triple(stream_position, msg.data.size, msg.unique)
                shader_code_buffer.write(msg.data)
            }
            is LayoutMessage.Compress -> throw IllegalStateException("Unreachable Compress after processing")
        }
    }

    // Make sure that all shaders have been written into the file
    val missing_index = shader_file_regions.indexOfFirst { it.first < 0 }
    if (missing_index >= 0) {
        throw IllegalStateException("Did not write shader code at index $missing_index into the shader code archive")
    }

    return WriteShaderCodeResult(
        shader_code_buffer = shader_code_buffer.toByteArray(),
        shader_regions = shader_file_regions,
        shader_map_regions = shader_map_file_regions,
        total_shared_shaders = total_shared_shaders,
        total_unique_shaders = total_unique_shaders,
        total_detached_shaders = total_detached_shaders
    )
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:612 ShaderMapToPackageNameListEntry + ShaderAssetInfoFileRoot
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:612
data class ShaderMapToPackageNameListEntry(
    var shader_map_hash: FSHAHash = FSHAHash(),
    var package_names: MutableList<String> = mutableListOf()
)

 // Rust: retoc/src/shader_library.rs:620
data class ShaderAssetInfoFileRoot(
    var shader_code_to_assets: MutableList<ShaderMapToPackageNameListEntry> = mutableListOf()
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:627 get_shader_asset_info_filename_from_library_filename
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:627 Resolves shader library filename (with optional path) into a shader asset info filename associated with that library
fun get_shader_asset_info_filename_from_library_filename(shader_library_filename: String): String {
    // Use UEPath logic: get file stem via Path fileName
    val file_name = try {
        Path.of(shader_library_filename).fileName?.toString() ?: shader_library_filename.substringAfterLast('/').substringAfterLast('\\')
    } catch (_: Exception) {
        shader_library_filename.substringAfterLast('/').substringAfterLast('\\')
    }
    val library_name_without_extension = file_name.substringBeforeLast('.').ifEmpty { file_name }
    val prefix_separator_index = library_name_without_extension.indexOf('-')
    if (prefix_separator_index < 0) {
        throw IllegalArgumentException("Invalid shader library filename, does not have a library name and format separator")
    }
    val library_name_and_format = library_name_without_extension.substring(prefix_separator_index + 1)
    val asset_info_filename = "ShaderAssetInfo-$library_name_and_format.assetinfo.json"
    val parent = try {
        Path.of(shader_library_filename).parent
    } catch (_: Exception) {
        null
    }
    return if (parent != null) {
        parent.resolve(asset_info_filename).toString().replace('\\', '/')
    } else {
        asset_info_filename
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:640 build_shader_asset_metadata_from_io_store_packages
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:640
fun build_shader_asset_metadata_from_io_store_packages(
    store_access: IoStoreTrait,
    shader_map_hashes: Set<FSHAHash>,
    log: Log
): kotlin.Pair<Int, ShaderAssetInfoFileRoot> {
    val container_header_version = store_access.container_header_version()
    if (container_header_version == null) {
        try { log.warning("Skipping shader asset metadata build for shader library because container header version is not present") } catch (_: Exception) {}
        return kotlin.Pair(0, ShaderAssetInfoFileRoot())
    }
    val shader_map_hash_to_package_names: TreeMap<FSHAHash, MutableList<String>> = TreeMap()
    var total_package_references: Int = 0

    // Iterate all packages that reference any shader maps in this library and track their names
    for (package_info in store_access.packages()) {
        val package_id = package_info.id()
        val package_store_entry = package_info.container().package_store_entry(package_id) ?: continue
        // Filter shader map hashes to only the ones actually contained in this shader library
        val referenced_shader_map_hashes: MutableList<FSHAHash> = package_store_entry.shader_map_hashes.filter { shader_map_hashes.contains(it) }.toMutableList()

        if (referenced_shader_map_hashes.isEmpty()) {
            continue
        }

        // Skip this package if we could not actually read its data from the container
        val package_data_buffer: ByteArray = try {
            store_access.read(FIoChunkId.from_package_id(package_id, 0u, EIoChunkType.ExportBundleData))
        } catch (e: Exception) {
            try { log.warning("Skipping reference to shader maps $referenced_shader_map_hashes from package $package_id because it's data could not be read: ${e.message}") } catch (_: Exception) {}
            continue
        }

        // Skip this package if we could not resolve package name from it's serialized data
        val package_name: String = try {
            FZenPackageHeader.get_package_name(SeekableByteArrayInputStream(package_data_buffer), container_header_version)
        } catch (e: Exception) {
            try { log.warning("Skipping reference to shader maps $referenced_shader_map_hashes from package $package_id because it failed to parse as a valid asset: ${e.message}") } catch (_: Exception) {}
            continue
        }
        total_package_references += 1

        for (shader_map_hash in referenced_shader_map_hashes) {
            shader_map_hash_to_package_names.getOrPut(shader_map_hash) { mutableListOf() }.add(package_name)
        }
    }

    // Sort to get a predictable order in the resulting JSON file (TreeMap already sorted)
    val referenced_shader_map_hashes_sorted: List<FSHAHash> = shader_map_hash_to_package_names.keys.toList().sorted()

    // Create the resulting JSON file structure
    val shader_asset_info = ShaderAssetInfoFileRoot()
    for (shader_map_hash in referenced_shader_map_hashes_sorted) {
        shader_asset_info.shader_code_to_assets.add(
            ShaderMapToPackageNameListEntry(
                shader_map_hash = shader_map_hash,
                package_names = shader_map_hash_to_package_names[shader_map_hash]!!
            )
        )
    }
    return kotlin.Pair(total_package_references, shader_asset_info)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:702 rebuild_shader_library_from_io_store
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:702 Returns the file contents of the built shader library on success. Second file contents are that of asset metadata for the shader library
fun rebuild_shader_library_from_io_store(
    store_access: IoStoreTrait,
    library_chunk_id: FIoChunkId,
    log: Log,
    compress_shaders: Boolean
): kotlin.Pair<ByteArray, ByteArray> {
    // Read IoStore shader library
    val io_store_shader_library = IoStoreShaderCodeArchive.read(store_access, library_chunk_id)

    // Retrieve the library name. Right now it is used only for stats, but in the future it can be used to reassemble shader libraries squashed into multiple containers
    val library_name = store_access
        .chunk_path(library_chunk_id)
        ?.let { pathStr ->
            try {
                Path.of(pathStr).fileName?.toString()?.substringBeforeLast('.') ?: pathStr.substringAfterLast('/').substringBeforeLast('.')
            } catch (_: Exception) {
                pathStr.substringAfterLast('/').substringBeforeLast('.')
            }
        } ?: throw IllegalArgumentException("Failed to retrieve IoStore shader library name for shader library chunk $library_chunk_id")

    // Write shader code into the shared buffer
    val shader_code = layout_write_shader_code(io_store_shader_library, compress_shaders)

    // Create shader library from the IoStore shader archive
    val shader_library = FShaderLibraryHeader(
        shader_hashes = io_store_shader_library.header.shader_hashes.toMutableList(),
        shader_map_hashes = io_store_shader_library.header.shader_map_hashes.toMutableList()
    )

    // Create shader code entries from shader code entries in the IoStore library
    (shader_library.shader_entries as? ArrayList)?.ensureCapacity(io_store_shader_library.header.shader_entries.size)
    for (shader_index in 0 until io_store_shader_library.header.shader_entries.size) {
        val shader_frequency = io_store_shader_library.header.shader_entries[shader_index].shader_frequency()
        val uncompressed_shader_code_size = io_store_shader_library.shaders_code[shader_index].size
        val (shader_code_offset, compressed_shader_code_size, _) = shader_code.shader_regions[shader_index]

        shader_library.shader_entries.add(
            FShaderCodeEntry(
                offset = shader_code_offset.toULong(),
                size = compressed_shader_code_size.toUInt(),
                uncompressed_size = uncompressed_shader_code_size.toUInt(),
                frequency = shader_frequency
            )
        )
    }

    // Copy the shader indices, since we are not changing what shaders belong to which shader maps
    shader_library.shader_indices = io_store_shader_library.header.shader_indices.toMutableList()

    // Create shader map entries from IoStore shader map entries. They need minimal changes other than writing preload dependencies
    (shader_library.shader_map_entries as? ArrayList)?.ensureCapacity(io_store_shader_library.header.shader_map_entries.size)
    for (shader_map_index in 0 until io_store_shader_library.header.shader_map_entries.size) {
        val shader_map_entry = io_store_shader_library.header.shader_map_entries[shader_map_index]

        val first_preload_dependency_index = shader_library.preload_entries.size
        var num_preload_dependencies: Int = 0

        // If we have any unique shaders for this shader map, write them all in one preload entry
        val (shader_map_start_offset, shader_map_size) = shader_code.shader_map_regions[shader_map_index]
        if (shader_map_size > 0) {
            shader_library.preload_entries.add(
                FFileCachePreloadEntry(
                    offset = shader_map_start_offset,
                    size = shader_map_size.toLong()
                )
            )
            num_preload_dependencies += 1
        }

        // Write preload entries for any shared shaders referenced by this shader map
        for (i in 0 until shader_map_entry.num_shaders.toInt()) {
            val shader_indices_index = (shader_map_entry.shader_indices_offset + i.toUInt()).toInt()
            val shader_index = io_store_shader_library.header.shader_indices[shader_indices_index].toInt()
            val (shader_start_offset, shader_compressed_size, is_shader_unique) = shader_code.shader_regions[shader_index]

            if (!is_shader_unique) {
                shader_library.preload_entries.add(
                    FFileCachePreloadEntry(
                        offset = shader_start_offset,
                        size = shader_compressed_size.toLong()
                    )
                )
                num_preload_dependencies += 1
            }
        }

        shader_library.shader_map_entries.add(
            FShaderMapEntry(
                shader_indices_offset = shader_map_entry.shader_indices_offset,
                num_shaders = shader_map_entry.num_shaders,
                first_preload_index = first_preload_dependency_index.toUInt(),
                num_preload_entries = num_preload_dependencies.toUInt()
            )
        )
    }

    // Serialize shader library now by serializing the header and then appending the shader code after it
    val result_shader_library_buffer = ByteArrayOutputStream()
    // UE4.24 and above is 2, name or what changed unknown
    val shader_library_version_loose = 2
    // ByteBuffer LE verification
    val bbVer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(shader_library_version_loose).array()
    check(ByteBuffer.wrap(bbVer).order(ByteOrder.LITTLE_ENDIAN).int == shader_library_version_loose)
    result_shader_library_buffer.write_i32_le(shader_library_version_loose)
    // Serialize shader library header
    shader_library.serialize(result_shader_library_buffer)
    // Serialize shader code
    result_shader_library_buffer.write(shader_code.shader_code_buffer)

    // Resolve asset names referencing the shader maps contained in this library
    val contained_shader_map_hashes: Set<FSHAHash> = shader_library.shader_map_hashes.toSet()
    val (total_package_references, shader_asset_info) = build_shader_asset_metadata_from_io_store_packages(store_access, contained_shader_map_hashes, log)
    val result_shader_asset_metadata_buffer = serialize_shader_asset_info_to_json(shader_asset_info)

    // Print shader library statistics to stdout if allowed
    val compression_ratio = if (shader_code.shader_code_buffer.isNotEmpty()) Math.round((io_store_shader_library.total_shader_code_size.toDouble() / shader_code.shader_code_buffer.size.toDouble()) * 100.0) else 0L
    try {
        info(log, "Shader Library $library_name statistics: Shared Shaders: ${shader_code.total_shared_shaders}; Unique Shaders: ${shader_code.total_unique_shaders}; Detached Shaders: ${shader_code.total_detached_shaders}; Shader Maps: ${shader_library.shader_map_entries.size} (referenced by $total_package_references packages), Uncompressed Size: ${io_store_shader_library.total_shader_code_size / 1024 / 1024}MB, Compressed Size: ${shader_code.shader_code_buffer.size / 1024 / 1024}MB, Compression Ratio: $compression_ratio%")
    } catch (_: Exception) {}
    return kotlin.Pair(result_shader_library_buffer.toByteArray(), result_shader_asset_metadata_buffer)
}

private fun serialize_shader_asset_info_to_json(info: ShaderAssetInfoFileRoot): ByteArray {
    // Rust serde_json::to_vec_pretty
    val sb = StringBuilder()
    sb.append("{\n")
    sb.append("  \"ShaderCodeToAssets\": [\n")
    for ((idx, entry) in info.shader_code_to_assets.withIndex()) {
        sb.append("    {\n")
        sb.append("      \"ShaderMapHash\": \"${entry.shader_map_hash.bytes.joinToString("") { "%02x".format(it) }}\",\n")
        sb.append("      \"Assets\": [")
        for ((j, pkg) in entry.package_names.withIndex()) {
            sb.append("\"${escape_json_string(pkg)}\"")
            if (j < entry.package_names.size - 1) sb.append(", ")
        }
        sb.append("]\n")
        sb.append("    }")
        if (idx < info.shader_code_to_assets.size - 1) sb.append(",")
        sb.append("\n")
    }
    sb.append("  ]\n")
    sb.append("}\n")
    return sb.toString().toByteArray(Charsets.UTF_8)
}

private fun escape_json_string(s: String): String {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:819 is_raytracing_shader_frequency
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:819
fun is_raytracing_shader_frequency(shader_frequency: UByte): Boolean {
    return shader_frequency == EShaderFrequency.RayGen.value ||
        shader_frequency == EShaderFrequency.RayMiss.value ||
        shader_frequency == EShaderFrequency.RayHitGroup.value ||
        shader_frequency == EShaderFrequency.RayCallable.value
}

// Overload for Int/UByte compat
fun is_raytracing_shader_frequency(shader_frequency: Int): Boolean = is_raytracing_shader_frequency(shader_frequency.toUByte())
fun is_raytracing_shader_frequency(shader_frequency: Byte): Boolean = is_raytracing_shader_frequency(shader_frequency.toUByte())

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:823 build_io_store_shader_code_archive_header
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:823
fun build_io_store_shader_code_archive_header(
    shader_library: FShaderLibraryHeader,
    shader_format_name: String,
    container_version: EIoStoreTocVersion,
    max_uncompressed_shader_group_size: Int
): FIoStoreShaderCodeArchiveHeader {
    val shader_to_referencing_shader_maps: TreeMap<Int, MutableList<Int>> = TreeMap()

    // Figure out which shader maps each shader belongs to
    for (shader_map_index in 0 until shader_library.shader_map_hashes.size) {
        val shader_map_entry = shader_library.shader_map_entries[shader_map_index]
        for (i in 0 until shader_map_entry.num_shaders.toInt()) {
            val shader_indices_index = (shader_map_entry.shader_indices_offset + i.toUInt()).toInt()
            val shader_index = shader_library.shader_indices[shader_indices_index].toInt()
            shader_to_referencing_shader_maps.getOrPut(shader_index) { mutableListOf() }.add(shader_map_index)
        }
    }

    // Sort shader map indices in natural order to keep them deterministic
    for (shader_index in 0 until shader_library.shader_hashes.size) {
        shader_to_referencing_shader_maps.getOrPut(shader_index) { mutableListOf() }.sort()
    }

    // Sort shader indices by the number of shader maps that reference them, and then by shader map IDs, and then by shader indices for determinism
    val sorted_shader_indices: MutableList<Int> = shader_to_referencing_shader_maps.keys.toMutableList()
    sorted_shader_indices.sortWith { a, b ->
        val shader_maps_a = shader_to_referencing_shader_maps[a]!!
        val shader_maps_b = shader_to_referencing_shader_maps[b]!!
        if (shader_maps_a.size != shader_maps_b.size) {
            shader_maps_a.size.compareTo(shader_maps_b.size)
        } else {
            // Lexicographic compare of lists
            var cmp = 0
            val minLen = min(shader_maps_a.size, shader_maps_b.size)
            for (i in 0 until minLen) {
                cmp = shader_maps_a[i].compareTo(shader_maps_b[i])
                if (cmp != 0) break
            }
            if (cmp != 0) cmp else a.compareTo(b)
        }
    }

    // Split into streaks of shaders that are referenced by the same set of shader maps
    val shader_groups: MutableList<MutableList<Int>> = mutableListOf()
    var current_shader_group: MutableList<Int> = mutableListOf()
    var last_shader_map_set_seen: List<Int> = emptyList()

    for (shader_index in sorted_shader_indices) {
        val referencing_shader_maps = shader_to_referencing_shader_maps[shader_index]!!
        if (current_shader_group.isEmpty()) {
            current_shader_group.add(shader_index)
            last_shader_map_set_seen = referencing_shader_maps.toList()
        } else if (last_shader_map_set_seen != referencing_shader_maps) {
            shader_groups.add(current_shader_group)
            current_shader_group = mutableListOf(shader_index)
            last_shader_map_set_seen = referencing_shader_maps.toList()
        } else {
            current_shader_group.add(shader_index)
        }
    }
    if (current_shader_group.isNotEmpty()) {
        shader_groups.add(current_shader_group)
    }

    // Split each shader group into non-raytracing and raytracing shaders if requested
    var mutable_shader_groups: MutableList<MutableList<Int>> = shader_groups
    val separate_raytracing_shaders = shader_format_name == "PCD3D_SM5"
    if (separate_raytracing_shaders) {
        val new_groups: MutableList<MutableList<Int>> = mutableListOf()
        for (x in mutable_shader_groups) {
            val non_raytracing_shaders: MutableList<Int> = mutableListOf()
            val raytracing_shaders: MutableList<Int> = mutableListOf()
            for (shader_index in x) {
                val shader_frequency = shader_library.shader_entries[shader_index].frequency
                if (is_raytracing_shader_frequency(shader_frequency)) {
                    raytracing_shaders.add(shader_index)
                } else {
                    non_raytracing_shaders.add(shader_index)
                }
            }
            if (non_raytracing_shaders.isNotEmpty() && raytracing_shaders.isNotEmpty()) {
                new_groups.add(non_raytracing_shaders)
                new_groups.add(raytracing_shaders)
            } else {
                new_groups.add(x)
            }
        }
        mutable_shader_groups = new_groups
    }

    // Utility function to sort shader indices
    fun sort_shaders_ascending(shader_index_a: Int, shader_index_b: Int): Int {
        val shader_code_entry_a = shader_library.shader_entries[shader_index_a]
        val shader_code_entry_b = shader_library.shader_entries[shader_index_b]
        var cmp = shader_code_entry_a.uncompressed_size.compareTo(shader_code_entry_b.uncompressed_size)
        if (cmp != 0) return cmp
        cmp = shader_code_entry_a.size.compareTo(shader_code_entry_b.size)
        if (cmp != 0) return cmp
        cmp = shader_code_entry_a.frequency.compareTo(shader_code_entry_b.frequency)
        if (cmp != 0) return cmp
        return shader_code_entry_a.offset.compareTo(shader_code_entry_b.offset)
    }

    // Now, split the shader groups by their size, ensuring that no group is larger than maximum group size
    val final_groups: MutableList<MutableList<Int>> = mutableListOf()
    for (x in mutable_shader_groups) {
        var group_size: Int = 0
        for (shader_index in x) {
            group_size += shader_library.shader_entries[shader_index].uncompressed_size.toInt()
        }
        if (group_size <= max_uncompressed_shader_group_size || x.size == 1) {
            final_groups.add(x)
            continue
        }
        val num_new_groups = min(group_size / max_uncompressed_shader_group_size + 1, x.size)
        val sorted_shaders = x.toMutableList()
        sorted_shaders.sortWith { a, b -> sort_shaders_ascending(b, a) } // descending
        val new_shader_groups: MutableList<MutableList<Int>> = MutableList(num_new_groups) { mutableListOf() }
        val new_shader_group_sizes: MutableList<Int> = MutableList(num_new_groups) { 0 }
        for (shader_index in sorted_shaders) {
            var smallest_new_group_index = 0
            for (new_shader_group_index in 1 until num_new_groups) {
                if (new_shader_group_sizes[new_shader_group_index] < new_shader_group_sizes[smallest_new_group_index]) {
                    smallest_new_group_index = new_shader_group_index
                }
            }
            val shader_size = shader_library.shader_entries[shader_index].uncompressed_size.toInt()
            new_shader_groups[smallest_new_group_index].add(shader_index)
            new_shader_group_sizes[smallest_new_group_index] += shader_size
        }
        final_groups.addAll(new_shader_groups)
    }
    mutable_shader_groups = final_groups

    // Final step, sort shaders in each shader group ascending
    for (shader_group in mutable_shader_groups) {
        shader_group.sortWith { a, b -> sort_shaders_ascending(a, b) }
    }

    // Convert shader map indices without touching their indices offsets, since we copy the original offsets there is no reason to change them
    val shader_map_entries: MutableList<FIoStoreShaderMapEntry> = shader_library.shader_map_entries.map {
        FIoStoreShaderMapEntry(shader_indices_offset = it.shader_indices_offset, num_shaders = it.num_shaders)
    }.toMutableList()

    // We have the resulting shader group contents, we can create the IO store shader library header now
    val io_store_library_header = FIoStoreShaderCodeArchiveHeader(
        shader_map_hashes = shader_library.shader_map_hashes.toMutableList(),
        shader_hashes = shader_library.shader_hashes.toMutableList(),
        shader_group_chunk_ids = mutableListOf(),
        shader_map_entries = shader_map_entries,
        shader_entries = MutableList(shader_library.shader_hashes.size) { FIoStoreShaderCodeEntry.default() },
        shader_group_entries = mutableListOf(),
        shader_indices = shader_library.shader_indices.toMutableList()
    )

    // Finds an existing index sequence in shader indices matching the provided array, or adds a new one
    fun find_or_add_sequence_in_shader_indices(library_header: FIoStoreShaderCodeArchiveHeader, indices: List<Int>): Int {
        if (indices.isEmpty()) return 0
        val first_new_shader_index = indices[0]
        val max_indices_index = library_header.shader_indices.size - indices.size + 1
        if (max_indices_index >= 0) {
            for (indices_index in 0 until max_indices_index) {
                if (library_header.shader_indices[indices_index].toInt() != first_new_shader_index) continue
                var found_rest = true
                for (k in 1 until indices.size) {
                    if (library_header.shader_indices[indices_index + k].toInt() != indices[k]) {
                        found_rest = false
                        break
                    }
                }
                if (!found_rest) continue
                return indices_index
            }
        }
        val new_indices_index = library_header.shader_indices.size
        for (shader_index in indices) {
            library_header.shader_indices.add(shader_index.toUInt())
        }
        return new_indices_index
    }

    // Build resulting shader group entries and their chunk IDs
    (io_store_library_header.shader_group_entries as? ArrayList)?.ensureCapacity(mutable_shader_groups.size)
    (io_store_library_header.shader_group_chunk_ids as? ArrayList)?.ensureCapacity(mutable_shader_groups.size)

    for ((shader_group_index, shader_group) in mutable_shader_groups.withIndex()) {
        val hasher = MessageDigest.getInstance("SHA-1")
        var uncompressed_group_size: Int = 0

        // Populate shader entries and calculate the resulting hash of the group
        for (shader_index in shader_group) {
            val shader_code_entry = shader_library.shader_entries[shader_index]
            val shader_hash = shader_library.shader_hashes[shader_index]
            io_store_library_header.shader_entries[shader_index] = FIoStoreShaderCodeEntry.new(shader_group_index, uncompressed_group_size, shader_code_entry.frequency)

            hasher.update(shader_hash.bytes)
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(shader_code_entry.uncompressed_size.toInt()).array()
            check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == shader_code_entry.uncompressed_size)
            hasher.update(bb)
            uncompressed_group_size += shader_code_entry.uncompressed_size.toInt()
        }
        // Add the name of the shader library format into the group hash
        hasher.update(shader_format_name.toByteArray(Charsets.UTF_8))
        val shader_hash_bytes = hasher.digest()
        val shader_hash = FSHAHash(shader_hash_bytes)

        // Store shader indices into the global array, or find an existing entry
        val group_indices_offset = find_or_add_sequence_in_shader_indices(io_store_library_header, shader_group)

        // Prime uncompressed size, but leave compressed size as zero. it will be written later
        io_store_library_header.shader_group_entries.add(
            FIoStoreShaderGroupEntry(
                shader_indices_offset = group_indices_offset.toUInt(),
                num_shaders = shader_group.size.toUInt(),
                uncompressed_size = uncompressed_group_size.toUInt(),
                compressed_size = 0u
            )
        )
        io_store_library_header.shader_group_chunk_ids.add(
            FIoChunkId.create_shader_code_chunk_id(shader_hash).with_version(container_version).get_raw()
        )
    }
    return io_store_library_header
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:1062 read_shader_asset_info
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:1062
fun read_shader_asset_info(
    shader_asset_metadata_buffer: ByteArray,
    package_name_to_shader_maps: MutableMap<String, MutableList<FSHAHash>>
) {
    val text = shader_asset_metadata_buffer.toString(Charsets.UTF_8).trim()
    if (text.isEmpty() || text == "{}" || text == "null") return
    // Very simple JSON parse: extract ShaderCodeToAssets array then each object
    // Look for "ShaderMapHash": "hex", "Assets": [...]
    // Use regex-like manual scanning to avoid dependencies
    try {
        val shader_code_to_assets_idx = text.indexOf("ShaderCodeToAssets")
        if (shader_code_to_assets_idx < 0) return
        val array_start = text.indexOf('[', shader_code_to_assets_idx)
        val array_end = text.lastIndexOf(']')
        if (array_start < 0 || array_end < 0 || array_end <= array_start) return
        val array_content = text.substring(array_start + 1, array_end)
        // Split objects by "ShaderMapHash"
        var pos = 0
        while (true) {
            val hash_key_idx = array_content.indexOf("ShaderMapHash", pos)
            if (hash_key_idx < 0) break
            val colon_idx = array_content.indexOf(':', hash_key_idx)
            val first_quote = array_content.indexOf('"', colon_idx)
            val second_quote = array_content.indexOf('"', first_quote + 1)
            if (first_quote < 0 || second_quote < 0) break
            val hex = array_content.substring(first_quote + 1, second_quote)
            val hash_bytes = hex_string_to_bytes(hex)
            val fs = FSHAHash(hash_bytes)
            // Find Assets
            val assets_key_idx = array_content.indexOf("Assets", second_quote)
            if (assets_key_idx < 0) break
            val bracket_open = array_content.indexOf('[', assets_key_idx)
            val bracket_close = array_content.indexOf(']', bracket_open)
            if (bracket_open < 0 || bracket_close < 0) break
            val assets_content = array_content.substring(bracket_open + 1, bracket_close)
            val assets = mutableListOf<String>()
            var ap = 0
            while (true) {
                val q1 = assets_content.indexOf('"', ap)
                if (q1 < 0) break
                val q2 = assets_content.indexOf('"', q1 + 1)
                if (q2 < 0) break
                // handle escaped quotes: simple - assume no escaped inside package names except \/?
                var raw = assets_content.substring(q1 + 1, q2)
                // Unescape json escapes for string
                raw = raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                assets.add(raw)
                ap = q2 + 1
            }
            for (pkg in assets) {
                package_name_to_shader_maps.getOrPut(pkg) { mutableListOf() }.add(fs)
            }
            pos = bracket_close + 1
        }
    } catch (e: Exception) {
        throw IllegalStateException("Failed to parse shader asset info JSON: ${e.message}", e)
    }
}

private fun hex_string_to_bytes(hex: String): ByteArray {
    val clean = hex.trim().removePrefix("0x").removePrefix("0X")
    require(clean.length % 2 == 0) { "hex string length must be even: $hex" }
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val byteStr = clean.substring(i * 2, i * 2 + 2)
        out[i] = byteStr.toInt(16).toByte()
    }
    require(out.size == 20) { "FSHAHash must be 20 bytes, got ${out.size}" }
    return out
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:1073 write_io_store_library
// ---------------------------------------------------------------------------
// Rust: retoc/src/shader_library.rs:1073
fun write_io_store_library(
    store_writer: IoStoreWriter,
    raw_shader_library_buffer: ByteArray,
    shader_library_path: UEPath,
    log: Log
) {
    if (raw_shader_library_buffer.isEmpty()) {
        throw IllegalArgumentException("raw_shader_library_buffer empty")
    }
    val shader_library_reader = SeekableByteArrayInputStream(raw_shader_library_buffer)

    // Read shader library header
    val shader_library_version_loose: Int = shader_library_reader.read_i32_le()
    val bbVer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(shader_library_version_loose).array()
    check(ByteBuffer.wrap(bbVer).order(ByteOrder.LITTLE_ENDIAN).int == shader_library_version_loose)
    if (shader_library_version_loose != 2) {
        throw IllegalArgumentException("Unknown shader library file version $shader_library_version_loose. Current version is 2")
    }
    val shader_library_header = FShaderLibraryHeader.deserialize_static(shader_library_reader)
    // Shader library code offsets are relative to the end of the shader library header
    val shader_library_code_start_offset = shader_library_reader.position()
    val total_library_compressed_size = raw_shader_library_buffer.size - shader_library_code_start_offset.toInt()

    // Figure out the name of the format of this shader library
    val library_filename = try {
        val p = Path.of(shader_library_path)
        p.fileName?.toString()?.substringBeforeLast('.') ?: shader_library_path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    } catch (_: Exception) {
        shader_library_path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
    }
    val shader_library_name_separator_index = library_filename.indexOf('-')
    if (shader_library_name_separator_index < 0) {
        throw IllegalArgumentException("Failed to derive shader library name from shader library filename")
    }
    val shader_format_separator_index = library_filename.lastIndexOf('-')
    if (shader_format_separator_index < 0) {
        throw IllegalArgumentException("Failed to derive format name from shader library filename")
    }

    // Note that this splitting logic is actually wrong, shader format will end up being part of the library name, ahd shader format will end up being a name of the shader platform
    // However, we have to follow the wrong logic in UnrealPak to get matching shader chunk IDs. If it ever gets fixed in UE, this logic will need to be changed.
    // Shader library names are like this: ShaderArchive-Global-PCD3D_SM6-PCD3D_SM6.ushaderbytecode
    // Logic above gives "Global-PCD3D_SM6" as library name and "PCD3D_SM6" as shader format.
    // However, actual library name is "Global", actual shader format name is "PCD3D" and shader platform is "PCD3D_SM6"
    val shader_library_name = library_filename.substring(shader_library_name_separator_index + 1, shader_format_separator_index)
    val shader_format_name = library_filename.substring(shader_format_separator_index + 1)

    // Create IoStore shader library header to split shaders into groups
    val max_shader_group_size = 1024 * 1024 // from UE source, can be adjusted per game using r.ShaderCodeLibrary.MaxShaderGroupSize CVar
    var io_store_library_header = build_io_store_shader_code_archive_header(shader_library_header, shader_format_name, store_writer.container_version(), max_shader_group_size)

    // Cached compression method for this shader library
    var compression_method: CompressionMethod? = null
    var total_compressed_groups_size: Int = 0
    var total_library_uncompressed_size: Int = 0

    // Create shader chunks for each shader group from the library
    for (shader_group_index in 0 until io_store_library_header.shader_group_entries.size) {
        val shader_group_entry = io_store_library_header.shader_group_entries[shader_group_index]
        val shader_group_chunk_buffer = ByteArrayOutputStream()

        // Read and decompress each individual shader contained within this group
        for (i in 0 until shader_group_entry.num_shaders.toInt()) {
            val shader_indices_index = (shader_group_entry.shader_indices_offset + i.toUInt()).toInt()
            val shader_index = io_store_library_header.shader_indices[shader_indices_index].toInt()

            // Resolve the offset of the shader code into the shader library and seek there
            val shader_code_entry = shader_library_header.shader_entries[shader_index]
            val shader_code_offset = shader_library_code_start_offset + shader_code_entry.offset.toLong()
            shader_library_reader.seek(shader_code_offset)

            // Read the shader code into the temporary buffer
            val uncompressed_size = shader_code_entry.size.toInt()
            val uncompressed_shader_code_buf = ByteArray(uncompressed_size)
            shader_library_reader.read_exact(uncompressed_shader_code_buf)

            var uncompressed_shader_code = uncompressed_shader_code_buf

            // Decompress the shader code if it's size is actually different from the uncompressed size
            if (shader_code_entry.size != shader_code_entry.uncompressed_size) {
                val holder = arrayOf(compression_method)
                uncompressed_shader_code = decompress_shader_code(uncompressed_shader_code, holder, shader_code_entry.uncompressed_size.toInt())
                compression_method = holder[0]
            }

            // Write the shader code into the uncompressed chunk buffer. Make sure shader code offset matches it's actual placement
            val expected_shader_code_offset = io_store_library_header.shader_entries[shader_index].shader_uncompressed_offset_in_group()
            val actual_shader_code_offset = shader_group_chunk_buffer.size()
            if (actual_shader_code_offset != expected_shader_code_offset) {
                throw IllegalStateException("Shader code placement inside of the group did not match it's expected placement from the library header. Expected shader code to be at offset $expected_shader_code_offset, but it's actual placement is at offset $actual_shader_code_offset")
            }
            shader_group_chunk_buffer.write(uncompressed_shader_code)
            total_library_uncompressed_size += shader_code_entry.uncompressed_size.toInt()
        }

        // Make sure the uncompressed size matches the expected size written into the header
        val actual_uncompressed_group_size = shader_group_chunk_buffer.size()
        val expected_uncompressed_group_size = shader_group_entry.uncompressed_size.toInt()
        if (actual_uncompressed_group_size != expected_uncompressed_group_size) {
            throw IllegalStateException("Expected uncompressed group size to be $expected_uncompressed_group_size as written into the header, but after the actual shader code placement uncompressed size was $actual_uncompressed_group_size")
        }

        // If we know the compression method for shaders, compress this groups content with it
        var final_group_buffer = shader_group_chunk_buffer.toByteArray()
        if (compression_method != null) {
            val compressed_group_data = compress_shader(final_group_buffer, compression_method)
            if (compressed_group_data.size < actual_uncompressed_group_size) {
                final_group_buffer = compressed_group_data
            }
        }

        // Now that we know compressed group size, write it into the shader group header
        val compressed_group_size = final_group_buffer.size
        io_store_library_header.shader_group_entries[shader_group_index].compressed_size = compressed_group_size.toUInt()
        val shader_group_chunk_id = io_store_library_header.shader_group_chunk_ids[shader_group_index]

        // Write the shader code chunk for this group into the container. Note that shader chunks do not have filenames
        store_writer.write_chunk_raw(shader_group_chunk_id, null, final_group_buffer)
        total_compressed_groups_size += compressed_group_size
    }

    // Create a new chunk for the shader library header
    val io_store_shader_library_buffer = ByteArrayOutputStream()
    // Write the version and then the contents of the shader code archive
    val io_store_library_version = EIoStoreShaderLibraryVersion.Initial
    val io_store_library_version_raw: UInt = io_store_library_version.value
    val bbRaw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(io_store_library_version_raw.toInt()).array()
    check(ByteBuffer.wrap(bbRaw).order(ByteOrder.LITTLE_ENDIAN).int.toUInt() == io_store_library_version_raw)
    io_store_shader_library_buffer.write_u32_le(io_store_library_version_raw)
    FIoStoreShaderCodeArchiveHeader.serialize_static(io_store_library_header, io_store_shader_library_buffer, io_store_library_version)

    // Write the shader library header chunk using the provided filename
    val shader_library_chunk_id = FIoChunkId.create_shader_library_chunk_id(shader_library_name, shader_format_name)
    store_writer.write_chunk(shader_library_chunk_id, shader_library_path, io_store_shader_library_buffer.toByteArray())

    // Write statistics
    val recompression_ratio = if (total_compressed_groups_size != 0) Math.round((total_library_compressed_size.toDouble() / total_compressed_groups_size.toDouble()) * 100.0) else 0L
    val compression_ratio = if (total_compressed_groups_size != 0) Math.round((total_library_uncompressed_size.toDouble() / total_compressed_groups_size.toDouble()) * 100.0) else 0L
    try {
        val file_stem = try { Path.of(shader_library_path).fileName?.toString()?.substringBeforeLast('.') ?: shader_library_path } catch (_: Exception) { shader_library_path }
        info(log, "Shader Library $file_stem statistics: Shader Groups: ${io_store_library_header.shader_group_entries.size}, Shader Maps: ${io_store_library_header.shader_map_entries.size}, Uncompressed Size: ${total_library_uncompressed_size / 1024 / 1024}MB, Original Compressed Size: ${total_library_compressed_size / 1024 / 1024}MB, Total Group Compressed Size: ${total_compressed_groups_size / 1024 / 1024}MB, Recompression Ratio: $recompression_ratio%, Total Compression Ratio: $compression_ratio%")
    } catch (_: Exception) {}
}

