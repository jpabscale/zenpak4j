// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/iostore.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions", "LongMethod", "ComplexMethod", "SpellCheckingInspection", "MemberVisibilityCanBePrivate", "ReturnCount", "LoopWithTooManyJumpStatements", "MagicNumber", "ThrowsCount", "TooGenericExceptionCaught")

package com.github.jpabscale.zenpak4j.retoc

import com.github.jpabscale.zenpak4j.console.Console
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashSet
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:21 macro_rules! indent_println!
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:21
fun Console.indent_println(indent: Int, message: String) {
    println(" ".repeat(2 * indent) + message)
}

fun Console.indent_println(indent: Int, format: String, vararg args: Any?) {
    val msg = if (args.isEmpty()) format else format.format(*args)
    println(" ".repeat(2 * indent) + msg)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:27 UniqueIterator
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:27
class UniqueIterator<T>(private val inner: Iterator<T>) : Iterator<T> {
    private val encountered: HashSet<T> = HashSet()
    private var next_item: T? = null
    private var has_next_computed: Boolean = false
    private var has_next_value: Boolean = false

    override fun hasNext(): Boolean {
        if (has_next_computed) return has_next_value
        while (inner.hasNext()) {
            val n = inner.next()
            if (encountered.add(n)) {
                next_item = n
                has_next_value = true
                has_next_computed = true
                return true
            }
        }
        has_next_value = false
        has_next_computed = true
        return false
    }

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        has_next_computed = false
        @Suppress("UNCHECKED_CAST")
        return next_item as T
    }

    companion object {
        fun <T> new(inner: Iterator<T>): UniqueIterator<T> = UniqueIterator(inner)
        fun <T> new(sequence: Sequence<T>): UniqueIterator<T> = UniqueIterator(sequence.iterator())
    }
}

// Helper to expose as Sequence
fun <T> unique_sequence(inner: Sequence<T>): Sequence<T> = Sequence { UniqueIterator(inner.iterator()) }

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:58 open
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:58
fun open(path: Path, config: Config, err: PrintStream = System.err): IoStoreTrait {
    return if (Files.isDirectory(path)) {
        IoStoreBackend.open(path, config, err)
    } else {
        IoStoreContainer.open(path, config, err)
    }
}

fun open(path: String, config: Config, err: PrintStream = System.err): IoStoreTrait = open(Path.of(path), config, err)

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:64 open_with_container_paths
// ---------------------------------------------------------------------------
//@parity:on EXC-009
// Rust: retoc/src/iostore.rs:64
fun open_with_container_paths(paths: List<Path>, config: Config, err: PrintStream = System.err): IoStoreTrait {
    val container_paths = paths.flatMap { collect_container_paths(it) }
    return IoStoreBackend.open_paths(container_paths, config, err)
}
//@parity:off EXC-009

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:69 collect_container_paths
// ---------------------------------------------------------------------------
//@parity:on EXC-009
// Rust: retoc/src/iostore.rs:69
fun collect_container_paths(path: Path): List<Path> {
    return if (Files.isDirectory(path)) {
        val paths = mutableListOf<Path>()
        Files.list(path).use { stream ->
            stream.forEach { entry ->
                if (entry.extension == "utoc") {
                    paths.add(entry)
                }
            }
        }
        paths.sortWith(Comparator { a, b ->
            val a_name = a.fileName?.toString()?.substringBeforeLast(".") ?: a.toString()
            val b_name = b.fileName?.toString()?.substringBeforeLast(".") ?: b.toString()
            // Rust: sort_container_name(b_name).cmp(&sort_container_name(a_name))
            compare_sort_keys(sort_container_name(b_name), sort_container_name(a_name))
        })
        paths
    } else {
        listOf(path)
    }
}
//@parity:off EXC-009

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:93 sort_container_name
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:93
fun sort_container_name(full_name: String): Triple<Boolean, UInt, String> {
    var base_name = full_name
    var chunk_version: UInt = 0u
    if (base_name.endsWith("_P")) {
        base_name = base_name.removeSuffix("_P")
        chunk_version = 1u
        val idx = base_name.lastIndexOf('_')
        if (idx >= 0) {
            val name = base_name.substring(0, idx)
            val version_str = base_name.substring(idx + 1)
            val parsed = version_str.toUIntOrNull()
            if (parsed != null) {
                base_name = name
                chunk_version = parsed + 2u
            }
        }
    }
    // Rust: (full_name == "global", chunk_version, base_name)
    return Triple(full_name == "global", chunk_version, base_name)
}

