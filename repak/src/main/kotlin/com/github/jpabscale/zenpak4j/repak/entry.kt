// Rust: repak/src/entry.rs:1
package com.github.jpabscale.zenpak4j.repak

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import com.qyntrax.unzstd.ZstdDecompressor
import net.jpountz.lz4.LZ4Factory
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// Rust: repak/src/entry.rs:10
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
enum class EntryLocation {
    Data,
    Index
}

// Rust: repak/src/entry.rs:16
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
data class Block(
    var start: ULong,
    var end: ULong
) {
    companion object {
        // Rust: repak/src/entry.rs:22 Block::read
        fun read(reader: InputStream): Block {
            return Block(
                start = reader.read_u64_le(),
                end = reader.read_u64_le()
            )
        }
    }

    // Rust: repak/src/entry.rs:29 Block::write
    fun write(writer: OutputStream) {
        writer.write_u64_le(this.start)
        writer.write_u64_le(this.end)
    }
}

// Rust: repak/src/entry.rs:36 align
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun align(offset: ULong): ULong {
    return (offset + 15u) and 15u.toULong().inv()
}

// Rust: repak/src/entry.rs:41 compression_index_size
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
enum class CompressionIndexSize {
    U8,
    U32
}

//@parity:on EXC-005
// Rust: repak/src/entry.rs:41 compression_index_size
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun compression_index_size(version: Version, context: RepakContext? = null): CompressionIndexSize {
    val gid = context?.get_game_id(null) ?: get_game_id(null)
    return when (gid) {
        GAME_ID_VISIONS_OF_MANA -> CompressionIndexSize.U8
        else -> when (version) {
            Version.V8A -> CompressionIndexSize.U8
            else -> CompressionIndexSize.U32
        }
    }
}
//@parity:off EXC-005

