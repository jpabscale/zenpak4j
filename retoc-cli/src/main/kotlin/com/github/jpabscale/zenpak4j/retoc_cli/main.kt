// Rust: retoc_cli/src/main.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection", "MagicNumber", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName")

package com.github.jpabscale.zenpak4j.retoc_cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.jpabscale.zenpak4j.retoc.*
import com.github.jpabscale.zenpak4j.retoc_actions.*
import java.nio.file.Path

// The action implementations live in :actions (retoc_actions) — see EXC-014.
// This file keeps only the Clikt surface (options/commands), Config wiring and version parsing.

// ---------------------------------------------------------------------------
// Clikt-based CLI — mirrors clap::Parser derive with 1:1 command names
// Keep snake_case for parity; stubs for bodies to compile
// ---------------------------------------------------------------------------

// Rust: retoc_cli/src/main.rs:294 Args as Clikt root
class RetocCli : CliktCommand(name = "retoc") {
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-012
    val game_id: String? by option("-g", "--game-id")
    //@parity:off EXC-012
    val override_container_header_version: String? by option("--override-container-header-version")
    val override_toc_version: String? by option("--override-toc-version")

    override fun run() {
        //@parity:on EXC-012
        if (game_id != null) {
            try { set_global_game_id(game_id) } catch (_: Exception) {}
        }
        //@parity:off EXC-012
        // config is built per-subcommand from options; mirrors Rust main's Arc<Config> creation
    }
}