private fun compare_sort_keys(a: Triple<Boolean, UInt, String>, b: Triple<Boolean, UInt, String>): Int {
    // Rust tuple cmp: bool (false < true), then u32, then str
    if (a.first != b.first) {
        return a.first.compareTo(b.first)
    }
    if (a.second != b.second) {
        return a.second.compareTo(b.second)
    }
    return a.third.compareTo(b.third)
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:112 IoStoreTrait
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:112
interface IoStoreTrait {
    // Kotlin addition mirroring Rust Drop semantics (deterministic close); default no-op
    fun close() {}
    fun container_name(): String
    //@parity:on EXC-009
    fun container_id(): FIoContainerId
    fun mount_point(): String
    //@parity:off EXC-009
    fun container_file_version(): EIoStoreTocVersion?
    fun container_header_version(): EIoContainerHeaderVersion?
    fun print_info(depth: Int, console: Console)

    fun read(chunk_id: FIoChunkId): ByteArray
    fun read_raw(chunk_id_raw: FIoChunkIdRaw): ByteArray
    fun has_chunk_id(chunk_id: FIoChunkId): Boolean
    fun has_chunk_id_raw(chunk_id_raw: FIoChunkIdRaw): Boolean
    fun chunks(): Sequence<ChunkInfo>
    fun chunks_all(): Sequence<ChunkInfo>
    fun packages(): Sequence<PackageInfo>
    fun packages_all(): Sequence<PackageInfo>
    fun child_containers(): Sequence<IoStoreTrait>
    fun chunk_path(chunk_id: FIoChunkId): String?
    fun package_store_entry(package_id: FPackageId): StoreEntry?
    fun lookup_package_redirect(source_package_id: FPackageId): FPackageId?

    fun load_script_objects(): ZenScriptObjects {
        val new_id = FIoChunkId.create(0UL, 0u, EIoChunkType.ScriptObjects)
        val meta_id = FIoChunkId.create(0UL, 0u, EIoChunkType.LoaderInitialLoadMeta)
        val names_id = FIoChunkId.create(0UL, 0u, EIoChunkType.LoaderGlobalNames)

        for (container in child_containers()) {
            val version = container.container_file_version() ?: continue
            if (version > EIoStoreTocVersion.PerfectHash) {
                if (container.has_chunk_id(new_id)) {
                    val data = container.read(new_id)
                    return ZenScriptObjects.deserialize_new(ByteArrayInputStream(data))
                }
            } else if (container.has_chunk_id(meta_id)) {
                val data = container.read(meta_id)
                val names = container.read(names_id)
                return ZenScriptObjects.deserialize_old(ByteArrayInputStream(data), names)
            }
        }

        val is_new = container_file_version()?.let { it > EIoStoreTocVersion.PerfectHash } ?: false
        return if (is_new) {
            val data = read(new_id)
            ZenScriptObjects.deserialize_new(ByteArrayInputStream(data))
        } else {
            val data = read(meta_id)
            val names = read(names_id)
            ZenScriptObjects.deserialize_old(ByteArrayInputStream(data), names)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:175 ChunkInfo
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:175
class ChunkInfo(
    private val id: FIoChunkId,
    private val container: IoStoreContainer,
    private val size: ULong
) {
    fun id(): FIoChunkId = id
    fun container(): IoStoreContainer = container
    fun size(): ULong = size
    fun path(): String? = container.chunk_path(id)
    private fun toc_index(): UInt = container.toc.chunk_id_map[id] ?: error("chunk id not found in toc: $id")
    fun hash(): FIoChunkHash = container.toc.chunk_metas[toc_index().toInt()].chunk_hash
    fun read(): ByteArray = container.read(id)

    override fun equals(other: Any?): Boolean = other is ChunkInfo && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:217 PackageInfo
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:217
class PackageInfo(
    private val id: FPackageId,
    private val container: IoStoreContainer
) {
    fun id(): FPackageId = id
    fun container(): IoStoreContainer = container

    override fun equals(other: Any?): Boolean = other is PackageInfo && id == other.id
    override fun hashCode(): Int = id.hashCode()
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:241 IoStoreBackend
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:241
class IoStoreBackend private constructor(
    private val containers: List<IoStoreTrait>
) : IoStoreTrait, AutoCloseable {
    // Kotlin addition mirroring Rust's Drop (closes all child container pools)
    override fun close() {
        for (c in containers) c.close()
    }


    companion object {
        // Rust: retoc/src/iostore.rs:246 new
        fun new(): IoStoreBackend = IoStoreBackend(emptyList())

        // Rust: retoc/src/iostore.rs:249 open
        fun open(dir: Path, config: Config, err: PrintStream = System.err): IoStoreBackend {
            return open_paths(collect_container_paths(dir), config, err)
        }

        // Rust: retoc/src/iostore.rs:253 open_paths
        fun open_paths(container_paths: List<Path>, config: Config, err: PrintStream = System.err): IoStoreBackend {
            val containers: List<IoStoreTrait> = container_paths.map { path ->
                IoStoreContainer.open(path, config, err) as IoStoreTrait
            }

            //@parity:on EXC-009
            val allow_mixed = config.toc_version_override != null
            var previous_container_version: EIoStoreTocVersion? = null
            var previous_container_name: String = ""
            var previous_header_container_version: EIoContainerHeaderVersion? = null
            var previous_header_container_name: String = ""

            for (container in containers) {
                val this_container_version = container.container_file_version() ?: continue
                val this_container_name = container.container_name()

                if (previous_container_version == null) {
                    previous_container_name = this_container_name
                    previous_container_version = this_container_version
                }
                if (this_container_version != previous_container_version) {
                    if (allow_mixed) {
                        err.println("warning: composite container mixes TOC versions: Container $previous_container_name and $this_container_name have different versions $previous_container_version and $this_container_version")
                    } else {
                        throw IllegalArgumentException("Cannot create composite container for containers of different versions: Container $previous_container_name and $this_container_name have different versions $previous_container_version and $this_container_version. Use --override-toc-version to allow mixing.")
                    }
                }

                val this_container_header_version = container.container_header_version()
                if (this_container_header_version != null) {
                    if (previous_header_container_version == null) {
                        previous_header_container_name = this_container_name
                        previous_header_container_version = this_container_header_version
                    }
                    if (this_container_header_version != previous_header_container_version) {
                        if (allow_mixed) {
                            err.println("warning: composite container mixes header versions: Container $previous_header_container_name and $this_container_name have different versions $previous_header_container_version and $this_container_header_version")
                        } else {
                            throw IllegalArgumentException("Cannot create composite container for containers of different header versions: Container $previous_header_container_name and $this_container_name have different versions $previous_header_container_version and $this_container_header_version. Use --override-toc-version to allow mixing.")
                        }
                    }
                }
            }
            //@parity:off EXC-009

            return IoStoreBackend(containers)
        }
    }

    // IoStoreTrait impl
    override fun container_name(): String = "VIRTUAL"

    override fun container_id(): FIoContainerId = containers.firstOrNull()?.container_id() ?: FIoContainerId(0UL)

    override fun mount_point(): String = containers.firstOrNull()?.mount_point() ?: ""

    override fun container_file_version(): EIoStoreTocVersion? = containers.firstOrNull()?.container_file_version()

    override fun container_header_version(): EIoContainerHeaderVersion? = containers.firstNotNullOfOrNull { it.container_header_version() }

    override fun print_info(depth: Int, console: Console) {
        var d = depth
        console.indent_println(d, container_name())
        d += 1
        if (child_containers().count() != 0) {
            console.indent_println(d, "child containers (${containers.size}):")
            for (container in child_containers()) {
                container.print_info(d + 1, console)
            }
        }
    }

    override fun read(chunk_id: FIoChunkId): ByteArray {
        var cid = chunk_id
        val version = container_file_version()
        if (version != null) {
            cid = cid.with_version(version)
        }
        val container = containers.firstOrNull { it.has_chunk_id(cid) } ?: throw IllegalArgumentException("$cid not found in any containers")
        return container.read(cid)
    }

    override fun read_raw(chunk_id_raw: FIoChunkIdRaw): ByteArray {
        val container = containers.firstOrNull { it.has_chunk_id_raw(chunk_id_raw) } ?: throw IllegalArgumentException("$chunk_id_raw not found in any containers")
        return container.read_raw(chunk_id_raw)
    }

    override fun has_chunk_id(chunk_id: FIoChunkId): Boolean = containers.any { it.has_chunk_id(chunk_id) }

    override fun has_chunk_id_raw(chunk_id_raw: FIoChunkIdRaw): Boolean = containers.any { it.has_chunk_id_raw(chunk_id_raw) }

    override fun chunks(): Sequence<ChunkInfo> = unique_sequence(chunks_all())

    override fun chunks_all(): Sequence<ChunkInfo> = containers.asSequence().flatMap { it.chunks_all() }

    override fun packages(): Sequence<PackageInfo> = containers.asSequence().flatMap { it.packages() }

    override fun packages_all(): Sequence<PackageInfo> = unique_sequence(containers.asSequence().flatMap { it.packages() })

    override fun child_containers(): Sequence<IoStoreTrait> = containers.asSequence()

    override fun chunk_path(chunk_id: FIoChunkId): String? {
        for (c in containers) {
            val p = c.chunk_path(chunk_id)
            if (p != null) return p
        }
        return null
    }

    override fun package_store_entry(package_id: FPackageId): StoreEntry? {
        for (c in containers) {
            val e = c.package_store_entry(package_id)
            if (e != null) return e
        }
        return null
    }

    override fun lookup_package_redirect(source_package_id: FPackageId): FPackageId? {
        for (c in containers) {
            val r = c.lookup_package_redirect(source_package_id)
            if (r != null) return r
        }
        return null
    }
}

// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:396 IoStoreContainer
// ---------------------------------------------------------------------------
// Rust: retoc/src/iostore.rs:396
class IoStoreContainer(
    private val name: String,
    private val path: Path,
    var toc: Toc,
    private var cas: FilePool,
    private var container_header: FIoContainerHeader?
) : IoStoreTrait, AutoCloseable {
    // Kotlin addition mirroring Rust's Drop (deterministic close of pooled handles)
    override fun close() {
        cas.close()
    }


    companion object {
        // Rust: retoc/src/iostore.rs:406 open
        fun open(toc_path: Path, config: Config, err: PrintStream = System.err): IoStoreContainer {
            val path = toc_path
            // Rust: let toc: Toc = BufReader::new(fs::File::open(&path)?).de_ctx(config.clone())?;
            val toc: Toc = Files.newInputStream(path).use { stream ->
                // Use BufferedInputStream for parity with BufReader
                val bis = java.io.BufferedInputStream(stream)
                Toc().de_with_config(bis, config)
            }

            val cas_path = path.resolveSibling(path.fileName.toString().substringBeforeLast(".") + ".ucas")
            val cas: FilePool = FilePool.new(cas_path, Runtime.getRuntime().availableProcessors())

            val container = IoStoreContainer(
                name = path.fileName?.toString()?.substringBeforeLast(".") ?: path.toString(),
                path = path,
                toc = toc,
                cas = cas,
                container_header = null
            )

            // Rust: retoc/src/iostore.rs:420 avoid linear search for header - kept as linear find for statement parity
            // Rust: retoc/src/iostore.rs:421 populate header lazily - kept eager for parity; future may use lazy delegate
            // Rust: let header_chunk = container.chunks().find(|info| info.id().get_chunk_type() == EIoChunkType::ContainerHeader);
            val header_chunk = container.chunks().firstOrNull { it.id().get_chunk_type() == EIoChunkType.ContainerHeader }
            if (header_chunk != null) {
                val chunk_id = header_chunk.id()
                try {
                    val data = container.read(chunk_id)
                    val header = FIoContainerHeader.deserialize(ByteArrayInputStream(data), config.container_header_version_override)
                    container.container_header = header
                } catch (e: Exception) {
                    err.println("Failed to parse ContainerHeader ($chunk_id). Package metadata will be unavailable: ${e.message}")
                }
            }

            return container
        }

        fun open(toc_path: String, config: Config, err: PrintStream = System.err): IoStoreContainer = open(Path.of(toc_path), config, err)
    }

    // Rust: retoc/src/iostore.rs:438 container_path
    fun container_path(): Path = path

    // Rust: retoc/src/iostore.rs:442 name
    fun name(): String = name

    // Rust: retoc/src/iostore.rs:445 container_id (inherent)
    fun container_id_inherent(): FIoContainerId = toc.container_id

    // Rust: retoc/src/iostore.rs:448 mount_point (inherent)
    fun mount_point_inherent(): String = toc.directory_index.mount_point

    // IoStoreTrait impl
    override fun container_name(): String = name

    override fun container_id(): FIoContainerId = toc.container_id

    override fun mount_point(): String = toc.directory_index.mount_point

    override fun container_file_version(): EIoStoreTocVersion? = toc.version

    override fun container_header_version(): EIoContainerHeaderVersion? = container_header?.version

    override fun print_info(depth: Int, console: Console) {
        var d = depth
        console.indent_println(d, container_name())
        d += 1
        console.indent_println(d, "container_id: ${toc.container_id}")
        console.indent_println(d, "container_flags: ${toc.container_flags}")
        console.indent_println(d, "version: ${toc.version}")
        val mount_point = toc.directory_index.mount_point
        if (mount_point.isNotEmpty()) {
            console.indent_println(d, "mount_point: $mount_point")
        }
        console.indent_println(d, "chunks: ${toc.chunks.size}")
        console.indent_println(d, "packages: ${packages().count()}")
        // assumes header has already been parsed
        console.indent_println(d, "container_header_version: ${container_header?.version}")
        console.indent_println(d, "compression_methods: ${toc.compression_methods}")
    }

    override fun read(chunk_id: FIoChunkId): ByteArray {
        val cid = chunk_id.with_version(toc.version)
        val index = toc.chunk_id_map[cid] ?: throw IllegalArgumentException("container $name does not contain $cid")
        val handle = cas.acquire()
        try {
            // Rust: self.toc.read(&mut file_lock.file(), index)
            return toc.read(handle.file(), index)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read chunk $cid", e)
        } finally {
            handle.close()
        }
    }

    override fun read_raw(chunk_id_raw: FIoChunkIdRaw): ByteArray {
        return read(FIoChunkId.from_raw(chunk_id_raw, toc.version))
    }

    override fun has_chunk_id(chunk_id: FIoChunkId): Boolean {
        return toc.chunk_id_map.containsKey(chunk_id.with_version(toc.version))
    }

    override fun has_chunk_id_raw(chunk_id_raw: FIoChunkIdRaw): Boolean {
        return has_chunk_id(FIoChunkId.from_raw(chunk_id_raw, toc.version))
    }

    override fun chunks(): Sequence<ChunkInfo> {
        // chunks should already be unique in individual containers
        return chunks_all()
    }

    override fun chunks_all(): Sequence<ChunkInfo> {
        return toc.chunks.asSequence().zip(toc.chunk_offset_lengths.asSequence()) { id, offset_and_length ->
            ChunkInfo(id, this, offset_and_length.get_length())
        }
    }

    override fun packages(): Sequence<PackageInfo> {
        // packages should already be unique in individual containers
        return packages_all()
    }

    override fun packages_all(): Sequence<PackageInfo> {
        val header = container_header ?: return emptySequence()
        return header.package_ids().asSequence().map { id -> PackageInfo(id, this) }
    }

    override fun child_containers(): Sequence<IoStoreTrait> = emptySequence()

    override fun chunk_path(chunk_id: FIoChunkId): String? = toc.file_name(chunk_id)

    override fun package_store_entry(package_id: FPackageId): StoreEntry? = container_header?.get_store_entry(package_id)

    override fun lookup_package_redirect(source_package_id: FPackageId): FPackageId? = container_header?.lookup_package_redirect(source_package_id)
}
