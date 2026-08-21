// Rust: retoc/src/global.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused")

package com.github.jpabscale.zenpak4j.retoc

//@parity:on EXC-007
// Rust: retoc/src/global.rs:5 FF7R2_GAME_ID
const val FF7R2_GAME_ID: String = "End"
//@parity:off EXC-007

//@parity:on EXC-001
// Rust: retoc/src/global.rs:3 GAME_ID (OnceLock) -> instance RetocContext
class RetocContext(var game_id: String?) {

    // Rust: retoc/src/global.rs:6 get_game_id
    fun get_game_id(default: String?): String? = game_id ?: default
}
//@parity:off EXC-001
