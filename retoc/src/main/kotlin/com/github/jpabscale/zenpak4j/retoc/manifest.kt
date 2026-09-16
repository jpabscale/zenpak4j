// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: retoc/src/manifest.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "RedundantVisibilityModifier", "TooManyFunctions")

package com.github.jpabscale.zenpak4j.retoc

import java.io.InputStream
import java.io.OutputStream

// Rust: retoc/src/manifest.rs:7 PackageStoreManifest
data class PackageStoreManifest(
    var oplog: OpLog
)

// Rust: retoc/src/manifest.rs:12 OpLog
data class OpLog(
    var entries: List<Op>
)

// Rust: retoc/src/manifest.rs:17 Op
data class Op(
    var packagestoreentry: PackageStoreEntry,
    var packagedata: List<ChunkData>,
    var bulkdata: List<ChunkData>
)

// Rust: retoc/src/manifest.rs:24 PackageStoreEntry
data class PackageStoreEntry(
    var packagename: String
)

// Rust: retoc/src/manifest.rs:30 ChunkData
data class ChunkData(
    var id: FIoChunkIdRaw,
    var filename: String
)

// Rust: retoc/src/lib.rs:766 FIoChunkIdRaw
// Definition moved to lib.kt for 1:1 parity; reference lib.kt canonical definition
// (Removed duplicate to avoid duplicate class errors)
