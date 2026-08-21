// ZenPakService — thread-safe in-process API for repak/retoc, for automod's GenerateMod
// Replaces subprocess fork (retocExe/repakExe via os.proc) with direct library calls.
// All methods delegate to the shared action layer in :actions (repak_actions/retoc_actions,
// EXC-014) — the exact same code the CLIs run, so behavior cannot drift from the CLI.
// Thread safety: fresh builders/readers/writers per call; the game-id module globals are
// ThreadLocal-backed (EXC-005/EXC-007) and set per call on the calling thread, and all
// game-id reads happen on the calling thread (never inside the actions' worker pools).
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused")

package com.github.jpabscale.zenpak4j

import com.github.jpabscale.zenpak4j.oodle_loader.oodle
import com.github.jpabscale.zenpak4j.repak.Compression
import com.github.jpabscale.zenpak4j.repak.Version
import com.github.jpabscale.zenpak4j.repak.global_game_id
import com.github.jpabscale.zenpak4j.repak_actions.AesKey
import com.github.jpabscale.zenpak4j.repak_actions.ActionPack
import com.github.jpabscale.zenpak4j.repak_actions.ActionUnpack
import com.github.jpabscale.zenpak4j.repak_actions.pack
import com.github.jpabscale.zenpak4j.repak_actions.unpack
import com.github.jpabscale.zenpak4j.retoc.AesKey as RetocAesKey
import com.github.jpabscale.zenpak4j.retoc.Config
import com.github.jpabscale.zenpak4j.retoc.EngineVersion
import com.github.jpabscale.zenpak4j.retoc.FGuid
import com.github.jpabscale.zenpak4j.retoc.UEPath
import com.github.jpabscale.zenpak4j.retoc.UEPathBuf
import com.github.jpabscale.zenpak4j.retoc.set_global_game_id
import com.github.jpabscale.zenpak4j.retoc_actions.ActionToLegacy
import com.github.jpabscale.zenpak4j.retoc_actions.ActionToZen
import com.github.jpabscale.zenpak4j.retoc_actions.ActionUnpack as RetocActionUnpack
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_legacy
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_legacy_inner
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_zen
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_zen_reader
import com.github.jpabscale.zenpak4j.retoc_actions.action_unpack
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Thread-safe in-process service for repak/retoc, to be used by automod's GenerateMod
 * instead of forking retoc/repak binaries. Each method delegates to the shared :actions
 * implementations and is safe to call concurrently from multiple GenerateMod threads.
 *
 * Motivation: avoid per-asset subprocess round-trips (fork + IPC + temp-dir) — same win as
 * uasset4j's in-JVM pipeline (52s vs 2:38 on StellarBlade .demo.sb).
 */
object ZenPakService {

    // --- repak ---

    /**
     * repak pack: pack directory [inputDir] into [outputPak] with mount_point, version, compression.
     * Delegates to repak_actions.pack (deterministic sorted write, EXC-004). quiet=true suppresses
     * the CLI's completion message.
     */
    fun repak_pack(
        inputDir: Path,
        outputPak: Path,
        mount_point: String = "../../../",
        version: Version = Version.V8B,
        compression: Compression? = null,
        path_hash_seed: ULong = 0u,
        game_id: String? = null,
        verbose: Boolean = false,
    ) {
        // game-id is consumed via the ThreadLocal global on the calling thread (EXC-005);
        // always assign so a null clears any stale value from a previous call on this thread.
        global_game_id = game_id
        pack(
            ActionPack(
                input = inputDir.toString(),
                output = outputPak.toString(),
                mount_point = mount_point,
                version = version,
                compression = compression,
                path_hash_seed = path_hash_seed,
                verbose = verbose,
                quiet = true
            )
        )
    }

    /**
     * repak unpack: unpack [pakFile] into [outputDir] stripping prefix.
     * Delegates to repak_actions.unpack (multi-input flow, include globs validated upstream,
     * WriteOutsideOutput guard, parallel unpack with per-task channels).
     * [aes_key] accepts hex (optionally 0x-prefixed) or base64, like the CLI's -a.
     */
    fun repak_unpack(
        pakFile: Path,
        outputDir: Path,
        strip_prefix: String = "../../../",
        aes_key: String? = null,
        game_id: String? = null,
        include: List<String> = emptyList(),
        verbose: Boolean = false,
    ) {
        repak_unpack(listOf(pakFile), outputDir, strip_prefix, aes_key, game_id, include, verbose)
    }

    /** Multi-pak variant of [repak_unpack]: first-match priority across [pakFiles]. */
    fun repak_unpack(
        pakFiles: List<Path>,
        outputDir: Path,
        strip_prefix: String = "../../../",
        aes_key: String? = null,
        game_id: String? = null,
        include: List<String> = emptyList(),
        verbose: Boolean = false,
    ) {
        global_game_id = game_id
        unpack(
            aes_key?.let { AesKey.from_string(it) },
            ActionUnpack(
                input = pakFiles.map { it.toString() },
                output = outputDir.toString(),
                strip_prefix = strip_prefix,
                verbose = verbose,
                quiet = true,
                force = false,
                include = include
            )
        )
    }

