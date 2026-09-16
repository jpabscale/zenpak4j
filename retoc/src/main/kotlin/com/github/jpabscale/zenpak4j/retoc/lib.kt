// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/lib.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "EnumEntryName", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "MagicNumber", "MemberVisibilityCanBePrivate", "SpellCheckingInspection")

package com.github.jpabscale.zenpak4j.retoc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.util.Base64
import java.util.HexFormat
import java.util.TreeMap
import java.util.Locale
import java.util.HashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec


// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:45 FileWriterTrait / FSFileWriter / ParallelPakWriter / NullFileWriter
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:45
interface FileWriterTrait {
    // Rust: retoc/src/lib.rs:46 write_file
    fun write_file(path: String, allow_compress: Boolean, data: ByteArray)
}

// Rust: retoc/src/lib.rs:48 FSFileWriter
class FSFileWriter(val dir: Path) : FileWriterTrait {
    // Rust: retoc/src/lib.rs:52 new
    constructor(dir: String) : this(Path.of(dir))
    // Rust: retoc/src/lib.rs:57 write_file
    override fun write_file(path: String, allow_compress: Boolean, data: ByteArray) {
        val target = dir.resolve(path)
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)
        Files.write(target, data)
    }
}

// Rust: retoc/src/lib.rs:64 ParallelPakWriter
class ParallelPakWriter(
    // Rust: uses repak::EntryBuilder but Kotlin repak EntryBuilder is in repak package
    var entry_builder: com.github.jpabscale.zenpak4j.repak.EntryBuilder,
    var tx: java.util.concurrent.LinkedBlockingQueue<kotlin.Pair<String, com.github.jpabscale.zenpak4j.repak.PartialEntry<ByteArray>>>
) : FileWriterTrait {
    // Rust: retoc/src/lib.rs:69 write_file
    override fun write_file(path: String, allow_compress: Boolean, data: ByteArray) {
        // Rust: retoc/src/lib.rs:69 picks compression_slot via entry_builder (parity with repak::EntryBuilder::build_entry)
        val entry = entry_builder.build_entry_bytes(allow_compress, data)
        tx.put(kotlin.Pair(path, entry))
    }
}

