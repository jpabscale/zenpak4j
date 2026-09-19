// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// ZenPakService — thread-safe in-process API for repak/retoc, for JVM embedders
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
import com.github.jpabscale.zenpak4j.retoc.EIoContainerHeaderVersion
import com.github.jpabscale.zenpak4j.retoc.EIoStoreTocVersion
import com.github.jpabscale.zenpak4j.retoc.EngineVersion
import com.github.jpabscale.zenpak4j.retoc.FGuid
import com.github.jpabscale.zenpak4j.retoc.UEPath
import com.github.jpabscale.zenpak4j.retoc.UEPathBuf
import com.github.jpabscale.zenpak4j.retoc.set_global_game_id
import com.github.jpabscale.zenpak4j.retoc.EIoChunkType
import com.github.jpabscale.zenpak4j.retoc.FIoChunkIdRaw
import com.github.jpabscale.zenpak4j.retoc.open
import com.github.jpabscale.zenpak4j.retoc_actions.ActionGet
import com.github.jpabscale.zenpak4j.retoc_actions.ActionPackRaw
import com.github.jpabscale.zenpak4j.retoc_actions.ActionToLegacy
import com.github.jpabscale.zenpak4j.retoc_actions.ActionToZen
import com.github.jpabscale.zenpak4j.retoc_actions.ActionUnpack as RetocActionUnpack
import com.github.jpabscale.zenpak4j.retoc_actions.ActionUnpackRaw
import com.github.jpabscale.zenpak4j.retoc_actions.action_get
import com.github.jpabscale.zenpak4j.retoc_actions.action_pack_raw
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_legacy
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_legacy_inner
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_zen
import com.github.jpabscale.zenpak4j.retoc_actions.action_to_zen_reader
import com.github.jpabscale.zenpak4j.retoc_actions.action_unpack
import com.github.jpabscale.zenpak4j.retoc_actions.action_unpack_raw
import java.util.HexFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Thread-safe in-process service for repak/retoc, letting JVM embedders work in-process
 * instead of forking retoc/repak binaries. Each method delegates to the shared :actions
 * implementations and is safe to call concurrently from multiple GenerateMod threads.
 *
 * Motivation: avoid per-asset subprocess round-trips (fork + IPC + temp-dir) — same win as
 * uasset4j's in-JVM pipeline, without shelling out to the bundled tools.
 */
object ZenPakService {

    /**
     * Point Oodle at a shared cache directory before the first oodle load: its dir is probed
     * first and used as the download target, so every consumer of this JVM shares one native
     * lib location instead of per-tool copies (EXC-013).
     */
    fun set_oodle_dir(path: Path?) {
        com.github.jpabscale.zenpak4j.oodle_loader.preferred_oodle_dir = path
    }

    // --- repak ---

    /**
     * repak pack: pack directory [inputDir] into [outputPak] with mount_point, version, compression.
     * Delegates to repak_actions.pack (deterministic sorted write, EXC-004). quiet=true suppresses
     * the CLI's completion message.
     */
    @JvmName("repak_pack")
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
     * [override_toc_version]/[override_container_header_version] mirror the CLI's
     * --override-toc-version/--override-container-header-version: the written container uses
     * them instead of [engine_version]'s versions.
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
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        set_global_game_id(game_id)
        // AES keys ride the Config (used when reading the game store), like the CLI's -a.
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
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
     * [override_toc_version]/[override_container_header_version] mirror the CLI's
     * --override-toc-version/--override-container-header-version: any non-null override
     * permits mixed-version composites (e.g. a mod .utoc over a game store of a different
     * TOC version); reads stay per-container.
     */
    fun retoc_to_legacy(
        inputFile: Path,
        outputDir: Path,
        engine_version: EngineVersion,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        retoc_to_legacy(inputFile, outputDir, engine_version, null, game_id,
            override_toc_version, override_container_header_version)
    }

    fun retoc_to_legacy(
        inputFile: Path,
        outputDir: Path,
        engine_version: EngineVersion,
        filter: String?,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        retoc_to_legacy(listOf(inputFile), outputDir, engine_version, filter, game_id,
            override_toc_version = override_toc_version,
            override_container_header_version = override_container_header_version)
    }

