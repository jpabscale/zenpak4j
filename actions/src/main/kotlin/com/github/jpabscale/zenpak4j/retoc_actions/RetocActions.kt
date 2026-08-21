//@parity:on EXC-014
// Rust: retoc_cli/src/main.rs — action layer shared by retoc-cli and :zenpak (EXC-014).
// Moved verbatim from retoc-cli/main.kt (same Rust refs); the Clikt commands stay in retoc-cli.
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection", "MagicNumber", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName")

package com.github.jpabscale.zenpak4j.retoc_actions

import com.github.jpabscale.zenpak4j.repak.Compression as RepakCompression
import com.github.jpabscale.zenpak4j.repak.PakBuilder
import com.github.jpabscale.zenpak4j.repak.Version as RepakVersion
import com.github.jpabscale.zenpak4j.retoc.*
import org.bouncycastle.crypto.digests.Blake3Digest
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Collections
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.extension
import kotlinx.coroutines.*

// ---------------------------------------------------------------------------
// Rust: retoc_cli/src/main.rs:39 ActionManifest
// ---------------------------------------------------------------------------
// Rust: retoc_cli/src/main.rs:39
data class ActionManifest(
    var utoc: Path
)

// Rust: retoc_cli/src/main.rs:44 ActionInfo
data class ActionInfo(
    var path: Path
)

// Rust: retoc_cli/src/main.rs:50 ActionList
data class ActionList(
    var utoc: Path,
    var all: Boolean = false,
    var hash: Boolean = false,
    var `package`: Boolean = false,
    var size: Boolean = false,
    var path: Boolean = false,
    var store: Boolean = false
)

// Rust: retoc_cli/src/main.rs:75 ActionVerify
data class ActionVerify(
    var utoc: Path
)

// Rust: retoc_cli/src/main.rs:81 ActionUnpack
data class ActionUnpack(
    var input: String,
    var output: Path,
    var filter: List<String> = emptyList(),
    var no_parallel: Boolean = false,
    var verbose: Boolean = false
)

// Rust: retoc_cli/src/main.rs:102 ActionUnpackRaw
data class ActionUnpackRaw(
    var utoc: Path,
    var output: Path
)

// Rust: retoc_cli/src/main.rs:110 ActionPackRaw
data class ActionPackRaw(
    var input: Path,
    var utoc: Path
)

// Rust: retoc_cli/src/main.rs:118 ActionToLegacy
data class ActionToLegacy(
    var input: String,
    var output: Path,
    var filter: List<String> = emptyList(),
    var no_assets: Boolean = false,
    var no_shaders: Boolean = false,
    var no_script_objects: Boolean = false,
    var no_compres_shaders: Boolean = false,
    var dry_run: Boolean = false,
    var version: EngineVersion? = null,
    var script_cell: List<VerseScriptCell> = emptyList(),
    var verbose: Boolean = false,
    var debug: Boolean = false,
    var no_parallel: Boolean = false
)

// Rust: retoc_cli/src/main.rs:166 ActionToZen
data class ActionToZen(
    var input: Path,
    var output: Path,
    var filter: List<String> = emptyList(),
    var game_store: String? = null,
    var version: EngineVersion,
    var script_cell: List<VerseScriptCell> = emptyList(),
    var verbose: Boolean = false,
    var debug: Boolean = false,
    var no_parallel: Boolean = false
)

// Rust: retoc_cli/src/main.rs:203 ActionGet
data class ActionGet(
    var input: Path,
    var chunk_id: FIoChunkIdRaw,
    var output: Path? = null
)

// Rust: retoc_cli/src/main.rs:217 ActionDumpTest
data class ActionDumpTest(
    var input: Path,
    var output_dir: Path,
    var package_id: FPackageId
)

// Rust: retoc_cli/src/main.rs:227 ActionGenScriptObjects
data class ActionGenScriptObjects(
    var input: Path,
    var output: Path,
    var version: EngineVersion
)

// Rust: retoc_cli/src/main.rs:241 ActionPrintScriptObjects
data class ActionPrintScriptObjects(
    var input: Path
)

// Rust: retoc_cli/src/main.rs:247 ActionAssetRegistry
data class ActionAssetRegistry(
    var input: Path
)

// Rust: retoc_cli/src/main.rs:254 enum Action
sealed class Action {
    data class Manifest(val inner: ActionManifest) : Action()
    data class Info(val inner: ActionInfo) : Action()
    data class ListAction(val inner: ActionList) : Action()
    data class Verify(val inner: ActionVerify) : Action()
    data class Unpack(val inner: ActionUnpack) : Action()
    data class UnpackRaw(val inner: ActionUnpackRaw) : Action()
    data class PackRaw(val inner: ActionPackRaw) : Action()
    data class ToLegacy(val inner: ActionToLegacy) : Action()
    data class ToZen(val inner: ActionToZen) : Action()
    data class Get(val inner: ActionGet) : Action()
    data class DumpTest(val inner: ActionDumpTest) : Action()
    data class GenScriptObjects(val inner: ActionGenScriptObjects) : Action()
    data class PrintScriptObjects(val inner: ActionPrintScriptObjects) : Action()
    data class AssetRegistry(val inner: ActionAssetRegistry) : Action()
}

// Rust: retoc_cli/src/main.rs:294 Args
data class Args(
    var aes_key: String? = null,
    var game_id: String? = null,
    var override_container_header_version: EIoContainerHeaderVersion? = null,
    var override_toc_version: EIoStoreTocVersion? = null,
    var action: Action
)

// Rust: retoc_cli/src/main.rs:769 progress_style
fun progress_style(): String = "[{elapsed_precise}] {bar:40.cyan/blue} {pos:>7}/{len:7} {wide_msg}"

class SimpleProgressBar(val total: Long, val style: String) {
    @Volatile private var pos: Long = 0
    @Volatile private var msg: String = ""
    fun set_message(m: String) { msg = m }
    fun inc(n: Long = 1) { pos += n }
    fun finish_with_message(m: String) { msg = m }
    fun set_position(p: Long) { pos = p }
    fun get_position(): Long = pos
}

// Rust: retoc_cli/src/main.rs:570 mod raw
object raw {
    // Rust: retoc_cli/src/main.rs:578 RawIoManifest
    data class RawIoManifest(
        var chunk_paths: MutableMap<ChunkId, String> = mutableMapOf(),
        var version: EIoStoreTocVersion = EIoStoreTocVersion.Initial,
        var mount_point: String = "",
        var container_id: ULong? = null,
        var container_header_version: EIoContainerHeaderVersion? = null
    )