// Rust: repak/src/entry.rs:57 Entry
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
data class Entry(
    var offset: ULong,
    var compressed: ULong,
    var uncompressed: ULong,
    var compression_slot: UInt?,
    var timestamp: ULong?,
    var hash: ByteArray?,
    var blocks: List<Block>?,
    var flags: UByte,
    var compression_block_size: UInt
) {
    // Rust: repak/src/entry.rs:71 is_encrypted
    fun is_encrypted(): Boolean = (this.flags.toInt() and 1) != 0

    // Rust: repak/src/entry.rs:74 is_deleted
    fun is_deleted(): Boolean = ((this.flags.toInt() shr 1) and 1) != 0

    companion object {
        // Rust: repak/src/entry.rs:77 get_serialized_size
        @Suppress("FunctionName", "PropertyName", "VariableNaming")
        fun get_serialized_size(
            version: Version,
            compression: UInt?,
            block_count: UInt,
            context: RepakContext? = null
        ): ULong {
            var size = 0uL
            size += 8u // offset
            size += 8u // compressed
            size += 8u // uncompressed
            size += when (compression_index_size(version, context)) {
                CompressionIndexSize.U8 -> 1u
                CompressionIndexSize.U32 -> 4u
            }
            size += if (version.version_major() == VersionMajor.Initial) 8u else 0u
            size += 20u // hash
            size += if (compression != null) 4u + (8u + 8u) * block_count.toULong() else 0u
            size += 1u // encrypted
            size += if (version.version_major() >= VersionMajor.CompressionEncryption) 4u else 0u
            return size
        }

        // Rust: repak/src/entry.rs:77 get_serialized_size overload without context for compat
        fun get_serialized_size(
            version: Version,
            compression: UInt?,
            block_count: UInt
        ): ULong = get_serialized_size(version, compression, block_count, null)

        // Rust: repak/src/entry.rs:107 write_file
        @Suppress("FunctionName", "PropertyName", "VariableNaming")
        fun write_file(
            channel: SeekableByteChannel,
            version: Version,
            compression_slots: MutableList<Compression?>,
            allowed_compression: List<Compression>,
            data: ByteArray,
            context: RepakContext? = null
        ): Entry {
            val partial_entry = build_partial_entry(allowed_compression, data)
            val stream_position = channel.position().toULong()
            val entry = partial_entry.build_entry(version, compression_slots, stream_position, context)
            val out = Channels.newOutputStream(channel)
            entry.write(out, version, EntryLocation.Data, context)
            partial_entry.write_data(out)
            return entry
        }

        // Rust: repak/src/entry.rs:107 write_file overload for OutputStream with position
        fun write_file(
            writer: OutputStream,
            position: ULong,
            version: Version,
            compression_slots: MutableList<Compression?>,
            allowed_compression: List<Compression>,
            data: ByteArray,
            context: RepakContext? = null
        ): Entry {
            val partial_entry = build_partial_entry(allowed_compression, data)
            val entry = partial_entry.build_entry(version, compression_slots, position, context)
            entry.write(writer, version, EntryLocation.Data, context)
            partial_entry.write_data(writer)
            return entry
        }

        // Rust: repak/src/entry.rs:122 read
        @Suppress("FunctionName", "PropertyName", "VariableNaming")
        fun read(
            reader: InputStream,
            version: Version,
            context: RepakContext? = null
        ): Entry {
            val ver = version.version_major()
            val offset = reader.read_u64_le()
            val compressed = reader.read_u64_le()
            val uncompressed = reader.read_u64_le()
            val raw_compression: UInt = when (compression_index_size(version, context)) {
                CompressionIndexSize.U8 -> reader.read_u8().toUInt()
                CompressionIndexSize.U32 -> reader.read_u32_le()
            }
            val compression: UInt? = if (raw_compression == 0u) null else raw_compression - 1u
            val timestamp = (ver == VersionMajor.Initial).then_try { reader.read_u64_le() }
            val hash = reader.read_guid()
            val blocks = (ver >= VersionMajor.CompressionEncryption && compression != null)
                .then_try { reader.read_array { Block.read(it) } }
            val flags = (ver >= VersionMajor.CompressionEncryption)
                .then_try { reader.read_u8() } ?: 0u
            val compression_block_size = (ver >= VersionMajor.CompressionEncryption)
                .then_try { reader.read_u32_le() } ?: 0u
            return Entry(
                offset = offset,
                compressed = compressed,
                uncompressed = uncompressed,
                compression_slot = compression,
                timestamp = timestamp,
                hash = hash,
                blocks = blocks,
                flags = flags,
                compression_block_size = compression_block_size
            )
        }

        // Rust: repak/src/entry.rs:200 read_encoded
        @Suppress("FunctionName", "PropertyName", "VariableNaming")
        fun read_encoded(
            reader: InputStream,
            version: Version,
            context: RepakContext? = null
        ): Entry {
            val bits = reader.read_u32_le()
            val compression = when (val v = (bits shr 23) and 0x3Fu) {
                0u -> null
                else -> v - 1u
            }
            val encrypted = (bits and (1u shl 22)) != 0u
            val compression_block_count: UInt = (bits shr 6) and 0xFFFFu
            var compression_block_size: UInt = bits and 0x3Fu

            if (compression_block_size == 0x3Fu) {
                compression_block_size = reader.read_u32_le()
            } else {
                compression_block_size = compression_block_size shl 11
            }

            fun var_int(bit: Int): ULong {
                return if ((bits and (1u shl bit)) != 0u) {
                    reader.read_u32_le().toULong()
                } else {
                    reader.read_u64_le()
                }
            }

            val offset = var_int(31)
            val uncompressed = var_int(30)
            val compressed = when (compression) {
                null -> uncompressed
                else -> var_int(29)
            }

            val offset_base = get_serialized_size(version, compression, compression_block_count, context)

            val blocks: List<Block>? = if (compression_block_count == 1u && !encrypted) {
                listOf(Block(start = offset_base, end = offset_base + compressed))
            } else if (compression_block_count > 0u) {
                var index = offset_base
                val list = mutableListOf<Block>()
                for (i in 0 until compression_block_count.toInt()) {
                    var block_size = reader.read_u32_le().toULong()
                    val block = Block(start = index, end = index + block_size)
                    if (encrypted) {
                        block_size = align(block_size)
                    }
                    index += block_size
                    list.add(block)
                }
                list
            } else {
                null
            }

            return Entry(
                offset = offset,
                compressed = compressed,
                uncompressed = uncompressed,
                timestamp = null,
                compression_slot = compression,
                hash = null,
                blocks = blocks,
                flags = (if (encrypted) 1u else 0u).toUByte(),
                compression_block_size = compression_block_size
            )
        }
    }

    // Rust: repak/src/entry.rs:160 write
    @Suppress("FunctionName", "PropertyName", "VariableNaming")
    fun write(
        writer: OutputStream,
        version: Version,
        location: EntryLocation,
        context: RepakContext? = null
    ) {
        writer.write_u64_le(
            when (location) {
                EntryLocation.Data -> 0uL
                EntryLocation.Index -> this.offset
            }
        )
        writer.write_u64_le(this.compressed)
        writer.write_u64_le(this.uncompressed)
        val compression = this.compression_slot?.let { it + 1u } ?: 0u
        when (compression_index_size(version, context)) {
            CompressionIndexSize.U8 -> writer.write_u8(compression.toUByte())
            CompressionIndexSize.U32 -> writer.write_u32_le(compression)
        }
        if (version.version_major() == VersionMajor.Initial) {
            writer.write_u64_le(this.timestamp ?: 0uL)
        }
        val h = this.hash
        if (h != null) {
            writer.write(h)
        } else {
            throw IllegalStateException("hash missing")
        }
        if (version.version_major() >= VersionMajor.CompressionEncryption) {
            if (this.blocks != null) {
                writer.write_u32_le(this.blocks!!.size.toUInt())
                for (block in this.blocks!!) {
                    block.write(writer)
                }
            }
            writer.write_u8(this.flags)
            writer.write_u32_le(this.compression_block_size)
        }
    }

    // Rust: repak/src/entry.rs:160 write overload without context
    fun write(
        writer: OutputStream,
        version: Version,
        location: EntryLocation
    ) = write(writer, version, location, null)

    // Rust: repak/src/entry.rs:277 write_encoded
    @Suppress("FunctionName", "PropertyName", "VariableNaming")
    fun write_encoded(writer: OutputStream) {
        var compression_block_size = (this.compression_block_size shr 11) and 0x3Fu
        if ((compression_block_size shl 11) != this.compression_block_size) {
            compression_block_size = 0x3Fu
        }
        val compression_blocks_count: UInt = if (this.compression_slot != null) {
            this.blocks!!.size.toUInt()
        } else {
            0u
        }
        val is_size_32_bit_safe = this.compressed <= UInt.MAX_VALUE.toULong()
        val is_uncompressed_size_32_bit_safe = this.uncompressed <= UInt.MAX_VALUE.toULong()
        val is_offset_32_bit_safe = this.offset <= UInt.MAX_VALUE.toULong()

        check(compression_blocks_count < 0x10000u) { "compression blocks count fits in 16 bits" }

        val flags: UInt = (compression_block_size) or
                (compression_blocks_count shl 6) or
                ((if (is_encrypted()) 1u else 0u) shl 22) or
                ((this.compression_slot?.let { it + 1u } ?: 0u) shl 23) or
                ((if (is_size_32_bit_safe) 1u else 0u) shl 29) or
                ((if (is_uncompressed_size_32_bit_safe) 1u else 0u) shl 30) or
                ((if (is_offset_32_bit_safe) 1u else 0u) shl 31)

        writer.write_u32_le(flags)

        if (compression_block_size == 0x3Fu) {
            writer.write_u32_le(this.compression_block_size)
        }

        if (is_offset_32_bit_safe) {
            writer.write_u32_le(this.offset.toUInt())
        } else {
            writer.write_u64_le(this.offset)
        }

        if (is_uncompressed_size_32_bit_safe) {
            writer.write_u32_le(this.uncompressed.toUInt())
        } else {
            writer.write_u64_le(this.uncompressed)
        }

        if (this.compression_slot != null) {
            if (is_size_32_bit_safe) {
                writer.write_u32_le(this.compressed.toUInt())
            } else {
                writer.write_u64_le(this.compressed)
            }

            check(this.blocks != null) { "blocks must be present when compressed" }
            val blocks = this.blocks!!
            if (blocks.size > 1 || is_encrypted()) {
                for (b in blocks) {
                    val block_size = b.end - b.start
                    writer.write_u32_le(block_size.toUInt())
                }
            }
        }
    }

    // Rust: repak/src/entry.rs:342 read_file
    @Suppress("FunctionName", "PropertyName", "VariableNaming")
    fun read_file(
        reader: SeekableByteChannel,
        version: Version,
        compression: List<Compression?>,
        key: Key,
        buf: OutputStream,
        context: RepakContext? = null
    ) {
        // Rust: reader.seek(SeekFrom::Start(self.offset))?
        reader.position(this.offset.toLong())
        val input = Channels.newInputStream(reader)
        // Rust: Entry::read(reader, version)?;
        // We re-read header to get data_offset; discard result
        read(input, version, context)
        val data_offset = reader.position().toULong()

        val read_len: Int = if (is_encrypted()) align(this.compressed).toInt() else this.compressed.toInt()

        // Rust: let mut data = reader.read_len(match self.is_encrypted() { true => align(self.compressed), false => self.compressed } as usize)?;
        val data = ByteArray(read_len)
        var totalRead = 0
        val bb = java.nio.ByteBuffer.wrap(data)
        while (totalRead < read_len) {
            val n = reader.read(bb)
            if (n < 0) throw EOFException("unexpected EOF reading entry data")
            totalRead += n
        }

        var mutableData = data
        if (is_encrypted()) {
            // Rust: decrypt per 16 – decrypt whole aligned buffer via ECB NoPadding then truncate
            when (key) {
                is Key.None -> throw RepakError.Encrypted
                is Key.Some -> {
                    if (key.key == null) throw RepakError.Encrypted
                    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key.key)
                    // Rust decrypts per 16 chunks in place; we do single doFinal for efficiency (requires multiple of 16)
                    val decrypted = cipher.doFinal(mutableData)
                    System.arraycopy(decrypted, 0, mutableData, 0, mutableData.size)
                    mutableData = mutableData.copyOf(this.compressed.toInt())
                }
            }
        }

        // Rust: ranges via RelativeChunkOffsets handling
        val ranges: List<IntRange> = run {
            // offset closure
            fun offset(index: ULong): Int {
                return if (version.version_major() >= VersionMajor.RelativeChunkOffsets) {
                    (index - (data_offset - this.offset)).toInt()
                } else {
                    (index - data_offset).toInt()
                }
            }
            when (val b = this.blocks) {
                null -> listOf(0 until mutableData.size)
                else -> b.map { block -> offset(block.start) until offset(block.end) }
            }
        }

        val slot = this.compression_slot
        val comp: Compression? = if (slot != null) compression.getOrNull(slot.toInt()) else null

        if (comp == null) {
            buf.write(mutableData)
        } else {
            val chunk_size = if (ranges.size == 1) this.uncompressed.toInt() else this.compression_block_size.toInt()
            when (comp) {
                Compression.Zlib -> {
                    for (range in ranges) {
                        val slice = mutableData.copyOfRange(range.first, range.last + 1)
                        val decoder = InflaterInputStream(ByteArrayInputStream(slice))
                        decoder.copyTo(buf)
                        decoder.close()
                    }
                }
                Compression.Gzip -> {
                    for (range in ranges) {
                        val slice = mutableData.copyOfRange(range.first, range.last + 1)
                        val decoder = GZIPInputStream(ByteArrayInputStream(slice))
                        decoder.copyTo(buf)
                        decoder.close()
                    }
                }
                Compression.Zstd -> {
                    // pure-JVM decoder (no natives): works on all platforms incl. win-arm64
                    for (range in ranges) {
                        val slice = mutableData.copyOfRange(range.first, range.last + 1)
                        val decompSize = ZstdDecompressor.getDecompressedSize(slice, 0, slice.size).toInt()
                        val out = ByteArray(decompSize)
                        val written = ZstdDecompressor().decompress(slice, 0, slice.size, out, 0, decompSize)
                        buf.write(out, 0, written)
                    }
                }
                Compression.LZ4 -> {
                    val decompressed = ByteArray(this.uncompressed.toInt())
                    val factory = LZ4Factory.fastestInstance()
                    val decompressor = factory.fastDecompressor()
                    var decompOffset = 0
                    for (range in ranges) {
                        val slice = mutableData.copyOfRange(range.first, range.last + 1)
                        val destLen = minOf(chunk_size, decompressed.size - decompOffset)
                        try {
                            decompressor.decompress(slice, 0, decompressed, decompOffset, destLen)
                        } catch (e: Exception) {
                            throw RepakError.DecompressionFailed(Compression.LZ4)
                        }
                        decompOffset += destLen
                    }
                    buf.write(decompressed)
                }
                Compression.Oodle -> {
                    try {
                        val oodle = com.github.jpabscale.zenpak4j.oodle_loader.oodle()
                        val decompressed = ByteArray(this.uncompressed.toInt())
                        var decompOffset = 0
                        for (range in ranges) {
                            val slice = mutableData.copyOfRange(range.first, range.last + 1)
                            val destLen = minOf(chunk_size, decompressed.size - decompOffset)
                            val destSlice = decompressed.copyOfRange(decompOffset, decompOffset + destLen)
                            // oodle decompress expects output buffer slice
                            val tmpOut = ByteArray(destLen)
                            val ret = oodle.decompress(slice, tmpOut)
                            if (ret == 0) throw RepakError.DecompressionFailed(Compression.Oodle)
                            // copy back
                            System.arraycopy(tmpOut, 0, decompressed, decompOffset, destLen)
                            decompOffset += destLen
                        }
                        buf.write(decompressed)
                    } catch (e: RepakError) {
                        throw e
                    } catch (e: Exception) {
                        throw RepakError.OodleFailed(e)
                    }
                }
            }
        }
        buf.flush()
    }

    // Rust: repak/src/entry.rs:342 read_file overload with context default
    fun read_file(
        reader: SeekableByteChannel,
        version: Version,
        compression: List<Compression?>,
        key: Key,
        buf: OutputStream
    ) = read_file(reader, version, compression, key, buf, null)
}
