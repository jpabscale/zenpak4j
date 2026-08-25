// Rust: oodle_loader/src/lib.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "EnumEntryName", "unused", "TooManyFunctions")

package com.github.jpabscale.zenpak4j.oodle_loader

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

// Rust: oodle_loader/src/lib.rs:7  oodle_lz::Compressor
enum class Compressor(val value: Int) {
    None(3),
    Kraken(8),
    Leviathan(13),
    Mermaid(9),
    Selkie(11),
    Hydra(12);

    companion object {
        fun from_value(v: Int): Compressor = entries.first { it.value == v }
    }
}

// Rust: oodle_loader/src/lib.rs:27  oodle_lz::CompressionLevel
enum class CompressionLevel(val value: Int) {
    None(0),
    SuperFast(1),
    VeryFast(2),
    Fast(3),
    Normal(4),
    Optimal1(5),
    Optimal2(6),
    Optimal3(7),
    Optimal4(8),
    Optimal5(9),
    HyperFast1(-1),
    HyperFast2(-2),
    HyperFast3(-3),
    HyperFast4(-4);

    companion object {
        fun from_value(v: Int): CompressionLevel = entries.first { it.value == v }
    }
}

// Rust: oodle_loader/src/lib.rs:100  OODLE_VERSION / OODLE_BASE_URL
const val OODLE_VERSION = "2.9.10"
const val OODLE_BASE_URL = "https://github.com/WorkingRobot/OodleUE/raw/refs/heads/main/Engine/Source/Programs/Shared/EpicGames.Oodle/Sdk/"
const val BASE_URL = OODLE_BASE_URL

// Rust: oodle_loader/src/lib.rs:103  OodlePlatform
data class OodlePlatform(val path: String, val name: String, val hash: String) {
    fun url(): String = "${OODLE_BASE_URL.trimEnd('/')}/$OODLE_VERSION/$path/$name"
}

// Rust: oodle_loader/src/lib.rs:109  OODLE_PLATFORM per cfg
val OODLE_PLATFORM_LINUX_X86_64 = OodlePlatform(
    path = "linux/lib",
    name = "liboo2corelinux64.so.9",
    hash = "ed7e98f70be1254a80644efd3ae442ff61f854a2fe9debb0b978b95289884e9c",
)

val OODLE_PLATFORM_LINUX_AARCH64 = OodlePlatform(
    path = "linuxarm/lib",
    name = "liboo2corelinuxarm64.so.9",
    hash = "161a8ecca8cc2d4ea6469779c2cc529ed5bb2765d99466273c29fdbef4657374",
)

val OODLE_PLATFORM_LINUX_ARM = OodlePlatform(
    path = "linuxarm/lib",
    name = "liboo2corelinuxarm32.so.9",
    hash = "83cda016c033844fe650e49fac4cc19ff0a0fb4a3c9a7576a320ea39a9e4626b",
)

val OODLE_PLATFORM_MACOS = OodlePlatform(
    path = "mac/lib",
    name = "liboo2coremac64.2.9.10.dylib",
    hash = "b09af35f6b84a61e2b6488495c7927e1cef789b969128fa1c845e51a475ec501",
)

val OODLE_PLATFORM_WINDOWS = OodlePlatform(
    path = "win/redist",
    name = "oo2core_9_win64.dll",
    hash = "6f5d41a7892ea6b2db420f2458dad2f84a63901c9a93ce9497337b16c195f457",
)