// Rust: retoc_cli/src/main.rs:255 Action::Manifest
class ManifestCommand : CliktCommand(name = "manifest") {
    val utoc_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionManifest(utoc = Path.of(utoc_str))
        action_manifest(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:257 Action::Info
class InfoCommand : CliktCommand(name = "info") {
    val path_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionInfo(path = Path.of(path_str))
        action_info(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:259 Action::List
class ListCommand : CliktCommand(name = "list") {
    val utoc_str: String by argument()
    val all: Boolean by option("--all").flag()
    val hash: Boolean by option("--hash").flag()
    val `package`: Boolean by option("--package").flag()
    val size: Boolean by option("--size").flag()
    val path: Boolean by option("--path").flag()
    val store: Boolean by option("--store").flag()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionList(utoc = Path.of(utoc_str), all = all, hash = hash, `package` = `package`, size = size, path = path, store = store)
        action_list(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:261 Action::Verify
class VerifyCommand : CliktCommand(name = "verify") {
    val utoc_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionVerify(utoc = Path.of(utoc_str))
        action_verify(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:263 Action::Unpack
class UnpackCommand : CliktCommand(name = "unpack") {
    val input: String by argument()
    val output_str: String by argument()
    val filter: List<String> by option("-f", "--filter").multiple()
    val no_parallel: Boolean by option("--no-parallel").flag()
    val verbose: Boolean by option("-v", "--verbose").flag()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionUnpack(input = input, output = Path.of(output_str), filter = filter, no_parallel = no_parallel, verbose = verbose)
        action_unpack(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:267 Action::UnpackRaw
class UnpackRawCommand : CliktCommand(name = "unpack-raw") {
    val utoc_str: String by argument()
    val output_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionUnpackRaw(utoc = Path.of(utoc_str), output = Path.of(output_str))
        action_unpack_raw(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:269 Action::PackRaw
class PackRawCommand : CliktCommand(name = "pack-raw") {
    val input_str: String by argument()
    val utoc_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionPackRaw(input = Path.of(input_str), utoc = Path.of(utoc_str))
        action_pack_raw(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:273 Action::ToLegacy
class ToLegacyCommand : CliktCommand(name = "to-legacy") {
    val input: String by argument()
    val output_str: String by argument()
    val filter: List<String> by option("-f", "--filter").multiple()
    val no_assets: Boolean by option("--no-assets").flag()
    val no_shaders: Boolean by option("--no-shaders").flag()
    val no_script_objects: Boolean by option("--no-script-objects").flag()
    val no_compres_shaders: Boolean by option("--no-compres-shaders").flag()
    val dry_run: Boolean by option("-n", "--dry-run").flag()
    val version: String? by option("--version")
    val script_cell: List<String> by option("--script-cell").multiple()
    val verbose: Boolean by option("-v", "--verbose").flag()
    val debug: Boolean by option("--debug").flag()
    val no_parallel: Boolean by option("--no-parallel").flag()
    override fun run() {
        val cfg = build_config_from_parent()
        val engine_version = version?.let { parse_engine_version(it) }
        val cells = script_cell.mapNotNull { try { VerseScriptCell.from_string(it) } catch (_: Exception) { null } }
        val args = ActionToLegacy(
            input = input,
            output = Path.of(output_str),
            filter = filter,
            no_assets = no_assets,
            no_shaders = no_shaders,
            no_script_objects = no_script_objects,
            no_compres_shaders = no_compres_shaders,
            dry_run = dry_run,
            version = engine_version,
            script_cell = cells,
            verbose = verbose,
            debug = debug,
            no_parallel = no_parallel
        )
        action_to_legacy(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:275 Action::ToZen
class ToZenCommand : CliktCommand(name = "to-zen") {
    val input_str: String by argument()
    val output_str: String by argument()
    val filter: List<String> by option("-f", "--filter").multiple()
    val game_store: String? by option("--game-store")
    val version_str: String? by option("--version")
    val script_cell: List<String> by option("--script-cell").multiple()
    val verbose: Boolean by option("-v", "--verbose").flag()
    val debug: Boolean by option("--debug").flag()
    val no_parallel: Boolean by option("--no-parallel").flag()
    override fun run() {
        val cfg = build_config_from_parent()
        val parsed_version = parse_engine_version(version_str ?: "UE5_0")
        val cells = script_cell.mapNotNull { try { VerseScriptCell.from_string(it) } catch (_: Exception) { null } }
        val args = ActionToZen(
            input = Path.of(input_str),
            output = Path.of(output_str),
            filter = filter,
            game_store = game_store,
            version = parsed_version,
            script_cell = cells,
            verbose = verbose,
            debug = debug,
            no_parallel = no_parallel
        )
        action_to_zen(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:278 Action::Get
class GetCommand : CliktCommand(name = "get") {
    val input_str: String by argument()
    val chunk_id_str: String by argument()
    val output_str: String? by argument().optional()
    override fun run() {
        val cfg = build_config_from_parent()
        val raw = try { FIoChunkIdRaw.from_string(chunk_id_str) } catch (_: Exception) { FIoChunkIdRaw() }
        val args = ActionGet(input = Path.of(input_str), chunk_id = raw, output = output_str?.let { Path.of(it) })
        action_get(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:281 Action::DumpTest
class DumpTestCommand : CliktCommand(name = "dump-test") {
    val input_str: String by argument()
    val output_dir_str: String by argument()
    val package_id_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val pid = try { FPackageId.from_string(package_id_str) } catch (_: Exception) { FPackageId(0uL) }
        val args = ActionDumpTest(input = Path.of(input_str), output_dir = Path.of(output_dir_str), package_id = pid)
        action_dump_test(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:285 Action::GenScriptObjects
class GenScriptObjectsCommand : CliktCommand(name = "gen-script-objects") {
    val input_str: String by argument()
    val output_str: String by argument()
    val version_str: String? by option("--version")
    override fun run() {
        val cfg = build_config_from_parent()
        val parsed_version = parse_engine_version(version_str ?: "UE5_0")
        val args = ActionGenScriptObjects(input = Path.of(input_str), output = Path.of(output_str), version = parsed_version)
        action_gen_script_objects(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:288 Action::PrintScriptObjects
class PrintScriptObjectsCommand : CliktCommand(name = "print-script-objects") {
    val input_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionPrintScriptObjects(input = Path.of(input_str))
        action_print_script_objects(args, cfg)
    }
}

// Rust: retoc_cli/src/main.rs:290 Action::AssetRegistry
class AssetRegistryCommand : CliktCommand(name = "asset-registry") {
    val input_str: String by argument()
    override fun run() {
        val cfg = build_config_from_parent()
        val args = ActionAssetRegistry(input = Path.of(input_str))
        action_asset_registry(args, cfg)
    }
}

private fun CliktCommand.build_config_from_parent(): Config {
    // Rust main() runs before subcommand dispatch: get_game_id(args.game_id), then Config with
    // override_container_header_version / override_toc_version and aes_keys[FGuid::default()] = AesKey::from_str(&aes)?.
    // Clikt does not execute the root run() when a subcommand is invoked, so apply the root options here.
    val parent = currentContext.parent?.command as? RetocCli
    parent?.game_id?.let { set_global_game_id(it) }
    val config = Config(
        container_header_version_override = parent?.override_container_header_version?.let {
            EIoContainerHeaderVersion.entries.find { v -> v.name == it }
                ?: throw IllegalArgumentException("invalid value '$it' for '--override-container-header-version' <EIoContainerHeaderVersion>")
        },
        toc_version_override = parent?.override_toc_version?.let {
            EIoStoreTocVersion.entries.find { v -> v.name == it }
                ?: throw IllegalArgumentException("invalid value '$it' for '--override-toc-version' <EIoStoreTocVersion>")
        }
    )
    val aes = parent?.aes_key
    if (aes != null) {
        config.aes_keys[FGuid()] = AesKey.from_str(aes)
    }
    return config
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fun parse_engine_version(s: String): EngineVersion {
    // Rust: clap::ValueEnum #[clap(rename_all = "verbatim")] — exact variant names; invalid values exit with an error
    return EngineVersion.entries.find { it.name == s }
        ?: throw UsageError("invalid value '$s' for '--version': expected one of ${EngineVersion.entries.joinToString(", ")}")
}

// ---------------------------------------------------------------------------
// Rust: retoc_cli/src/main.rs:312 fn main
// ---------------------------------------------------------------------------
fun main(args: Array<String>) {
    RetocCli().subcommands(
        ManifestCommand(),
        InfoCommand(),
        ListCommand(),
        VerifyCommand(),
        UnpackCommand(),
        UnpackRawCommand(),
        PackRawCommand(),
        ToLegacyCommand(),
        ToZenCommand(),
        GetCommand(),
        DumpTestCommand(),
        GenScriptObjectsCommand(),
        PrintScriptObjectsCommand(),
        AssetRegistryCommand()
    ).main(args)
}
