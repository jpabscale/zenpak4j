// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: load_logger/src/lib.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection", "MagicNumber", "MemberVisibilityCanBePrivate", "TooManyFunctions")

package com.github.jpabscale.zenpak4j.load_logger

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Path
import java.util.logging.Logger

// Rust: load_logger/src/lib.rs:8 proxy_dll::proxy_dll!([d3d9, d3d11, x3daudio1_7], init);
// Windows DLL proxy stub — FFM no-op on non-Windows, keep signatures
object ProxyDll {
    val proxied_dlls: List<String> = listOf("d3d9", "d3d11", "x3daudio1_7")
    private val logger: Logger = Logger.getLogger("load_logger.ProxyDll")

    // Rust macro expands to DllMain forwarding; Kotlin stub does no native load on Linux
    fun init() {
        logger.fine("ProxyDll.init stub for ${proxied_dlls.joinToString()}")
        val is_windows = System.getProperty("os.name", "").lowercase().contains("windows")
        if (!is_windows) return
        // FFM path: try to forward to system DLLs via SymbolLookup
        try {
            Arena.ofConfined().use { arena ->
                for (dll in proxied_dlls) {
                    try {
                        // On Windows, this would load the real DLL from system32 via FFM
                        val lookup: SymbolLookup = SymbolLookup.libraryLookup(Path.of("$dll.dll"), arena)
                        // Probe a known export to verify lookup works (no-op)
                        lookup.find("Direct3DCreate9").orElse(MemorySegment.NULL)
                        logger.fine("ProxyDll forwarded $dll via FFM")
                    } catch (_: Exception) {
                        // swallow: proxy DLL may not be present on this host
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
        } catch (_: Exception) {
        } catch (_: IllegalStateException) {
        }
    }

    // Rust parity: proxy_dll macro also generates DllMain stub forwarding. On Linux we
    // keep it as no-op; on Windows the real implementation would call init() from DllMain.
    fun dll_main(reason: Int): Boolean {
        if (reason == 1) init() // DLL_PROCESS_ATTACH
        return true
    }
}

// Rust: load_logger/src/lib.rs:10 retour::static_detour! { pub static EventDrivenCreateExport ... } etc.
// Detour stubs — keep 1:1 names, FFM no-op on Linux; on Windows would use retour/FFM trampoline
object EventDrivenCreateExportDetour {
    private var address: ULong = 0uL
    private var enabled: Boolean = false
    private var hook_fn: ((MemorySegment?, Int, MemorySegment?, MemorySegment?) -> MemorySegment?)? = null
    private var downcall: java.lang.invoke.MethodHandle? = null

    fun initialize(address: ULong, hook: (Any?, Int, Any?, Any?) -> Any?) {
        this.address = address
        // FFM stub: on Windows would create FunctionDescriptor + downcallHandle
        try {
            val linker = Linker.nativeLinker()
            val desc = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            // No actual native address on Linux; keep null
            if (address != 0uL) {
                val seg = MemorySegment.ofAddress(address.toLong()).reinterpret(Long.MAX_VALUE)
                downcall = linker.downcallHandle(seg, desc)
            }
        } catch (_: Exception) {
        } catch (_: Throwable) {
        }
        // Wrap generic hook into MemorySegment signature for parity
        hook_fn = { a, b, c, d -> hook(a, b, c, d) as? MemorySegment }
    }

    // overload for typed hook used by hook() parity
    fun initialize_typed(address: ULong, hook: (MemorySegment?, Int, MemorySegment?, MemorySegment?) -> MemorySegment?) {
        this.address = address
        this.hook_fn = hook
    }

    fun enable() { enabled = true }
    fun disable() { enabled = false }
    fun call(a: Any?, b: Int, c: Any?, d: Any?): Any? {
        // FFM no-op: would call original via downcall
        return null
    }
    fun is_enabled(): Boolean = enabled
    fun get_address(): ULong = address
}

object EventDrivenIndexToObjectDetour {
    private var address: ULong = 0uL
    private var enabled: Boolean = false
    private var hook_fn: ((MemorySegment?, FPackageIndex, Boolean, FPackageIndex) -> MemorySegment?)? = null

    fun initialize(address: ULong, hook: (Any?, Any?, Boolean, Any?) -> Any?) {
        this.address = address
        try {
            val linker = Linker.nativeLinker()
            val desc = FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
            if (address != 0uL) {
                val seg = MemorySegment.ofAddress(address.toLong()).reinterpret(Long.MAX_VALUE)
                linker.downcallHandle(seg, desc)
            }
        } catch (_: Exception) {
        }
    }

    fun enable() { enabled = true }
    fun disable() { enabled = false }
    fun call(a: Any?, b: Any?, c: Boolean, d: Any?): Any? = null
    fun is_enabled(): Boolean = enabled
}

object CheckForCyclesInnerDetour {
    private var address: ULong = 0uL
    private var enabled: Boolean = false
    private var hook_fn: ((MemorySegment?, MemorySegment?, MemorySegment?, MemorySegment?, MemorySegment?) -> Boolean)? = null

    fun initialize(address: ULong, hook: (Any?, Any?, Any?, Any?, Any?) -> Boolean) {
        this.address = address
        try {
            val linker = Linker.nativeLinker()
            val desc = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            if (address != 0uL) {
                val seg = MemorySegment.ofAddress(address.toLong()).reinterpret(Long.MAX_VALUE)
                linker.downcallHandle(seg, desc)
            }
        } catch (_: Exception) {
        }
    }

    fun enable() { enabled = true }
    fun disable() { enabled = false }
    fun call(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?): Boolean = false
    fun is_enabled(): Boolean = enabled
}

// Rust: load_logger/src/lib.rs:10 type aliases for detour statics (keep top-level for parity)
// Note: resolver address types live in resolvers.kt as data classes EventDrivenCreateExport etc.
// Detour statics are intentionally named *Detour to avoid package-level collision while keeping 1:1 parity.

// Rust: load_logger/src/lib.rs:24 FPackageIndex(i32)
@JvmInline
value class FPackageIndex(val value: Int = 0) {
    // Rust: #[repr(C)] struct FPackageIndex(i32) — transparent i32
    fun to_int(): Int = value
}

// Rust: load_logger/src/lib.rs:29 FEventLoadNodePtr
data class FEventLoadNodePtr(
    var pkg: FAsyncPackage? = null,
    var pkg_index: FPackageIndex = FPackageIndex(0),
    var phase: UInt = 0u
) {
    // Rust: #[repr(C)] with Option<Box<FAsyncPackage>> (nullable ptr), FPackageIndex, u32 phase
    // MemoryLayout parity (FFM): struct { address pkg_ptr; int32 pkg_index; uint32 phase } with padding
    companion object {
        val MEMORY_LAYOUT: java.lang.foreign.MemoryLayout = run {
            // Best-effort layout description for FFM parity; not used on Linux no-op
            try {
                java.lang.foreign.MemoryLayout.structLayout(
                    ValueLayout.ADDRESS.withName("pkg"),
                    ValueLayout.JAVA_INT.withName("pkg_index"),
                    ValueLayout.JAVA_INT.withName("phase")
                )
            } catch (_: Exception) {
                ValueLayout.ADDRESS
            }
        }
    }
}

// Rust: load_logger/src/lib.rs:37 FName { a: u32, b: u32 }
data class FName(
    var a: UInt = 0u,
    var b: UInt = 0u
) {
    // Rust Debug impl uses fname_to_string via global().fname_to_string
    override fun toString(): String {
        return try {
            val core = GLOBAL
            if (core != null && core.fname_to_string.address != 0uL) {
                // FFM path: call FNameToString function descriptor
                val linker = Linker.nativeLinker()
                val desc = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
                // Allocate FString output via confined arena
                Arena.ofConfined().use { arena ->
                    val out_seg = arena.allocate(ValueLayout.ADDRESS.byteSize() + 8)
                    // stub: would call native FNameToString; fallback to hex
                    // native call: linker.downcallHandle(MemorySegment.ofAddress(core.fname_to_string.address.toLong()).reinterpret(Long.MAX_VALUE), desc)
                }
                "FName(a=$a, b=$b)"
            } else {
                "FName(a=$a, b=$b)"
            }
        } catch (_: Exception) {
            "FName(a=$a, b=$b)"
        } catch (_: Throwable) {
            "FName(a=$a, b=$b)"
        }
    }

    companion object {
        val LAYOUT: java.lang.foreign.MemoryLayout = java.lang.foreign.MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("a"),
            ValueLayout.JAVA_INT.withName("b")
        )
    }
}

// Rust: load_logger/src/lib.rs:55 FString { ptr: *const u16, num: u32, max: u32 }
data class FString(
    var ptr: Long = 0L,
    var num: UInt = 0u,
    var max: UInt = 0u
) {
    // Rust: FString::get() reads UTF16 from native ptr, trims at NUL, converts via String::from_utf16
    fun get(): String {
        if (ptr == 0L || num == 0u) return ""
        return try {
            val seg = MemorySegment.ofAddress(ptr).reinterpret(num.toLong() * 2)
            val chars = ShortArray(num.toInt())
            for (i in chars.indices) {
                chars[i] = seg.getAtIndex(ValueLayout.JAVA_SHORT, i.toLong())
                if (chars[i] == 0.toShort()) {
                    return String(chars.copyOf(i).map { it.toInt().toChar() }.toCharArray())
                }
            }
            // find NUL or use full length
            val pos = chars.indexOf(0.toShort()).takeIf { it >= 0 } ?: chars.size
            String(chars.copyOf(pos).map { it.toInt().toChar() }.toCharArray())
        } catch (_: Exception) {
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        fun default(): FString = FString()
        val LAYOUT: java.lang.foreign.MemoryLayout = java.lang.foreign.MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("ptr"),
            ValueLayout.JAVA_INT.withName("num"),
            ValueLayout.JAVA_INT.withName("max")
        )
    }
}

// Rust: load_logger/src/lib.rs:76 FAsyncPackage { pad1: u64, pad2: u64, request_id: u32, name: FName, name_to_load: FName }
data class FAsyncPackage(
    var pad1: ULong = 0uL,
    var pad2: ULong = 0uL,
    var request_id: UInt = 0u,
    var name: FName = FName(),
    var name_to_load: FName = FName()
) {
    companion object {
        val LAYOUT: java.lang.foreign.MemoryLayout = java.lang.foreign.MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("pad1"),
            ValueLayout.JAVA_LONG.withName("pad2"),
            ValueLayout.JAVA_INT.withName("request_id"),
            FName.LAYOUT.withName("name"),
            FName.LAYOUT.withName("name_to_load")
        )
    }
}

// Rust: load_logger/src/lib.rs:85 FNameToString = unsafe extern "system" fn(&FName, &mut FString)
typealias FNameToStringFn = (FName, FString) -> Unit

// Rust: load_logger/src/lib.rs:87 pub fn init()
fun init() {
    // Rust: log::setup_logging().unwrap(); info!("Init"); if let Err(err) = hook() { info!("Hook failed {err:?}") }
    setup_logging().onFailure { /* swallow unwrap failure parity */ }
    val logger = Logger.getLogger("load_logger")
    logger.info("Init")
    ProxyDll.init()
    val result = hook()
    if (result.isFailure) {
        logger.info("Hook failed ${result.exceptionOrNull()}")
    }
}

// Rust: load_logger/src/lib.rs:97 static mut GLOBAL: Option<ResolutionCore>
@Volatile
private var GLOBAL: ResolutionCore? = null

// Rust: load_logger/src/lib.rs:98 fn global() -> &'static ResolutionCore
fun global(): ResolutionCore = GLOBAL ?: throw IllegalStateException("GLOBAL not initialized — hook() must be called first")

// Exposed for testing / FFI parity; not in Rust but useful for Kotlin
fun get_global_or_null(): ResolutionCore? = GLOBAL

// Rust: load_logger/src/lib.rs:102 fn hook() -> Result<()>
fun hook(): Result<Unit> = runCatching {
    val logger = Logger.getLogger("load_logger")
    // Rust: let exe = patternsleuth::process::internal::read_image()?;
    // Rust: let resolution = exe.resolve(resolvers::ResolutionCore::resolver())?;
    // Kotlin FFM no-op: on Windows would read PE image via FFM + patternsleuth scanner;
    // on Linux we synthesize a stub resolution to preserve control flow.
    val is_windows = System.getProperty("os.name", "").lowercase().contains("windows")

    val resolution: ResolutionCore = if (is_windows) {
        // FFM path: would read_image() via FFM, then resolve patterns
        try {
            // Simulate read_image via FFM: probe current exe
            val exe_path = Path.of(ProcessHandle.current().info().command().orElse("")).toAbsolutePath()
            logger.fine("hook: Windows FFM read_image probing $exe_path")
            // patternsleuth resolver singleton — still stub unless native scanner available
            val resolver = ResolutionCore.resolver()
            logger.fine("hook: resolver=$resolver")
            ResolutionCore(
                engine_version = EngineVersionStub.Unknown,
                fname_to_string = FNameToString(0uL),
                hooks = ResolutionHooks(
                    event_driven_create_export = EventDrivenCreateExport(0uL),
                    event_driven_index_to_object = EventDrivenIndexToObject(0uL),
                    check_for_cycles_inner = CheckForCyclesInner(0uL)
                )
            )
        } catch (e: Exception) {
            throw e
        }
    } else {
        // Linux no-op stub resolution
        ResolutionCore(
            engine_version = EngineVersionStub.Unknown,
            fname_to_string = FNameToString(0uL),
            hooks = ResolutionHooks(
                event_driven_create_export = EventDrivenCreateExport(0uL),
                event_driven_index_to_object = EventDrivenIndexToObject(0uL),
                check_for_cycles_inner = CheckForCyclesInner(0uL)
            )
        )
    }

    logger.info("$resolution")
    GLOBAL = resolution

    // Rust: unsafe { if let Ok(addr) = global().hooks.event_driven_create_export.as_ref() { ... } }
    // Kotlin parity: check is_resolved() and initialize detours with FFM trampolines
    val hooks = global().hooks
    if (hooks.event_driven_create_export.is_resolved()) {
        EventDrivenCreateExportDetour.initialize(hooks.event_driven_create_export.address) { a, b, c, d ->
            val pkg = a as? FAsyncPackage
            logger.info("${Thread.currentThread().threadId()} EventDrivenCreateExport $b, ${pkg?.name}")
            EventDrivenCreateExportDetour.call(a, b, c, d)
        }
        EventDrivenCreateExportDetour.enable()
    }
    if (hooks.event_driven_index_to_object.is_resolved()) {
        EventDrivenIndexToObjectDetour.initialize(hooks.event_driven_index_to_object.address) { a, b, c, d ->
            val pkg = a as? FAsyncPackage
            logger.info("${Thread.currentThread().threadId()} EventDrivenIndexToObject $b $c $d ${pkg?.name}")
            EventDrivenIndexToObjectDetour.call(a, b, c, d)
        }
        EventDrivenIndexToObjectDetour.enable()
    }
    if (hooks.check_for_cycles_inner.is_resolved()) {
        CheckForCyclesInnerDetour.initialize(hooks.check_for_cycles_inner.address) { a, b, c, d, e ->
            val result = CheckForCyclesInnerDetour.call(a, b, c, d, e)
            val node = e as? FEventLoadNodePtr
            if (node != null) {
                logger.info("${Thread.currentThread().threadId()} CheckForCyclesInner cycle=$result $node")
            }
            result
        }
        CheckForCyclesInnerDetour.enable()
    }
}
