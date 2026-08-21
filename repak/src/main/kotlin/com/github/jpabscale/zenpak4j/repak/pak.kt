// Rust: repak/src/pak.rs:1
package com.github.jpabscale.zenpak4j.repak

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.TreeMap
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// Rust: repak/src/pak.rs:12 Hash
// Note: Hash is already defined in footer.kt as `class Hash(val bytes: ByteArray)`
// Keep alias for pak.rs parity; actual class lives in footer.kt to avoid duplicate definition
// If Hash not found, define here - fallback
// We keep reference: `typealias PakHash = Hash` would be duplicate, so rely on footer.kt definition

// Rust: repak/src/pak.rs:20 PakBuilder
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class PakBuilder(
    var key: Key = Key.Default,
    var allowed_compression: MutableList<Compression> = mutableListOf()
) {
    companion object {
        // Rust: repak/src/pak.rs:32 new
        fun new(): PakBuilder = PakBuilder()
    }

    // Rust: repak/src/pak.rs:39 key
    fun key(key: SecretKeySpec): PakBuilder {
        this.key = Key.Some(key)
        return this
    }

    // Rust: repak/src/pak.rs:44 compression
    fun compression(compression: Iterable<Compression>): PakBuilder {
        this.allowed_compression = compression.toMutableList()
        return this
    }

    // Rust: repak/src/pak.rs:48 reader
    // context is Kotlin-only (EXC-001): threads the game-id explicitly so parallel
    // read_file workers see it without depending on thread-local globals.
    fun reader(reader: SeekableByteChannel, context: RepakContext? = null): PakReader {
        return PakReader.new_any_inner(reader, this.key, context)
    }

    // Rust: repak/src/pak.rs:48 reader overload for InputStream+Seek? use SeekableByteChannel
    fun reader_with_version(reader: SeekableByteChannel, version: Version, context: RepakContext? = null): PakReader {
        return PakReader.new_inner(reader, version, this.key, context)
    }

    // Rust: repak/src/pak.rs:58 writer
    fun writer(
        writer: SeekableByteChannel,
        version: Version,
        mount_point: String,
        path_hash_seed: ULong? = null,
        context: RepakContext? = null
    ): PakWriter<SeekableByteChannel> {
        return PakWriter.new_inner(writer, this.key, version, mount_point, path_hash_seed, this.allowed_compression, context)
    }

    // Rust: repak/src/pak.rs:58 writer overload for OutputStream channel variant
    fun writer_with_channel(
        writer: SeekableByteChannel,
        version: Version,
        mount_point: String,
        path_hash_seed: ULong?,
        context: RepakContext? = null
    ): PakWriter<SeekableByteChannel> {
        return PakWriter.new_inner(writer, this.key, version, mount_point, path_hash_seed, this.allowed_compression, context)
    }
}