    /** repak list: entry paths under [strip_prefix], slash-normalized. */
    fun repak_list(
        pakFile: Path,
        strip_prefix: String = "../../../",
        aes_key: String? = null,
        game_id: String? = null,
    ): List<String> {
        global_game_id = game_id
        val keySpec = aes_key?.let { AesKey.from_string(it) }
        var builder = com.github.jpabscale.zenpak4j.repak.PakBuilder.new()
        if (keySpec != null) builder = builder.key(keySpec.key)
        FileChannel.open(pakFile, StandardOpenOption.READ).use { channel ->
            val pak = builder.reader(channel)
            val mount_point = java.nio.file.Paths.get(pak.mount_point())
            val prefix = java.nio.file.Paths.get(strip_prefix)
            return pak.files().map { f ->
                val full = mount_point.resolve(f)
                val stripped = strip_prefix(full, prefix)
                stripped.toString().replace('\\', '/')
            }
        }
    }

    /** repak info: same fields as the CLI info command, as a map. */
    fun repak_info(pakFile: Path, aes_key: String? = null, game_id: String? = null): Map<String, String> {
        global_game_id = game_id
        val keySpec = aes_key?.let { AesKey.from_string(it) }
        var builder = com.github.jpabscale.zenpak4j.repak.PakBuilder.new()
        if (keySpec != null) builder = builder.key(keySpec.key)
        FileChannel.open(pakFile, StandardOpenOption.READ).use { channel ->
            val pak = builder.reader(channel)
            return mapOf(
                "mount_point" to pak.mount_point(),
                "version" to pak.version().toString(),
                "version_major" to pak.version().version_major().toString(),
                "encrypted_index" to pak.encrypted_index().toString(),
                "encryption_guid" to (pak.encryption_guid()?.toString(16)?.padStart(32, '0')?.let { "Some($it)" } ?: "None"),
                "compression" to (pak.used_compression().joinToString(" ,").ifEmpty { "None" }),
                "path_hash_seed" to (pak.path_hash_seed()?.toString(16)?.padStart(8, '0')?.let { "Some($it)" } ?: "None"),
                "file_entries" to pak.files().size.toString()
            )
        }
    }

    // --- retoc ---