    // Rust: retoc_cli/src/main.rs:588 ChunkId
    data class ChunkId(var raw: FIoChunkIdRaw = FIoChunkIdRaw()) {
        fun to_hex(): String = HexFormat.of().formatHex(raw.id)
        companion object {
            fun from_hex(s: String): ChunkId = ChunkId(FIoChunkIdRaw.from_string(s))
        }
        override fun equals(other: Any?): Boolean = other is ChunkId && raw == other.raw
        override fun hashCode(): Int = raw.hashCode()
    }

    fun raw_manifest_to_json(m: RawIoManifest): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"chunk_paths\": {\n")
        val entries = m.chunk_paths.entries.toList()
        for ((i, e) in entries.withIndex()) {
            val hex = HexFormat.of().formatHex(e.key.raw.id)
            val escaped = e.value.replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append("    \"$hex\": \"$escaped\"")
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  },\n")
        sb.append("  \"version\": \"${m.version.name}\",\n")
        val mountEsc = m.mount_point.replace("\\", "\\\\").replace("\"", "\\\"")
        sb.append("  \"mount_point\": \"$mountEsc\",\n")
        sb.append("  \"container_id\": ${m.container_id?.toString() ?: "null"},\n")
        sb.append("  \"container_header_version\": ${m.container_header_version?.let { "\"${it.name}\"" } ?: "null"}\n")
        sb.append("}\n")
        return sb.toString()
    }

    fun raw_manifest_from_json(text: String): RawIoManifest {
        val chunk_paths = mutableMapOf<ChunkId, String>()
        val cpRegex = Regex("\"chunk_paths\"\\s*:\\s*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
        val cpMatch = cpRegex.find(text)
        if (cpMatch != null) {
            val inner = cpMatch.groupValues[1]
            val entryRegex = Regex("\"([0-9a-fA-F]+)\"\\s*:\\s*\"([^\"]*)\"")
            for (em in entryRegex.findAll(inner)) {
                val hex = em.groupValues[1]
                val path = em.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
                try {
                    val bytes = HexFormat.of().parseHex(hex)
                    if (bytes.size == 12) chunk_paths[ChunkId(FIoChunkIdRaw(bytes))] = path
                } catch (_: Exception) {}
            }
        }
        val versionRegex = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")
        val versionStr = versionRegex.find(text)?.groupValues?.get(1) ?: "Initial"
        val version = try { EIoStoreTocVersion.valueOf(versionStr) } catch (_: Exception) { EIoStoreTocVersion.Initial }
        val mountRegex = Regex("\"mount_point\"\\s*:\\s*\"([^\"]*)\"")
        val mount = mountRegex.find(text)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: ""
        val containerIdRegex = Regex("\"container_id\"\\s*:\\s*(null|\\d+)")
        val cidStr = containerIdRegex.find(text)?.groupValues?.get(1)
        val cid = if (cidStr == null || cidStr == "null") null else try { cidStr.toULong() } catch (_: Exception) { null }
        val headerVerRegex = Regex("\"container_header_version\"\\s*:\\s*(null|\"([^\"]*)\")")
        val hvMatch = headerVerRegex.find(text)
        val hvStr = hvMatch?.groupValues?.get(2)
        val hv = if (hvStr.isNullOrEmpty()) null else try { EIoContainerHeaderVersion.valueOf(hvStr) } catch (_: Exception) { null }
        return RawIoManifest(chunk_paths = chunk_paths, version = version, mount_point = mount, container_id = cid, container_header_version = hv)
    }
}

