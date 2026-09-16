// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
// Rust: load_logger/src/resolvers.rs:1
@file:Suppress("FunctionName", "PropertyName", "ClassName", "unused", "SpellCheckingInspection", "EnumEntryName", "MagicNumber")

package com.github.jpabscale.zenpak4j.load_logger

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

// Rust: load_logger/src/resolvers.rs:11 ResolutionCore
// impl_try_collector! { pub struct ResolutionCore { engine_version: EngineVersion, fname_to_string: FNameToString, hooks: ResolutionHooks } }
// Kotlin stub: FFM no-op, keep signatures, no native scanning on Linux
data class ResolutionCore(
    var engine_version: EngineVersionStub = EngineVersionStub.Unknown,
    var fname_to_string: FNameToString = FNameToString(0uL),
    var hooks: ResolutionHooks = ResolutionHooks()
) {
    companion object {
        // Rust: ResolutionCore::resolver() -> patternsleuth resolver singleton (join_all impl_try_collector)
        // On Windows this would run ensure_one on engine_version, fname_to_string, hooks; on Linux stub.
        fun resolver(): Any = "stub_resolver_ResolutionCore"

        // FFM parity: patternsleuth resolver would scan PE image; stub keeps API shape
        fun resolver_with_ffi(arena: Arena, exe: MemorySegment): ResolutionCore {
            // no-op: would call impl_try_collector logic with join_all
            return ResolutionCore()
        }
    }

    // Rust: Debug hex output used in lib.rs hook() via info!("{resolution:#x?}")
    fun to_hex_string(): String = "ResolutionCore(engine_version=$engine_version, fname_to_string=0x${fname_to_string.address.toString(16)}, hooks=$hooks)"
}

// Rust: load_logger/src/resolvers.rs:21 ResolutionHooks
// impl_collector! { pub struct ResolutionHooks { event_driven_create_export, event_driven_index_to_object, check_for_cycles_inner } }
data class ResolutionHooks(
    var event_driven_create_export: EventDrivenCreateExport = EventDrivenCreateExport(0uL),
    var event_driven_index_to_object: EventDrivenIndexToObject = EventDrivenIndexToObject(0uL),
    var check_for_cycles_inner: CheckForCyclesInner = CheckForCyclesInner(0uL)
) {
    companion object {
        fun resolver(): Any = "stub_resolver_ResolutionHooks"
    }
}

// Rust: load_logger/src/resolvers.rs:7 EngineVersion (patternsleuth::unreal::engine_version)
// patternsleuth::unreal::engine_version::EngineVersion — ordered UE4..UE5 versions
enum class EngineVersionStub {
    Unknown,
    UE4_25,
    UE4_26,
    UE4_27,
    UE5_0,
    UE5_1,
    UE5_2,
    UE5_3,
    UE5_4,
    UE5_5;

    companion object {
        fun resolver(): Any = "stub_resolver_EngineVersion"
        fun from_str(s: String): EngineVersionStub = when (s) {
            "4.25" -> UE4_25
            "4.26" -> UE4_26
            "4.27" -> UE4_27
            "5.0" -> UE5_0
            "5.1" -> UE5_1
            "5.2" -> UE5_2
            "5.3" -> UE5_3
            "5.4" -> UE5_4
            "5.5" -> UE5_5
            else -> Unknown
        }
    }
}

// Alias to preserve Rust name EngineVersion exact parity (type = EngineVersionStub)
typealias EngineVersion = EngineVersionStub

// Rust: load_logger/src/resolvers.rs:15 FNameToString
// patternsleuth::unreal::fname::FNameToString (FFM address wrapper) — resolver scans for FName::ToString
data class FNameToString(val address: ULong = 0uL) {
    // Rust: transmute + call unsafe extern "system" fn(&FName, &mut FString)
    // Kotlin FFM parity: downcall handle via Linker.nativeLinker + FunctionDescriptor
    fun call(fname: FName, out: FString) {
        if (address == 0uL) return // no-op on Linux
        try {
            val linker = Linker.nativeLinker()
            val desc = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            val seg = MemorySegment.ofAddress(address.toLong()).reinterpret(Long.MAX_VALUE)
            val handle = linker.downcallHandle(seg, desc)
            Arena.ofConfined().use { arena ->
                val fname_seg = arena.allocate(FName.LAYOUT)
                fname_seg.set(ValueLayout.JAVA_INT, 0, fname.a.toInt())
                fname_seg.set(ValueLayout.JAVA_INT, 4, fname.b.toInt())
                val fstring_seg = arena.allocate(FString.LAYOUT)
                // would call handle.invoke(fname_seg, fstring_seg) and read back
                // no-op stub
            }
        } catch (_: Exception) {
        } catch (_: Throwable) {
        }
    }

    // Rust resolver hint: FNameToString is found via patternleuth unreal::fname resolver
    companion object {
        fun resolver(): Any = "stub_resolver_FNameToString"
    }

    fun is_resolved(): Boolean = address != 0uL
}