// Rust: repak/src/pak.rs:77 PakReader
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class PakReader(
    var pak: Pak,
    var key: Key,
    var context: RepakContext? = null
) {
    companion object {
        // Rust: repak/src/pak.rs:164 new_any_inner
        fun new_any_inner(reader: SeekableByteChannel, key: Key, context: RepakContext? = null): PakReader {
            val log = StringBuilder("\n")
            for (ver in Version.iter()) {
                try {
                    // Need to reset position before each try? Pak.read will seek
                    val pak = Pak.read(reader, ver, key, context)
                    return PakReader(pak, key, context)
                } catch (e: Exception) {
                    log.append("trying version ${ver} failed: ${e.message}\n")
                }
            }
            throw RepakError.UnsupportedOrEncrypted(log.toString())
        }

        // Rust: repak/src/pak.rs:180 new_inner
        fun new_inner(reader: SeekableByteChannel, version: Version, key: Key, context: RepakContext? = null): PakReader {
            val pak = Pak.read(reader, version, key, context)
            return PakReader(pak, key, context)
        }
    }

    // Rust: repak/src/pak.rs:188 version
    fun version(): Version = this.pak.version

    // Rust: repak/src/pak.rs:192 mount_point
    fun mount_point(): String = this.pak.mount_point

    // Rust: repak/src/pak.rs:196 encrypted_index
    fun encrypted_index(): Boolean = this.pak.encrypted_index

    // Rust: repak/src/pak.rs:200 encryption_guid
    fun encryption_guid(): ULong? = this.pak.encryption_guid

    // Rust: repak/src/pak.rs:204 path_hash_seed
    fun path_hash_seed(): ULong? = this.pak.index.path_hash_seed

    // Rust: repak/src/pak.rs:208 get
    fun get(path: String, reader: SeekableByteChannel): ByteArray {
        val buf = ByteArrayOutputStream()
        read_file(path, reader, buf)
        return buf.toByteArray()
    }

    // Rust: repak/src/pak.rs:214 read_file
    fun read_file(path: String, reader: SeekableByteChannel, writer: OutputStream) {
        val entry = this.pak.index.entries[path] ?: throw RepakError.MissingEntry(path)
        entry.read_file(reader, this.pak.version, this.pak.compression, this.key, writer, this.context)
    }

    // Rust: repak/src/pak.rs:232 files
    fun files(): List<String> = this.pak.index.entries.keys.toList()

    // Rust: repak/src/pak.rs:236 used_compression
    fun used_compression(): List<Compression> {
        val used = MutableList(this.pak.compression.size) { 0 }
        for (entry in this.pak.index.entries.values) {
            val slot = entry.compression_slot
            if (slot != null) {
                val idx = slot.toInt()
                if (idx >= 0 && idx < used.size) {
                    used[idx] = used[idx] + 1
                }
            }
        }
        return used.mapIndexedNotNull { idx, count ->
            if (count > 0) this.pak.compression.getOrNull(idx) else null
        }.filterNotNull()
    }

    // Rust: repak/src/pak.rs:253 into_pakwriter
    fun into_pakwriter(writer: SeekableByteChannel): PakWriter<SeekableByteChannel> {
        val offset = this.pak.index_offset ?: throw RepakError.Other("missing index_offset")
        writer.position(offset)
        val allowed = this.pak.compression.filterNotNull().toMutableList()
        return PakWriter(
            pak = this.pak,
            writer = writer,
            key = this.key,
            allowed_compression = allowed,
            context = this.context
        )
    }
}

// Rust: repak/src/pak.rs:83 PakWriter
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class PakWriter<W : SeekableByteChannel>(
    var pak: Pak,
    var writer: W,
    var key: Key,
    var allowed_compression: MutableList<Compression>,
    var context: RepakContext? = null
) {
    companion object {
        // Rust: repak/src/pak.rs:268 new_inner
        fun <W : SeekableByteChannel> new_inner(
            writer: W,
            key: Key,
            version: Version,
            mount_point: String,
            path_hash_seed: ULong?,
            allowed_compression: List<Compression>,
            context: RepakContext? = null
        ): PakWriter<W> {
            return PakWriter(
                pak = Pak.new(version, mount_point, path_hash_seed),
                writer = writer,
                key = key,
                allowed_compression = allowed_compression.toMutableList(),
                context = context
            )
        }
    }

    // Rust: repak/src/pak.rs:284 into_writer
    fun into_writer(): W = this.writer

    // Rust: repak/src/pak.rs:288 write_file
    fun write_file(path: String, allow_compress: Boolean, data: ByteArray) {
        val entry = Entry.write_file(
            this.writer,
            this.pak.version,
            this.pak.compression,
            if (allow_compress) this.allowed_compression else emptyList(),
            data,
            this.context
        )
        this.pak.index.add_entry(path, entry)
    }

    // Rust: repak/src/pak.rs:288 write_file overload for String data
    fun write_file(path: String, allow_compress: Boolean, data: String) = write_file(path, allow_compress, data.toByteArray())

    // Rust: repak/src/pak.rs:312 entry_builder
    fun entry_builder(): EntryBuilder {
        return EntryBuilder(this.allowed_compression.toList())
    }

    // Rust: repak/src/pak.rs:318 write_entry
    fun <D> write_entry(path: String, partial_entry: PartialEntry<D>) {
        val stream_position = this.writer.position().toULong()
        val entry = partial_entry.build_entry(this.pak.version, this.pak.compression, stream_position, this.context)
        val out = Channels.newOutputStream(this.writer)
        entry.write(out, this.pak.version, EntryLocation.Data, this.context)
        partial_entry.write_data(out)
        this.pak.index.add_entry(path, entry)
    }

    // Rust: repak/src/pak.rs:342 write_index
    fun write_index(): W {
        this.pak.write(this.writer, this.key, this.context)
        return this.writer
    }
}

