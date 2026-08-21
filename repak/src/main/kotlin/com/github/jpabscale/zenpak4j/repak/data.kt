// Rust: repak/src/data.rs:1
package com.github.jpabscale.zenpak4j.repak

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import com.github.luben.zstd.Zstd
import net.jpountz.lz4.LZ4Factory

// Rust: repak/src/data.rs:10
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class PartialEntry<D>(
    var compression: Compression?,
    var compressed_size: ULong,
    var uncompressed_size: ULong,
    var compression_block_size: UInt,
    var data: PartialEntryData<D>,
    var hash: ByteArray
) {
    // Rust: repak/src/data.rs:64 PartialEntry::build_entry
    fun build_entry(
        version: Version,
        compression_slots: MutableList<Compression?>,
        file_offset: ULong,
        context: RepakContext? = null
    ): Entry {
        val compression_slot: UInt? = this.compression?.let { get_compression_slot(version, compression_slots, it) }

        // Rust: assert!(compression_slot.is_none() || !matches!(&self.data, PartialEntryData::Blocks(b) if b.is_empty()))
        check(!(compression_slot != null && this.data is PartialEntryData.Blocks && (this.data as PartialEntryData.Blocks<D>).blocks.isEmpty())) {
            "compressed entry must have at least one block"
        }

        val blocks: List<Block>? = when (val d = this.data) {
            is PartialEntryData.Slice -> null
            is PartialEntryData.Blocks -> {
                val entry_size = Entry.get_serialized_size(version, compression_slot, d.blocks.size.toUInt(), context)
                var offset = entry_size
                if (version.version_major() < VersionMajor.RelativeChunkOffsets) {
                    offset += file_offset
                }
                val list = mutableListOf<Block>()
                for (block in d.blocks) {
                    val start = offset
                    offset += block.data.size.toULong()
                    val end = offset
                    list.add(Block(start, end))
                }
                list
            }
        }

        return Entry(
            offset = file_offset,
            compressed = this.compressed_size,
            uncompressed = this.uncompressed_size,
            compression_slot = compression_slot,
            timestamp = null,
            hash = this.hash,
            blocks = blocks,
            flags = 0u,
            compression_block_size = this.compression_block_size
        )
    }

    // Rust: repak/src/data.rs:122 PartialEntry::write_data
    fun write_data(stream: OutputStream) {
        when (val d = this.data) {
            is PartialEntryData.Slice -> {
                val bytes: ByteArray = when (val v = d.value) {
                    is ByteArray -> v
                    is String -> (v as String).toByteArray()
                    else -> throw RepakError.Other("unsupported data type for write_data")
                }
                stream.write(bytes)
            }
            is PartialEntryData.Blocks -> {
                for (block in d.blocks) {
                    stream.write(block.data)
                }
            }
        }
    }
}

// Rust: repak/src/data.rs:18
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class PartialBlock(
    var uncompressed_size: Int,
    var data: ByteArray
)

// Rust: repak/src/data.rs:22
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
sealed class PartialEntryData<D> {
    // Rust: repak/src/data.rs:23 Slice
    class Slice<D>(val value: D) : PartialEntryData<D>()
    // Rust: repak/src/data.rs:24 Blocks
    class Blocks<D>(val blocks: MutableList<PartialBlock>) : PartialEntryData<D>()
}

// Rust: repak/src/data.rs:27 get_compression_slot
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun get_compression_slot(
    version: Version,
    compression_slots: MutableList<Compression?>,
    compression: Compression
): UInt {
    val slot = compression_slots.withIndex().find { it.value == compression }
    return if (slot != null) {
        slot.index.toUInt()
    } else {
        if (version.version_major() < VersionMajor.FNameBasedCompression) {
            throw RepakError.Other("cannot use $compression prior to FNameBasedCompression (pak version 8)")
        }
        val empty = compression_slots.withIndex().find { it.value == null }
        if (empty != null) {
            compression_slots[empty.index] = compression
            empty.index.toUInt()
        } else {
            compression_slots.add(compression)
            (compression_slots.size - 1).toUInt()
        }
    }
}

// Rust: repak/src/data.rs:64 extension delegation for generic top-level usage
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun <D> PartialEntry<D>.build_entry_ext(
    version: Version,
    compression_slots: MutableList<Compression?>,
    file_offset: ULong,
    context: RepakContext? = null
): Entry = this.build_entry(version, compression_slots, file_offset, context)