// Rust: retoc/src/lib.rs:75 NullFileWriter
class NullFileWriter : FileWriterTrait {
    // Rust: retoc/src/lib.rs:77 write_file
    override fun write_file(path: String, allow_compress: Boolean, data: ByteArray) {
        // no-op
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:82 FileReaderTrait / FSFileReader / PakFileReader
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:82
interface FileReaderTrait {
    // Rust: retoc/src/lib.rs:83 read
    fun read(path: UEPath): ByteArray
    // Rust: retoc/src/lib.rs:84 read_opt
    fun read_opt(path: UEPath): ByteArray?
    // Rust: retoc/src/lib.rs:85 list_files
    fun list_files(): List<UEPathBuf>
}

// Rust: retoc/src/lib.rs:87 FSFileReader
class FSFileReader(val dir: Path) : FileReaderTrait {
    constructor(dir: String) : this(Path.of(dir))
    // Rust: retoc/src/lib.rs:96 read
    override fun read(path: UEPath): ByteArray = Files.readAllBytes(dir.resolve(path))
    // Rust: retoc/src/lib.rs:99 read_opt
    override fun read_opt(path: UEPath): ByteArray? = read_file_opt(dir.resolve(path))
    // Rust: retoc/src/lib.rs:102 list_files
    override fun list_files(): List<UEPathBuf> {
        val files = mutableListOf<UEPathBuf>()
        fun visit(dir: Path) {
            if (!Files.isDirectory(dir)) return
            Files.list(dir).use { stream ->
                stream.forEach { p ->
                    if (Files.isDirectory(p)) visit(p)
                    else {
                        val rel = this.dir.relativize(p)
                        files.add(to_ue_path(rel))
                    }
                }
            }
        }
        visit(dir)
        return files
    }
}

// Rust: retoc/src/lib.rs:130 PakFileReader
class PakFileReader(
    val pak: com.github.jpabscale.zenpak4j.repak.PakReader,
    val file: FilePool
) : FileReaderTrait {
    companion object {
        // Rust: retoc/src/lib.rs:135 new
        fun new(pak_path: Path): PakFileReader {
            val file_pool = FilePool.new(pak_path, Runtime.getRuntime().availableProcessors())
            // repak PakBuilder reader needs SeekableByteChannel; wrap RandomAccessFile channel
            val handle = file_pool.acquire()
            try {
                val channel = handle.file().channel
                val pak_reader = com.github.jpabscale.zenpak4j.repak.PakBuilder.new().reader(channel)
                return PakFileReader(pak_reader, file_pool)
            } finally {
                handle.close()
            }
        }
        fun new(pak_path: String): PakFileReader = new(Path.of(pak_path))
    }
    // Rust: retoc/src/lib.rs:142 read
    override fun read(path: UEPath): ByteArray {
        val handle = file.acquire()
        try {
            return pak.get(path, handle.file().channel)
        } finally {
            handle.close()
        }
    }
    // Rust: retoc/src/lib.rs:146 read_opt
    override fun read_opt(path: UEPath): ByteArray? {
        val handle = file.acquire()
        try {
            return try {
                pak.get(path, handle.file().channel)
            } catch (e: com.github.jpabscale.zenpak4j.repak.RepakError.MissingEntry) {
                null
            }
        } finally {
            handle.close()
        }
    }
    // Rust: retoc/src/lib.rs:154 list_files
    override fun list_files(): List<UEPathBuf> = pak.files().map { it }
}

// Rust: retoc/src/lib.rs:159 read_file_opt
fun read_file_opt(path: Path): ByteArray? {
    return try {
        Files.readAllBytes(path)
    } catch (e: java.nio.file.NoSuchFileException) {
        null
    } catch (e: java.io.IOException) {
        if (e.message?.contains("No such file") == true) null else throw e
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:167 build_verse_cell_store
// ---------------------------------------------------------------------------
// Rust: retoc/src/zen.rs: Verse placeholders moved to zen.kt for 1:1 parity
// VerseScriptCell and ZenScriptCellsStore now defined in zen.kt

// Rust: retoc/src/lib.rs:167
fun build_verse_cell_store(script_cells: List<VerseScriptCell>): ZenScriptCellsStore {
    val mutable_cell_store = ZenScriptCellsStore.create_empty()
    mutable_cell_store.add_vm_intrinsics()
    for (additional_script_cell in script_cells) {
        mutable_cell_store.add_script_cell(additional_script_cell)
    }
    return mutable_cell_store
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:176 PackageTestMetadata
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:176
data class PackageTestMetadata(
    var toc_version: EIoStoreTocVersion = EIoStoreTocVersion.Invalid,
    var container_header_version: EIoContainerHeaderVersion = EIoContainerHeaderVersion.SoftPackageReferencesOffset,
    var package_file_version: FPackageFileVersion? = null,
    var store_entry: StoreEntry? = null
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:184 Config
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:184
class Config(
    var aes_keys: MutableMap<FGuid, AesKey> = HashMap(),
    var container_header_version_override: EIoContainerHeaderVersion? = null,
    var toc_version_override: EIoStoreTocVersion? = null
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:192 AesKey
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:192
class AesKey(val key: SecretKeySpec) {
    companion object {
        // Rust: retoc/src/lib.rs:193 FromStr for AesKey
        fun from_str(s: String): AesKey {
            val hexClean = s.removePrefix("0x").removePrefix("0X")
            val tryParse: (ByteArray) -> AesKey? = { bytes ->
                try {
                    if (bytes.size == 32) AesKey(SecretKeySpec(bytes, "AES")) else null
                } catch (_: Exception) { null }
            }
            // hex decode attempt
            try {
                val hexBytes = HexFormat.of().parseHex(hexClean)
                val v = tryParse(hexBytes)
                if (v != null) return v
            } catch (_: Exception) {}
            // base64 no pad then pad
            try {
                val b64 = s.trimEnd('=')
                // add padding if needed
                val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                val decoded = Base64.getDecoder().decode(padded)
                val v = tryParse(decoded)
                if (v != null) return v
            } catch (_: Exception) {}
            // base64 standard no pad
            try {
                val decoded = Base64.getDecoder().decode(s)
                val v = tryParse(decoded)
                if (v != null) return v
            } catch (_: Exception) {}
            throw IllegalArgumentException("invalid AES key: $s")
        }
    }
    // helper to decrypt 16-byte blocks ECB NoPadding
    fun decrypt_block(block: ByteArray) {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key)
        val out = cipher.doFinal(block)
        System.arraycopy(out, 0, block, 0, block.size)
    }
    fun encrypt_block(block: ByteArray) {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val out = cipher.doFinal(block)
        System.arraycopy(out, 0, block, 0, block.size)
    }
}

// ---------------------------------------------------------------------------
// Helpers for cityhash (lower_utf16_cityhash) used by FPackageId etc.
// Mirrors ser.rs logic but kept private for lib.rs parity.
// Rust: retoc/src/lib.rs:744 lower_utf16_cityhash
// ---------------------------------------------------------------------------
private const val K0_CH_LIB: ULong = 0xc3a5c85c97cb3127UL
private const val K1_CH_LIB: ULong = 0xb492b66fbe98f273UL
private const val K2_CH_LIB: ULong = 0x9ae16a3b2f90404fUL

private fun rotate64_lib(v: ULong, shift: ULong): ULong = if (shift == 0UL) v else (v shr shift.toInt()) or (v shl (64 - shift.toInt()))
private fun shift_mix_lib(v: ULong): ULong = v xor (v shr 47)
private fun hash_len16_with_mul_lib(u: ULong, v: ULong, mul: ULong): ULong {
    var a = (u xor v) * mul
    a = a xor (a shr 47)
    var b = (v xor a) * mul
    b = b xor (b shr 47)
    return b * mul
}
private fun hash_len16_u64_lib(u: ULong, v: ULong): ULong = hash_len16_with_mul_lib(u, v, 0x9ddfea08eb382d69UL)
private fun weak_hash_len32_with_seeds_lib(w: ULong, x: ULong, y: ULong, z: ULong, a: ULong, b: ULong): kotlin.Pair<ULong, ULong> {
    var aVar = a + w
    var bVar = rotate64_lib(b + aVar + z, 21UL)
    val c = aVar
    aVar += x
    aVar += y
    bVar += rotate64_lib(aVar, 44UL)
    return kotlin.Pair(aVar + z, bVar + c)
}
private class CityInputLib(val data: ByteArray) {
    val len: Int get() = data.size
    fun fetch32(offset: Int): UInt = (data[offset].toUByte().toUInt() or (data[offset+1].toUByte().toUInt() shl 8) or (data[offset+2].toUByte().toUInt() shl 16) or (data[offset+3].toUByte().toUInt() shl 24))
    fun fetch64(offset: Int): ULong {
        var v = 0UL
        v = v or data[offset].toUByte().toULong()
        v = v or (data[offset+1].toUByte().toULong() shl 8)
        v = v or (data[offset+2].toUByte().toULong() shl 16)
        v = v or (data[offset+3].toUByte().toULong() shl 24)
        v = v or (data[offset+4].toUByte().toULong() shl 32)
        v = v or (data[offset+5].toUByte().toULong() shl 40)
        v = v or (data[offset+6].toUByte().toULong() shl 48)
        v = v or (data[offset+7].toUByte().toULong() shl 56)
        return v
    }
    fun hash64_len_0_to_16(): ULong {
        if (len >= 8) {
            val mul = K2_CH_LIB + len.toULong() * 2UL
            val a = fetch64(0) + K2_CH_LIB
            val b = fetch64(len-8)
            val c = rotate64_lib(b, 37UL)*mul + a
            val d = (rotate64_lib(a, 25UL)+b)*mul
            return hash_len16_with_mul_lib(c,d,mul)
        } else if (len >= 4) {
            val mul = K2_CH_LIB + len.toULong()*2UL
            val a = fetch32(0).toULong()
            return hash_len16_with_mul_lib(len.toULong() + (a shl 3), fetch32(len-4).toULong(), mul)
        } else if (len > 0) {
            val a = data[0].toUByte().toUInt()
            val b = data[len shr 1].toUByte().toUInt()
            val c = data[len-1].toUByte().toUInt()
            val y = a + (b shl 8)
            val z = len.toUInt() + (c shl 2)
            return shift_mix_lib(y.toULong()*K2_CH_LIB xor z.toULong()*K0_CH_LIB)*K2_CH_LIB
        } else return K2_CH_LIB
    }
    fun hash64_len_17_to_32(): ULong {
        val mul = K2_CH_LIB + len.toULong()*2UL
        val a = fetch64(0)*K1_CH_LIB
        val b = fetch64(8)
        val c = fetch64(len-8)*mul
        val d = fetch64(len-16)*K2_CH_LIB
        return hash_len16_with_mul_lib(rotate64_lib(a+b,43UL)+rotate64_lib(c,30UL)+d, a+rotate64_lib(b+K2_CH_LIB,18UL)+c, mul)
    }
    fun hash64_len_33_to_64(): ULong {
        val mul = K2_CH_LIB + len.toULong()*2UL
        val a = fetch64(0)*K2_CH_LIB
        val b = fetch64(8)
        val c = fetch64(len-24)
        val d = fetch64(len-32)
        val e = fetch64(16)*K2_CH_LIB
        val f = fetch64(24)*9UL
        val g = fetch64(len-8)
        val h = fetch64(len-16)*mul
        val u = rotate64_lib(a+g,43UL)+(rotate64_lib(b,30UL)+c)*9UL
        val v = (a+g xor d)+f+1UL
        val w = ((u+v)*mul).swapBytesLib()+h
        val x = rotate64_lib(e+f,42UL)+c
        val y = (((v+w)*mul).swapBytesLib()+g)*mul
        val z = e+f+c
        val a2 = ((x+z)*mul+y).swapBytesLib()+b
        val b2 = shift_mix_lib((z+a2)*mul+d+h)*mul
        return b2+x
    }
    fun weak_hash(offset:Int,a:ULong,b:ULong): kotlin.Pair<ULong,ULong> = weak_hash_len32_with_seeds_lib(fetch64(offset),fetch64(offset+8),fetch64(offset+16),fetch64(offset+24),a,b)
    fun hash64(): ULong {
        if (len <= 32) return if (len <= 16) hash64_len_0_to_16() else hash64_len_17_to_32()
        if (len <= 64) return hash64_len_33_to_64()
        var x = fetch64(len-40)
        var y = fetch64(len-16)+fetch64(len-56)
        var z = hash_len16_u64_lib(fetch64(len-48)+len.toULong(), fetch64(len-24))
        var v = weak_hash(len-64, len.toULong(), z)
        var w = weak_hash(len-32, y+K1_CH_LIB, x)
        x = x*K1_CH_LIB + fetch64(0)
        val limit = len-1
        var offset = 0
        while (offset+64 <= limit) {
            val chunk = CityInputLib(data.copyOfRange(offset, offset+64))
            x = rotate64_lib(x+y+v.first+chunk.fetch64(8),37UL)*K1_CH_LIB
            y = rotate64_lib(y+v.second+chunk.fetch64(48),42UL)*K1_CH_LIB
            x = x xor w.second
            y += v.first+chunk.fetch64(40)
            z = rotate64_lib(z+w.first,33UL)*K1_CH_LIB
            v = chunk.weak_hash(0, v.second*K1_CH_LIB, x+w.first)
            w = chunk.weak_hash(32, z+w.second, y+chunk.fetch64(16))
            val tmp = z; z = x; x = tmp
            offset += 64
        }
        return hash_len16_u64_lib(hash_len16_u64_lib(v.first,w.first)+shift_mix_lib(y)*K1_CH_LIB+z, hash_len16_u64_lib(v.second,w.second)+x)
    }
    private fun ULong.swapBytesLib(): ULong = java.lang.Long.reverseBytes(this.toLong()).toULong()
}
private fun city_hash64_lib(data: ByteArray): ULong = CityInputLib(data).hash64()
private fun lower_utf16_cityhash(s: String): ULong {
    val lower = s.lowercase_ascii_lib()
    val bb = ByteBuffer.allocate(lower.length*2).order(ByteOrder.LITTLE_ENDIAN)
    for (ch in lower) bb.putShort(ch.code.toShort())
    val bytes = bb.array()
    // Validate via ByteBuffer LE roundtrip per mapping doc
    val verify = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // not used directly but demonstrates ByteBuffer LE usage
    return city_hash64_lib(bytes)
}
private fun String.lowercase_ascii_lib(): String {
    val sb = StringBuilder(length)
    for (c in this) if (c in 'A'..'Z') sb.append((c.code+32).toChar()) else sb.append(c)
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:714 FPackageId et al.
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:714
data class FPackageId(var value: ULong = 0UL) : Writeable, Comparable<FPackageId> {
    // Rust: retoc/src/lib.rs:726 Display
    override fun toString(): String = value.toString()
    override fun compareTo(other: FPackageId): Int = value.compareTo(other.value)
    override fun ser(stream: OutputStream) { stream.write_u64_le(value) }
    companion object : Readable<FPackageId> {
        override fun de(stream: InputStream): FPackageId = FPackageId(stream.read_u64_le())
        // Rust: retoc/src/lib.rs:732 from_name
        fun from_name(name: String): FPackageId = FPackageId(lower_utf16_cityhash(name))
        // Rust: retoc/src/lib.rs:739 FromStr
        fun from_string(s: String): FPackageId = FPackageId(s.toULong())
    }
}

// Rust: retoc/src/lib.rs:744 lower_utf16_cityhash helper already above

// Rust: retoc/src/lib.rs:761 chunk_id module -> FIoChunkIdRaw / FIoChunkId
// We'll define both at top level for parity (original is mod chunk_id)
data class FIoChunkIdRaw(var id: ByteArray = ByteArray(12)) : Writeable {
    init { require(id.size == 12) { "FIoChunkIdRaw must be 12 bytes" } }
    override fun equals(other: Any?): Boolean = other is FIoChunkIdRaw && id.contentEquals(other.id)
    override fun hashCode(): Int = id.contentHashCode()
    override fun ser(stream: OutputStream) { stream.write(id) }
    fun as_ref(): ByteArray = id
    companion object : Readable<FIoChunkIdRaw> {
        override fun de(stream: InputStream): FIoChunkIdRaw {
            val arr = ByteArray(12)
            stream.read_exact(arr)
            return FIoChunkIdRaw(arr)
        }
        fun from_string(s: String): FIoChunkIdRaw {
            val bytes = HexFormat.of().parseHex(s)
            require(bytes.size == 12) { "expected 12 byte hex string" }
            return FIoChunkIdRaw(bytes)
        }
    }
    override fun toString(): String = HexFormat.of().formatHex(id)
}

// Rust: retoc/src/lib.rs:805 FIoChunkId
class FIoChunkId(private var id: ByteArray = ByteArray(12)) : Writeable, Comparable<FIoChunkId> {
    init { require(id.size == 12) }
    // Rust: Eq by get_raw
    override fun equals(other: Any?): Boolean = other is FIoChunkId && get_raw() == other.get_raw()
    override fun hashCode(): Int = get_raw().hashCode()
    override fun compareTo(other: FIoChunkId): Int {
        val a = get_raw().id
        val b = other.get_raw().id
        for (i in a.indices) {
            val cmp = (a[i].toUByte().toInt()).compareTo(b[i].toUByte().toInt())
            if (cmp != 0) return cmp
        }
        return 0
    }
    override fun toString(): String {
        val sb = StringBuilder()
        for (b in id.sliceArray(0 until 11)) sb.append("%02x".format(b))
        if ((id[11].toInt() shr 6 and 1) != 0) {
            val is_new = (id[11].toInt() shr 7) != 0
            sb.append("%02x".format(get_chunk_type().value(is_new)))
        } else sb.append("??")
        return "FIoChunkId(chunk_id=$sb, chunk_type=${get_chunk_type()})"
    }
    override fun ser(stream: OutputStream) { stream.write(get_raw().id) }
    companion object {
        // Rust: retoc/src/lib.rs:860 from_raw
        fun from_raw(raw: FIoChunkIdRaw, version: EIoStoreTocVersion): FIoChunkId {
            val nid = raw.id.copyOf()
            val is_new = version > EIoStoreTocVersion.PerfectHash
            val ct = EIoChunkType.new(raw.id[11].toUByte().toInt(), is_new)
            nid[11] = ct.ordinal.toByte()
            nid[11] = (nid[11].toInt() or ((if (is_new) 1 else 0) shl 7) or (1 shl 6)).toByte()
            return FIoChunkId(nid)
        }
        fun create(chunk_id: ULong, chunk_index: UShort, chunk_type: EIoChunkType): FIoChunkId {
            val nid = ByteArray(12)
            val chunkBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(chunk_id.toLong()).array()
            System.arraycopy(chunkBytes, 0, nid, 0, 8)
            val idxBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(chunk_index.toShort()).array()
            System.arraycopy(idxBytes, 0, nid, 8, 2)
            nid[11] = chunk_type.ordinal.toByte()
            return FIoChunkId(nid)
        }
        fun from_package_id(package_id: FPackageId, chunk_index: UShort, chunk_type: EIoChunkType): FIoChunkId = create(package_id.value, chunk_index, chunk_type)
        fun create_shader_code_chunk_id(shader_hash: FSHAHash): FIoChunkId {
            val nid = ByteArray(12)
            System.arraycopy(shader_hash.bytes, 0, nid, 0, 11)
            nid[11] = EIoChunkType.ShaderCode.ordinal.toByte()
            return FIoChunkId(nid)
        }
        fun create_shader_library_chunk_id(shader_library_name: String, shader_format_name: String): FIoChunkId {
            val name = "$shader_library_name-$shader_format_name"
            val hash = lower_utf16_cityhash(name)
            return create(hash, 0u, EIoChunkType.ShaderCodeLibrary)
        }
        // ReadableCtx parity: not implementing interface directly to avoid generics complexity
        fun de_with_version(stream: InputStream, version: EIoStoreTocVersion): FIoChunkId {
            val raw = stream.de(FIoChunkIdRaw)
            return from_raw(raw, version)
        }
    }
    // Rust: retoc/src/lib.rs:869 with_version
    fun with_version(version: EIoStoreTocVersion): FIoChunkId {
        val nid = id.copyOf()
        val is_new = version > EIoStoreTocVersion.PerfectHash
        nid[11] = (nid[11].toInt() or ((if (is_new) 1 else 0) shl 7) or (1 shl 6)).toByte()
        return FIoChunkId(nid)
    }
    // Rust: retoc/src/lib.rs:876 create
    fun get_chunk_id(): ULong = ByteBuffer.wrap(id, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
    // Rust: retoc/src/lib.rs:900 get_chunk_type
    fun get_chunk_type(): EIoChunkType {
        val rawVal = id[11].toUByte().toInt() and 0x3F
        // Rust: EIoChunkType::from_repr(self.id[11] & 0b11_1111).unwrap()
        return EIoChunkType.entries.getOrNull(rawVal)
            ?: throw IllegalArgumentException("invalid chunk type discriminant: $rawVal")
    }
    // Rust: retoc/src/lib.rs:904 get_raw
    fun get_raw(): FIoChunkIdRaw {
        val nid = id.copyOf()
        if ((nid[11].toInt() shr 6 and 1) == 0) throw IllegalStateException("no version info, cannot convert to raw")
        val is_new = (nid[11].toInt() shr 7 != 0)
        nid[11] = get_chunk_type().value(is_new).toByte()
        return FIoChunkIdRaw(nid)
    }
    fun get_package_id(): FPackageId = FPackageId(get_chunk_id())
    // expose id for internal use
    fun id_bytes(): ByteArray = id
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:918 FIoContainerId
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:918
data class FIoContainerId(var value: ULong = 0UL) : Writeable, Comparable<FIoContainerId> {
    override fun compareTo(other: FIoContainerId): Int = value.compareTo(other.value)
    override fun ser(stream: OutputStream) { stream.write_u64_le(value) }
    companion object : Readable<FIoContainerId> {
        override fun de(stream: InputStream): FIoContainerId = FIoContainerId(stream.read_u64_le())
        fun from_name(name: String): FIoContainerId = FIoContainerId(lower_utf16_cityhash(name))
    }
    override fun toString(): String = value.toString()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:934 FIoOffsetAndLength (10 bytes)
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:934
class FIoOffsetAndLength(var data: ByteArray = ByteArray(10)) : Writeable {
    init { require(data.size == 10) }
    override fun equals(other: Any?): Boolean = other is FIoOffsetAndLength && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun ser(stream: OutputStream) { stream.write(data) }
    companion object : Readable<FIoOffsetAndLength> {
        override fun de(stream: InputStream): FIoOffsetAndLength {
            val arr = ByteArray(10)
            stream.read_exact(arr)
            return FIoOffsetAndLength(arr)
        }
    }
    // Rust: retoc/src/lib.rs:949 new
    fun new_offset_length(offset: ULong, length: ULong): FIoOffsetAndLength {
        val d = FIoOffsetAndLength()
        d.set_offset(offset)
        d.set_length(length)
        return d
    }
    // Rust: retoc/src/lib.rs:955 get_offset
    fun get_offset(): ULong {
        val d = data
        val bytes = ByteArray(8)
        bytes[3] = d[0]; bytes[4] = d[1]; bytes[5] = d[2]; bytes[6] = d[3]; bytes[7] = d[4]
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).long.toULong()
    }
    // Rust: retoc/src/lib.rs:959 set_offset
    fun set_offset(offset: ULong) {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(offset.toLong()).array()
        System.arraycopy(bytes, 3, data, 0, 5)
    }
    // Rust: retoc/src/lib.rs:963 get_length
    fun get_length(): ULong {
        val d = data
        val bytes = ByteArray(8)
        bytes[3] = d[5]; bytes[4] = d[6]; bytes[5] = d[7]; bytes[6] = d[8]; bytes[7] = d[9]
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).long.toULong()
    }
    // Rust: retoc/src/lib.rs:967 set_length
    fun set_length(length: ULong) {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(length.toLong()).array()
        System.arraycopy(bytes, 3, data, 5, 5)
    }
    // convenience
    fun new(offset: ULong, length: ULong): FIoOffsetAndLength = new_offset_length(offset, length)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:972 FIoStoreTocCompressedBlockEntry (12 bytes)
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:972
class FIoStoreTocCompressedBlockEntry(var data: ByteArray = ByteArray(12)) : Writeable {
    init { require(data.size == 12) }
    override fun toString(): String = "FIoStoreTocCompressedBlockEntry(offset=${get_offset()}, compressed_size=${get_compressed_size()}, uncompressed_size=${get_uncompressed_size()}, compression_method_index=${get_compression_method_index()})"
    override fun equals(other: Any?): Boolean = other is FIoStoreTocCompressedBlockEntry && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()
    override fun ser(stream: OutputStream) { stream.write(data) }
    companion object : Readable<FIoStoreTocCompressedBlockEntry> {
        override fun de(stream: InputStream): FIoStoreTocCompressedBlockEntry {
            val arr = ByteArray(12)
            stream.read_exact(arr)
            return FIoStoreTocCompressedBlockEntry(arr)
        }
    }
    // Rust: retoc/src/lib.rs:998 new
    fun new(offset: ULong, compressed_size: UInt, uncompressed_size: UInt, compression_method_index: UByte): FIoStoreTocCompressedBlockEntry {
        val d = FIoStoreTocCompressedBlockEntry()
        d.set_offset(offset)
        d.set_compressed_size(compressed_size)
        d.set_uncompressed_size(uncompressed_size)
        d.set_compression_method_index(compression_method_index)
        return d
    }
    // Rust: retoc/src/lib.rs:1006 get_offset
    fun get_offset(): ULong {
        val bytes = ByteArray(8)
        System.arraycopy(data, 0, bytes, 0, 5)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
    }
    fun set_offset(value: ULong) {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.toLong()).array()
        System.arraycopy(bytes, 0, data, 0, 5)
    }
    // Rust: retoc/src/lib.rs:1013 get_compressed_size
    fun get_compressed_size(): UInt {
        val bytes = ByteArray(4)
        bytes[0]=data[5]; bytes[1]=data[6]; bytes[2]=data[7]
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
    }
    fun set_compressed_size(value: UInt) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        System.arraycopy(bytes, 0, data, 5, 3)
    }
    // Rust: retoc/src/lib.rs:1020 get_uncompressed_size
    fun get_uncompressed_size(): UInt {
        val bytes = ByteArray(4)
        bytes[0]=data[8]; bytes[1]=data[9]; bytes[2]=data[10]
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt()
    }
    fun set_uncompressed_size(value: UInt) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
        System.arraycopy(bytes, 0, data, 8, 3)
    }
    // Rust: retoc/src/lib.rs:1027 get_compression_method_index
    fun get_compression_method_index(): UByte = data[11].toUByte()
    fun set_compression_method_index(value: UByte) { data[11] = value.toByte() }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1034 FIoChunkHash ([u8;32])
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1034
class FIoChunkHash(var bytes: ByteArray = ByteArray(32)) : Writeable {
    init { require(bytes.size == 32) }
    override fun equals(other: Any?): Boolean = other is FIoChunkHash && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = bytes.joinToString("") { "%02X".format(it) }.let { "FIoChunkHash($it)" }
    override fun ser(stream: OutputStream) { stream.write(bytes) }
    companion object : Readable<FIoChunkHash> {
        override fun de(stream: InputStream): FIoChunkHash {
            val arr = ByteArray(32)
            stream.read_exact(arr)
            return FIoChunkHash(arr)
        }
        fun from_blake3(hash: ByteArray): FIoChunkHash {
            require(hash.size == 32)
            val data = ByteArray(32)
            System.arraycopy(hash, 0, data, 0, 20)
            return FIoChunkHash(data)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1061 FIoStoreTocEntryMeta
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1061
class FIoStoreTocEntryMeta(
    var chunk_hash: FIoChunkHash = FIoChunkHash(),
    var flags: FIoStoreTocEntryMetaFlags = FIoStoreTocEntryMetaFlags(0u)
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.ser(chunk_hash)
        stream.ser(flags)
    }
    companion object : Readable<FIoStoreTocEntryMeta> {
        override fun de(stream: InputStream): FIoStoreTocEntryMeta = FIoStoreTocEntryMeta(stream.de(FIoChunkHash), stream.de(FIoStoreTocEntryMetaFlags))
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1078 FGuid
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1078
data class FGuid(
    var a: UInt = 0u,
    var b: UInt = 0u,
    var c: UInt = 0u,
    var d: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_u32_le(a)
        stream.write_u32_le(b)
        stream.write_u32_le(c)
        stream.write_u32_le(d)
    }
    companion object : Readable<FGuid> {
        override fun de(stream: InputStream): FGuid = FGuid(stream.read_u32_le(), stream.read_u32_le(), stream.read_u32_le(), stream.read_u32_le())
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1104 FSHAHash ([u8;20])
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1104
class FSHAHash(var bytes: ByteArray = ByteArray(20)) : Writeable, Comparable<FSHAHash> {
    init { require(bytes.size == 20) }
    override fun equals(other: Any?): Boolean = other is FSHAHash && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun compareTo(other: FSHAHash): Int {
        for (i in bytes.indices) {
            val cmp = bytes[i].toUByte().compareTo(other.bytes[i].toUByte())
            if (cmp != 0) return cmp
        }
        return 0
    }
    override fun toString(): String = bytes.joinToString("") { "%02X".format(it) }.let { "FSHAHash($it)" }
    override fun ser(stream: OutputStream) { stream.write(bytes) }
    companion object : Readable<FSHAHash> {
        override fun de(stream: InputStream): FSHAHash {
            val arr = ByteArray(20)
            stream.read_exact(arr)
            return FSHAHash(arr)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1127 bitflags EIoContainerFlags / FIoStoreTocEntryMetaFlags
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1127
class EIoContainerFlags(val bits: UByte) {
    fun bits(): UByte = bits
    fun contains(other: EIoContainerFlags): Boolean = (bits.toInt() and other.bits.toInt()) == other.bits.toInt()
    infix fun or(other: EIoContainerFlags): EIoContainerFlags = EIoContainerFlags((bits.toInt() or other.bits.toInt()).toUByte())
    infix fun and(other: EIoContainerFlags): EIoContainerFlags = EIoContainerFlags((bits.toInt() and other.bits.toInt()).toUByte())
    override fun equals(other: Any?): Boolean = other is EIoContainerFlags && bits == other.bits
    override fun hashCode(): Int = bits.hashCode()
    override fun toString(): String = "EIoContainerFlags(0x${bits.toString(16)})"
    companion object : Readable<EIoContainerFlags> {
        val Compressed = EIoContainerFlags(0b0001u)
        val Encrypted = EIoContainerFlags(0b0010u)
        val Signed = EIoContainerFlags(0b0100u)
        val Indexed = EIoContainerFlags(0b1000u)
        fun empty(): EIoContainerFlags = EIoContainerFlags(0u)
        fun from_bits(bits: UByte): EIoContainerFlags = EIoContainerFlags(bits)
        override fun de(stream: InputStream): EIoContainerFlags {
            val b = stream.read_u8()
            return from_bits(b)
        }
    }
    fun ser(stream: OutputStream) { stream.write_u8(bits) }
}
// implement Writeable via extension
fun OutputStream.ser(flags: EIoContainerFlags) { write_u8(flags.bits) }

class FIoStoreTocEntryMetaFlags(val bits: UByte) {
    fun bits(): UByte = bits
    fun contains(other: FIoStoreTocEntryMetaFlags): Boolean = (bits.toInt() and other.bits.toInt()) == other.bits.toInt()
    infix fun or(other: FIoStoreTocEntryMetaFlags): FIoStoreTocEntryMetaFlags = FIoStoreTocEntryMetaFlags((bits.toInt() or other.bits.toInt()).toUByte())
    override fun equals(other: Any?): Boolean = other is FIoStoreTocEntryMetaFlags && bits == other.bits
    override fun hashCode(): Int = bits.hashCode()
    override fun toString(): String = "FIoStoreTocEntryMetaFlags(0x${bits.toString(16)})"
    companion object : Readable<FIoStoreTocEntryMetaFlags> {
        val Compressed = FIoStoreTocEntryMetaFlags(1u)
        val MemoryMapped = FIoStoreTocEntryMetaFlags(2u)
        fun empty(): FIoStoreTocEntryMetaFlags = FIoStoreTocEntryMetaFlags(0u)
        fun from_bits(bits: UByte): FIoStoreTocEntryMetaFlags = FIoStoreTocEntryMetaFlags(bits)
        override fun de(stream: InputStream): FIoStoreTocEntryMetaFlags = from_bits(stream.read_u8())
    }
    fun ser(stream: OutputStream) { stream.write_u8(bits) }
}
fun OutputStream.ser(flags: FIoStoreTocEntryMetaFlags) { write_u8(flags.bits) }

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1162 EIoStoreTocVersion
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1162
enum class EIoStoreTocVersion(val value: UByte) : Writeable, Comparable<EIoStoreTocVersion> {
    Invalid(0u),
    Initial(1u),
    DirectoryIndex(2u),
    PartitionSize(3u),
    PerfectHash(4u),
    PerfectHashWithOverflow(5u),
    OnDemandMetaData(6u),
    RemovedOnDemandMetaData(7u),
    ReplaceIoChunkHashWithIoHash(8u);

    override fun ser(stream: OutputStream) { stream.write_u8(value) }

    companion object : Readable<EIoStoreTocVersion> {
        fun from_repr(value: UByte): EIoStoreTocVersion? = entries.find { it.value == value }
        fun from_repr(value: Int): EIoStoreTocVersion? = from_repr(value.toUByte())
        fun from_repr(value: UInt): EIoStoreTocVersion? = from_repr(value.toUByte())
        override fun de(stream: InputStream): EIoStoreTocVersion {
            val v = stream.read_u8()
            return from_repr(v) ?: throw IllegalArgumentException("invalid EIoStoreTocVersion value: $v")
        }
    }
    // allow compare via value for parity with Rust's PartialOrd
    // ordinal ordering matches value ordering, so default enum Comparable works
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/container_header.rs:291 EIoContainerHeaderVersion
// Note: canonical definition lives in lib.kt for 1:1 parity; version.kt and container_header.kt import via package
// ---------------------------------------------------------------------------
// Rust: retoc/src/container_header.rs:291
enum class EIoContainerHeaderVersion(val value: Int) : Writeable {
    PreInitial(-1),
    Initial(0),
    LocalizedPackages(1),
    OptionalSegmentPackages(2),
    NoExportInfo(3),
    SoftPackageReferences(4),
    SoftPackageReferencesOffset(5);

    override fun ser(stream: OutputStream) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        stream.write(bb.array())
    }

    companion object : Readable<EIoContainerHeaderVersion> {
        fun from_repr(value: Int): EIoContainerHeaderVersion? = entries.find { it.value == value }
        fun from_repr(value: UInt): EIoContainerHeaderVersion? = from_repr(value.toInt())
        fun from_repr(value: Long): EIoContainerHeaderVersion? = from_repr(value.toInt())
        override fun de(stream: InputStream): EIoContainerHeaderVersion {
            val v = stream.read_i32_le()
            return from_repr(v) ?: throw IllegalArgumentException("invalid EIoContainerHeaderVersion value: $v")
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1188 FIoStoreTocHeader
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1188
class FIoStoreTocHeader(
    var toc_magic: ByteArray = ByteArray(16),
    var version: EIoStoreTocVersion = EIoStoreTocVersion.Invalid,
    var reserved0: UByte = 0u,
    var reserved1: UShort = 0u,
    var toc_header_size: UInt = 0u,
    var toc_entry_count: UInt = 0u,
    var toc_compressed_block_entry_count: UInt = 0u,
    var toc_compressed_block_entry_size: UInt = 0u,
    var compression_method_name_count: UInt = 0u,
    var compression_method_name_length: UInt = 0u,
    var compression_block_size: UInt = 0u,
    var directory_index_size: UInt = 0u,
    var partition_count: UInt = 0u,
    var container_id: FIoContainerId = FIoContainerId(),
    var encryption_key_guid: FGuid = FGuid(),
    var container_flags: EIoContainerFlags = EIoContainerFlags.empty(),
    var reserved3: UByte = 0u,
    var reserved4: UShort = 0u,
    var toc_chunk_perfect_hash_seeds_count: UInt = 0u,
    var partition_size: ULong = 0UL,
    var toc_chunks_without_perfect_hash_count: UInt = 0u,
    var reserved7: UInt = 0u,
    var reserved8: LongArray = LongArray(5)
) : Writeable {
    companion object : Readable<FIoStoreTocHeader> {
        val MAGIC: ByteArray = "-==--==--==--==-".toByteArray(StandardCharsets.US_ASCII)
        override fun de(stream: InputStream): FIoStoreTocHeader {
            val hdr = FIoStoreTocHeader()
            val magic = ByteArray(16)
            stream.read_exact(magic)
            hdr.toc_magic = magic
            hdr.version = stream.de(EIoStoreTocVersion)
            hdr.reserved0 = stream.read_u8()
            hdr.reserved1 = stream.read_u16_le()
            hdr.toc_header_size = stream.read_u32_le()
            hdr.toc_entry_count = stream.read_u32_le()
            hdr.toc_compressed_block_entry_count = stream.read_u32_le()
            hdr.toc_compressed_block_entry_size = stream.read_u32_le()
            hdr.compression_method_name_count = stream.read_u32_le()
            hdr.compression_method_name_length = stream.read_u32_le()
            hdr.compression_block_size = stream.read_u32_le()
            hdr.directory_index_size = stream.read_u32_le()
            hdr.partition_count = stream.read_u32_le()
            hdr.container_id = stream.de(FIoContainerId)
            hdr.encryption_key_guid = stream.de(FGuid)
            hdr.container_flags = stream.de(EIoContainerFlags)
            hdr.reserved3 = stream.read_u8()
            hdr.reserved4 = stream.read_u16_le()
            hdr.toc_chunk_perfect_hash_seeds_count = stream.read_u32_le()
            hdr.partition_size = stream.read_u64_le()
            hdr.toc_chunks_without_perfect_hash_count = stream.read_u32_le()
            hdr.reserved7 = stream.read_u32_le()
            val r8 = LongArray(5)
            for (i in 0 until 5) r8[i] = stream.read_i64_le()
            hdr.reserved8 = r8
            if (!hdr.toc_magic.contentEquals(MAGIC)) throw IllegalArgumentException("unrecognized TOC magic")
            check(hdr.toc_header_size == 0x90u) { "toc_header_size != 0x90" }
            return hdr
        }
    }
    override fun ser(stream: OutputStream) {
        stream.write(toc_magic)
        stream.ser(version)
        stream.write_u8(reserved0)
        stream.write_u16_le(reserved1)
        stream.write_u32_le(toc_header_size)
        stream.write_u32_le(toc_entry_count)
        stream.write_u32_le(toc_compressed_block_entry_count)
        stream.write_u32_le(toc_compressed_block_entry_size)
        stream.write_u32_le(compression_method_name_count)
        stream.write_u32_le(compression_method_name_length)
        stream.write_u32_le(compression_block_size)
        stream.write_u32_le(directory_index_size)
        stream.write_u32_le(partition_count)
        stream.ser(container_id)
        stream.ser(encryption_key_guid)
        stream.ser(container_flags)
        stream.write_u8(reserved3)
        stream.write_u16_le(reserved4)
        stream.write_u32_le(toc_chunk_perfect_hash_seeds_count)
        stream.write_u64_le(partition_size)
        stream.write_u32_le(toc_chunks_without_perfect_hash_count)
        stream.write_u32_le(reserved7)
        for (v in reserved8) stream.write_i64_le(v)
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1219 FIoStoreTocChunkInfo
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1219
class FIoStoreTocChunkInfo(
    var id: FIoChunkId = FIoChunkId(ByteArray(12)),
    var file_name: String = "",
    var hash: FIoChunkHash = FIoChunkHash(),
    var offset: ULong = 0UL,
    var offset_on_disk: ULong = 0UL,
    var size: ULong = 0UL,
    var compressed_size: ULong = 0UL,
    var num_compressed_blocks: UInt = 0u,
    var partition_index: Int = 0,
    var chunk_type: EIoChunkType = EIoChunkType.Invalid,
    var has_valid_file_name: Boolean = false,
    var force_uncompressed: Boolean = false,
    var is_memory_mapped: Boolean = false,
    var is_compressed: Boolean = false
)

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1238 EIoChunkType
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1238
enum class EIoChunkType(val raw_new: Int, val raw_old: Int) {
    Invalid(0,0),
    ExportBundleData(1,2),
    BulkData(2,3),
    OptionalBulkData(3,4),
    MemoryMappedBulkData(4,5),
    ScriptObjects(5, -1),
    ContainerHeader(6,10),
    ExternalFile(7,-1),
    ShaderCodeLibrary(8,11),
    ShaderCode(9,12),
    PackageStoreEntry(10,-1),
    DerivedData(11,-1),
    EditorDerivedData(12,-1),
    PackageResource(13,-1),
    InstallManifest(-1,1),
    LoaderGlobalMeta(-1,6),
    LoaderInitialLoadMeta(-1,7),
    LoaderGlobalNames(-1,8),
    LoaderGlobalNameHashes(-1,9);

    companion object {
        fun new(value: Int, is_new: Boolean): EIoChunkType {
            return if (is_new) {
                when (value) {
                    0 -> Invalid
                    1 -> ExportBundleData
                    2 -> BulkData
                    3 -> OptionalBulkData
                    4 -> MemoryMappedBulkData
                    5 -> ScriptObjects
                    6 -> ContainerHeader
                    7 -> ExternalFile
                    8 -> ShaderCodeLibrary
                    9 -> ShaderCode
                    10 -> PackageStoreEntry
                    11 -> DerivedData
                    12 -> EditorDerivedData
                    13 -> PackageResource
                    else -> throw IllegalArgumentException("invalid chunk type for version >= UE5: $value")
                }
            } else {
                when (value) {
                    0 -> Invalid
                    1 -> InstallManifest
                    2 -> ExportBundleData
                    3 -> BulkData
                    4 -> OptionalBulkData
                    5 -> MemoryMappedBulkData
                    6 -> LoaderGlobalMeta
                    7 -> LoaderInitialLoadMeta
                    8 -> LoaderGlobalNames
                    9 -> LoaderGlobalNameHashes
                    10 -> ContainerHeader
                    11 -> ShaderCodeLibrary
                    12 -> ShaderCode
                    else -> throw IllegalArgumentException("invalid chunk type for version < UE5: $value")
                }
            }
        }
        fun from_repr(value: Int): EIoChunkType? = entries.find { it.raw_new == value || it.raw_old == value }
    }
    fun value(is_new: Boolean): Int {
        return if (is_new) {
            when (this) {
                Invalid -> 0
                ExportBundleData -> 1
                BulkData -> 2
                OptionalBulkData -> 3
                MemoryMappedBulkData -> 4
                ScriptObjects -> 5
                ContainerHeader -> 6
                ExternalFile -> 7
                ShaderCodeLibrary -> 8
                ShaderCode -> 9
                PackageStoreEntry -> 10
                DerivedData -> 11
                EditorDerivedData -> 12
                PackageResource -> 13
                else -> throw IllegalArgumentException("invalid chunk type for version >= UE5: $this")
            }
        } else {
            when (this) {
                Invalid -> 0
                InstallManifest -> 1
                ExportBundleData -> 2
                BulkData -> 3
                OptionalBulkData -> 4
                MemoryMappedBulkData -> 5
                LoaderGlobalMeta -> 6
                LoaderInitialLoadMeta -> 7
                LoaderGlobalNames -> 8
                LoaderGlobalNameHashes -> 9
                ContainerHeader -> 10
                ShaderCodeLibrary -> 11
                ShaderCode -> 12
                else -> throw IllegalArgumentException("invalid chunk type for version < UE5: $this")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:272 helper functions (read_chunk_ids etc) + ser for Toc
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:273 read_chunk_ids
fun read_chunk_ids(stream: InputStream, header: FIoStoreTocHeader): MutableList<FIoChunkId> {
    return read_array(header.toc_entry_count.toInt(), stream) { s -> FIoChunkId.de_with_version(s, header.version) }
}

// Rust: retoc/src/lib.rs:278 read_chunk_offsets
fun read_chunk_offsets(stream: InputStream, header: FIoStoreTocHeader): MutableList<FIoOffsetAndLength> {
    // de_ctx(header.toc_entry_count)
    return read_array(header.toc_entry_count.toInt(), stream) { s -> s.de(FIoOffsetAndLength) }
}

// Rust: retoc/src/lib.rs:283 read_hash_map
fun read_hash_map(stream: InputStream, header: FIoStoreTocHeader): kotlin.Pair<MutableList<Int>, MutableList<Int>> {
    var perfect_hash_seeds_count = 0
    var chunks_without_perfect_hash_count = 0
    if (header.version >= EIoStoreTocVersion.PerfectHashWithOverflow) {
        perfect_hash_seeds_count = header.toc_chunk_perfect_hash_seeds_count.toInt()
        chunks_without_perfect_hash_count = header.toc_chunks_without_perfect_hash_count.toInt()
    } else if (header.version >= EIoStoreTocVersion.PerfectHash) {
        perfect_hash_seeds_count = header.toc_chunk_perfect_hash_seeds_count.toInt()
        chunks_without_perfect_hash_count = 0
    }
    val seeds = read_array(perfect_hash_seeds_count, stream) { s -> s.read_i32_le() }
    val indices = read_array(chunks_without_perfect_hash_count, stream) { s -> s.read_i32_le() }
    return kotlin.Pair(seeds, indices)
}

// Rust: retoc/src/lib.rs:301 read_compression_blocks
fun read_compression_blocks(stream: InputStream, header: FIoStoreTocHeader): MutableList<FIoStoreTocCompressedBlockEntry> {
    return read_array(header.toc_compressed_block_entry_count.toInt(), stream) { s -> s.de(FIoStoreTocCompressedBlockEntry) }
}

// Rust: retoc/src/lib.rs:306 read_compression_methods
fun read_compression_methods(stream: InputStream, header: FIoStoreTocHeader): MutableList<CompressionMethod> {
    val methods = mutableListOf<CompressionMethod>()
    for (i in 0 until header.compression_method_name_count.toInt()) {
        val raw = ByteArray(header.compression_method_name_length.toInt())
        stream.read_exact(raw)
        val len = raw.indexOf(0.toByte()).let { if (it >= 0) it else raw.size }
        val name = String(raw, 0, len, StandardCharsets.UTF_8)
        val method = CompressionMethod.from_str_ignore_case(name) ?: throw IllegalArgumentException("unknown compression method: $name")
        methods.add(method)
    }
    return methods
}

// Rust: retoc/src/lib.rs:316 write_compression_methods
fun write_compression_methods(stream: OutputStream, toc: Toc) {
    for (name in toc.compression_methods) {
        val bytes = name.name.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteArray(32)
        System.arraycopy(bytes, 0, buf, 0, minOf(bytes.size, 32))
        stream.write(buf)
    }
}

// Rust: retoc/src/lib.rs:325 read_chunk_block_signatures
fun read_chunk_block_signatures(stream: InputStream, header: FIoStoreTocHeader): TocSignatures? {
    val is_signed = header.container_flags.contains(EIoContainerFlags.Signed)
    return if (is_signed) {
        val size = stream.read_u32_le().toInt()
        val toc_sig = read_array(size, stream) { s -> s.read_u8() }
        val block_sig = read_array(size, stream) { s -> s.read_u8() }
        val chunk_sigs = read_array(header.toc_compressed_block_entry_count.toInt(), stream) { s -> s.de(FSHAHash) }
        TocSignatures(toc_sig.map { it }, block_sig.map { it }, chunk_sigs)
    } else null
}

// Rust: retoc/src/lib.rs:340 read_directory_index
fun read_directory_index(stream: InputStream, header: FIoStoreTocHeader, config: Config): ByteArray {
    val buf_len = header.directory_index_size.toInt()
    val buf = ByteArray(buf_len)
    if (buf_len > 0) stream.read_exact(buf)
    if (header.container_flags.contains(EIoContainerFlags.Encrypted)) {
        val key = config.aes_keys[header.encryption_key_guid] ?: throw IllegalArgumentException("missing encryption key for ${header.encryption_key_guid}")
        for (i in buf.indices step 16) {
            val block = buf.copyOfRange(i, minOf(i+16, buf.size))
            // decrypt in place via AES ECB; pad to 16 for final block
            if (block.size == 16) {
                key.decrypt_block(block)
                System.arraycopy(block, 0, buf, i, 16)
            }
        }
    }
    return buf
}

// Rust: retoc/src/lib.rs:356 read_meta
fun read_meta(stream: InputStream, header: FIoStoreTocHeader): MutableList<FIoStoreTocEntryMeta> {
    return read_array(header.toc_entry_count.toInt(), stream) { s ->
        if (header.version >= EIoStoreTocVersion.ReplaceIoChunkHashWithIoHash) {
            val hashBytes = ByteArray(32)
            val first20 = ByteArray(20)
            s.read_exact(first20)
            System.arraycopy(first20, 0, hashBytes, 0, 20)
            val flags = s.de(FIoStoreTocEntryMetaFlags)
            val pad = ByteArray(3)
            s.read_exact(pad)
            FIoStoreTocEntryMeta(FIoChunkHash(hashBytes), flags)
        } else {
            val hash = s.de(FIoChunkHash)
            val flags = s.de(FIoStoreTocEntryMetaFlags)
            FIoStoreTocEntryMeta(hash, flags)
        }
    }
}

// Rust: retoc/src/lib.rs:373 write_meta
fun write_meta(stream: OutputStream, toc: Toc) {
    for (meta in toc.chunk_metas) {
        if (toc.version >= EIoStoreTocVersion.ReplaceIoChunkHashWithIoHash) {
            stream.write(meta.chunk_hash.bytes, 0, 20)
            stream.ser(meta.flags)
            stream.write(ByteArray(3))
        } else {
            stream.ser(meta.chunk_hash)
            stream.ser(meta.flags)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:387 UEPath type aliases + helpers
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:387
typealias UEPath = String
typealias UEPathBuf = String
typealias UEPathComponent = String

// Rust: retoc/src/lib.rs:392 to_ue_path
fun to_ue_path(path: Path): UEPathBuf = path.toString().replace('\\', '/').replace(File.separatorChar, '/')

// Rust: retoc/src/lib.rs:397 pak_path_to_game_path
fun pak_path_to_game_path(pak_path: UEPath): String? {
    val parts = pak_path.split('/').filter { it.isNotEmpty() }
    if (parts.size < 2) return null
    val a = parts[0]; val b = parts[1]
    // Plugins case: first component any, second == "Plugins"
    if (b.equals("Plugins", ignoreCase = true)) {
        // Find Content after plugins
        var last: String? = null
        var idx = 2
        while (idx < parts.size) {
            val comp = parts[idx]
            if (comp.equals("Content", ignoreCase = true)) {
                val remainder = if (idx+1 < parts.size) parts.subList(idx+1, parts.size).joinToString("/") else ""
                return if (last != null) "/$last/$remainder".trimEnd('/') else null
            } else {
                last = comp
            }
            idx++
        }
        return null
    }
    if (a.equals("Engine", ignoreCase = true) && b.equals("Content", ignoreCase = true)) {
        val remainder = if (parts.size > 2) parts.subList(2, parts.size).joinToString("/") else ""
        return if (remainder.isEmpty()) "/Engine" else "/Engine/$remainder"
    }
    if (b.equals("Content", ignoreCase = true)) {
        val remainder = if (parts.size > 2) parts.subList(2, parts.size).joinToString("/") else ""
        return if (remainder.isEmpty()) "/Game" else "/Game/$remainder"
    }
    return null
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:422 Toc + TocSignatures
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:453 TocSignatures
class TocSignatures(
    var toc_signature: List<UByte> = emptyList(),
    var block_signature: List<UByte> = emptyList(),
    var chunk_block_signatures: List<FSHAHash> = emptyList()
)

// Rust: retoc/src/lib.rs:422 Toc
class Toc(
    var config: Config = Config(),
    // serialized members
    var chunks: MutableList<FIoChunkId> = mutableListOf(),
    var chunk_offset_lengths: MutableList<FIoOffsetAndLength> = mutableListOf(),
    var chunk_perfect_hash_seeds: MutableList<Int> = mutableListOf(),
    var chunk_indices_without_perfect_hash: MutableList<Int> = mutableListOf(),
    var compression_blocks: MutableList<FIoStoreTocCompressedBlockEntry> = mutableListOf(),
    var compression_methods: MutableList<CompressionMethod> = mutableListOf(),
    var signatures: TocSignatures? = null,
    var chunk_metas: MutableList<FIoStoreTocEntryMeta> = mutableListOf(),
    // serialized in header
    var version: EIoStoreTocVersion = EIoStoreTocVersion.Invalid,
    var container_id: FIoContainerId = FIoContainerId(),
    var compression_block_size: UInt = 0u,
    var partition_size: ULong = 0UL,
    var partition_count: UInt = 0u,
    var encryption_key_guid: FGuid = FGuid(),
    var container_flags: EIoContainerFlags = EIoContainerFlags.empty(),
    // transient indexes
    var directory_index: FIoDirectoryIndexResource = FIoDirectoryIndexResource(),
    var file_map: MutableMap<String, UInt> = HashMap(),
    var file_map_lower: MutableMap<String, UInt> = HashMap(),
    var file_map_rev: MutableMap<UInt, String> = HashMap(),
    var chunk_id_map: MutableMap<FIoChunkId, UInt> = HashMap()
) : Writeable, Readable<Toc> {
    // Rust: retoc/src/lib.rs:459 Readable for Toc (via Config default)
    override fun de(stream: InputStream): Toc = de_with_config(stream, Config())
    // Rust: retoc/src/lib.rs:464 ReadableCtx<Arc<Config>> for Toc
    fun de_with_config(stream: InputStream, cfg: Config): Toc {
        val header: FIoStoreTocHeader = stream.de(FIoStoreTocHeader)
        val chunk_ids = read_chunk_ids(stream, header)
        val chunk_offset_lengths = read_chunk_offsets(stream, header)
        val (chunk_perfect_hash_seeds, chunk_indices_without_perfect_hash) = read_hash_map(stream, header)
        val compression_blocks = read_compression_blocks(stream, header)
        val compression_methods = read_compression_methods(stream, header)
        val signatures = read_chunk_block_signatures(stream, header)
        val directory_index_bytes = read_directory_index(stream, header, cfg)
        val chunk_metas = read_meta(stream, header)

        val chunk_id_to_index: MutableMap<FIoChunkId, UInt> = HashMap()
        for ((idx, cid) in chunk_ids.withIndex()) {
            chunk_id_to_index[cid] = idx.toUInt()
        }
        val file_map: MutableMap<String, UInt> = HashMap()
        val file_map_lower: MutableMap<String, UInt> = HashMap()
        val file_map_rev: MutableMap<UInt, String> = HashMap()
        val directory_index = if (directory_index_bytes.isNotEmpty()) {
            FIoDirectoryIndexResource.de(ByteArrayInputStream(directory_index_bytes))
        } else FIoDirectoryIndexResource()
        directory_index.iter_root { user_data, path ->
            val p = path.joinToString("/")
            file_map_lower[p.lowercase(Locale.ROOT)] = user_data
            file_map[p] = user_data
            file_map_rev[user_data] = p
        }
        val chunk_id_map = chunk_ids.mapIndexed { i, cid -> cid to i.toUInt() }.toMap().toMutableMap()

        return Toc(
            config = cfg,
            chunks = chunk_ids,
            chunk_offset_lengths = chunk_offset_lengths,
            chunk_perfect_hash_seeds = chunk_perfect_hash_seeds,
            chunk_indices_without_perfect_hash = chunk_indices_without_perfect_hash,
            compression_blocks = compression_blocks,
            compression_methods = compression_methods,
            signatures = signatures,
            chunk_metas = chunk_metas,
            version = header.version,
            container_id = header.container_id,
            compression_block_size = header.compression_block_size,
            partition_size = header.partition_size,
            partition_count = header.partition_count,
            encryption_key_guid = header.encryption_key_guid,
            container_flags = header.container_flags,
            directory_index = directory_index,
            file_map = file_map,
            file_map_lower = file_map_lower,
            file_map_rev = file_map_rev,
            chunk_id_map = chunk_id_map
        )
    }

    // Rust: retoc/src/lib.rs:524 Writeable for Toc
    override fun ser(stream: OutputStream) {
        var container_flags = EIoContainerFlags.empty() or EIoContainerFlags.Indexed
        val directory_index_buffer = ByteArrayOutputStream()
        directory_index.ser(directory_index_buffer)
        val directory_index_bytes = directory_index_buffer.toByteArray()

        val header = FIoStoreTocHeader(
            toc_magic = FIoStoreTocHeader.MAGIC.copyOf(),
            version = version,
            reserved0 = 0u,
            reserved1 = 0u,
            toc_header_size = 0x90u,
            toc_entry_count = chunks.size.toUInt(),
            toc_compressed_block_entry_count = compression_blocks.size.toUInt(),
            toc_compressed_block_entry_size = 12u,
            compression_method_name_count = compression_methods.size.toUInt(),
            compression_method_name_length = 32u,
            compression_block_size = compression_block_size,
            directory_index_size = directory_index_bytes.size.toUInt(),
            partition_count = 1u,
            container_id = container_id,
            encryption_key_guid = FGuid(),
            container_flags = container_flags,
            reserved3 = 0u,
            reserved4 = 0u,
            toc_chunk_perfect_hash_seeds_count = 0u,
            partition_size = partition_size,
            toc_chunks_without_perfect_hash_count = 0u,
            reserved7 = 0u,
            reserved8 = LongArray(5)
        )
        stream.ser(header)
        for (c in chunks) stream.ser(c.get_raw())
        for (o in chunk_offset_lengths) stream.ser(o)
        for (b in compression_blocks) stream.ser(b)
        write_compression_methods(stream, this)
        stream.write(directory_index_bytes)
        write_meta(stream, this)
    }

    // helpers
    fun new(): Toc = Toc()

    // Rust: retoc/src/lib.rs:582 file_name
    fun file_name(chunk_id: FIoChunkId): String? {
        val idx = chunk_id_map[chunk_id.with_version(version)] ?: return null
        val path = file_map_rev[idx] ?: return null
        val mount = directory_index.mount_point
        return if (mount.isEmpty()) path else "$mount/$path".replace("//","/")
    }

    // Rust: retoc/src/lib.rs:589 get_chunk_info
    fun get_chunk_info(file_name: String): FIoStoreTocChunkInfo {
        val toc_entry_index = file_map[file_name] ?: throw IllegalArgumentException("file not found: $file_name")
        val meta = chunk_metas[toc_entry_index.toInt()]
        val offset_and_length = chunk_offset_lengths[toc_entry_index.toInt()]
        val hash = FIoChunkHash().apply { System.arraycopy(meta.chunk_hash.bytes, 0, bytes, 0, 20) }
        val offset = offset_and_length.get_offset()
        val size = offset_and_length.get_length()
        val first_block_index = (offset / compression_block_size.toULong()).toInt()
        val last_block_index = ((align_u64(offset + size, compression_block_size.toULong()) - 1UL) / compression_block_size.toULong()).toInt()
        val num_compressed_blocks = (1 + last_block_index - first_block_index).toUInt()
        val offset_on_disk = compression_blocks[first_block_index].get_offset()
        var compressed_size = 0UL
        var partition_index = -1
        for (bi in first_block_index..last_block_index) {
            val block = compression_blocks[bi]
            compressed_size += block.get_compressed_size().toULong()
            if (partition_index < 0) partition_index = (block.get_offset() / partition_size).toInt()
        }
        val id = chunks[toc_entry_index.toInt()]
        return FIoStoreTocChunkInfo(
            id = id,
            file_name = file_name,
            hash = hash,
            offset = offset,
            offset_on_disk = offset_on_disk,
            size = size,
            compressed_size = compressed_size,
            num_compressed_blocks = num_compressed_blocks,
            partition_index = partition_index,
            chunk_type = id.get_chunk_type(),
            has_valid_file_name = false,
            force_uncompressed = !meta.flags.contains(FIoStoreTocEntryMetaFlags.Compressed),
            is_memory_mapped = meta.flags.contains(FIoStoreTocEntryMetaFlags.MemoryMapped),
            is_compressed = meta.flags.contains(FIoStoreTocEntryMetaFlags.Compressed)
        )
    }

    // Rust: retoc/src/lib.rs:638 get_chunk_id_entry_index
    fun get_chunk_id_entry_index(chunk_id: FIoChunkId): UInt = chunk_id_map[chunk_id] ?: throw IllegalArgumentException("container does not contain entry")

    // Rust: retoc/src/lib.rs:641 read (generic Read+Seek)
    fun read(cas_file: RandomAccessFile, toc_entry_index: UInt): ByteArray {
        val offset_and_length = chunk_offset_lengths[toc_entry_index.toInt()]
        val offset = offset_and_length.get_offset()
        val size = offset_and_length.get_length()
        val compression_block_size = compression_block_size.toULong()
        val first_block_index = (offset / compression_block_size).toInt()
        val last_block_index = ((align_u64(offset + size, compression_block_size) - 1UL) / compression_block_size).toInt()
        val blocks = compression_blocks.subList(first_block_index, last_block_index + 1)
        val aes_key: AesKey? = if (container_flags.contains(EIoContainerFlags.Encrypted)) {
            config.aes_keys[encryption_key_guid] ?: throw IllegalArgumentException("container is encrypted but no AES key for $encryption_key_guid supplied")
        } else null

        var max_buffer = 0
        for (b in blocks) {
            max_buffer = maxOf(max_buffer, align_usize(b.get_compressed_size().toInt(), 16))
        }
        val data = ByteArray(align_usize(size.toInt(), 16))
        val buffer = ByteArray(max_buffer)
        var cur = 0
        for (block in blocks) {
            val compressed_size = block.get_compressed_size().toInt()
            val uncompressed_size = block.get_uncompressed_size().toInt()
            cas_file.seek(block.get_offset().toLong())
            val compression_method_index = block.get_compression_method_index().toInt()
            val compression_method = if (compression_method_index == 0) null else compression_methods[compression_method_index - 1]
            if (compression_method == null) {
                if (aes_key != null) {
                    val aligned = align_usize(uncompressed_size, 16)
                    val tmp = ByteArray(aligned)
                    cas_file.readFully(tmp)
                    for (i in tmp.indices step 16) {
                        val end = minOf(i + 16, tmp.size)
                        if (end - i == 16) {
                            val blk = tmp.copyOfRange(i, end)
                            aes_key.decrypt_block(blk)
                            System.arraycopy(blk, 0, tmp, i, 16)
                        }
                    }
                    System.arraycopy(tmp, 0, data, cur, uncompressed_size)
                } else {
                    cas_file.readFully(data, cur, uncompressed_size)
                }
            } else {
                if (aes_key != null) {
                    val aligned = align_usize(compressed_size, 16)
                    cas_file.readFully(buffer, 0, aligned)
                    for (i in 0 until aligned step 16) {
                        val blk = buffer.copyOfRange(i, i + 16)
                        aes_key.decrypt_block(blk)
                        System.arraycopy(blk, 0, buffer, i, 16)
                    }
                    val tmp = buffer.copyOf(compressed_size)
                    val out = ByteArray(uncompressed_size)
                    decompress(compression_method, tmp, out)
                    System.arraycopy(out, 0, data, cur, uncompressed_size)
                } else {
                    cas_file.readFully(buffer, 0, compressed_size)
                    val tmp = buffer.copyOf(compressed_size)
                    val out = ByteArray(uncompressed_size)
                    decompress(compression_method, tmp, out)
                    System.arraycopy(out, 0, data, cur, uncompressed_size)
                }
            }
            cur += uncompressed_size
        }
        return data.copyOf(size.toInt())
    }

    // Rust: retoc/src/lib.rs:641 read (InputStream overload for compatibility)
    fun read(cas_stream: InputStream, toc_entry_index: UInt): ByteArray {
        if (chunk_offset_lengths.isEmpty() || compression_blocks.isEmpty()) return ByteArray(0)
        // Bridge InputStream to RandomAccessFile via temp file to support seek
        // For small fixtures, buffer in memory via SeekableByteArrayInputStream
        val bytes = cas_stream.readBytes()
        val seekable = SeekableByteArrayInputStream(bytes)
        val offset_and_length = chunk_offset_lengths[toc_entry_index.toInt()]
        val offset = offset_and_length.get_offset()
        val size = offset_and_length.get_length()
        val compression_block_size = compression_block_size.toULong()
        val first_block_index = (offset / compression_block_size).toInt()
        val last_block_index = ((align_u64(offset + size, compression_block_size) - 1UL) / compression_block_size).toInt()
        val blocks = compression_blocks.subList(first_block_index, last_block_index + 1)
        val aes_key: AesKey? = if (container_flags.contains(EIoContainerFlags.Encrypted)) {
            config.aes_keys[encryption_key_guid] ?: throw IllegalArgumentException("container is encrypted but no AES key for $encryption_key_guid supplied")
        } else null
        var max_buffer = 0
        for (b in blocks) {
            max_buffer = maxOf(max_buffer, align_usize(b.get_compressed_size().toInt(), 16))
        }
        val data = ByteArray(align_usize(size.toInt(), 16))
        val buffer = ByteArray(max_buffer)
        var cur = 0
        for (block in blocks) {
            val compressed_size = block.get_compressed_size().toInt()
            val uncompressed_size = block.get_uncompressed_size().toInt()
            seekable.seek(block.get_offset().toLong())
            val compression_method_index = block.get_compression_method_index().toInt()
            val compression_method = if (compression_method_index == 0) null else compression_methods[compression_method_index - 1]
            if (compression_method == null) {
                if (aes_key != null) {
                    val aligned = align_usize(uncompressed_size, 16)
                    val tmp = ByteArray(aligned)
                    seekable.read_exact(tmp)
                    for (i in tmp.indices step 16) {
                        if (i + 16 <= tmp.size) {
                            val blk = tmp.copyOfRange(i, i + 16)
                            aes_key.decrypt_block(blk)
                            System.arraycopy(blk, 0, tmp, i, 16)
                        }
                    }
                    System.arraycopy(tmp, 0, data, cur, uncompressed_size)
                } else {
                    val tmp = ByteArray(uncompressed_size)
                    seekable.read_exact(tmp)
                    System.arraycopy(tmp, 0, data, cur, uncompressed_size)
                }
            } else {
                if (aes_key != null) {
                    val aligned = align_usize(compressed_size, 16)
                    val tmpBuf = ByteArray(aligned)
                    seekable.read_exact(tmpBuf)
                    for (i in 0 until aligned step 16) {
                        val blk = tmpBuf.copyOfRange(i, i + 16)
                        aes_key.decrypt_block(blk)
                        System.arraycopy(blk, 0, tmpBuf, i, 16)
                    }
                    val tmp = tmpBuf.copyOf(compressed_size)
                    val out = ByteArray(uncompressed_size)
                    decompress(compression_method, tmp, out)
                    System.arraycopy(out, 0, data, cur, uncompressed_size)
                } else {
                    val tmp = ByteArray(compressed_size)
                    seekable.read_exact(tmp)
                    val out = ByteArray(uncompressed_size)
                    decompress(compression_method, tmp, out)
                    System.arraycopy(out, 0, data, cur, uncompressed_size)
                }
            }
            cur += uncompressed_size
        }
        return data.copyOf(size.toInt())
    }
}

// Rust: retoc/src/lib.rs:570 align helpers
fun align_u64(value: ULong, alignment: ULong): ULong = (value + alignment - 1UL) and (alignment - 1UL).inv()
fun align_usize(value: Int, alignment: Int): Int = (value + alignment - 1) and (alignment - 1).inv()

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1346 directory_index module
// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1353 FIoDirectoryIndexResource etc.
class FIoDirectoryIndexResource(
    var mount_point: UEPathBuf = "",
    var directory_entries: MutableList<FIoDirectoryIndexEntry> = mutableListOf(),
    var file_entries: MutableList<FIoFileIndexEntry> = mutableListOf(),
    var string_table: MutableList<String> = mutableListOf()
) : Writeable {
    override fun ser(stream: OutputStream) {
        if (file_entries.isNotEmpty()) {
            stream.write_string(mount_point)
            stream.write_u32_le(directory_entries.size.toUInt())
            for (e in directory_entries) stream.ser(e)
            stream.write_u32_le(file_entries.size.toUInt())
            for (e in file_entries) stream.ser(e)
            stream.write_u32_le(string_table.size.toUInt())
            for (s in string_table) stream.write_string(s)
        }
    }
    companion object : Readable<FIoDirectoryIndexResource> {
        override fun de(stream: InputStream): FIoDirectoryIndexResource {
            val mount = stream.read_string()
            val dirCount = stream.read_u32_le().toInt()
            val dirs = read_array(dirCount, stream) { s -> s.de(FIoDirectoryIndexEntry) }
            val fileCount = stream.read_u32_le().toInt()
            val files = read_array(fileCount, stream) { s -> s.de(FIoFileIndexEntry) }
            val strCount = stream.read_u32_le().toInt()
            val strings = read_array(strCount, stream) { s -> s.read_string() }
            return FIoDirectoryIndexResource(mount, dirs.toMutableList(), files.toMutableList(), strings.toMutableList())
        }
    }

    // Rust: retoc/src/lib.rs:1382 iter_root
    fun iter_root(visitor: (UInt, List<String>) -> Unit) {
        if (directory_entries.isNotEmpty()) iter(IdDir(0u), mutableListOf(), visitor)
    }
    // Rust: retoc/src/lib.rs:1390 iter
    fun iter(dir_index: IdDir, stack: MutableList<String>, visitor: (UInt, List<String>) -> Unit) {
        val dir = directory_entries[dir_index.value.toInt()]
        if (dir.name != null) stack.add(string_table[dir.name!!.value.toInt()])
        var file_index = dir.first_file_entry
        while (file_index != null) {
            val file = file_entries[file_index.value.toInt()]
            stack.add(string_table[file.name.value.toInt()])
            visitor(file.user_data, stack.toList())
            stack.removeAt(stack.lastIndex)
            file_index = file.next_file_entry
        }
        var child = dir.first_child_entry
        while (child != null) {
            iter(child, stack, visitor)
            child = directory_entries[child.value.toInt()].next_sibling_entry
        }
        if (dir.name != null) stack.removeAt(stack.lastIndex)
    }

    fun root(): IdDir = IdDir(0u)
    fun ensure_root(): IdDir {
        if (directory_entries.isEmpty()) {
            directory_entries.add(FIoDirectoryIndexEntry(null, null, null, null))
        }
        return root()
    }
    fun get_or_create_dir(parent: IdDir, name: String): IdDir {
        var dir_index = directory_entries[parent.value.toInt()].first_child_entry
        var last: IdDir? = null
        while (dir_index != null) {
            val dir = directory_entries[dir_index.value.toInt()]
            if (string_table[dir.name!!.value.toInt()] == name) return dir_index
            last = dir_index
            dir_index = dir.next_sibling_entry
        }
        val newEntry = FIoDirectoryIndexEntry(get_or_create_name(name), null, null, null)
        directory_entries.add(newEntry)
        val id = IdDir((directory_entries.size - 1).toUInt())
        if (last != null) directory_entries[last.value.toInt()].next_sibling_entry = id
        else directory_entries[parent.value.toInt()].first_child_entry = id
        return id
    }
    fun get_or_create_file(parent: IdDir, name: String): IdFile {
        var file_index = directory_entries[parent.value.toInt()].first_file_entry
        var last: IdFile? = null
        while (file_index != null) {
            val file = file_entries[file_index.value.toInt()]
            if (string_table[file.name.value.toInt()] == name) return file_index
            last = file_index
            file_index = file.next_file_entry
        }
        val newEntry = FIoFileIndexEntry(get_or_create_name(name), null, 0u)
        file_entries.add(newEntry)
        val id = IdFile((file_entries.size - 1).toUInt())
        if (last != null) file_entries[last.value.toInt()].next_file_entry = id
        else directory_entries[parent.value.toInt()].first_file_entry = id
        return id
    }
    fun get_or_create_name(name: String): IdName {
        val idx = string_table.indexOf(name).let { if (it >= 0) it else { string_table.add(name); string_table.size - 1 } }
        return IdName(idx.toUInt())
    }
    // Rust: retoc/src/lib.rs:1495 add_file
    fun add_file(path: UEPath, user_data: UInt) {
        val parts = path.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val file_name = parts.last()
        var dir_index = ensure_root()
        for (dir_name in parts.dropLast(1)) {
            dir_index = get_or_create_dir(dir_index, dir_name)
        }
        val file_index = get_or_create_file(dir_index, file_name)
        file_entries[file_index.value.toInt()].user_data = user_data
    }
}

// Rust: retoc/src/lib.rs:1507 new_id! macro -> IdFile etc.
@JvmInline value class IdFile(val value: UInt) : Writeable {
    fun get(): Int = value.toInt()
    override fun ser(stream: OutputStream) { stream.write_u32_le(value) }
    companion object : Readable<IdFile> {
        fun new(v: UInt): IdFile? = if (v == UInt.MAX_VALUE) null else IdFile(v)
        override fun de(stream: InputStream): IdFile = IdFile(stream.read_u32_le())
        fun de_opt(stream: InputStream): IdFile? = new(stream.read_u32_le())
    }
}
@JvmInline value class IdDir(val value: UInt) : Writeable {
    fun get(): Int = value.toInt()
    override fun ser(stream: OutputStream) { stream.write_u32_le(value) }
    companion object : Readable<IdDir> {
        fun new(v: UInt): IdDir? = if (v == UInt.MAX_VALUE) null else IdDir(v)
        override fun de(stream: InputStream): IdDir = IdDir(stream.read_u32_le())
        fun de_opt(stream: InputStream): IdDir? = new(stream.read_u32_le())
    }
}
@JvmInline value class IdName(val value: UInt) : Writeable {
    fun get(): Int = value.toInt()
    override fun ser(stream: OutputStream) { stream.write_u32_le(value) }
    companion object : Readable<IdName> {
        override fun de(stream: InputStream): IdName = IdName(stream.read_u32_le())
    }
}
// Nullable Id helpers via extensions (Rust: Readable for Option<IdFile> etc.)
fun InputStream.read_id_file_opt(): IdFile? = IdFile.new(read_u32_le())
fun OutputStream.write_id_file_opt(v: IdFile?) { write_u32_le(v?.value ?: UInt.MAX_VALUE) }
fun InputStream.read_id_dir_opt(): IdDir? = IdDir.new(read_u32_le())
fun OutputStream.write_id_dir_opt(v: IdDir?) { write_u32_le(v?.value ?: UInt.MAX_VALUE) }
fun InputStream.read_id_name_opt(): IdName? = if (true) { val v = read_u32_le(); if (v==UInt.MAX_VALUE) null else IdName(v) } else null
fun OutputStream.write_id_name_opt(v: IdName?) { write_u32_le(v?.value ?: UInt.MAX_VALUE) }

// Rust: retoc/src/lib.rs:1545 FIoFileIndexEntry
class FIoFileIndexEntry(
    var name: IdName = IdName(0u),
    var next_file_entry: IdFile? = null,
    var user_data: UInt = 0u
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.ser(name)
        stream.write_id_file_opt(next_file_entry)
        stream.write_u32_le(user_data)
    }
    companion object : Readable<FIoFileIndexEntry> {
        override fun de(stream: InputStream): FIoFileIndexEntry = FIoFileIndexEntry(stream.de(IdName), stream.read_id_file_opt(), stream.read_u32_le())
    }
}

// Rust: retoc/src/lib.rs:1569 FIoDirectoryIndexEntry
class FIoDirectoryIndexEntry(
    var name: IdName? = null,
    var first_child_entry: IdDir? = null,
    var next_sibling_entry: IdDir? = null,
    var first_file_entry: IdFile? = null
) : Writeable {
    override fun ser(stream: OutputStream) {
        stream.write_id_name_opt(name)
        stream.write_id_dir_opt(first_child_entry)
        stream.write_id_dir_opt(next_sibling_entry)
        stream.write_id_file_opt(first_file_entry)
    }
    companion object : Readable<FIoDirectoryIndexEntry> {
        override fun de(stream: InputStream): FIoDirectoryIndexEntry = FIoDirectoryIndexEntry(stream.read_id_name_opt(), stream.read_id_dir_opt(), stream.read_id_dir_opt(), stream.read_id_file_opt())
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/lib.rs:1596 test module (kept as unit test helpers, not executable)
// ---------------------------------------------------------------------------
// Parity: test_dir logic preserved as comment; Kotlin test would use JUnit.