// Rust: repak/src/pak.rs:91 Pak
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class Pak(
    var version: Version,
    var mount_point: String,
    var index_offset: Long?,
    var index: Index,
    var encrypted_index: Boolean,
    var encryption_guid: ULong?,
    var compression: MutableList<Compression?>
) {
    companion object {
        // Rust: repak/src/pak.rs:102 Pak::new
        fun new(version: Version, mount_point: String, path_hash_seed: ULong?): Pak {
            val comp: MutableList<Compression?> = if (version.version_major() < VersionMajor.FNameBasedCompression) {
                mutableListOf(Compression.Zlib, Compression.Gzip, Compression.Oodle)
            } else {
                mutableListOf()
            }
            return Pak(
                version = version,
                mount_point = mount_point,
                index_offset = null,
                index = Index.new(path_hash_seed),
                encrypted_index = false,
                encryption_guid = null,
                compression = comp
            )
        }

        // Rust: repak/src/pak.rs:376 Pak::read
        @Suppress("FunctionName", "PropertyName", "VariableNaming")
        fun read(reader: SeekableByteChannel, version: Version, key: Key, context: RepakContext? = null): Pak {
            // Rust: reader.seek(SeekFrom::End(-version.size()))?
            reader.position(reader.size() - version.size())
            val footerInput = Channels.newInputStream(reader)
            val footer = Footer.read(footerInput, version)
            // Rust: reader.seek(SeekFrom::Start(footer.index_offset))?
            reader.position(footer.index_offset)
            val indexSize = footer.index_size.toInt()
            val indexBytes = ByteArray(indexSize)
            var pos = 0
            val bb = ByteBuffer.wrap(indexBytes)
            // Ensure we read exactly indexSize bytes from current position
            // Channel is at index_offset after seeking, sequential read will fill bb
            while (bb.hasRemaining()) {
                val n = reader.read(bb)
                if (n < 0) throw EOFException("unexpected EOF reading index")
                pos += n
            }

            if (footer.encrypted) {
                decrypt(key, indexBytes)
            }

            val indexCursor = ByteArrayInputStream(indexBytes)
            val mount_point = indexCursor.read_string()
            val len = indexCursor.read_u32_le().toInt()

            val index: Index = if (version.version_major() >= VersionMajor.PathHashIndex) {
                val path_hash_seed = indexCursor.read_u64_le()

                // path_hash_index
                val path_hash_index: List<Pair<ULong, Int>>? = if (indexCursor.read_u32_le() != 0u) {
                    val path_hash_index_offset = indexCursor.read_u64_le().toLong()
                    val path_hash_index_size = indexCursor.read_u64_le().toLong()
                    val pathHashHash = indexCursor.read_len(20)

                    reader.position(path_hash_index_offset)
                    val phiBuf = ByteArray(path_hash_index_size.toInt())
                    val phiBb = ByteBuffer.wrap(phiBuf)
                    while (phiBb.hasRemaining()) {
                        val n = reader.read(phiBb)
                        if (n < 0) throw EOFException("unexpected EOF reading path_hash_index")
                    }
                    if (footer.encrypted) {
                        decrypt(key, phiBuf)
                    }
                    val phiReader = ByteArrayInputStream(phiBuf)
                    val count = phiReader.read_u32_le().toInt()
                    val list = mutableListOf<Pair<ULong, Int>>()
                    for (i in 0 until count) {
                        val h = phiReader.read_u64_le()
                        val off = phiReader.read_i32_le()
                        list.add(Pair(h, off))
                    }
                    // trailing 0 already consumed? In write, they write 0 after; read does not consume? But we already read count, loop, no extra.
                    // The phi buffer includes final 0 u32, but Rust code reads only count entries and ignores trailing.
                    // We ignore remaining bytes.
                    list
                } else {
                    null
                }

                val full_directory_index: TreeMap<String, TreeMap<String, Int>>? = if (indexCursor.read_u32_le() != 0u) {
                    val fdi_offset = indexCursor.read_u64_le().toLong()
                    val fdi_size = indexCursor.read_u64_le().toLong()
                    val fdiHash = indexCursor.read_len(20)

                    reader.position(fdi_offset)
                    val fdiBuf = ByteArray(fdi_size.toInt())
                    val fdiBb = ByteBuffer.wrap(fdiBuf)
                    while (fdiBb.hasRemaining()) {
                        val n = reader.read(fdiBb)
                        if (n < 0) throw EOFException("unexpected EOF reading full_directory_index")
                    }
                    if (footer.encrypted) {
                        decrypt(key, fdiBuf)
                    }
                    val fdiReader = ByteArrayInputStream(fdiBuf)
                    val dirCount = fdiReader.read_u32_le().toInt()
                    val directories = TreeMap<String, TreeMap<String, Int>>()
                    for (i in 0 until dirCount) {
                        val dir_name = fdiReader.read_string()
                        val fileCount = fdiReader.read_u32_le().toInt()
                        val files = TreeMap<String, Int>()
                        for (j in 0 until fileCount) {
                            val file_name = fdiReader.read_string()
                            files[file_name] = fdiReader.read_i32_le()
                        }
                        directories[dir_name] = files
                    }
                    directories
                } else {
                    null
                }

                val size = indexCursor.read_u32_le().toInt()
                val encoded_entries = indexCursor.read_len(size)
                val non_encoded_entry_count = indexCursor.read_u32_le().toInt()
                val non_encoded_entries = mutableListOf<Entry>()
                for (i in 0 until non_encoded_entry_count) {
                    non_encoded_entries.add(Entry.read(indexCursor, version, context))
                }

                val entries_by_path = TreeMap<String, Entry>()
                if (full_directory_index != null) {
                    val encodedBytes = encoded_entries
                    // Custom InputStream that supports seeking via position
                    val encodedStream = object : InputStream() {
                        var cursor_pos = 0
                        override fun read(): Int {
                            if (cursor_pos >= encodedBytes.size) return -1
                            return encodedBytes[cursor_pos++].toInt() and 0xFF
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (cursor_pos >= encodedBytes.size) return -1
                            val avail = minOf(len, encodedBytes.size - cursor_pos)
                            System.arraycopy(encodedBytes, cursor_pos, b, off, avail)
                            cursor_pos += avail
                            return avail
                        }
                        fun seek_position(p: Long) {
                            cursor_pos = p.toInt()
                        }
                    }

                    for ((dir_name, dir) in full_directory_index) {
                        for ((file_name, encoded_offset) in dir) {
                            if (encoded_offset == Int.MIN_VALUE) {
                                continue
                            }
                            val entry: Entry = if (encoded_offset >= 0) {
                                encodedStream.seek_position(encoded_offset.toLong())
                                Entry.read_encoded(encodedStream, version, context)
                            } else {
                                val idx = (-encoded_offset) - 1
                                non_encoded_entries[idx]
                            }
                            val path = dir_name.removePrefix("/") + file_name
                            entries_by_path[path] = entry
                        }
                    }
                }

                Index(
                    path_hash_seed = path_hash_seed,
                    entries = entries_by_path
                )
            } else {
                val entries = TreeMap<String, Entry>()
                for (i in 0 until len) {
                    val path = indexCursor.read_string()
                    val entry = Entry.read(indexCursor, version, context)
                    entries[path] = entry
                }
                Index(
                    path_hash_seed = null,
                    entries = entries
                )
            }

            return Pak(
                version = version,
                mount_point = mount_point,
                index_offset = footer.index_offset,
                index = index,
                encrypted_index = footer.encrypted,
                encryption_guid = footer.encryption_uuid,
                compression = footer.compression.toMutableList()
            )
        }
    }

    // Rust: repak/src/pak.rs:535 Pak::write
    @Suppress("FunctionName", "PropertyName", "VariableNaming")
    fun write(writer: SeekableByteChannel, key: Key, context: RepakContext? = null) {
        val index_offset = writer.position()

        val indexBufBaos = ByteArrayOutputStream()
        // Use ByteArrayOutputStream as cursor
        indexBufBaos.write_string(this.mount_point)

        var secondary_index: Pair<ByteArray, ByteArray>? = null

        if (this.version < Version.V10) {
            val record_count = this.index.entries.size.toUInt()
            indexBufBaos.write_u32_le(record_count)
            for ((path, entry) in this.index.entries) {
                indexBufBaos.write_string(path)
                entry.write(indexBufBaos, this.version, EntryLocation.Index, context)
            }
        } else {
            val record_count = this.index.entries.size.toUInt()
            val path_hash_seed = this.index.path_hash_seed ?: 0uL
            indexBufBaos.write_u32_le(record_count)
            indexBufBaos.write_u64_le(path_hash_seed)

            // encoded entries and offsets
            val offsets = mutableListOf<UInt>()
            val encodedBaos = ByteArrayOutputStream()
            for (entry in this.index.entries.values) {
                offsets.add(encodedBaos.size().toUInt())
                entry.write_encoded(encodedBaos)
            }
            val encoded_entries = encodedBaos.toByteArray()

            val bytes_before_phi: ULong = run {
                var size = 0uL
                size += 4u // mount point len
                size += this.mount_point.toByteArray(Charsets.UTF_8).size.toULong() + 1u // NUL
                size += 8u // path hash seed
                size += 4u // record count
                size += 4u // has path hash index
                size += 8u + 8u + 20u // phi offset, size, hash
                size += 4u // has full directory index
                size += 8u + 8u + 20u // fdi offset, size, hash
                size += 4u // encoded entry size
                size += encoded_entries.size.toULong()
                size += 4u // unused file count
                size
            }

            val path_hash_index_offset = index_offset.toULong() + bytes_before_phi

            val phiBaos = ByteArrayOutputStream()
            generate_path_hash_index(phiBaos, path_hash_seed, this.index.entries, offsets)

            val fdiBaos = ByteArrayOutputStream()
            generate_full_directory_index(fdiBaos, this.index.entries, offsets)
            val phi_buf = phiBaos.toByteArray()
            val fdi_buf = fdiBaos.toByteArray()

            val full_directory_index_offset = path_hash_index_offset + phi_buf.size.toULong()

            indexBufBaos.write_u32_le(1u) // we have path hash index
            indexBufBaos.write_u64_le(path_hash_index_offset)
            indexBufBaos.write_u64_le(phi_buf.size.toULong())
            indexBufBaos.write(hash(phi_buf).bytes)

            indexBufBaos.write_u32_le(1u) // we have full directory index
            indexBufBaos.write_u64_le(full_directory_index_offset)
            indexBufBaos.write_u64_le(fdi_buf.size.toULong())
            indexBufBaos.write(hash(fdi_buf).bytes)

            indexBufBaos.write_u32_le(encoded_entries.size.toUInt())
            indexBufBaos.write(encoded_entries)

            indexBufBaos.write_u32_le(0u)

            secondary_index = Pair(phi_buf, fdi_buf)
        }

        val index_buf = indexBufBaos.toByteArray()
        val index_hash = hash(index_buf)

        // write index_buf to writer
        var written = 0
        val out = Channels.newOutputStream(writer)
        out.write(index_buf)
        out.flush()

        if (secondary_index != null) {
            out.write(secondary_index.first)
            out.write(secondary_index.second)
            out.flush()
        }

        val footer = Footer(
            encryption_uuid = null,
            encrypted = false,
            magic = MAGIC,
            version = this.version,
            version_major = this.version.version_major(),
            index_offset = index_offset,
            index_size = index_buf.size.toLong(),
            hash = index_hash,
            frozen = false,
            compression = this.compression.toList()
        )
        footer.write(out)
        out.flush()
    }

    // Rust: repak/src/pak.rs:535 overload without context
    fun write(writer: SeekableByteChannel, key: Key) = write(writer, key, null)
}