// Rust: repak/src/data.rs:122 extension delegation
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun <D> PartialEntry<D>.write_data_ext(stream: OutputStream) = this.write_data(stream)

// Rust: repak/src/data.rs:137 build_partial_entry
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun <D> build_partial_entry(
    allowed_compression: List<Compression>,
    data: D
): PartialEntry<D> {
    val hasher = MessageDigest.getInstance("SHA-1")

    val raw_bytes: ByteArray = when (data) {
        is ByteArray -> data
        is String -> (data as String).toByteArray()
        else -> throw RepakError.Other("unsupported data type for build_partial_entry: ${data!!::class}")
    }

    val compression: Compression? = if (raw_bytes.isEmpty()) {
        null
    } else {
        allowed_compression.firstOrNull()
    }

    val uncompressed_size = raw_bytes.size.toULong()
    val compression_block_size: UInt
    val entry_data: PartialEntryData<D>
    val compressed_size: ULong

    if (compression != null) {
        compression_block_size = (0x3e shl 11).toUInt() // max possible block size 0x1F000
        var csize = 0uL
        val blocks = mutableListOf<PartialBlock>()
        val block_size = compression_block_size.toInt()
        var offset = 0
        while (offset < raw_bytes.size) {
            val end = minOf(offset + block_size, raw_bytes.size)
            val chunk = raw_bytes.copyOfRange(offset, end)
            val compressed = compress(compression, chunk)
            csize += compressed.size.toULong()
            hasher.update(compressed)
            blocks.add(PartialBlock(uncompressed_size = chunk.size, data = compressed))
            offset = end
        }
        entry_data = PartialEntryData.Blocks(blocks)
        compressed_size = csize
    } else {
        compression_block_size = 0u
        hasher.update(raw_bytes)
        entry_data = PartialEntryData.Slice(data)
        compressed_size = uncompressed_size
    }

    val hash = hasher.digest()
    return PartialEntry(
        compression = compression,
        compressed_size = compressed_size,
        uncompressed_size = uncompressed_size,
        compression_block_size = compression_block_size,
        data = entry_data,
        hash = hash
    )
}

// Rust: repak/src/data.rs:137 build_partial_entry overload for ByteArray convenience
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun build_partial_entry_bytearray(
    allowed_compression: List<Compression>,
    data: ByteArray
): PartialEntry<ByteArray> = build_partial_entry(allowed_compression, data)

// Rust: repak/src/data.rs:199 compress
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun compress(compression: Compression, data: ByteArray): ByteArray {
    return when (compression) {
        Compression.Zlib -> {
            val baos = ByteArrayOutputStream()
            val deflater = Deflater(Deflater.BEST_SPEED)
            val dos = DeflaterOutputStream(baos, deflater)
            dos.write(data)
            dos.finish()
            dos.close()
            baos.toByteArray()
        }
        Compression.Gzip -> {
            // Rust: GzEncoder::new(_, Compression::fast()) = level 1
            val baos = ByteArrayOutputStream()
            val gos = object : GZIPOutputStream(baos) {
                init {
                    def.setLevel(Deflater.BEST_SPEED)
                }
            }
            gos.write(data)
            gos.finish()
            gos.close()
            baos.toByteArray()
        }
        Compression.Zstd -> {
            // Rust: zstd::stream::encode_all(data, 0)
            Zstd.compress(data, 0)
        }
        Compression.LZ4 -> {
            val factory = LZ4Factory.fastestInstance()
            val compressor = factory.fastCompressor()
            val maxLen = compressor.maxCompressedLength(data.size)
            val out = ByteArray(maxLen)
            val len = compressor.compress(data, 0, data.size, out, 0, maxLen)
            out.copyOf(len)
        }
        Compression.Oodle -> {
            try {
                val oodle = com.github.jpabscale.zenpak4j.oodle_loader.oodle()
                oodle.compress(data, com.github.jpabscale.zenpak4j.oodle_loader.Compressor.Mermaid, com.github.jpabscale.zenpak4j.oodle_loader.CompressionLevel.Normal)
            } catch (e: Exception) {
                throw RepakError.OodleFailed(e)
            }
        }
    }
}
