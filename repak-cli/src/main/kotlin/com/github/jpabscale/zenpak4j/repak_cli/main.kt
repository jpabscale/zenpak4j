// Ported from repak (MIT OR Apache-2.0) — Copyright (c) 2024 Truman Kilen, spuds
// Rust: repak_cli/src/main.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection", "MagicNumber", "TooManyFunctions", "LongMethod", "ComplexMethod", "EnumEntryName", "VariableNaming")

package com.github.jpabscale.zenpak4j.repak_cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.jpabscale.zenpak4j.repak.Compression
import com.github.jpabscale.zenpak4j.repak.Version
import com.github.jpabscale.zenpak4j.repak.global_game_id
import com.github.jpabscale.zenpak4j.repak.get_game_id
import com.github.jpabscale.zenpak4j.repak_actions.*
import java.nio.file.FileSystems
import kotlin.system.exitProcess

// The action implementations live in :actions (repak_actions) — see EXC-014.
// This file keeps only the Clikt surface (options/commands) and error handling.

// ---------------------------------------------------------------------------
// Helpers mirroring Rust path handling
// ---------------------------------------------------------------------------

private fun handle_error(e: Throwable): Nothing {
    val msg = e.message ?: e.toString()
    System.err.println("Error: $msg")
    exitProcess(1)
}

private fun effective_aes_key(local: String?, parent: String?): String? {
    return local ?: parent
}

//@parity:on EXC-005
private fun effective_game_id(local: String?, parent: String?): String? {
    return local ?: parent
}
//@parity:off EXC-005

// ---------------------------------------------------------------------------
// Clikt-based CLI — mirrors clap::Parser derive with 1:1 command names
// Keep snake_case for parity
// ---------------------------------------------------------------------------

// Rust: repak_cli/src/main.rs:150 Args as Clikt root
class RepakCli : CliktCommand(name = "repak") {
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")

    override fun run() {
        // Rust: get_game_id(args.game_id);
        if (game_id != null) {
            global_game_id = game_id
            // also set via helper
            get_game_id(game_id)
        }
    }
    //@parity:off EXC-005
}

// Rust: repak_cli/src/main.rs:135 Action::Info
class InfoCommand : CliktCommand(name = "info") {
    val input: String by argument()
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")
    override fun run() {
        val parent = currentContext.parent?.command as? RepakCli
        val effAesStr = effective_aes_key(aes_key, parent?.aes_key)
        val effGameId = effective_game_id(game_id, parent?.game_id)
        if (effGameId != null) {
            global_game_id = effGameId
            get_game_id(effGameId)
        }
    //@parity:off EXC-005
        val aes = try {
            effAesStr?.let { AesKey.from_string(it) }
        } catch (e: Exception) {
            handle_error(e)
        }
        val action = ActionInfo(input = input)
        // mirror Args construction for parity
        val args = Args(aes_key = aes, game_id = effGameId, action = Action.Info(action))
        try {
            info(args.aes_key, action)
        } catch (e: Exception) {
            handle_error(e)
        }
    }
}

// Rust: repak_cli/src/main.rs:137 Action::List
class ListCommand : CliktCommand(name = "list") {
    val input: String by argument()
    val strip_prefix: String by option("-s", "--strip-prefix").default("../../../")
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")
    override fun run() {
        val parent = currentContext.parent?.command as? RepakCli
        val effAesStr = effective_aes_key(aes_key, parent?.aes_key)
        val effGameId = effective_game_id(game_id, parent?.game_id)
        if (effGameId != null) {
            global_game_id = effGameId
            get_game_id(effGameId)
        }
    //@parity:off EXC-005
        val aes = try {
            effAesStr?.let { AesKey.from_string(it) }
        } catch (e: Exception) {
            handle_error(e)
        }
        val action = ActionList(input = input, strip_prefix = strip_prefix)
        val args = Args(aes_key = aes, game_id = effGameId, action = Action.ListAction(action))
        try {
            list(args.aes_key, action)
        } catch (e: Exception) {
            handle_error(e)
        }
    }
}

// Rust: repak_cli/src/main.rs:139 Action::HashList
class HashListCommand : CliktCommand(name = "hash-list") {
    val input: String by argument()
    val strip_prefix: String by option("-s", "--strip-prefix").default("../../../")
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")
    override fun run() {
        val parent = currentContext.parent?.command as? RepakCli
        val effAesStr = effective_aes_key(aes_key, parent?.aes_key)
        val effGameId = effective_game_id(game_id, parent?.game_id)
        if (effGameId != null) {
            global_game_id = effGameId
            get_game_id(effGameId)
        }
    //@parity:off EXC-005
        val aes = try {
            effAesStr?.let { AesKey.from_string(it) }
        } catch (e: Exception) {
            handle_error(e)
        }
        val action = ActionHashList(input = input, strip_prefix = strip_prefix)
        val args = Args(aes_key = aes, game_id = effGameId, action = Action.HashList(action))
        try {
            hash_list(args.aes_key, action)
        } catch (e: Exception) {
            handle_error(e)
        }
    }
}