    fun retoc_to_legacy(
        inputFiles: List<Path>,
        outputDir: Path,
        engine_version: EngineVersion,
        filter: String?,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
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
     * [override_toc_version]/[override_container_header_version] mirror the CLI's
     * --override-toc-version/--override-container-header-version: any non-null override
     * permits mixed-version composites (e.g. a mod .utoc over a game store of a different
     * TOC version); reads stay per-container.
     */
    fun retoc_unpack(
        utocFile: Path,
        outputDir: Path,
        filter: String? = null,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        retoc_unpack(listOf(utocFile), outputDir, filter, aes_key, game_id,
            override_toc_version, override_container_header_version)
    }

    fun retoc_unpack(
        inputFiles: List<Path>,
        outputDir: Path,
        filter: String? = null,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
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
     * retoc unpack-raw: extract every chunk of [input] (a .utoc) verbatim to
     * [outputDir]/chunks/<chunk-id-hex> plus [outputDir]/manifest.json — the exact input
     * shape [retoc_pack_raw] consumes. Chunk bytes are written as stored (no legacy conversion).
     */
    fun retoc_unpack_raw(
        input: Path,
        outputDir: Path,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
        }
        action_unpack_raw(ActionUnpackRaw(utoc = input, output = outputDir), config)
    }

    /**
     * Zen-JSON dump of a cooked package chunk: the container AST (summary, name map, import map,
     * export map, bundle/dependency tables, arcs, cell maps) plus a payload size/sha256 inventory
     * (bytes only when [includePayloads]). Deterministic; no legacy UAssetAPI data is embedded.
     */
    fun zen_package_to_json(chunk: Path, includePayloads: Boolean = false): String =
        com.github.jpabscale.zenpak4j.retoc.zen_package_to_json(
            java.nio.file.Files.readAllBytes(chunk), includePayloads)

    /**
     * Zen-JSON tables dump of the package at [pathSuffix] inside [container] (a .utoc or a dir):
     * locates the package chunk, extracts it, resolves the store entry from the container header
     * (Initial packages require it) and dumps. Returns the JSON text.
     */
    fun retoc_package_tables(
        container: Path,
        pathSuffix: String,
        includePayloads: Boolean = false,
        aes_key: String? = null,
        game_id: String? = null,
    ): String {
        val info = retoc_locate_package_chunk(container, pathSuffix, aes_key, game_id)
            ?: throw IllegalStateException("no package chunk matches '$pathSuffix' in $container")
        val tmp = java.nio.file.Files.createTempDirectory("zen-tables")
        try {
            val chunk = tmp.resolve("chunk.bin")
            retoc_get(container, info.chunkIdHex, chunk, aes_key, game_id)
            val headerDir = tmp.resolve("header")
            java.nio.file.Files.createDirectories(headerDir)
            retoc_extract_container_header(container, headerDir, aes_key, game_id)
            val headerFile = java.nio.file.Files.list(headerDir).use { s -> s.findFirst().orElse(null) }
                ?: throw IllegalStateException("no container header extracted from $container")
            val entry = com.github.jpabscale.zenpak4j.retoc.store_entry_of_container_header(
                java.nio.file.Files.readAllBytes(headerFile),
                com.github.jpabscale.zenpak4j.retoc.package_id_of_chunk_id(info.chunkIdHex))
            return com.github.jpabscale.zenpak4j.retoc.zen_package_to_json(
                java.nio.file.Files.readAllBytes(chunk), includePayloads,
                com.github.jpabscale.zenpak4j.retoc.EIoContainerHeaderVersion.Initial,
                com.github.jpabscale.zenpak4j.retoc.EIoStoreTocVersion.PartitionSize, entry)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Apply a Zen table patch (see `zen_package_apply_patch`) to the package at [pathSuffix] in
     * [container] and return the rewritten chunk bytes; refuses a patch whose result does not pass
     * `zen_package_validate`. Payload bytes are carried through untouched.
     */
    fun retoc_package_apply_tables(
        container: Path,
        pathSuffix: String,
        patchText: String,
        aes_key: String? = null,
        game_id: String? = null,
    ): ByteArray {
        val info = retoc_locate_package_chunk(container, pathSuffix, aes_key, game_id)
            ?: throw IllegalStateException("no package chunk matches '$pathSuffix' in $container")
        val tmp = java.nio.file.Files.createTempDirectory("zen-tables-apply")
        try {
            val chunk = tmp.resolve("chunk.bin")
            retoc_get(container, info.chunkIdHex, chunk, aes_key, game_id)
            val headerDir = tmp.resolve("header")
            java.nio.file.Files.createDirectories(headerDir)
            retoc_extract_container_header(container, headerDir, aes_key, game_id)
            val headerFile = java.nio.file.Files.list(headerDir).use { s -> s.findFirst().orElse(null) }
                ?: throw IllegalStateException("no container header extracted from $container")
            val entry = com.github.jpabscale.zenpak4j.retoc.store_entry_of_container_header(
                java.nio.file.Files.readAllBytes(headerFile),
                com.github.jpabscale.zenpak4j.retoc.package_id_of_chunk_id(info.chunkIdHex))
            val node = com.github.jpabscale.zenpak4j.retoc.zen_package_to_json_node(
                java.nio.file.Files.readAllBytes(chunk), true,
                com.github.jpabscale.zenpak4j.retoc.EIoContainerHeaderVersion.Initial,
                com.github.jpabscale.zenpak4j.retoc.EIoStoreTocVersion.PartitionSize, entry)
            val applied = com.github.jpabscale.zenpak4j.retoc.zen_package_apply_patch(
                node, com.fasterxml.jackson.databind.ObjectMapper().readTree(patchText))
            val bytes = com.github.jpabscale.zenpak4j.retoc.zen_package_from_json_node(node, entry)
            val findings = com.github.jpabscale.zenpak4j.retoc.zen_package_validate(bytes)
            if (findings.isNotEmpty()) {
                throw IllegalStateException("patched chunk fails validation: $findings")
            }
            println("Applied $applied Zen table edit(s)")
            return bytes
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    /**
     * Payload-preserving repack of one cooked package chunk: swap the export payloads that differ
     * between [originalChunk] (the game's own cooked chunk) and [donorChunk] (a to-zen conversion of
     * the patched asset) - or exactly [changedExports] when given - into the cooker's chunk, and
     * write [outputChunk] plus a [outputHeaderChunk] copy of the container header carrying the
     * updated store entry (the packer copies entries verbatim). Returns the swapped export indices.
     */
    fun retoc_graft_package_chunk(
        originalChunk: Path,
        donorChunk: Path,
        headerChunk: Path,
        packageId: ULong,
        outputChunk: Path,
        outputHeaderChunk: Path,
        changedExports: Set<Int>? = null,
    ): Set<Int> {
        val original = java.nio.file.Files.readAllBytes(originalChunk)
        val donor = java.nio.file.Files.readAllBytes(donorChunk)
        val headerBytes = java.nio.file.Files.readAllBytes(headerChunk)
        val originalPayloads = com.github.jpabscale.zenpak4j.retoc.extract_zen_payloads(original)
        val donorPayloads = com.github.jpabscale.zenpak4j.retoc.extract_zen_payloads(donor)
        require(originalPayloads.size == donorPayloads.size) {
            "donor export count ${donorPayloads.size} != original ${originalPayloads.size}"
        }
        val changed = changedExports ?: originalPayloads.indices.filterNot {
            originalPayloads[it].contentEquals(donorPayloads[it])
        }.toSet()
        val storeEntry = com.github.jpabscale.zenpak4j.retoc.FIoContainerHeader
            .deserialize(java.io.ByteArrayInputStream(headerBytes), null)
            .get_store_entry(com.github.jpabscale.zenpak4j.retoc.FPackageId(packageId))
            ?: throw IllegalStateException("no store entry for package $packageId in $headerChunk")
        val grafted = com.github.jpabscale.zenpak4j.retoc.graft_zen_chunk(
            original, changed.associateWith { donorPayloads[it] }, storeEntry)
        outputChunk.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.write(outputChunk, grafted.chunk)
        val patchedHeader = com.github.jpabscale.zenpak4j.retoc.patch_container_header_store_entry(
            headerBytes, com.github.jpabscale.zenpak4j.retoc.FPackageId(packageId), grafted.store_entry)
        outputHeaderChunk.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.write(outputHeaderChunk, patchedHeader)
        return changed
    }

    /**
     * retoc pack-raw: pack [inputDir] (chunks/ + manifest.json) into [outputUtoc]. ExportBundleData
     * chunks get their FPackageStoreEntry from a ContainerHeader chunk in the input when present,
     * so an extracted-then-patched game chunk keeps the cooker's store entry.
     */
    fun retoc_pack_raw(inputDir: Path, outputUtoc: Path, game_id: String? = null) {
        set_global_game_id(game_id)
        action_pack_raw(ActionPackRaw(input = inputDir, utoc = outputUtoc), Config())
    }

    /** retoc get: write the raw chunk [chunkIdHex] of [input] to [output] (stdout when null or "-"). */
    fun retoc_get(
        input: Path,
        chunkIdHex: String,
        output: Path? = null,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
        }
        action_get(
            ActionGet(input = input, chunk_id = FIoChunkIdRaw.from_string(chunkIdHex), output = output),
            config
        )
    }

    /**
     * Locate the package chunk whose stored path ends with [pathSuffix] inside [input] and return
     * its id/size plus the container's TOC/mount/header info — the fields a pack-raw manifest needs.
     * Null when the container does not hold that path.
     */
    fun retoc_locate_package_chunk(
        input: Path,
        pathSuffix: String,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ): RetocPackageChunkInfo? {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
        }
        val iostore = open(input, config)
        val chunk = iostore.chunks().firstOrNull { it.path()?.endsWith(pathSuffix) == true }
            ?: return null
        return RetocPackageChunkInfo(
            chunkIdHex = HexFormat.of().formatHex(chunk.id().get_raw().id),
            chunkSize = chunk.size().toLong(),
            tocVersion = iostore.container_file_version() ?: EIoStoreTocVersion.Initial,
            mountPoint = iostore.mount_point(),
            containerHeaderVersion = iostore.container_header_version()
        )
    }

    /**
     * Write [input]'s ContainerHeader chunk verbatim to [outputDir]/<chunk-id-hex> and return the
     * chunk id hex. Pairs with [retoc_pack_raw]: the header carries every FPackageStoreEntry of the
     * container, so a patched chunk extracted from it keeps the original store entry (imported
     * packages, export/bundle counts) instead of a re-derived one.
     */
    fun retoc_extract_container_header(
        input: Path,
        outputDir: Path,
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ): String {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
        }
        val iostore = open(input, config)
        val header = iostore.chunks().firstOrNull { it.id().get_chunk_type() == EIoChunkType.ContainerHeader }
            ?: throw IllegalStateException("no ContainerHeader chunk in $input")
        val chunkIdHex = HexFormat.of().formatHex(header.id().get_raw().id)
        Files.createDirectories(outputDir)
        Files.write(
            outputDir.resolve(chunkIdHex), header.read(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
        )
        return chunkIdHex
    }

    /**
     * retoc to-legacy into memory: run the exact action_to_legacy_inner pipeline over
     * [inputFiles], delivering every produced file through [on_file] (path, allow_compress,
     * bytes) instead of writing to disk. Shaders stay disabled, like [retoc_to_legacy].
     * Thread safety: [on_file] is invoked from the actions' worker pool and must be
     * thread-safe; paths are container-relative ("SB/Content/..."), slash-normalized.
     * [override_toc_version]/[override_container_header_version] mirror the CLI's
     * --override-toc-version/--override-container-header-version: any non-null override
     * permits mixed-version composites (e.g. a mod .utoc over a game store of a different
     * TOC version); reads stay per-container.
     */
    fun retoc_to_legacy_extract(
        inputFiles: List<Path>,
        engine_version: EngineVersion,
        filters: List<String> = emptyList(),
        aes_key: String? = null,
        game_id: String? = null,
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
        on_file: (path: String, allow_compress: Boolean, data: ByteArray) -> Unit,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
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
     * retoc to-zen from a caller-supplied file source (embedder in-memory seam): the exact
     * action_to_zen pipeline, but loose-file reads go through the [list_files]/[read]/
     * [read_opt] functions instead of FSFileReader(args.input). Paths are container-relative
     * ("SB/Content/..."), slash-normalized; [read_opt] returns null for absent files.
     * [override_toc_version]/[override_container_header_version] mirror the CLI's
     * --override-toc-version/--override-container-header-version: the written container uses
     * them instead of [engine_version]'s versions.
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
        override_toc_version: EIoStoreTocVersion? = null,
        override_container_header_version: EIoContainerHeaderVersion? = null,
    ) {
        set_global_game_id(game_id)
        val config = Config().apply {
            if (!aes_key.isNullOrBlank()) {
                aes_keys[FGuid()] = RetocAesKey.from_str(aes_key)
            }
            override_toc_version?.let { toc_version_override = it }
            override_container_header_version?.let { container_header_version_override = it }
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

    // Convenience for embedders: probe whether in-process Oodle can load.
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

/**
 * A package chunk located inside a game container ([retoc_locate_package_chunk]) — the id/size
 * plus the container's TOC version, mount point and container-header version, i.e. exactly the
 * fields a pack-raw manifest needs to re-pack the chunk under its original id.
 */
data class RetocPackageChunkInfo(
    val chunkIdHex: String,
    val chunkSize: Long,
    val tocVersion: EIoStoreTocVersion,
    val mountPoint: String,
    val containerHeaderVersion: EIoContainerHeaderVersion?
)