//@parity:on EXC-002
// current platform selection via os.name / os.arch
// Rust parity: single Windows x64 artifact; win-arm64 has no native Oodle (needs x64 emulation)
fun current_platform(): OodlePlatform {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val is_win = os.contains("win")
    val is_win_arm64 = is_win && (arch.contains("aarch64") || arch.contains("arm64"))
    if (is_win_arm64) {
        // No native Windows arm64 artifact in WorkingRobot/OodleUE (only win/redist/oo2core_9_win64.dll)
        // Throw clear error instead of UnsatisfiedLinkError from loading x64 dll on ARM64
        throw OodleError.LibLoading(
            RuntimeException(
                "Windows arm64 native Oodle not available (only x64 oo2core_9_win64.dll exists). " +
                    "Use x64 emulation (ARM64EC) or disable Oodle (fallback to Zlib/Zstd/LZ4). " +
                    "os.arch=$arch, platform would be ${OODLE_PLATFORM_WINDOWS.name} hash ${OODLE_PLATFORM_WINDOWS.hash}"
            )
        )
    }
    return when {
        is_win -> OODLE_PLATFORM_WINDOWS
        os.contains("mac") || os.contains("darwin") -> OODLE_PLATFORM_MACOS
        arch.contains("aarch64") || arch.contains("arm64") -> OODLE_PLATFORM_LINUX_AARCH64
        arch == "arm" || (arch.contains("arm") && !arch.contains("aarch")) -> OODLE_PLATFORM_LINUX_ARM
        else -> OODLE_PLATFORM_LINUX_X86_64
    }
}
//@parity:off EXC-002

// Rust: oodle_loader/src/lib.rs:144  url()
fun url(): String = current_platform().url()

// Rust: oodle_loader/src/lib.rs:151  Error
sealed class OodleError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HashMismatch(val expected: String, val found: String) :
        OodleError("Oodle lib hash mismatch expected: $expected got $found")

    object CompressionFailed : OodleError("Oodle compression failed")

    object InitializationFailed : OodleError("Oodle initialization failed previously")

    class Io(val io_cause: java.io.IOException) : OodleError("IO error $io_cause", io_cause)

    class Ureq(val ureq_cause: Throwable) : OodleError("ureq error $ureq_cause", ureq_cause)

    class LibLoading(val lib_cause: Throwable) : OodleError("Oodle libloading error $lib_cause", lib_cause)
}

// Rust: oodle_loader/src/lib.rs:172  check_hash
fun check_hash(data: ByteArray) {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(data)
    val hash = HexFormat.of().formatHex(digest)
    val expected = current_platform().hash
    if (hash != expected) {
        throw OodleError.HashMismatch(expected = expected, found = hash)
    }
}

// overload for platform-specific check (used by fetch_oodle)
fun check_hash(data: ByteArray, platform: OodlePlatform) {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(data)
    val hash = HexFormat.of().formatHex(digest)
    if (hash != platform.hash) {
        throw OodleError.HashMismatch(expected = platform.hash, found = hash)
    }
}

//@parity:on EXC-013
// Rust: oodle_loader/src/lib.rs:188  fetch_oodle
// Embedder hook: when set before the first oodle() load, its directory is probed FIRST (and used
// as the download target) so every consumer of this JVM shares one native-cache location.
@Volatile
var preferred_oodle_dir: Path? = null