// Rust: repak_cli/src/main.rs:141 Action::Unpack
class UnpackCommand : CliktCommand(name = "unpack") {
    //@parity:on EXC-006
    // Rust: #[arg(index = 1, action = Append, short, long)] input: Vec<String>
    // Clikt: multiple positional arguments, also supports -i/--input multiple
    val input: List<String> by argument().multiple()
    //@parity:off EXC-006
    val output: String? by option("-o", "--output")
    val strip_prefix: String by option("-s", "--strip-prefix").default("../../../")
    val verbose: Boolean by option("-v", "--verbose").flag()
    val quiet: Boolean by option("-q", "--quiet").flag()
    val force: Boolean by option("-f", "--force").flag()
    val include: List<String> by option("-i", "--include").multiple()
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")
    override fun run() {
        val parent = currentContext.parent?.command as? RepakCli
        val effAesStr = effective_aes_key(aes_key, parent?.aes_key)
        val effGameId = effective_game_id(game_id, parent?.game_id)
        if (effGameId != null) {
            global_game_id = effGameId
            get_game_id(effGameId)
        }
    //@parity:off EXC-005
        val aes = try {
            effAesStr?.let { AesKey.from_string(it) }
        } catch (e: Exception) {
            handle_error(e)
        }
        val action = ActionUnpack(
            input = input,
            output = output,
            strip_prefix = strip_prefix,
            verbose = verbose,
            quiet = quiet,
            force = force,
            include = include
        )
        for (pattern in include) {
            try {
                FileSystems.getDefault().getPathMatcher("glob:$pattern")
            } catch (e: Exception) {
                throw UsageError("invalid glob pattern '$pattern': ${e.message}")
            }
        }
        val args = Args(aes_key = aes, game_id = effGameId, action = Action.Unpack(action))
        try {
            unpack(args.aes_key, action)
        } catch (e: Exception) {
            handle_error(e)
        }
    }
}

// Rust: repak_cli/src/main.rs:143 Action::Pack
class PackCommand : CliktCommand(name = "pack") {
    val input: String by argument()
    val output: String? by argument().optional()
    val mount_point: String by option("-m", "--mount-point").default("../../../")
    val version: String by option("--version").default(Version.V8B.name)
    val compression: String? by option("--compression")
    val path_hash_seed: String by option("-p", "--path-hash-seed").default("0")
    val verbose: Boolean by option("-v", "--verbose").flag()
    val quiet: Boolean by option("-q", "--quiet").flag()
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")
    //@parity:off EXC-005
    override fun run() {
        val ver = Version.entries.find { it.name == version }
            ?: throw UsageError("invalid value '$version' for '--version': expected one of ${Version.entries.joinToString(", ")}")
        val comp = compression?.let { c ->
            Compression.entries.find { it.name == c }
                ?: throw UsageError("invalid value '$c' for '--compression': expected one of ${Compression.entries.joinToString(", ")}")
        }
        val seed = path_hash_seed.toULongOrNull()
            ?: throw UsageError("invalid value '$path_hash_seed' for '--path-hash-seed': expected a u64")
        val action = ActionPack(
            input = input,
            output = output,
            mount_point = mount_point,
            version = ver,
            compression = comp,
            path_hash_seed = seed,
            verbose = verbose,
            quiet = quiet
        )
        val parent = currentContext.parent?.command as? RepakCli
        //@parity:on EXC-005
        val effGameId = effective_game_id(game_id, parent?.game_id)
        if (effGameId != null) {
            global_game_id = effGameId
            get_game_id(effGameId)
        }
        //@parity:off EXC-005
        val args = Args(aes_key = null, game_id = effGameId, action = Action.Pack(action))
        // Rust: pack(action) does not take aes_key
        try {
            pack(action)
        } catch (e: Exception) {
            handle_error(e)
        }
        // keep args for parity even if not used
        @Suppress("UNUSED_VARIABLE") val unused = args
    }
}

// Rust: repak_cli/src/main.rs:145 Action::Get
class GetCommand : CliktCommand(name = "get") {
    val input: String by argument()
    val file: String by argument()
    val strip_prefix: String by option("-s", "--strip-prefix").default("../../../")
    val aes_key: String? by option("-a", "--aes-key")
    //@parity:on EXC-005
    val game_id: String? by option("-g", "--game-id")
    override fun run() {
        val parent = currentContext.parent?.command as? RepakCli
        val effAesStr = effective_aes_key(aes_key, parent?.aes_key)
        val effGameId = effective_game_id(game_id, parent?.game_id)
        if (effGameId != null) {
            global_game_id = effGameId
            get_game_id(effGameId)
        }
    //@parity:off EXC-005
        val aes = try {
            effAesStr?.let { AesKey.from_string(it) }
        } catch (e: Exception) {
            handle_error(e)
        }
        val action = ActionGet(input = input, file = file, strip_prefix = strip_prefix)
        val args = Args(aes_key = aes, game_id = effGameId, action = Action.Get(action))
        try {
            get(args.aes_key, action)
        } catch (e: Exception) {
            handle_error(e)
        }
    }
}

// ---------------------------------------------------------------------------
// Rust: repak_cli/src/main.rs:186 fn main
// ---------------------------------------------------------------------------
fun main(args: Array<String>) {
    // Rust: let args = Args::parse();
    // Rust: let aes_key = args.aes_key.map(|k| k.0);
    // Rust: get_game_id(args.game_id);
    // Rust: match args.action { Info=>info, List=>list, HashList=>hash_list, Unpack=>unpack, Pack=>pack, Get=>get }
    RepakCli().subcommands(
        InfoCommand(),
        ListCommand(),
        HashListCommand(),
        UnpackCommand(),
        PackCommand(),
        GetCommand()
    ).main(args)
}
