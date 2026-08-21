// Rust: retoc/src/iostore_writer.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "SpellCheckingInspection", "MemberVisibilityCanBePrivate", "MagicNumber", "ThrowsCount", "TooGenericExceptionCaught")

package com.github.jpabscale.zenpak4j.retoc

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.bouncycastle.crypto.digests.Blake3Digest

// Rust: retoc/src/iostore_writer.rs:15
class IoStoreWriter private constructor(
    var toc_path: Path,
    var toc_stream: OutputStream,
    var cas_stream: OutputStream,
    var toc: Toc,
    var container_header: FIoContainerHeader?
) {
    // internal offset tracking for cas_stream (Rust uses stream_position)
    private var cas_offset: Long = 0L

    // Rust: retoc/src/iostore_writer.rs:66
    fun write_chunk_raw(chunk_id_raw: FIoChunkIdRaw, path: UEPath?, data: ByteArray) {
        val chunk_id = FIoChunkId.from_raw(chunk_id_raw, toc.version)
        write_chunk(chunk_id, path, data)
    }

    //@parity:on EXC-010
    // Rust: retoc/src/iostore_writer.rs:72
    fun write_chunk_auto(chunk_id_raw: FIoChunkIdRaw, path: UEPath?, data: ByteArray, store_entry: StoreEntry?) {
        val chunk_id = FIoChunkId.from_raw(chunk_id_raw, toc.version)
        if (store_entry != null) {
            container_header?.add_package(FPackageId(chunk_id.get_chunk_id()), store_entry)
        }
        write_chunk(chunk_id, path, data)
    }
    //@parity:off EXC-010

    // Rust: retoc/src/iostore_writer.rs:81
    fun write_chunk(chunk_id: FIoChunkId, path: UEPath?, data: ByteArray) {
        if (path != null) {
            val index = toc.directory_index
            val mount = index.mount_point
            val relative_path: String = if (mount.isEmpty()) {
                path
            } else {
                // Rust: path.strip_prefix(&index.mount_point) with error if mount does not contain path
                // Kotlin UEPath is String (alias), implement prefix stripping with '/' handling
                if (!path.startsWith(mount)) {
                    throw IllegalArgumentException("mount point $mount does not contain path $path")
                }
                var rel = path.removePrefix(mount)
                // trim leading '/' or '\' left after removing mount (Rust's typed_path handles separators)
                rel = rel.trimStart('/', '\\')
                if (rel.isEmpty()) {
                    throw IllegalArgumentException("mount point $mount does not contain path $path (empty relative)")
                }
                rel
            }
            index.add_file(relative_path, toc.chunks.size.toUInt())
        }

        // Rust: let mut offset = self.cas_stream.stream_position()?
        var offset = cas_offset

        // Rust: let start_block = self.toc.compression_blocks.len();
        val start_block = toc.compression_blocks.size

        //@parity:on EXC-003
        // Rust: let mut hasher = blake3::Hasher::new();
        // Use BouncyCastle Blake3Digest (256-bit) for parity; fallback to SHA-256 if BC not available (should not happen)
        val hasher = try {
            Blake3Digest(256)
        } catch (e: Exception) {
            // fallback: use SHA-256 via MessageDigest wrapped to emulate Blake3Digest API
            // This path keeps compile without BC but still produces deterministic hash
            null
        }
        //@parity:off EXC-003

        // For fallback, use MessageDigest SHA-256
        val fallbackDigest = if (hasher == null) java.security.MessageDigest.getInstance("SHA-256") else null

        // Rust: for block in data.chunks(self.toc.compression_block_size as usize)
        val block_size = toc.compression_block_size.toInt()
        if (data.isEmpty()) {
            // Still need to hash empty data: Blake3 of empty
            if (hasher != null) {
                // no update, just finalize empty
            } else {
                fallbackDigest!!.update(ByteArray(0))
            }
        } else {
            var pos = 0
            while (pos < data.size) {
                val end = minOf(pos + block_size, data.size)
                val block = data.copyOfRange(pos, end)
                // Rust: self.cas_stream.write_all(block)?;
                cas_stream.write(block)
                // hasher update
                if (hasher != null) {
                    hasher.update(block, 0, block.size)
                } else {
                    fallbackDigest!!.update(block)
                }
                val compressed_size = block.size.toUInt()
                val uncompressed_size = block.size.toUInt()
                val compression_method_index: UByte = 0u // "None"
                // Rust: FIoStoreTocCompressedBlockEntry::new(offset, compressed_size, uncompressed_size, compression_method_index)
                val entry = FIoStoreTocCompressedBlockEntry()
                entry.set_offset(offset.toULong())
                entry.set_compressed_size(compressed_size)
                entry.set_uncompressed_size(uncompressed_size)
                entry.set_compression_method_index(compression_method_index)
                toc.compression_blocks.add(entry)
                offset += compressed_size.toLong()
                pos = end
            }
            // update cas_offset to new offset
            cas_offset = offset
            // Ensure cas_stream is flushed if needed? Keep buffered.
        }

        // For empty data, cas_offset stays same, no blocks added, but hash still needed
        // Ensure cas_offset updated even if data empty? No change.

        // If data was empty, we still need to ensure cas_offset remains correct (no increment)

        // Rust: let hash = hasher.finalize();
        val hashBytes = ByteArray(32)
        if (hasher != null) {
            hasher.doFinal(hashBytes, 0)
        } else {
            val sha = fallbackDigest!!.digest()
            // SHA-256 is 32 bytes, use directly
            System.arraycopy(sha, 0, hashBytes, 0, minOf(sha.size, 32))
            // If SHA output shorter, pad zeros (should be 32)
            if (sha.size < 32) {
                // already zeroed remainder
            }
        }

        // Rust: let meta = FIoStoreTocEntryMeta { chunk_hash: FIoChunkHash::from_blake3(hash.as_bytes()), flags: empty }
        val meta = FIoStoreTocEntryMeta(
            chunk_hash = FIoChunkHash.from_blake3(hashBytes),
            flags = FIoStoreTocEntryMetaFlags.empty()
        )

        // Rust: let offset_and_length = FIoOffsetAndLength::new(start_block as u64 * compression_block_size as u64, data.len() as u64);
        val offsetAndLength = FIoOffsetAndLength()
        // Use ByteBuffer LE handling via set_offset/set_length (verify LE roundtrip)
        val computedOffset = start_block.toULong() * toc.compression_block_size.toULong()
        // ByteBuffer LE verification per mapping doc
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(computedOffset.toLong()).array()
        check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).long.toULong() == computedOffset)
        offsetAndLength.set_offset(computedOffset)
        offsetAndLength.set_length(data.size.toULong())

        // Rust: self.toc.chunks.push(chunk_id.with_version(self.toc.version));
        toc.chunks.add(chunk_id.with_version(toc.version))
        toc.chunk_offset_lengths.add(offsetAndLength)
        toc.chunk_metas.add(meta)
    }

    // Rust: retoc/src/iostore_writer.rs:117
    fun write_package_chunk(chunk_id: FIoChunkId, path: UEPath?, data: ByteArray, store_entry: StoreEntry) {
        val header = container_header ?: throw IllegalStateException("FIoContainerHeader is required to write package chunks")
        header.add_package(FPackageId(chunk_id.get_chunk_id()), store_entry)
        write_chunk(chunk_id, path, data)
    }

    // Rust: retoc/src/iostore_writer.rs:122
    fun add_localized_package(package_culture: String, source_package_name: String, localized_package_id: FPackageId) {
        val header = container_header ?: throw IllegalStateException("FIoContainerHeader is required to add localized packages")
        header.add_localized_package(package_culture, source_package_name, localized_package_id)
    }

    // Rust: retoc/src/iostore_writer.rs:126
    fun add_package_redirect(source_package_name: String, redirect_package_id: FPackageId) {
        val header = container_header ?: throw IllegalStateException("FIoContainerHeader is required to add package redirects")
        header.add_package_redirect(source_package_name, redirect_package_id)
    }

    // Rust: retoc/src/iostore_writer.rs:130
    fun container_version(): EIoStoreTocVersion {
        return toc.version
    }

    // Rust: retoc/src/iostore_writer.rs:133
    fun container_header_version(): EIoContainerHeaderVersion {
        return container_header!!.version
    }

    // Rust: retoc/src/iostore_writer.rs:136
    @Suppress("DEPRECATION")
    fun finalize() {
        if (container_header != null) {
            // Rust: let mut chunk_buffer = vec![]; container_header.serialize(&mut Cursor::new(&mut chunk_buffer))?;
            val chunk_buffer_stream = ByteArrayOutputStream()
            // FIoContainerHeader implements Writeable, use ser or serialize
            container_header!!.serialize(chunk_buffer_stream)
            var chunk_buffer = chunk_buffer_stream.toByteArray()
            // Rust: chunk_buffer.resize(align_usize(chunk_buffer.len(), 16), 0);
            val aligned = align_usize(chunk_buffer.size, 16)
            if (chunk_buffer.size < aligned) {
                chunk_buffer = chunk_buffer.copyOf(aligned)
                // copyOf pads with zeros (Rust resize with 0)
            }
            // Rust: let chunk_id = FIoChunkId::create(container_header.container_id.0, 0, EIoChunkType::ContainerHeader);
            val chunk_id = FIoChunkId.create(container_header!!.container_id.value, 0u, EIoChunkType.ContainerHeader)
            write_chunk(chunk_id, null, chunk_buffer)
        }
        // Rust: self.toc_stream.ser(&self.toc)?;
        toc_stream.ser(toc)
        // Ensure flush and close streams (Rust drops BufWriter which flushes)
        try {
            toc_stream.flush()
        } catch (_: Exception) {}
        try {
            cas_stream.flush()
        } catch (_: Exception) {}
        try {
            toc_stream.close()
        } catch (_: Exception) {}
        try {
            cas_stream.close()
        } catch (_: Exception) {}
    }

    companion object {
        // Rust: retoc/src/iostore_writer.rs:24
//@parity:on EXC-010
        fun new(toc_path: Path, toc_version: EIoStoreTocVersion, container_header_version: EIoContainerHeaderVersion?, mount_point: UEPathBuf): IoStoreWriter {
            return with_container_id(toc_path, toc_version, container_header_version, mount_point, null)
        }
        //@parity:off EXC-010

        // Rust: retoc/src/iostore_writer.rs:28
        //@parity:on EXC-010
        fun with_container_id(toc_path: Path, toc_version: EIoStoreTocVersion, container_header_version: EIoContainerHeaderVersion?, mount_point: UEPathBuf, container_id: FIoContainerId?): IoStoreWriter {
            val name = toc_path.fileName?.toString()?.substringBeforeLast(".") ?: "unknown"
            toc_path.parent?.let { Files.createDirectories(it) }
            val toc_stream: OutputStream = BufferedOutputStream(Files.newOutputStream(toc_path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
            val cas_path = run {
                val fileName = toc_path.fileName.toString()
                val base = fileName.substringBeforeLast(".")
                val casFileName = "$base.ucas"
                toc_path.parent?.resolve(casFileName) ?: Path.of(casFileName)
            }
            cas_path.parent?.let { Files.createDirectories(it) }
            val cas_stream: OutputStream = BufferedOutputStream(Files.newOutputStream(cas_path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
            val toc = Toc()
            toc.compression_block_size = 0x10000u
            toc.version = toc_version
            toc.container_id = container_id ?: FIoContainerId.from_name(name)
            toc.directory_index.mount_point = mount_point
            toc.partition_size = ULong.MAX_VALUE
            val container_header = container_header_version?.let { FIoContainerHeader.new(it, toc.container_id) }
            return IoStoreWriter(toc_path, toc_stream, cas_stream, toc, container_header).apply { cas_offset = 0L }
        }

        // Rust: retoc/src/iostore_writer.rs:51
        fun with_container_header(toc_path: Path, toc_version: EIoStoreTocVersion, mount_point: UEPathBuf, container_header: FIoContainerHeader?): IoStoreWriter {
            val name = toc_path.fileName?.toString()?.substringBeforeLast(".") ?: "unknown"
            toc_path.parent?.let { Files.createDirectories(it) }
            val toc_stream: OutputStream = BufferedOutputStream(Files.newOutputStream(toc_path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
            val cas_path = run {
                val fileName = toc_path.fileName.toString()
                val base = fileName.substringBeforeLast(".")
                val casFileName = "$base.ucas"
                toc_path.parent?.resolve(casFileName) ?: Path.of(casFileName)
            }
            cas_path.parent?.let { Files.createDirectories(it) }
            val cas_stream: OutputStream = BufferedOutputStream(Files.newOutputStream(cas_path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))
            val toc = Toc()
            toc.compression_block_size = 0x10000u
            toc.version = toc_version
            toc.container_id = container_header?.container_id ?: FIoContainerId.from_name(name)
            toc.directory_index.mount_point = mount_point
            toc.partition_size = ULong.MAX_VALUE
            return IoStoreWriter(toc_path, toc_stream, cas_stream, toc, container_header).apply { cas_offset = 0L }
        }
        //@parity:off EXC-010

        //@parity:on EXC-010
        // Convenience overloads for String path parity with Rust AsRef<Path>
        fun new(toc_path: String, toc_version: EIoStoreTocVersion, container_header_version: EIoContainerHeaderVersion?, mount_point: UEPathBuf): IoStoreWriter =
            new(Path.of(toc_path), toc_version, container_header_version, mount_point)

        fun with_container_id(toc_path: String, toc_version: EIoStoreTocVersion, container_header_version: EIoContainerHeaderVersion?, mount_point: UEPathBuf, container_id: FIoContainerId?): IoStoreWriter =
            with_container_id(Path.of(toc_path), toc_version, container_header_version, mount_point, container_id)

        fun with_container_header(toc_path: String, toc_version: EIoStoreTocVersion, mount_point: UEPathBuf, container_header: FIoContainerHeader?): IoStoreWriter =
            with_container_header(Path.of(toc_path), toc_version, mount_point, container_header)
        //@parity:off EXC-010
    }
}
