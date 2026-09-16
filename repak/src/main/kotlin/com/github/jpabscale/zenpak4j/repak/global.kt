// Ported from repak (MIT OR Apache-2.0) — Copyright (c) 2024 Truman Kilen, spuds
// Rust: repak/src/global.rs:1
package com.github.jpabscale.zenpak4j.repak

//@parity:on EXC-005
// Rust: repak/src/global.rs:5 GAME_ID_VISIONS_OF_MANA
const val GAME_ID_VISIONS_OF_MANA: String = "VisionsofMana"

// Rust: repak/src/global.rs:3 GAME_ID (OnceLock) -> global singleton for parity
// Mirror Rust OnceLock<Option<String>> with a ThreadLocal so concurrent in-process calls
// (ZenPakService) cannot cross-contaminate game ids; all reads happen on the calling thread
// (compression_index_size is consumed from PakWriter.write_entry/write_index, never from
// parallelStream workers). RepakContext delegates to it.
private val tl_global_game_id = ThreadLocal.withInitial<String?> { null }

var global_game_id: String?
    get() = tl_global_game_id.get()
    set(value) { tl_global_game_id.set(value) }

// Rust: repak/src/global.rs:7 get_game_id
fun get_game_id(default: String?): String? {
    if (global_game_id == null && default != null) {
        global_game_id = default
    }
    return global_game_id ?: default
}

fun set_game_id(id: String?) {
    if (id != null && global_game_id == null) {
        global_game_id = id
    } else if (id != null) {
        global_game_id = id
    }
}
//@parity:off EXC-005

//@parity:on EXC-001
// Rust: repak/src/global.rs:3 GAME_ID (OnceLock) -> instance RepakContext
@Suppress("FunctionName", "PropertyName", "VariableNaming")
class RepakContext(var game_id: String?) {

    // Rust: repak/src/global.rs:7 get_game_id
    fun get_game_id(default: String?): String? = game_id ?: global_game_id ?: default
}
//@parity:off EXC-001