fun fetch_oodle(): Path {
    val platform = current_platform()
    val name = platform.name
    // Prefer cache/tmp over repo polluting locations (user.dir, current_exe)
    val candidates = mutableListOf<Path>()
    // 0) embedder-provided unified cache dir (EXC-013): when set it OWNS the lookup —
    // probed first and used as the download target, legacy locations are skipped
    try { preferred_oodle_dir?.let { candidates.add(it.resolve(name)) } } catch (_: Exception) {}
    if (preferred_oodle_dir == null) {
    // 1) XDG cache / home cache (preferred, persistent)
    try { candidates.add(Path.of(System.getProperty("user.home")).resolve(".cache/zenpak4j/$name")) } catch (_: Exception) {}
    // 2) java.io.tmpdir (ephemeral but writable)
    try { candidates.add(Path.of(System.getProperty("java.io.tmpdir")).resolve(name)) } catch (_: Exception) {}
    // 3) build/tmp in cwd (project-local cache)
    try {
        val cwd = Path.of(System.getProperty("user.dir"))
        candidates.add(cwd.resolve("build/tmp/$name"))
        candidates.add(cwd.resolve("build/oodle/$name"))
    } catch (_: Exception) {}
    // 4) user.dir (fallback, will be gitignored via *.so.9)
    try { candidates.add(Path.of(System.getProperty("user.dir")).resolve(name)) } catch (_: Exception) {}
    // 5) current_exe parent (Rust parity, but may be read-only like /usr/bin)
    val exe_command = ProcessHandle.current().info().command().orElse("")
    if (exe_command.isNotEmpty()) {
        try {
            val parent = Path.of(exe_command).parent
            if (parent != null) {
                candidates.add(parent.resolve(name))
            }
        } catch (_: Exception) {}
    }
    }

    // pick first existing, or first writable parent
    var oodle_path: Path? = null
    for (cand in candidates) {
        if (Files.exists(cand)) {
            oodle_path = cand
            break
        }
    }
    if (oodle_path == null) {
        // choose first writable parent
        for (cand in candidates) {
            val parent = cand.parent
            if (parent != null && Files.isWritable(parent) || !Files.exists(parent) ) {
                // try to ensure parent exists
                try {
                    if (parent != null && !Files.exists(parent)) Files.createDirectories(parent)
                } catch (_: Exception) { continue }
                if (parent == null || Files.isWritable(parent)) {
                    oodle_path = cand
                    break
                }
            }
        }
    }
    if (oodle_path == null) {
        oodle_path = candidates.first()
    }

    if (!Files.exists(oodle_path)) {
        // Normalize URL: trim trailing slash from base to avoid double slash
        val raw_url = platform.url()
        val download_url = raw_url.replace("//2.9", "/2.9") // fix double slash before version
        try {
            val client = HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.ALWAYS).build()
            val request = HttpRequest.newBuilder(URI.create(download_url)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() != 200) {
                throw OodleError.Ureq(RuntimeException("HTTP ${response.statusCode()} for $download_url"))
            }
            val buffer = response.body()
            check_hash(buffer, platform)
            // ensure parent exists
            val parent = oodle_path.parent
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent)
            Files.write(oodle_path, buffer)
        } catch (e: OodleError) {
            throw e
        } catch (e: java.io.IOException) {
            throw OodleError.Io(e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OodleError.Ureq(e)
        } catch (e: Exception) {
            throw OodleError.Ureq(e)
        }
    }
    // don't check existing file to allow user to substitute other versions (parity with Rust comment)
    return oodle_path
}
//@parity:off EXC-013