// Helper to emit manifest json for pakstore
private fun manifest_to_json(manifest: PackageStoreManifest): String {
    val sb = StringBuilder()
    sb.append("{\"oplog\":{\"entries\":[")
    for ((i, op) in manifest.oplog.entries.withIndex()) {
        if (i > 0) sb.append(",")
        sb.append("{\"packagestoreentry\":{\"packagename\":\"${op.packagestoreentry.packagename.replace("\"", "\\\"")}\"},\"packagedata\":[")
        for ((j, cd) in op.packagedata.withIndex()) {
            if (j > 0) sb.append(",")
            sb.append("{\"id\":\"${HexFormat.of().formatHex(cd.id.id)}\",\"filename\":\"${cd.filename.replace("\"", "\\\"")}\"}")
        }
        sb.append("],\"bulkdata\":[")
        for ((j, cd) in op.bulkdata.withIndex()) {
            if (j > 0) sb.append(",")
            sb.append("{\"id\":\"${HexFormat.of().formatHex(cd.id.id)}\",\"filename\":\"${cd.filename.replace("\"", "\\\"")}\"}")
        }
        sb.append("]}")
    }
    sb.append("]}}")
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Rust: retoc_cli/src/main.rs:351 fn action_manifest
// ---------------------------------------------------------------------------
fun action_manifest(args: ActionManifest, config: Config) {
    // Rust: let iostore = iostore::open(args.utoc, config)?;
    val iostore = open(args.utoc, config)
    val container_header_version = iostore.container_header_version() ?: throw IllegalStateException("missing container_header_version")
    val toc_version = iostore.container_file_version() ?: throw IllegalStateException("missing toc_version")

    // Rust: let entries = Arc::new(Mutex::new(vec![])); parallel via coroutines preserving Mutex
    val entries = Collections.synchronizedList(mutableListOf<Op>())

    // Rust: iostore.packages().par_bridge().try_for_each
    runBlocking {
        val jobs = iostore.packages().map { package_info ->
            async(Dispatchers.Default) {
                val chunk_id = FIoChunkId.from_package_id(package_info.id(), 0.toUShort(), EIoChunkType.ExportBundleData).with_version(toc_version)
                val package_path = iostore.chunk_path(chunk_id) ?: throw IllegalArgumentException("${package_info.id()} has no path name entry")
                val data = package_info.container().read(chunk_id)
                val package_name = try {
                    get_package_name(data, container_header_version)
                } catch (e: Exception) {
                    throw RuntimeException("failed to get package name for $package_path: ${e.message}", e)
                }
                val entry = Op(
                    packagestoreentry = PackageStoreEntry(packagename = package_name),
                    packagedata = mutableListOf(ChunkData(id = chunk_id.get_raw(), filename = package_path)),
                    bulkdata = mutableListOf()
                )
                val bulk_id = FIoChunkId.from_package_id(package_info.id(), 0.toUShort(), EIoChunkType.BulkData).with_version(toc_version)
                if (iostore.has_chunk_id(bulk_id)) {
                    // Rust: UEPath::new(&package_path).with_extension("ubulk")
                    val ubulk = try { package_path.with_extension("ubulk") } catch (_: Exception) {
                        val dot = package_path.lastIndexOf('.')
                        if (dot >= 0) package_path.substring(0, dot) + ".ubulk" else "$package_path.ubulk"
                    }
                    (entry.bulkdata as MutableList).add(ChunkData(id = bulk_id.get_raw(), filename = ubulk))
                }
                entries.add(entry)
            }
        }.toList()
        jobs.awaitAll()
    }

    val sorted = entries.sortedBy { it.packagestoreentry.packagename }
    val manifest = PackageStoreManifest(oplog = OpLog(entries = sorted))
    val json = manifest_to_json(manifest)
    val outPath = Path.of("pakstore.json")
    Files.writeString(outPath, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    println("wrote ${manifest.oplog.entries.size} entries to $outPath")
}

// Rust: retoc_cli/src/main.rs:401 action_info
fun action_info(args: ActionInfo, config: Config) {
    val iostore = open(args.path, config)
    iostore.print_info(0)
}

// Rust: retoc_cli/src/main.rs:407 action_list
fun action_list(args: ActionList, config: Config) {
    val iostore = open(args.utoc, config)
    val chunks = if (args.all) iostore.chunks_all() else iostore.chunks()
    for (chunk in chunks) {
        val id = chunk.id()
        val chunk_type = id.get_chunk_type()
        val package_id_str: String = if (chunk_type == EIoChunkType.ExportBundleData) {
            FPackageId(id.get_chunk_id()).value.toString()
        } else "-"

        val line = StringBuilder()
        var first = true
        fun column(fmt: String, vararg argv: Any?) {
            if (!first) line.append(" ")
            first = false
            val formatted = if (argv.isEmpty()) fmt else String.format(fmt, *argv)
            line.append(formatted)
        }

        column("%-30s", chunk.container().container_name())
        column("%s", HexFormat.of().formatHex(id.get_raw().id))
        if (args.hash) {
            column("%s", HexFormat.of().formatHex(chunk.hash().bytes))
        }
        if (args.`package`) {
            column("%-20s", package_id_str)
        }
        column("%-20s", chunk_type.name)
        if (args.size) {
            column("%10s", chunk.size().toString())
        }
        if (args.path) {
            column("%s", chunk.path() ?: "-")
        }
        if (args.store) {
            val package_store_entry = if (chunk_type == EIoChunkType.ExportBundleData) {
                try {
                    val e = chunk.container().package_store_entry(id.get_package_id())
                    e.toString()
                } catch (_: Exception) { "-" }
            } else "-"
            column("%s", package_store_entry)
        }
        println(line.toString())
    }
}

// Rust: retoc_cli/src/main.rs:461 action_verify
fun action_verify(args: ActionVerify, config: Config) {
    // Rust verifies Toc chunk hashes via blake3 against meta.hash; we mirror via IoStore + Blake3Digest
    val utoc = args.utoc
    val ucas = utoc.resolveSibling(utoc.fileName.toString().substringBeforeLast(".") + ".ucas")
    // Try direct Toc path first (mirrors Rust: BufReader::new(fs::File::open(&args.utoc)?).de_ctx(config)?)
    val toc: Toc = Files.newInputStream(utoc).use { stream ->
        val bis = BufferedInputStream(stream)
        // de_with_config handles header version etc
        Toc().de_with_config(bis, config)
    }
    // Rust parallel verify using rayon par_iter try_for_each_init with BufReader per thread
    runBlocking {
        val jobs = toc.chunk_metas.withIndex().map { (i, meta) ->
            async(Dispatchers.Default) {
                val data = try {
                    // Use RandomAccessFile parity with Rust's fs::File::open(ucas)
                    RandomAccessFile(ucas.toFile(), "r").use { raf ->
                        toc.read(raf, i.toUInt())
                    }
                } catch (e: Exception) {
                    // fallback to InputStream reading whole ucas (as in lib's InputStream overload)
                    Files.newInputStream(ucas).use { ins ->
                        toc.read(ins, i.toUInt())
                    }
                }
                val hashBytes = ByteArray(32)
                // Rust: let hash = blake3::hash(&data); meta.chunk_hash.0[..20] != hash.as_bytes()[..20]
                // BouncyCastle Blake3Digest for parity (EXC-003), SHA-256 fallback if BC unavailable
                val hasher = try {
                    Blake3Digest(256)
                } catch (_: Exception) {
                    null
                }
                if (hasher != null) {
                    hasher.update(data, 0, data.size)
                    hasher.doFinal(hashBytes, 0)
                } else {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val sha = md.digest(data)
                    System.arraycopy(sha, 0, hashBytes, 0, minOf(sha.size, 32))
                }
                val expected = meta.chunk_hash.bytes
                // Rust checks meta.chunk_hash.0[..20] != hash.as_bytes()[..20]
                if (!hashBytes.copyOfRange(0, 20).contentEquals(expected.copyOfRange(0, 20))) {
                    throw IllegalStateException("hash mismatch for chunk #$i")
                }
            }
        }
        jobs.awaitAll()
    }
    println("verified")
}

// Rust: retoc_cli/src/main.rs:531 action_unpack
fun action_unpack(args: ActionUnpack, config: Config) {
    //@parity:on EXC-012
    val input_paths = args.input.split(File.pathSeparator).map { Path.of(it.trim()) }.filter { it.toString().isNotEmpty() }
    val iostore = open_with_container_paths(input_paths, config)
    //@parity:off EXC-012
    val output = args.output
    Files.createDirectories(output)
    val chunks = iostore.chunks().toList()

    fun process(chunk: ChunkInfo) {
        val path = chunk.path() ?: return
        //@parity:on EXC-012
        if (args.filter.isNotEmpty() && args.filter.none { f -> path.contains(f) }) return
        //@parity:off EXC-012
        if (args.verbose) println(path)
        var relative = path
        while (relative.startsWith("../")) relative = relative.substring(3)
        // also strip leading "/" if mount handling produced absolute-ish
        relative = relative.trimStart('/')
        val data = chunk.read()
        val target = output.resolve(relative)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    //@parity:on EXC-012
    if (args.no_parallel) {
        for (c in chunks) process(c)
    } else {
        runBlocking {
            chunks.map { c -> async(Dispatchers.Default) { process(c) } }.awaitAll()
        }
    }
    //@parity:off EXC-012
    println("unpacked ${chunks.size} files to $output")
}

// Rust: retoc_cli/src/main.rs:614 action_unpack_raw
fun action_unpack_raw(args: ActionUnpackRaw, config: Config) {
    val iostore = open(args.utoc, config)
    val output = args.output
    val chunks_dir = output.resolve("chunks")
    val manifest_path = output.resolve("manifest.json")
    Files.createDirectories(output)
    Files.createDirectories(chunks_dir)

    //@parity:on EXC-012
    val manifest = raw.RawIoManifest(
        chunk_paths = mutableMapOf(),
        version = iostore.container_file_version() ?: EIoStoreTocVersion.Initial,
        mount_point = iostore.mount_point(),
        container_id = try { iostore.container_id().value } catch (_: Exception) { null },
        container_header_version = iostore.container_header_version()
    )
    //@parity:off EXC-012

    var count = 0
    for (chunk in iostore.chunks()) {
        val data = chunk.read()
        val hex = HexFormat.of().formatHex(chunk.id().get_raw().id)
        Files.write(chunks_dir.resolve(hex), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        val p = chunk.path()
        if (p != null) {
            manifest.chunk_paths[raw.ChunkId(chunk.id().get_raw())] = p
        }
        count++
    }

    val json = raw.raw_manifest_to_json(manifest)
    Files.writeString(manifest_path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    println("unpacked $count chunks to $output")
}

// Rust: retoc_cli/src/main.rs:647 action_pack_raw
fun action_pack_raw(args: ActionPackRaw, config: Config) {
    // config unused in Rust (_config) but kept for parity
    val manifest_text = Files.readString(args.input.resolve("manifest.json"))
    val manifest = raw.raw_manifest_from_json(manifest_text)

    var header_chunk: FIoContainerHeader? = null
    val package_chunks = mutableListOf<Triple<FIoChunkIdRaw, String?, ByteArray>>()

    val chunks_dir = args.input.resolve("chunks")
    Files.list(chunks_dir).use { stream ->
        stream.forEach { entry ->
            val fileName = entry.fileName.toString()
            val chunk_id = try { FIoChunkIdRaw.from_string(fileName) } catch (e: Exception) {
                // also handle hex decode directly if from_string expects 24 hex chars
                val bytes = HexFormat.of().parseHex(fileName)
                FIoChunkIdRaw(bytes)
            }
            val path = manifest.chunk_paths[raw.ChunkId(chunk_id)]
            val data = Files.readAllBytes(entry)
            val chunk_type = FIoChunkId.from_raw(chunk_id, manifest.version).get_chunk_type()
            if (chunk_type == EIoChunkType.ContainerHeader) {
                // Preserve header metadata
                try {
                    val hdr = FIoContainerHeader.deserialize(ByteArrayInputStream(data), null)
                    header_chunk = hdr
                } catch (_: Exception) {
                    // ignore parse failure, treat as package_chunks
                    package_chunks.add(Triple(chunk_id, path, data))
                }
            } else {
                package_chunks.add(Triple(chunk_id, path, data))
            }
        }
    }

    //@parity:on EXC-012
    val container_id = manifest.container_id?.let { FIoContainerId(it) } ?: header_chunk?.container_id
    val package_ids: Set<FPackageId> = package_chunks.mapNotNull { (cid, _, _) ->
        val t = FIoChunkId.from_raw(cid, manifest.version).get_chunk_type()
        if (t == EIoChunkType.ExportBundleData) FIoChunkId.from_raw(cid, manifest.version).get_package_id() else null
    }.toSet()

    val header: FIoContainerHeader? = header_chunk?.let { source_header ->
        val preserve = source_header.package_ids().all { it in package_ids } && source_header.package_ids().size == package_ids.size
        if (preserve) source_header else FIoContainerHeader.new(source_header.version, container_id ?: source_header.container_id)
    } ?: manifest.container_header_version?.let { v -> FIoContainerHeader.new(v, container_id ?: FIoContainerId(0uL)) }
    //@parity:off EXC-012

    val writer = IoStoreWriter.with_container_header(args.utoc, manifest.version, manifest.mount_point, header)
    for ((chunk_id, path, data) in package_chunks) {
        val chunk_type = FIoChunkId.from_raw(chunk_id, manifest.version).get_chunk_type()
        if (chunk_type == EIoChunkType.ExportBundleData) {
            val package_id = FIoChunkId.from_raw(chunk_id, manifest.version).get_package_id()
            val store_entry = header_chunk?.get_store_entry(package_id)
            writer.write_chunk_auto(chunk_id, path?.let { it }, data, store_entry)
        } else {
            writer.write_chunk_raw(chunk_id, path?.let { it }, data)
        }
    }
    writer.finalize()

    // Create legacy pak index (necessary for game to detect container)
    val pak_path = args.utoc.resolveSibling(args.utoc.fileName.toString().substringBeforeLast(".") + ".pak")
    val ch = Files.newByteChannel(pak_path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    ch.use {
        val pakWriter = PakBuilder.new().writer(it, RepakVersion.V11, manifest.mount_point, null)
        pakWriter.write_index()
    }
}

// Rust: retoc_cli/src/main.rs:710 action_to_legacy
fun action_to_legacy(args: ActionToLegacy, config: Config) {
    val log = Log.new_stdout(args.verbose, args.debug)
    if (args.dry_run) {
        action_to_legacy_inner(args, config, NullFileWriter(), log)
    } else if (args.output.toString().endsWith(".pak")) {
        // parallel pak writer via coroutines + LinkedBlockingQueue mirroring Rust's sync_channel(0)
        val ch = Files.newByteChannel(args.output, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        val pakWriter = PakBuilder.new().compression(listOf(RepakCompression.Oodle)).writer(ch, RepakVersion.V11, "../../../", null)
        val queue = java.util.concurrent.LinkedBlockingQueue<kotlin.Pair<String, Any>>()
        // We'll use coroutine scope to run conversion and then drain queue sequentially
        // For parity we keep simple: run inner with a FileWriter that enqueues, then drain
        val writer = object : FileWriterTrait {
            override fun write_file(path: String, allow_compress: Boolean, data: ByteArray) {
                val entry = pakWriter.entry_builder().build_entry_bytes(allow_compress, data)
                // unchecked cast to Any to avoid generic; queue expects Pair
                @Suppress("UNCHECKED_CAST")
                queue.put(kotlin.Pair(path, entry as Any))
            }
        }
        // Rust uses rayon::in_place_scope with sync_channel(0) (concurrent producer/consumer);
        // Kotlin runs the producer to completion, then drains the queue sequentially
        runBlocking {
            val job = async(Dispatchers.Default) {
                action_to_legacy_inner(args, config, writer, log)
                // signal end by putting sentinel? Instead we close after
            }
            // Wait for producer, then drain
            job.await()
            // Drain queue
            while (queue.isNotEmpty()) {
                val polled = queue.poll() ?: break
                @Suppress("UNCHECKED_CAST")
                val typed = polled as kotlin.Pair<String, com.github.jpabscale.zenpak4j.repak.PartialEntry<ByteArray>>
                pakWriter.write_entry(typed.first, typed.second)
            }
        }
        pakWriter.write_index()
        ch.close()
    } else {
        val file_writer = FSFileWriter(args.output)
        action_to_legacy_inner(args, config, file_writer, log)
    }
}

// Rust: retoc_cli/src/main.rs:751 action_to_legacy_inner
fun action_to_legacy_inner(args: ActionToLegacy, config: Config, file_writer: FileWriterTrait, log: Log) {
    //@parity:on EXC-012
    val input_paths = args.input.split(File.pathSeparator).map { Path.of(it.trim()) }.filter { it.toString().isNotEmpty() }
    val iostore = open_with_container_paths(input_paths, config)
    //@parity:off EXC-012
    if (!args.no_assets) {
        action_to_legacy_assets(args, file_writer, iostore, log)
    }
    if (!args.no_shaders) {
        action_to_legacy_shaders(args, file_writer, iostore, log)
    }
    if (!args.no_script_objects && iostore.container_file_version() != null && iostore.container_file_version()!! > EIoStoreTocVersion.PerfectHash) {
        val script_objects = iostore.load_script_objects()
        val buf = ByteArrayOutputStream()
        script_objects.serialize_new(buf)
        file_writer.write_file("scriptobjects.bin", false, buf.toByteArray())
    }
}

// Rust: retoc_cli/src/main.rs:769 progress_style already defined

// Rust: retoc_cli/src/main.rs:773 action_to_legacy_assets
fun action_to_legacy_assets(args: ActionToLegacy, file_writer: FileWriterTrait, iostore: IoStoreTrait, log: Log) {
    val packages_to_extract = mutableListOf<kotlin.Pair<PackageInfo, String>>()
    //@parity:on EXC-012
    val selected_package_ids = mutableSetOf<FPackageId>()
    for (package_info in iostore.packages()) {
        val chunk_id = FIoChunkId.from_package_id(package_info.id(), 0.toUShort(), EIoChunkType.ExportBundleData)
        val package_path = iostore.chunk_path(chunk_id) ?: throw IllegalArgumentException("${package_info.id()} has no path name entry. Cannot extract")
        if (args.filter.isNotEmpty() && args.filter.none { f -> package_path.contains(f) }) continue
        if (!selected_package_ids.add(package_info.id())) continue
        packages_to_extract.add(kotlin.Pair(package_info, package_path))
    }
    //@parity:off EXC-012

    val script_cell_store = build_verse_cell_store(args.script_cell)
    val package_file_version: FPackageFileVersion? = args.version?.package_file_version()
    val package_context = FZenPackageContext.create(iostore, package_file_version, log, script_cell_store)

    val count = packages_to_extract.size
    val failed_count = AtomicInteger(0)
    val progress = SimpleProgressBar(count.toLong(), progress_style())
    log.set_progress(progress)

    fun process(pair: kotlin.Pair<PackageInfo, String>) {
        val package_info = pair.first
        val package_path = pair.second
        try {
            //@parity:on EXC-012
            verbose(log, "${package_path} [from ${package_info.container().container_name()}]")
            //@parity:off EXC-012
        } catch (_: Exception) { verbose(log, package_path) }
        val stripped = try {
            if (package_path.startsWith("../../../")) package_path.substring(9) else package_path
        } catch (e: Exception) { package_path }
        try { progress.set_message(stripped) } catch (_: Exception) {}
        try {
            build_legacy(package_context, package_info.id(), stripped, file_writer)
        } catch (e: Exception) {
            try { info(log, "${e.message}") } catch (_: Exception) { println(e) }
            failed_count.incrementAndGet()
        }
        try { progress.inc(1) } catch (_: Exception) {}
    }

    if (args.no_parallel) {
        for (p in packages_to_extract) process(p)
    } else {
        runBlocking {
            packages_to_extract.map { p -> async(Dispatchers.Default) { process(p) } }.awaitAll()
        }
    }

    try { progress.finish_with_message("") } catch (_: Exception) {}
    log.set_progress(null)
    val failed = failed_count.get()
    try { info(log, "Extracted ${count - failed} ($failed failed) legacy assets to ${args.output}") } catch (_: Exception) { println("Extracted ${count - failed} ($failed failed) legacy assets to ${args.output}") }
}

// Rust: retoc_cli/src/main.rs:837 action_to_legacy_shaders
fun action_to_legacy_shaders(args: ActionToLegacy, file_writer: FileWriterTrait, iostore: IoStoreTrait, log: Log) {
    val compress_shaders = !args.no_compres_shaders
    var libraries_extracted = 0
    for (chunk_info in iostore.chunks().filter { it.id().get_chunk_type() == EIoChunkType.ShaderCodeLibrary }) {
        val shader_library_path = chunk_info.path() ?: throw IllegalArgumentException("Failed to retrieve pathname for shader library chunk ${chunk_info.id()}")
        if (args.filter.isNotEmpty() && args.filter.none { f -> shader_library_path.contains(f) }) continue
        try { verbose(log, "Extracting Shader Library: $shader_library_path") } catch (_: Exception) {}
        val stripped = if (shader_library_path.startsWith("../../../")) shader_library_path.substring(9) else shader_library_path
        val shader_asset_info_path = get_shader_asset_info_filename_from_library_filename(stripped)
        val (shader_library_buffer, shader_asset_info_buffer) = rebuild_shader_library_from_io_store(iostore, chunk_info.id(), log, compress_shaders)
        file_writer.write_file(stripped, false, shader_library_buffer)
        file_writer.write_file(shader_asset_info_path, compress_shaders, shader_asset_info_buffer)
        libraries_extracted += 1
    }
    try { info(log, "Extracted $libraries_extracted shader code libraries to ${args.output}") } catch (_: Exception) { println("Extracted $libraries_extracted shader code libraries") }
}

// Rust: retoc_cli/src/main.rs:862 action_to_zen
fun action_to_zen(args: ActionToZen, config: Config) {
    val input: FileReaderTrait = if (Files.isDirectory(args.input)) FSFileReader(args.input) else PakFileReader.new(args.input)
    action_to_zen_reader(args, config, input)
}

/**
 * action_to_zen with a caller-supplied [FileReaderTrait] (automod in-memory seam): the identical
 * pipeline, but loose-file bytes come from [input] instead of FSFileReader(args.input). Everything
 * after reader construction is unchanged from action_to_zen.
 */
fun action_to_zen_reader(args: ActionToZen, config: Config, input: FileReaderTrait) {
    val mount_point: UEPath = "../../../"

    //@parity:on EXC-011
    val gs_value = args.game_store
    val game_package_names: HashSet<String>? = if (gs_value != null) {
        val game_store_paths = gs_value.split(File.pathSeparator).map { Path.of(it.trim()) }.filter { it.toString().isNotEmpty() }
        val game_iostore = open_with_container_paths(game_store_paths, config)
        val set = HashSet<String>()
        for (chunk in game_iostore.chunks().filter { it.id().get_chunk_type() == EIoChunkType.ExportBundleData }) {
            val p = chunk.path() ?: continue
            val stripped = when {
                p.endsWith(".uasset") -> p.removeSuffix(".uasset")
                p.endsWith(".umap") -> p.removeSuffix(".umap")
                else -> continue
            }
            val withoutMount = if (stripped.startsWith("../../../")) stripped.substring(9) else stripped
            val gamePath = try { pak_path_to_game_path(withoutMount) } catch (_: Exception) { null }
            if (gamePath != null) set.add(gamePath)
        }
        try { info(Log.new_stdout(false,false), "Loaded ${set.size} package paths from game store") } catch (_: Exception) {}
        set
    } else null
    //@parity:off EXC-011

    val container_header_version = config.container_header_version_override ?: args.version.container_header_version()
    val toc_version = config.toc_version_override ?: args.version.toc_version()

    val writer = IoStoreWriter.new(args.output, toc_version, container_header_version, mount_point)

    val log = Log.new_stdout(args.verbose, args.debug)
    val asset_paths = mutableListOf<UEPathBuf>()
    val shader_lib_paths = mutableListOf<UEPathBuf>()
    var script_objects: ZenScriptObjects? = null

    fun check_path(p: UEPath): Boolean = if (args.filter.isEmpty()) true else args.filter.any { f -> p.contains(f) }

    val files = input.list_files()
    val files_set = files.toHashSet()

    //@parity:on EXC-004
    // Deterministic: sort asset and shader paths before processing for byte-identical containers
    // Rust uses walk order (non-deterministic) + parallel completion order; Kotlin sorts for determinism
    for (path in files.sorted()) {
        val ext = try { Path.of(path).extension } catch (_: Exception) { path.substringAfterLast('.', "") }
        val is_asset = ext == "uasset" || ext == "umap"
        if (is_asset && check_path(path)) {
            val uexp = path.with_extension("uexp")
            if (files_set.contains(uexp)) asset_paths.add(path)
            else try { info(log, "Skipping $path because it does not have a split exports file. Are you sure the package is cooked?") } catch (_: Exception) {}
        }
        val is_shader_lib = ext == "ushaderbytecode"
        if (is_shader_lib && check_path(path)) shader_lib_paths.add(path)
        if (toc_version > EIoStoreTocVersion.PerfectHash && Path.of(path).fileName?.toString() == "scriptobjects.bin") {
            val buf = input.read(path)
            script_objects = ZenScriptObjects.deserialize_new(ByteArrayInputStream(buf))
        }
    }
    //@parity:off EXC-004

    // Convert shader libraries first
    val package_name_to_referenced_shader_maps: HashMap<String, MutableList<FSHAHash>> = HashMap()
    for (path in shader_lib_paths) {
        try { info(log, "converting shader library $path") } catch (_: Exception) {}
        val shader_library_buffer = input.read(path)
        val asset_metadata_filename = get_shader_asset_info_filename_from_library_filename(Path.of(path).fileName.toString())
        val asset_metadata_path = Path.of(path).parent?.resolve(asset_metadata_filename)?.toString()?.replace('\\','/') ?: asset_metadata_filename
        // Read asset metadata if present
        val meta = input.read_opt(asset_metadata_path)
        if (meta != null) {
            read_shader_asset_info(meta, package_name_to_referenced_shader_maps)
        }
        write_io_store_library(writer, shader_library_buffer, (mount_point + "/" + path).replace("//","/"), log)
    }

    val script_cell_store = build_verse_cell_store(args.script_cell)
    val progress = SimpleProgressBar(asset_paths.size.toLong(), progress_style())
    log.set_progress(progress)

    val container_header_version_for_assets = writer.container_header_version()
    val needs_asset_import_fixup = container_header_version_for_assets <= EIoContainerHeaderVersion.Initial

    // Process assets with coroutine channel mirroring Rust's sync_channel(0)
    if (needs_asset_import_fixup) {
        // Collect all, then fixup, then write
        val converted_lookup = HashMap<FPackageId, ConvertedZenAssetBundle>()
        val all_converted = mutableListOf<ConvertedZenAssetBundle>()
        var total_package_data_size = 0

        // Parallel conversion using coroutines
        val converted_list: List<ConvertedZenAssetBundle> = runBlocking {
            asset_paths.map { path ->
                async(Dispatchers.Default) {
                    try { progress.set_message(path) } catch (_: Exception) {}
                    val bundle = FSerializedAssetBundle(
                        asset_file_buffer = input.read(path),
                        exports_file_buffer = input.read(path.with_extension("uexp")),
                        bulk_data_buffer = input.read_opt(path.with_extension("ubulk")),
                        optional_bulk_data_buffer = input.read_opt(path.with_extension("uptnl")),
                        memory_mapped_bulk_data_buffer = input.read_opt(path.with_extension("m.ubulk"))
                    )
                    val converted = build_zen_asset(
                        bundle,
                        package_name_to_referenced_shader_maps,
                        (mount_point + "/" + path).replace("//","/"),
                        args.version.package_file_version(),
                        container_header_version_for_assets,
                        needs_asset_import_fixup,
                        script_objects,
                        script_cell_store,
                        game_package_names,
                        log
                    )
                    try { progress.inc(1) } catch (_: Exception) {}
                    converted
                }
            }.awaitAll()
        }

        for (converted in converted_list) {
            var mut = converted
            mut.write_and_release_bulk_data(writer)
            total_package_data_size += mut.package_data_size()
            converted_lookup[mut.package_id] = mut
            all_converted.add(mut)
        }

        try { info(log, "Applying import fix-ups to the converted assets. Package data in memory: ${total_package_data_size / 1024 / 1024}MB") } catch (_: Exception) {}
        try { progress.set_position(0) } catch (_: Exception) {}
        for (converted in all_converted) {
            converted.fixup_legacy_external_arcs(converted_lookup, log)
            try { progress.inc(1) } catch (_: Exception) {}
        }
        try { info(log, "Writing converted assets") } catch (_: Exception) {}
        try { progress.set_position(0) } catch (_: Exception) {}
        for (converted in all_converted) {
            converted.write_package_data(writer)
            try { progress.inc(1) } catch (_: Exception) {}
        }
    } else {
        //@parity:on EXC-004
        // Deterministic: collect all converted assets via parallel, sort by package_name, then write in sorted order
        // Rust uses completion order via sync_channel(0); Kotlin sorts for byte-identical containers
        val convertedList = runBlocking {
            asset_paths.map { path ->
                async(Dispatchers.Default) {
                    try { verbose(log, "converting asset $path") } catch (_: Exception) {}
                    try { progress.set_message(path) } catch (_: Exception) {}
                    val bundle = FSerializedAssetBundle(
                        asset_file_buffer = input.read(path),
                        exports_file_buffer = input.read(path.with_extension("uexp")),
                        bulk_data_buffer = input.read_opt(path.with_extension("ubulk")),
                        optional_bulk_data_buffer = input.read_opt(path.with_extension("uptnl")),
                        memory_mapped_bulk_data_buffer = input.read_opt(path.with_extension("m.ubulk"))
                    )
                    val converted = build_zen_asset(
                        bundle,
                        package_name_to_referenced_shader_maps,
                        (mount_point + "/" + path).replace("//","/"),
                        args.version.package_file_version(),
                        container_header_version_for_assets,
                        false,
                        script_objects,
                        script_cell_store,
                        game_package_names,
                        log
                    )
                    // Don't write here; return for sorted write
                    try { progress.inc(1) } catch (_: Exception) {}
                    converted
                }
            }.awaitAll()
        }
        // Sort by package name for deterministic write order (TreeMap order)
        val sortedConverted = convertedList.sortedBy { it.package_id.toString() }
        for (converted in sortedConverted) {
            synchronized(writer) {
                converted.write(writer)
            }
        }
        //@parity:off EXC-004
    }

    try { progress.finish_with_message("") } catch (_: Exception) {}
    log.set_progress(null)

    //@parity:on EXC-011
    // Pass through remaining plain files as external chunks
    val passthrough_chunk_type = if (toc_version > EIoStoreTocVersion.PerfectHash) EIoChunkType.ExternalFile else EIoChunkType.BulkData
    var external_chunk_index: UShort = 0u
    for (path in files) {
        val ext = try { Path.of(path).extension } catch (_: Exception) { path.substringAfterLast('.', "") }
        val file_name = try { Path.of(path).fileName?.toString() ?: "" } catch (_: Exception) { path.substringAfterLast('/') }
        val is_converted_content = ext in setOf("uasset","umap","uexp","ubulk","uptnl","m.ubulk","ushaderbytecode") ||
            (file_name.startsWith("ShaderAssetInfo-") && ext == "assetinfo.json") || file_name == "scriptobjects.bin"
        if (is_converted_content || !check_path(path)) continue
        try { verbose(log, "passing through $path") } catch (_: Exception) {}
        val data = input.read(path)
        val chunk_id = FIoChunkId.create(external_chunk_index.toULong(), 0.toUShort(), passthrough_chunk_type)
        external_chunk_index = (external_chunk_index + 1u).toUShort()
        writer.write_chunk(chunk_id, (mount_point + "/" + path).replace("//","/"), data)
    }
    //@parity:off EXC-011

    //@parity:on EXC-011
    writer.finalize()
    val pak_path = args.output.resolveSibling(args.output.fileName.toString().substringBeforeLast(".") + ".pak")
    if (!Files.exists(pak_path)) {
        val ch = Files.newByteChannel(pak_path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        ch.use {
            val pakWriter = PakBuilder.new().writer(it, RepakVersion.V11, mount_point, null)
            pakWriter.write_index()
        }
    }
    //@parity:off EXC-011
}

// Rust: retoc_cli/src/main.rs:1092 action_get
fun action_get(args: ActionGet, config: Config) {
    val iostore = open(args.input, config)
    val data = iostore.read_raw(args.chunk_id)
    val outPath = args.output
    if (outPath != null && outPath.toString() != "-") {
        Files.createDirectories(outPath.parent ?: Path.of("."))
        Files.write(outPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    } else {
        System.out.write(data)
        System.out.flush()
    }
}

// Rust: ser_hex TraceStream (ser-hex@3d890bb) — flat port for dump-test.
// Rust nests read/seek actions under #[instrument] spans; Kotlin records the same action
// sequence under a single "root" span (no span nesting).
class TraceStream(data: ByteArray) : SeekableByteArrayInputStream(data) {
    val actions = mutableListOf<kotlin.Pair<String, Long>>()

    override fun read(): Int {
        val n = super.read()
        if (n >= 0) actions.add("Read" to 1L)
        return n
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n >= 0) actions.add("Read" to n.toLong())
        return n
    }

    override fun seek(pos: Long) {
        super.seek(pos)
        actions.add("Seek" to pos)
    }
}

// Rust: ser_hex Trace::save — {"data":<base64>, "root":..., "start_index":...}
// serde_json default map is BTreeMap, so keys serialize sorted: data, root, start_index.
private fun trace_to_json(stream: TraceStream, data: ByteArray): String {
    val actionsJson = stream.actions.joinToString(",", "[", "]") { (kind, value) ->
        when (kind) {
            "Read" -> "{\"Read\":$value}"
            else -> "{\"Seek\":$value}"
        }
    }
    return "{\"data\":\"${Base64.getEncoder().encodeToString(data)}\",\"root\":{\"Span\":{\"name\":\"root\",\"actions\":$actionsJson}},\"start_index\":0}"
}

// Rust: retoc_cli/src/main.rs:1106 action_dump_test
fun action_dump_test(args: ActionDumpTest, config: Config) {
    val iostore = open(args.input, config)
    val chunk_id = FIoChunkId.from_package_id(args.package_id, 0.toUShort(), EIoChunkType.ExportBundleData)
    val game_path = iostore.chunk_path(chunk_id) ?: throw IllegalArgumentException("no path found for package")
    val name = try { Path.of(game_path).fileName?.toString() ?: game_path.substringAfterLast('/') } catch (_: Exception) { game_path.substringAfterLast('/') }
    val outPath = args.output_dir.resolve(name)
    Files.createDirectories(args.output_dir)
    val data = iostore.read(chunk_id)
    Files.write(outPath, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    val store_entry = iostore.package_store_entry(args.package_id)
    val metadata = PackageTestMetadata(
        toc_version = iostore.container_file_version() ?: EIoStoreTocVersion.Initial,
        container_header_version = iostore.container_header_version() ?: EIoContainerHeaderVersion.SoftPackageReferencesOffset,
        package_file_version = null,
        store_entry = store_entry
    )
    // Write metadata json pretty – manual
    val metaJson = buildString {
        append("{\n")
        append("  \"toc_version\": \"${metadata.toc_version.name}\",\n")
        append("  \"container_header_version\": \"${metadata.container_header_version.name}\",\n")
        append("  \"package_file_version\": ${metadata.package_file_version?.let { "{\"file_version_ue4\": ${it.file_version_ue4}, \"file_version_ue5\": ${it.file_version_ue5}}" } ?: "null"},\n")
        val storeEntryJson = metadata.store_entry?.let { se ->
            buildString {
                append("{\n")
                append("    \"export_bundles_size\": ${se.export_bundles_size},\n")
                append("    \"load_order\": ${se.load_order},\n")
                append("    \"export_count\": ${se.export_count},\n")
                append("    \"export_bundle_count\": ${se.export_bundle_count},\n")
                append("    \"imported_packages\": [${se.imported_packages.joinToString(", ") { it.value.toString() }}],\n")
                append("    \"shader_map_hashes\": [${se.shader_map_hashes.joinToString(", ") { "\"${it.bytes.joinToString("") { b -> "%02x".format(b) }}\"" }}]\n")
                append("  }")
            }
        } ?: "null"
        append("  \"store_entry\": $storeEntryJson\n")
        append("}\n")
    }
    val metaPath = outPath.resolveSibling(outPath.fileName.toString().substringBeforeLast(".") + ".metadata.json")
    Files.writeString(metaPath, metaJson, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    // Trace / header dump
    // Rust: ser_hex::TraceStream::new(path.with_extension("trace.json"), Cursor::new(data))
    val trace = TraceStream(data)
    val header = FZenPackageHeader.deserialize(trace, metadata.store_entry, metadata.toc_version, metadata.container_header_version, metadata.package_file_version)
    val headerTxt = outPath.resolveSibling(outPath.fileName.toString().substringBeforeLast(".") + ".header.txt")
    Files.writeString(headerTxt, header.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    // Rust: Trace::save writes {"data": <base64>, "start_index": 0, "root": {"Span": ...}} (serde_json compact, sorted keys)
    val traceJson = outPath.resolveSibling(outPath.fileName.toString().substringBeforeLast(".") + ".trace.json")
    Files.writeString(traceJson, trace_to_json(trace, data), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

    // bulk variants
    val bulkId = FIoChunkId.from_package_id(args.package_id, 0.toUShort(), EIoChunkType.BulkData)
    if (iostore.has_chunk_id(bulkId)) {
        val bulk = iostore.read(bulkId)
        Files.write(outPath.resolveSibling(outPath.fileName.toString().substringBeforeLast(".") + ".ubulk"), bulk, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
    val optId = FIoChunkId.from_package_id(args.package_id, 0.toUShort(), EIoChunkType.OptionalBulkData)
    if (iostore.has_chunk_id(optId)) {
        val d = iostore.read(optId)
        Files.write(outPath.resolveSibling(outPath.fileName.toString().substringBeforeLast(".") + ".uptnl"), d, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
    val memId = FIoChunkId.from_package_id(args.package_id, 0.toUShort(), EIoChunkType.MemoryMappedBulkData)
    if (iostore.has_chunk_id(memId)) {
        val d = iostore.read(memId)
        Files.write(outPath.resolveSibling(outPath.fileName.toString().substringBeforeLast(".") + ".m.ubulk"), d, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }
}

// Rust: retoc_cli/src/main.rs:1156 action_gen_script_objects
fun action_gen_script_objects(args: ActionGenScriptObjects, config: Config) {
    // Parse jmap reflection dump (JSON) matching Rust's jmap crate schema:
    // {"objects": {path: {"type": "Class", "outer": ..., "class_default_object": ...}}}
    val root = JSONObject(Files.readString(args.input))
    val objects = root.getJSONObject("objects")

    // map CDO => Class
    val cdos = mutableMapOf<String, String>()
    for (path in objects.keys()) {
        val obj = objects.getJSONObject(path)
        if (path.startsWith("/Script/") && obj.optString("type") == "Class" && obj.has("class_default_object") && !obj.isNull("class_default_object")) {
            cdos[obj.getString("class_default_object")] = path
        }
    }

    val nameMap = FNameMap.create(EMappedNameType.Global)
    val scriptObjects = mutableListOf<FScriptObjectEntry>()
    for (path in objects.keys()) {
        if (!path.startsWith("/Script/")) continue
        val obj = objects.getJSONObject(path)
        if (obj.optString("type") == "Class" && obj.has("class_default_object") && !obj.isNull("class_default_object")) {
            cdos[obj.getString("class_default_object")] = path
        }
        val components = path.split('/', '.', ':')
        val lastName = components.last()
        val name = if (components.size - 1 == 2) path else lastName
        val outerIndex = if (obj.has("outer") && !obj.isNull("outer")) FPackageObjectIndex.create_script_import(obj.getString("outer")) else FPackageObjectIndex.create_null()
        val cdoClassIndex = cdos[path]?.let { FPackageObjectIndex.create_script_import(it) } ?: FPackageObjectIndex.create_null()
        scriptObjects.add(
            FScriptObjectEntry(
                object_name = nameMap.store(name),
                global_index = FPackageObjectIndex.create_script_import(path),
                outer_index = outerIndex,
                cdo_class_index = cdoClassIndex
            )
        )
    }

    val writer = IoStoreWriter.new(args.output, args.version.toc_version(), null, "")
    val use_new_format = args.version.toc_version() > EIoStoreTocVersion.PerfectHash
    if (use_new_format) {
        val buf = ByteArrayOutputStream()
        nameMap.serialize(buf)
        buf.write_vec(scriptObjects)
        writer.write_chunk(FIoChunkId.create(0uL, 0.toUShort(), EIoChunkType.ScriptObjects), null, buf.toByteArray())
    } else {
        val (bufNames, bufHashes) = write_name_batch_parts(nameMap.copy_raw_names())
        val bufObjects = ByteArrayOutputStream()
        bufObjects.write_vec(scriptObjects)
        writer.write_chunk(FIoChunkId.create(0uL, 0.toUShort(), EIoChunkType.LoaderGlobalNames), null, bufNames)
        writer.write_chunk(FIoChunkId.create(0uL, 0.toUShort(), EIoChunkType.LoaderGlobalNameHashes), null, bufHashes)
        writer.write_chunk(FIoChunkId.create(0uL, 0.toUShort(), EIoChunkType.LoaderInitialLoadMeta), null, bufObjects.toByteArray())
    }
    writer.finalize()
    println("Generated script objects container with ${scriptObjects.size} objects using ${if (use_new_format) "new" else "old"} format")
}

// Rust: retoc_cli/src/main.rs:1235 action_print_script_objects
fun action_print_script_objects(args: ActionPrintScriptObjects, config: Config) {
    val iostore = open(args.input, config)
    val script_objects = iostore.load_script_objects()
    script_objects.print()
}

// Rust: retoc_cli/src/main.rs:1242 action_asset_registry
fun action_asset_registry(args: ActionAssetRegistry, config: Config) {
    val data = Files.readAllBytes(args.input)
    val registry = AssetRegistry().deserialize(ByteArrayInputStream(data))
    println("Asset Registry")
    println("  Version: ${registry.version}")
    println("  Registry Version: ${registry.registry_version}")
    println("  Assets: ${registry.asset_data.size}")
    println()
    for (asset in registry.asset_data) {
        // use dbg helper if available
        try {
            val dbg = dbg.Dbg.new(registry, asset)
            println(dbg.debug_asset_data())
        } catch (_: Exception) {
            println(asset)
        }
        println()
    }
}

// Helper to write vec for ByteArrayOutputStream (uses ser.kt's write_u32_le)
private fun ByteArrayOutputStream.write_vec(items: List<Writeable>) {
    this.write_u32_le(items.size.toUInt())
    for (it in items) it.ser(this)
}
//@parity:off EXC-014