// Rust: load_logger/src/resolvers.rs:30 EventDrivenCreateExport(pub u64)
// impl_resolver_singleton!(all, EventDrivenCreateExport, |ctx| async { ... utf16_pattern("EventDrivenCreateExport") ... })
data class EventDrivenCreateExport(val address: ULong) {
    companion object {
        // Rust pattern: scan xrefs of "EventDrivenCreateExport"
        const val PATTERN_HINT = "EventDrivenCreateExport"
        // Rust: util::utf16_pattern("EventDrivenCreateExport\0")
        fun utf16_pattern(): ByteArray = "EventDrivenCreateExport\u0000".toByteArray(StandardCharsets.UTF_16LE)
        fun resolver(): Any = "stub_resolver_EventDrivenCreateExport"

        // Rust: ensure_one(root_functions(xrefs)) helper parity
        fun ensure_one(candidates: List<ULong>): ULong {
            require(candidates.size == 1) { "ensure_one expected 1 candidate, got ${candidates.size}" }
            return candidates.first()
        }

        // FFM parity: scan_xrefs + root_functions would be FFM memory scan
        fun scan_stub(ctx: Any, arena: Arena): ULong = 0uL
    }
    // Rust: ensure_one(root_functions(xrefs))
    fun is_resolved(): Boolean = address != 0uL
    fun as_ref(): Result<EventDrivenCreateExport> = if (is_resolved()) Result.success(this) else Result.failure(IllegalStateException("EventDrivenCreateExport unresolved"))
}

// Rust: load_logger/src/resolvers.rs:41 EventDrivenIndexToObject(pub u64)
// string: "Missing Dependency, request for %s but it was still waiting for creation."
data class EventDrivenIndexToObject(val address: ULong) {
    companion object {
        const val PATTERN_HINT = "Missing Dependency, request for %s but it was still waiting for creation."
        fun utf16_pattern(): ByteArray = "Missing Dependency, request for %s but it was still waiting for creation.\u0000".toByteArray(StandardCharsets.UTF_16LE)
        fun resolver(): Any = "stub_resolver_EventDrivenIndexToObject"
        fun ensure_one(candidates: List<ULong>): ULong {
            require(candidates.size == 1) { "ensure_one expected 1 candidate, got ${candidates.size}" }
            return candidates.first()
        }
    }
    fun is_resolved(): Boolean = address != 0uL
    fun as_ref(): Result<EventDrivenIndexToObject> = if (is_resolved()) Result.success(this) else Result.failure(IllegalStateException("EventDrivenIndexToObject unresolved"))
}

// Rust: load_logger/src/resolvers.rs:54 CheckForCyclesInner(pub u64)
// pattern: "4c 89 44 24 18 48 89 4c 24 08 55 56 41 54 41 55 41 57 48 81 ec ?? 00 00 00 4c 8b ac 24 ?? 00 00 00 4d 8b e0 48 8b f2 4d 8b c5 48 8d 54 24 34"
data class CheckForCyclesInner(val address: ULong) {
    companion object {
        const val PATTERN = "4c 89 44 24 18 48 89 4c 24 08 55 56 41 54 41 55 41 57 48 81 ec ?? 00 00 00 4c 8b ac 24 ?? 00 00 00 4d 8b e0 48 8b f2 4d 8b c5 48 8d 54 24 34"
        fun resolver(): Any = "stub_resolver_CheckForCyclesInner"

        // Rust: join_all(patterns.iter().map(|p| ctx.scan(Pattern::new(p).unwrap()))).await
        fun patterns(): List<String> = listOf(PATTERN)

        fun ensure_one(candidates: List<ULong>): ULong {
            require(candidates.size == 1) { "ensure_one expected 1 candidate, got ${candidates.size}" }
            return candidates.first()
        }

        // FFM parity: Pattern::new(p).unwrap() => validate pattern bytes
        fun pattern_bytes(): ByteArray {
            // parse hex pattern with ?? wildcards for FFM scan stub
            return ByteArray(0)
        }
    }
    fun is_resolved(): Boolean = address != 0uL
    fun as_ref(): Result<CheckForCyclesInner> = if (is_resolved()) Result.success(this) else Result.failure(IllegalStateException("CheckForCyclesInner unresolved"))
}

// Rust parity helpers — patternsleuth::resolvers::ensure_one, impl_collector etc. stubs
// These are kept as top-level functions to preserve 1:1 control flow names for Kotlin port
fun ensure_one_uint64(candidates: List<ULong>): ULong {
    if (candidates.size != 1) throw IllegalStateException("ensure_one expected 1, got ${candidates.size}")
    return candidates.first()
}

// Rust: patternsleuth::scanner::Pattern stub
data class Pattern(val pattern: String) {
    companion object {
        fun new(p: String): Pattern = Pattern(p)
    }
}

// Rust: patternsleuth::resolvers::unreal::util helpers stubs (utf16_pattern, scan_xrefs, root_functions)
object UnrealUtil {
    fun utf16_pattern(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_16LE)
    fun scan_xrefs(ctx: Any, strings: List<ULong>): List<ULong> = emptyList()
    fun root_functions(ctx: Any, refs: List<ULong>): List<ULong> = emptyList()
    fun scan_xrefs_stub(arena: Arena, seg: MemorySegment): List<ULong> = emptyList()
}
