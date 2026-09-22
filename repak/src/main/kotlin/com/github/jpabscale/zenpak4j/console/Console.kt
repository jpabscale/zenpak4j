// Copyright (c) 2026 jpabscale — original code (not part of the repak/retoc port)
// Console — embedder stream seam: Rust println!/print! statements stay verbatim inside
// Console extension functions, so an in-process caller binds its own stdout/stderr streams
// without a process-global System.setOut/System.setErr capture. See docs/mapping.md §13
// (EXC-016).
@file:Suppress("FunctionName", "ClassName", "unused")

package com.github.jpabscale.zenpak4j.console

import java.io.PrintStream

//@parity:on EXC-016
class Console(val out: PrintStream = System.out, val err: PrintStream = System.err) {
    fun println(msg: Any?) = out.println(msg)
    fun println() = out.println()
}
//@parity:off EXC-016