// Rust: repak/src/pak.rs:123 Index
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class Index(
    var path_hash_seed: ULong?,
    var entries: TreeMap<String, Entry>
) {
    companion object {
        // Rust: repak/src/pak.rs:130 new
        fun new(path_hash_seed: ULong?): Index = Index(path_hash_seed, TreeMap())
    }

    // Rust: repak/src/pak.rs:137 entries
    fun entries(): TreeMap<String, Entry> = this.entries

    // Rust: repak/src/pak.rs:141 into_entries
    fun into_entries(): TreeMap<String, Entry> = this.entries

    // Rust: repak/src/pak.rs:145 add_entry
    fun add_entry(path: String, entry: Entry) {
        this.entries[path] = entry
    }
}

// Rust: repak/src/pak.rs:150 decrypt
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun decrypt(key: Key, bytes: ByteArray) {
    when (key) {
        is Key.None -> throw RepakError.Encrypted
        is Key.Some -> {
            val secret = key.key ?: throw RepakError.Encrypted
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret)
            // Rust decrypts per 16-byte chunks in place (pak.rs:150-161); trailing bytes are left untouched
            if (bytes.isNotEmpty()) {
                var offset = 0
                while (offset + 16 <= bytes.size) {
                    val dec = cipher.doFinal(bytes, offset, 16)
                    System.arraycopy(dec, 0, bytes, offset, 16)
                    offset += 16
                }
            }
        }
    }
}