    /**
     * retoc to-zen: convert loose uasset/uexp (+ubulk/uptnl/m.ubulk/ushaderbytecode) under
     * [inputDir] into a .utoc/.ucas container at [outputUtoc]. Delegates to
     * retoc_actions.action_to_zen — identical pipeline to the CLI incl. shader asset-info
     * harvesting (--game-store), passthrough chunks and the companion .pak index.
     */
    fun retoc_to_zen(
        inputDir: Path,
        outputUtoc: Path,
        engine_version: EngineVersion,
        game_store: List<Path>? = null,
        filter: String? = null,
        aes_key: String? = null,
        game_id: String? = null,
        verbose: Boolean = false,
    ) {
        set_global_game_id(game_id)
        // AES keys ride the Config (used when reading the game store), like the CLI's -a.
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
        }
        action_to_zen(
            ActionToZen(
                input = inputDir,
                output = outputUtoc,
                filter = filter?.let { listOf(it) } ?: emptyList(),
                game_store = game_store?.joinToString(File.pathSeparator),
                version = engine_version,
                script_cell = emptyList(),
                verbose = verbose,
                debug = false,
                no_parallel = false
            ),
            config
        )
    }

    /**
     * retoc to-legacy: extract packages from the container at [inputFile] into loose
     * uasset/uexp files under [outputDir]. Shaders stay disabled for GenerateMod
     * (no_shaders=true); script objects follow the CLI default.
     */
    fun retoc_to_legacy(
        inputFile: Path,
        outputDir: Path,
        engine_version: EngineVersion,
        game_id: String? = null,
    ) {
        retoc_to_legacy(inputFile, outputDir, engine_version, null, game_id)
    }

    fun retoc_to_legacy(
        inputFile: Path,
        outputDir: Path,
        engine_version: EngineVersion,
        filter: String?,
        game_id: String? = null,
    ) {
        retoc_to_legacy(listOf(inputFile), outputDir, engine_version, filter, game_id)
    }

    fun retoc_to_legacy(
        inputFiles: List<Path>,
        outputDir: Path,
        engine_version: EngineVersion,
        filter: String?,
        aes_key: String? = null,
        game_id: String? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
        }
        action_to_legacy(
            ActionToLegacy(
                input = inputFiles.joinToString(File.pathSeparator),
                output = outputDir,
                filter = filter?.let { listOf(it) } ?: emptyList(),
                no_assets = false,
                no_shaders = true,
                no_script_objects = false,
                no_compres_shaders = false,
                dry_run = false,
                version = engine_version,
                script_cell = emptyList(),
                verbose = false,
                debug = false,
                no_parallel = false
            ),
            config
        )
    }

    /**
     * retoc unpack: dump all chunks (path-addressable ones) of the container(s) into
     * [outputDir]. Delegates to retoc_actions.action_unpack; [aes_key] is inserted under
     * FGuid() like the CLI's -a.
     */
    fun retoc_unpack(
        utocFile: Path,
        outputDir: Path,
        filter: String? = null,
        aes_key: String? = null,
        game_id: String? = null,
    ) {
        retoc_unpack(listOf(utocFile), outputDir, filter, aes_key, game_id)
    }

    fun retoc_unpack(
        inputFiles: List<Path>,
        outputDir: Path,
        filter: String? = null,
        aes_key: String? = null,
        game_id: String? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
        }
        action_unpack(
            RetocActionUnpack(
                input = inputFiles.joinToString(File.pathSeparator),
                output = outputDir,
                filter = filter?.let { listOf(it) } ?: emptyList(),
                no_parallel = false,
                verbose = false
            ),
            config
        )
    }

    /**
     * retoc to-legacy into memory: run the exact action_to_legacy_inner pipeline over
     * [inputFiles], delivering every produced file through [on_file] (path, allow_compress,
     * bytes) instead of writing to disk. Shaders stay disabled, like [retoc_to_legacy].
     * Thread safety: [on_file] is invoked from the actions' worker pool and must be
     * thread-safe; paths are container-relative ("SB/Content/..."), slash-normalized.
     */
    fun retoc_to_legacy_extract(
        inputFiles: List<Path>,
        engine_version: EngineVersion,
        filters: List<String> = emptyList(),
        aes_key: String? = null,
        game_id: String? = null,
        on_file: (path: String, allow_compress: Boolean, data: ByteArray) -> Unit,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
        }
        val writer = object : com.github.jpabscale.zenpak4j.retoc.FileWriterTrait {
            override fun write_file(path: String, allow_compress: Boolean, data: ByteArray) =
                on_file(path, allow_compress, data)
        }
        action_to_legacy_inner(
            ActionToLegacy(
                input = inputFiles.joinToString(File.pathSeparator),
                output = Files.createTempDirectory("zenpak-legacy-mem"),
                filter = filters,
                no_assets = false,
                no_shaders = true,
                no_script_objects = false,
                no_compres_shaders = false,
                dry_run = false,
                version = engine_version,
                script_cell = emptyList(),
                verbose = false,
                debug = false,
                no_parallel = false
            ),
            config,
            writer,
            com.github.jpabscale.zenpak4j.retoc.Log.new_stdout(false, false)
        )
    }

    /**
     * retoc to-zen from a caller-supplied file source (automod in-memory seam): the exact
     * action_to_zen pipeline, but loose-file reads go through the [list_files]/[read]/
     * [read_opt] functions instead of FSFileReader(args.input). Paths are container-relative
     * ("SB/Content/..."), slash-normalized; [read_opt] returns null for absent files.
     */
    fun retoc_to_zen_from(
        list_files: () -> List<String>,
        read: (String) -> ByteArray,
        read_opt: (String) -> ByteArray?,
        outputUtoc: Path,
        engine_version: EngineVersion,
        filters: List<String> = emptyList(),
        game_store: List<Path>? = null,
        aes_key: String? = null,
        game_id: String? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
        }
        val input = object : com.github.jpabscale.zenpak4j.retoc.FileReaderTrait {
            override fun read(path: UEPath): ByteArray = read(path)
            override fun read_opt(path: UEPath): ByteArray? = read_opt(path)
            override fun list_files(): List<UEPathBuf> = list_files()
        }
        action_to_zen_reader(
            ActionToZen(
                input = outputUtoc,
                output = outputUtoc,
                filter = filters,
                game_store = game_store?.joinToString(File.pathSeparator),
                version = engine_version,
                script_cell = emptyList(),
                verbose = false,
                debug = false,
                no_parallel = false
            ),
            config,
            input
        )
    }

    // Convenience for automod's GenerateMod: probe whether in-process Oodle can load.
    // Uncompressed/zlib workflows work even when this returns false.
    fun is_available(): Boolean = try {
        oodle()
        true
    } catch (_: Exception) {
        false
    }

    private fun strip_prefix(path: java.nio.file.Path, prefix: java.nio.file.Path): java.nio.file.Path {
        val pStr = prefix.toString()
        if (pStr.isEmpty()) return path
        val normPath = path.normalize()
        val normPrefix = prefix.normalize()
        if (normPrefix.toString().isEmpty()) return normPath
        if (!normPath.startsWith(normPrefix)) throw com.github.jpabscale.zenpak4j.repak.RepakError.PrefixMismatch(prefix = prefix.toString(), path = path.toString())
        return normPrefix.relativize(normPath)
    }
}
