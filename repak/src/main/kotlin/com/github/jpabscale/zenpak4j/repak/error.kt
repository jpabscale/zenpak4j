// Rust: repak/src/error.rs:1
package com.github.jpabscale.zenpak4j.repak

import java.io.IOException

// Rust: repak/src/error.rs:4 Error
@Suppress("ClassName", "FunctionName", "PropertyName", "VariableNaming")
sealed class RepakError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    // Rust: repak/src/error.rs:6 Strum
    data class Strum(val parse_error: Exception) :
        RepakError("enum conversion: ${parse_error.message}", parse_error)

    // Rust: repak/src/error.rs:9 Aes
    object Aes : RepakError("expect 256 bit AES key as base64 or hex string")

    // Rust: repak/src/error.rs:14 Compression
    object Compression : RepakError("enable the compression feature to read compressed paks")

    // Rust: repak/src/error.rs:17 Encryption
    object Encryption : RepakError("enable the encryption feature to read encrypted paks")

    // Rust: repak/src/error.rs:20 Oodle
    object Oodle : RepakError("enable the oodle feature to read Oodle compressed paks")

    // Rust: repak/src/error.rs:24 Io
    data class Io(val io_error: IOException) :
        RepakError("io error: ${io_error.message}", io_error)

    // Rust: repak/src/error.rs:27 Fmt
    data class Fmt(val fmt_error: Exception) :
        RepakError("fmt error: ${fmt_error.message}", fmt_error)

    // Rust: repak/src/error.rs:30 Utf8
    data class Utf8(val utf8_error: Exception) :
        RepakError("utf8 conversion: ${utf8_error.message}", utf8_error)

    // Rust: repak/src/error.rs:33 Utf16
    data class Utf16(val utf16_error: Exception) :
        RepakError("utf16 conversion: ${utf16_error.message}", utf16_error)

    // Rust: repak/src/error.rs:36 IntoInner
    data class IntoInner(val into_inner_error: IOException) :
        RepakError("bufwriter dereference: ${into_inner_error.message}", into_inner_error)

    // Rust: repak/src/error.rs:40 Bool
    data class Bool(val value: UByte) :
        RepakError("got $value, which is not a boolean")

    // Rust: repak/src/error.rs:42 Magic
    data class Magic(val magic: UInt) :
        RepakError("found magic of 0x${magic.toString(16)} instead of 0x${MAGIC.toString(16)}")

    // Rust: repak/src/error.rs:45 OodleFailed
    data class OodleFailed(val oodle_error: Throwable) :
        RepakError("Oodle loader error: ${oodle_error.message}", oodle_error)

    // Rust: repak/src/error.rs:49 MissingEntry
    data class MissingEntry(val path: String) :
        RepakError("No entry found at $path")

    // Rust: repak/src/error.rs:52 PrefixMismatch
    data class PrefixMismatch(val prefix: String, val path: String) :
        RepakError("Prefix \"$prefix\" does not match path \"$path\"")

    // Rust: repak/src/error.rs:55 WriteOutsideOutput
    data class WriteOutsideOutput(val path: String) :
        RepakError("Attempted to write to \"$path\" which is outside of output directory")

    // Rust: repak/src/error.rs:58 OutputNotEmpty
    data class OutputNotEmpty(val path: String) :
        RepakError("Output directory is not empty: \"$path\"")

    // Rust: repak/src/error.rs:61 InputNotADirectory
    data class InputNotADirectory(val path: String) :
        RepakError("Input is not a directory: \"$path\"")

    // Rust: repak/src/error.rs:64 DecompressionFailed
    data class DecompressionFailed(
        val compression: com.github.jpabscale.zenpak4j.repak.Compression,
    ) : RepakError("$compression decompression failed")

    // Rust: repak/src/error.rs:66 Version
    data class Version(
        val used: VersionMajor,
        val version: VersionMajor,
    ) : RepakError("used version $used but pak is version $version")

    // Rust: repak/src/error.rs:73 Encrypted
    object Encrypted : RepakError("pak is encrypted but no key was provided")

    // Rust: repak/src/error.rs:76 OsString
    data class OsString(val os_string: String) :
        RepakError("error with OsString")

    // Rust: repak/src/error.rs:79 UnsupportedOrEncrypted
    data class UnsupportedOrEncrypted(val prefix: String) :
        RepakError("${prefix}version unsupported or is encrypted (possibly missing --aes-key?)")

    // Rust: repak/src/error.rs:82 Other
    data class Other(val error: String) : RepakError(error)

    companion object
}