// Rust: repak/src/pak.rs:348 Data
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class Data(val bytes: ByteArray) : java.io.Serializable {
    fun as_ref(): ByteArray = bytes
}

// Rust: repak/src/pak.rs:355 EntryBuilder
@Suppress("FunctionName", "PropertyName", "VariableNaming", "ClassName")
class EntryBuilder(
    var allowed_compression: List<Compression>
) {
    // Rust: repak/src/pak.rs:362 build_entry
    fun <D> build_entry(compress: Boolean, data: D): PartialEntry<D> {
        val compression = if (compress) this.allowed_compression else emptyList()
        return build_partial_entry(compression, data)
    }

    // Rust: repak/src/pak.rs:362 overload for ByteArray
    fun build_entry_bytes(compress: Boolean, data: ByteArray): PartialEntry<ByteArray> {
        val compression = if (compress) this.allowed_compression else emptyList()
        return build_partial_entry(compression, data)
    }
}

// Rust: repak/src/pak.rs:674 hash
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun hash(data: ByteArray): Hash {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(data)
    return Hash(md.digest())
}

// Rust: repak/src/pak.rs:674 hash overload for ByteArray segment
fun hash(data: List<Byte>): Hash = hash(data.toByteArray())

// Rust: repak/src/pak.rs:681 generate_path_hash_index
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun generate_path_hash_index(
    writer: OutputStream,
    path_hash_seed: ULong,
    entries: TreeMap<String, Entry>,
    offsets: List<UInt>
) {
    writer.write_u32_le(entries.size.toUInt())
    val keys = entries.keys.toList()
    for (i in keys.indices) {
        val path = keys[i]
        val offset = offsets[i]
        val path_hash = fnv64_path(path, path_hash_seed)
        writer.write_u64_le(path_hash)
        writer.write_u32_le(offset)
    }
    writer.write_u32_le(0u)
}

