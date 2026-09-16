// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/compression.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "EnumEntryName", "unused")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import com.github.luben.zstd.Zstd
import com.qyntrax.unzstd.ZstdDecompressor
import net.jpountz.lz4.LZ4Factory

// Rust: retoc/src/compression.rs:5 CompressionMethod
enum class CompressionMethod {
    Zlib,
    Zstd,
    LZ4,
    Oodle;

    override fun toString(): String = name

    companion object {
        // Rust: strum::VariantArray + AsRefStr parity
        val VARIANTS: Array<CompressionMethod> = entries.toTypedArray()

        // Rust: retoc/src/compression.rs:13 from_str_ignore_case
        fun from_str_ignore_case(value: String): CompressionMethod? =
            VARIANTS.find { it.name.equals(value, ignoreCase = true) }

        fun from_string(value: String): CompressionMethod? = from_str_ignore_case(value)
    }
}

// Rust: retoc/src/compression.rs:18 compress
fun compress(compression: CompressionMethod, input: ByteArray, output: OutputStream) {
    when (compression) {
        CompressionMethod.Zlib -> {
            // Rust: flate2::write::ZlibEncoder best
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            val encoder = DeflaterOutputStream(output, deflater, true)
            encoder.write(input)
            encoder.finish()
            encoder.flush()
            // do not close output; just finish deflater
        }
        CompressionMethod.Zstd -> {
            // Rust: zstd::stream::encode_all(input, 0)
            val buf = Zstd.compress(input, 0)
            output.write(buf)
        }
        CompressionMethod.LZ4 -> {
            // Rust: lz4_flex::block::compress
            val factory = LZ4Factory.fastestInstance()
            val compressor = factory.fastCompressor()
            val maxLen = compressor.maxCompressedLength(input.size)
            val out = ByteArray(maxLen)
            val len = compressor.compress(input, 0, input.size, out, 0, maxLen)
            output.write(out, 0, len)
        }
        CompressionMethod.Oodle -> {
            // Rust: oodle_loader::oodle()?.compress(input, Mermaid, Normal)
            val buffer = com.github.jpabscale.zenpak4j.oodle_loader.oodle()
                .compress(input, com.github.jpabscale.zenpak4j.oodle_loader.Compressor.Mermaid, com.github.jpabscale.zenpak4j.oodle_loader.CompressionLevel.Normal)
            output.write(buffer)
        }
    }
}

// Rust: retoc/src/compression.rs:41 decompress
fun decompress(compression: CompressionMethod, input: ByteArray, output: ByteArray) {
    when (compression) {
        CompressionMethod.Zlib -> {
            // Rust: flate2::read::ZlibDecoder::new(input).read_exact(output)
            val decoder = InflaterInputStream(ByteArrayInputStream(input))
            var offset = 0
            while (offset < output.size) {
                val n = decoder.read(output, offset, output.size - offset)
                if (n == -1) throw java.io.IOException("Zlib decompression failed: unexpected EOF, expected ${output.size} bytes got $offset")
                offset += n
            }
            decoder.close()
        }
        CompressionMethod.Zstd -> {
            // Rust: zstd::bulk::decompress_to_buffer(input, output)
            // pure-JVM decoder (no natives): works on all platforms incl. win-arm64
            val written = ZstdDecompressor().decompress(input, 0, input.size, output, 0, output.size)
            if (written != output.size) {
                throw java.io.IOException("Zstd decompression failed: expected ${output.size} got $written")
            }
        }
        CompressionMethod.LZ4 -> {
            // Rust: lz4_flex::block::decompress_into(input, output)
            val factory = LZ4Factory.fastestInstance()
            val decompressor = factory.fastDecompressor()
            val n = try {
                decompressor.decompress(input, 0, output, 0, output.size)
            } catch (e: Exception) {
                throw java.io.IOException("LZ4 decompression failed: ${e.message}", e)
            }
            if (n != output.size) {
                // lz4 returns decompressed bytes; verify
                if (n < 0) throw java.io.IOException("LZ4 decompression failed")
            }
        }
        CompressionMethod.Oodle -> {
            // Rust: oodle_loader::oodle()?.decompress(input, output)
            val status = com.github.jpabscale.zenpak4j.oodle_loader.oodle().decompress(input, output)
            if (status < 0 || status != output.size) {
                throw java.io.IOException("Oodle decompression failed: expected ${output.size} output bytes, got $status")
            }
        }
    }
}