// Rust: oodle_loader/src/lib.rs:200  pub struct Oodle { _library, compress, decompress, ... }
// FFM via java.lang.foreign.*  (Java 25 final)
class Oodle(
    private val arena: Arena,
    private val compressHandle: MethodHandle,
    private val decompressHandle: MethodHandle,
    private val get_compressed_buffer_size_neededHandle: MethodHandle,
    private val set_printfHandle: MethodHandle,
    private val lookup: SymbolLookup,
    private val libPath: Path,
) : AutoCloseable {

    companion object {
        // Rust: oodle_lz::Compress signature
        // (compressor: i32, rawBuf: *const u8, rawLen: usize, compBuf: *mut u8, level: i32,
        //  pOptions: *const (), dictionaryBase: *const (), lrm: *const (), scratchMem: *mut u8, scratchSize: usize) -> isize
        private fun compress_descriptor(): FunctionDescriptor =
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            )

        // Rust: oodle_lz::Decompress signature (14 args)
        private fun decompress_descriptor(): FunctionDescriptor =
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
            )

        private fun get_compressed_buffer_size_needed_descriptor(): FunctionDescriptor =
            FunctionDescriptor.of(
                ValueLayout.JAVA_LONG,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG,
            )

        private fun set_printf_descriptor(): FunctionDescriptor =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)

        // Factory mirroring Oodle::new + load_oodle
        fun load_from_path(libPath: Path): Oodle {
            // Shared (not confined) arena: the library and its function pointers must be
            // invocable from any thread — compress/decompress run on parallelStream/coroutine
            // workers, and a confined arena would make every off-loader-thread call throw
            // WrongThreadException.
            val arena = Arena.ofShared()
            try {
                val lookup = SymbolLookup.libraryLookup(libPath, arena)
                val linker = Linker.nativeLinker()

                val compress_sym = lookup.find("OodleLZ_Compress").orElseThrow {
                    OodleError.LibLoading(RuntimeException("symbol OodleLZ_Compress not found in $libPath"))
                }
                val decompress_sym = lookup.find("OodleLZ_Decompress").orElseThrow {
                    OodleError.LibLoading(RuntimeException("symbol OodleLZ_Decompress not found in $libPath"))
                }
                val get_size_sym = lookup.find("OodleLZ_GetCompressedBufferSizeNeeded").orElseThrow {
                    OodleError.LibLoading(RuntimeException("symbol OodleLZ_GetCompressedBufferSizeNeeded not found in $libPath"))
                }
                val set_printf_sym = lookup.find("OodleCore_Plugins_SetPrintf").orElseThrow {
                    OodleError.LibLoading(RuntimeException("symbol OodleCore_Plugins_SetPrintf not found in $libPath"))
                }

                val compressHandle = linker.downcallHandle(compress_sym, compress_descriptor())
                val decompressHandle = linker.downcallHandle(decompress_sym, decompress_descriptor())
                val get_sizeHandle = linker.downcallHandle(get_size_sym, get_compressed_buffer_size_needed_descriptor())
                val set_printfHandle: MethodHandle = linker.downcallHandle(set_printf_sym, set_printf_descriptor())

                val oodle = Oodle(arena, compressHandle, decompressHandle, get_sizeHandle, set_printfHandle, lookup, libPath)
                // silence oodle logging: (res.set_printf)(null)
                set_printfHandle.invokeWithArguments(MemorySegment.NULL)
                return oodle
            } catch (e: OodleError) {
                try { arena.close() } catch (_: Exception) {}
                throw e
            } catch (e: Throwable) {
                try { arena.close() } catch (_: Exception) {}
                throw OodleError.LibLoading(e)
            }
        }
    }

    // Rust: Oodle::compress
    fun compress(input: ByteArray, compressor: Compressor, compression_level: CompressionLevel): ByteArray {
        val buffer_size = get_compressed_buffer_size_needed(compressor, input.size.toLong())
        // allocate temp arena for this call (input + output)
        Arena.ofConfined().use { tmp_arena ->
            val rawSeg: MemorySegment = if (input.isEmpty()) MemorySegment.NULL else tmp_arena.allocate(input.size.toLong())
            if (input.isNotEmpty()) {
                // copy input bytes into native memory
                val src = MemorySegment.ofArray(input)
                MemorySegment.copy(src, 0L, rawSeg, 0L, input.size.toLong())
            }
            val compSeg: MemorySegment = if (buffer_size == 0L) MemorySegment.NULL else tmp_arena.allocate(buffer_size)
            val ret: Long = try {
                @Suppress("UNCHECKED_CAST")
                compressHandle.invokeWithArguments(
                    compressor.value,
                    rawSeg,
                    input.size.toLong(),
                    compSeg,
                    compression_level.value,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    0L,
                ) as Long
            } catch (e: Throwable) {
                throw OodleError.LibLoading(e)
            }
            if (ret == -1L) {
                throw OodleError.CompressionFailed
            }
            val outSize = ret.toInt()
            if (outSize == 0) return ByteArray(0)
            val out = ByteArray(outSize)
            val dst = MemorySegment.ofArray(out)
            MemorySegment.copy(compSeg, 0L, dst, 0L, ret)
            return out
        }
    }

    // Rust: Oodle::decompress  (14 args, returns isize)
    fun decompress(input: ByteArray, output: ByteArray): Int {
        Arena.ofConfined().use { tmp_arena ->
            val compSeg: MemorySegment = if (input.isEmpty()) MemorySegment.NULL else tmp_arena.allocate(input.size.toLong())
            if (input.isNotEmpty()) {
                val src = MemorySegment.ofArray(input)
                MemorySegment.copy(src, 0L, compSeg, 0L, input.size.toLong())
            }
            val rawSeg: MemorySegment = if (output.isEmpty()) MemorySegment.NULL else tmp_arena.allocate(output.size.toLong())
            // output buffer is uninitialized; will be filled by native call

            val ret: Long = try {
                decompressHandle.invokeWithArguments(
                    compSeg,
                    input.size.toLong(),
                    rawSeg,
                    output.size.toLong(),
                    1, // fuzzSafe
                    1, // checkCRC
                    0, // verbosity
                    0L, // decBufBase
                    0L, // decBufSize
                    0L, // fpCallback
                    0L, // callbackUserData
                    MemorySegment.NULL, // decoderMemory
                    0L, // decoderMemorySize
                    3, // threadPhase
                ) as Long
            } catch (e: Throwable) {
                throw OodleError.LibLoading(e)
            }
            if (ret >= 0 && output.isNotEmpty()) {
                // copy back to output array
                val dst = MemorySegment.ofArray(output)
                // rawSeg contains decompressed data on success; copy only if ret <= output.size
                val copyLen = minOf(ret, output.size.toLong())
                MemorySegment.copy(rawSeg, 0L, dst, 0L, copyLen)
            }
            return ret.toInt()
        }
    }

    // Long variant for callers needing 64-bit status (mirrors Rust isize)
    fun decompress_long(input: ByteArray, output: ByteArray): Long = decompress(input, output).toLong()

    // Rust: Oodle::get_compressed_buffer_size_needed
    fun get_compressed_buffer_size_needed(compressor: Compressor, raw_buffer: Long): Long {
        return try {
            get_compressed_buffer_size_neededHandle.invokeWithArguments(compressor.value, raw_buffer) as Long
        } catch (e: Throwable) {
            throw OodleError.LibLoading(e)
        }
    }

    fun get_compressed_buffer_size_needed(compressor: Compressor, raw_buffer: Int): Long =
        get_compressed_buffer_size_needed(compressor, raw_buffer.toLong())

    override fun close() {
        try { arena.close() } catch (_: Exception) {}
    }
}