// Rust: repak/src/pak.rs:699 fnv64
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun fnv64(data: Iterable<UByte>, offset: ULong): ULong {
    val OFFSET: ULong = 0xcbf29ce484222325uL
    val PRIME: ULong = 0x00000100000001b3uL
    var hash = OFFSET + offset // wrapping_add
    for (b in data) {
        hash = hash xor b.toULong()
        hash *= PRIME
    }
    return hash
}

// Rust: repak/src/pak.rs:699 fnv64 overload for ByteArray
fun fnv64(data: ByteArray, offset: ULong): ULong = fnv64(data.map { it.toUByte() }, offset)

// Rust: repak/src/pak.rs:713 fnv64_path
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun fnv64_path(path: String, offset: ULong): ULong {
    val lower = path.lowercase(Locale.ROOT)
    // Rust: lower.encode_utf16().flatMap(u16::to_le_bytes)
    val bytes = mutableListOf<UByte>()
    for (ch in lower) {
        val code = ch.code.toUShort()
        // to_le_bytes
        bytes.add((code.toInt() and 0xFF).toUByte())
        bytes.add(((code.toInt() shr 8) and 0xFF).toUByte())
    }
    return fnv64(bytes, offset)
}

// Rust: repak/src/pak.rs:719 split_path_child
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun split_path_child(path: String): Pair<String, String>? {
    if (path == "/" || path.isEmpty()) {
        return null
    } else {
        val p = if (path.endsWith("/")) path.dropLast(1) else path
        val idx = p.lastIndexOf('/')
        return if (idx >= 0) {
            Pair(p.substring(0, idx + 1), p.substring(idx + 1))
        } else {
            Pair("/", p)
        }
    }
}

// Rust: repak/src/pak.rs:732 generate_full_directory_index
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun generate_full_directory_index(
    writer: OutputStream,
    entries: TreeMap<String, Entry>,
    offsets: List<UInt>
) {
    val fdi: TreeMap<String, TreeMap<String, UInt>> = TreeMap()
    val keys = entries.keys.toList()
    for (i in keys.indices) {
        val path = keys[i]
        val offset = offsets[i]
        var p: String = path
        while (true) {
            val split = split_path_child(p) ?: break
            val parent = split.first
            p = parent
            fdi.getOrPut(p) { TreeMap() }
        }
        val (directory, filename) = split_path_child(path) ?: throw RepakError.Other("none root path")
        fdi.getOrPut(directory) { TreeMap() }[filename] = offset
    }

    writer.write_u32_le(fdi.size.toUInt())
    for ((directory, files) in fdi) {
        writer.write_string(directory)
        writer.write_u32_le(files.size.toUInt())
        for ((filename, offset) in files) {
            writer.write_string(filename)
            writer.write_u32_le(offset)
        }
    }
}

// Rust: repak/src/pak.rs:763 encrypt
@Suppress("FunctionName", "PropertyName", "VariableNaming")
fun encrypt(key: SecretKeySpec, bytes: ByteArray) {
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    // Rust encrypts per 16-byte chunks in place; trailing bytes are left untouched
    if (bytes.isNotEmpty()) {
        var offset = 0
        while (offset + 16 <= bytes.size) {
            val enc = cipher.doFinal(bytes, offset, 16)
            System.arraycopy(enc, 0, bytes, offset, 16)
            offset += 16
        }
    }
}