// Rust: static OODLE: OnceLock<Option<Oodle>>
object OodleLoader {
    @Volatile
    private var cached: Oodle? = null

    @Volatile
    private var cached_error: OodleError? = null

    @Volatile
    private var initialized = false

    @Synchronized
    fun oodle(): Oodle {
        if (cached != null) return cached!!
        if (initialized) {
            // Rust: first failure returns the original error; every later call returns InitializationFailed
            throw OodleError.InitializationFailed
        }
        return try {
            val loaded = load_oodle()
            cached = loaded
            initialized = true
            loaded
        } catch (e: OodleError) {
            initialized = true
            cached_error = e
            throw e
        } catch (e: Exception) {
            initialized = true
            val wrapped = OodleError.LibLoading(e)
            cached_error = wrapped
            throw wrapped
        }
    }

    // for testing: reset (not in Rust, but helpful)
    @Synchronized
    fun reset() {
        cached?.close()
        cached = null
        cached_error = null
        initialized = false
    }
}

// Rust: fn load_oodle() -> Result<Oodle>
fun load_oodle(): Oodle {
    val path = fetch_oodle()
    return Oodle.load_from_path(path)
}

// Rust: pub fn oodle() -> Result<&'static Oodle>
fun oodle(): Oodle = OodleLoader.oodle()
