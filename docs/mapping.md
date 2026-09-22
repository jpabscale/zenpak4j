# Rust → Kotlin language mapping (living doc)

> **Must-read for sub-agents.** Before porting a file, read this doc and `plan.md`. Keep Rust identifiers literally (`snake_case`) for scriptable parity (`rg "fun read_encoded"`). Kotlin file names mirror Rust (`pak.rs` → `pak.kt`, `ext.rs` → `ext.kt`, `oodle_loader/src/lib.rs` → `oodle_loader/lib.kt`). Add new mappings under `## Changelog` and keep `## 0` pins up to date.
> All Kotlin packages are `com.github.jpabscale.zenpak4j.<module>` (e.g., `...repak`, `...retoc`, `...oodle_loader`, `...repak_cli`). Underscore in crate name is kept in package (`oodle_loader`, `repak_cli`) and allowed in `config/detekt.yml` `PackageNaming` `[a-z][a-zA-Z0-9_]*`.

## 0. Pinned upstream

| Repo | Pinned `trumank` SHA | File to bump | Check |
|------|----------------------|--------------|-------|
| `trumank/repak` | `355b5f62f51959c7cc6dd5a51708646ef483065d` | `gradle/libs.versions.toml` (`repak-upstream-sha`) + `scripts/pin-check.sh` | `git ls-remote https://github.com/trumank/repak refs/heads/master` |
| `trumank/retoc` | `885a8dae740cb1ce1e41ff2e74f67f9f0c118237` | `gradle/libs.versions.toml` (`retoc-upstream-sha`) + `scripts/pin-check.sh` | `git fetch https://github.com/trumank/<repo> master && git log --oneline <pinned>..FETCH_HEAD` |

Diff on update: `git fetch https://github.com/trumank/<repo> master && git log --oneline <pinned>..FETCH_HEAD`. Fixtures are **not committed** — resolved via sibling `../repak` / `../retoc` or `build/fixtures` downloaded from the pinned SHA (`scripts/download-fixtures.sh`, `gradle/libs.versions.toml` `repak-upstream-sha`). The `jpabscale/*` forks are retired; port-specific behavior lives in the port and is approved in `docs/parity-exceptions.json`.

## 1. Build & toolchain

| Rust | Kotlin | Notes |
|------|--------|-------|
| `Cargo.toml` `resolver="2"` workspace | `settings.gradle.kts` + `gradle/libs.versions.toml` + `gradle-wrapper.properties` | `group com.github.jpabscale.zenpak4j`, `version 0.1.0-SNAPSHOT` |
| `rust-toolchain.toml` `stable` | `Kotlin 2.4.20` + `Gradle 9.7.0` + `Java 25` toolchain | `kotlin { jvmToolchain(25) }` + `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` (FFM `java.lang.foreign` final in 22+, requires `25`); bytecode `JVM_21` fallback via `Kotlin 2.4.20` toolchain, `--enable-native-access=ALL-UNNAMED` for tests |
| `cargo test` | `gradle check` (`:repak:test` `85` + `:retoc:test` `47`, `detekt` `0` smells) | `useJUnitPlatform()` + `junit-platform-launcher`, fixtures via `FileChannel` / `SeekableByteChannel` |

## 2. Types

| Rust | Kotlin | Notes |
|------|--------|-------|
| `struct Foo { x: T }` | `class Foo(var x: T)` / `data class Foo` if `Clone+PartialEq` (add `equals`/`hashCode` via `contentEquals` for `ByteArray`) | Keep field name `x` `snake_case` literally; mirror file `foo.rs`→`foo.kt`, header `// Rust: <crate>/src/foo.rs:<line> <item>` |
| `enum Foo { A, B(T) }` | `sealed class Foo` or `enum class Foo` (unit variants) | `strum::Display` → `override toString() = name`; `FromRepr` → `companion from_repr(Int)`; `EnumString` → `valueOf` |
| `trait Foo` | `interface Foo` | `Send+Sync` → no marker |
| `type Alias = ...` | `typealias Alias = ...` |  |
| `const C: T = v` | `const val C: T = v` (top-level or `companion object`) | `MAGIC 0x5A6F12E1u`, `OODLE_VERSION "2.9.10"` |
| `static S: OnceLock<Option<String>>` (`global.rs`) | `class RepakContext(var game_id: String?)` / `RetocContext` instance param | **Never global singleton**; pass `context: RepakContext?` through `compression_index_size`, `Pak::read`/`write`, `Entry::read` etc. (was `OnceLock` global in Rust) |

## 3. Functions / methods

| Rust | Kotlin |
|------|--------|
| `impl Foo { fn bar(&self, x: T) -> Result<U,E> }` | `class Foo { fun bar(x: T): U }` keep `snake_case` verbatim; `Result` → `throws FooError` or `Result<U>` + `getOrThrow()` |
| `fn foo<T: Read>(r: &mut T)` generic `Read+Seek` | `fun <T: InputStream + Seekable> foo(r: T)` or overloads for `InputStream`/`SeekableByteChannel`/`RandomAccessFile` |
| `?` operator | early `return` / `?: throw` / `getOrThrow()` — preserve control flow and `log` builder (`UnsupportedOrEncrypted`) |
| `#[cfg(feature="...")]` (`compression`, `oodle`, `encryption`) | runtime `if` + `try { oodle().compress } catch { throw RepakError.Oodle }` ; `encryption` always enabled via `Cipher` |
| `macro_rules!` / `paste` / `formatdoc` | `inline fun` / manual expansion + `StringBuilder` |
| `#[derive(Clone, Debug)]` | `data class` / manual `copy()` + `toString` hex for `Hash` |

## 4. Option / Result / collections

| Rust | Kotlin |
|------|--------|
| `Option<T>` / `Some(v)` / `None` | `T?` / `v` / `null`; `unwrap()` → `!!` + `checkNotNull`, `ok_or(e)` → `?: throw e`, `transpose` → `?.let` |
| `Result<T,E>` / `Ok`/`Err` | `Result<T>` or `throws E : Exception` (`sealed class RepakError`); `Err(e)` → `throw e` |
| `Vec<T>` / `Vec<u8>` | `MutableList<T>` / `ByteArray` (`ByteArray` for `Vec<u8>`, `contentEquals`/`contentHashCode`) |
| `BTreeMap<K,V>` (ordered, deterministic) | `TreeMap<K,V>` / `sortedMapOf()` — **must stay ordered** (`Pak::read`/`write` `PHI`/`FDI`, `AssetRegistry` `149k` names) |
| `HashMap`/`HashSet` / `IndexMap`/`IndexSet` | `HashMap`/`HashSet` or `mutableMapOf`/`mutableSetOf` / `LinkedHashMap`+`LinkedHashSet` (preserve insertion order for `AssetRegistry` dedup) |
| `&[u8]` / `&str` / `String` slices | `ByteArray` / `String`; slices `copyOfRange`, `lowercase()` + `encode_utf16` for `fnv64_path` |

## 5. Ownership / lifetimes / borrowing

| Rust | Kotlin |
|------|--------|
| `&T` / `&mut T` | `T` (GC ref); `&mut` → `var` param or inline mutation + `ByteBuffer` |
| `'a` lifetime / `Box<dyn Trait>` | GC — comment only; `Box<dyn AsRef<[u8]>>` → `interface` ref |
| `Arc<T>` / `Mutex<T>` / `RwLock<T>` | `AtomicReference` / `ReentrantLock` / `ReentrantReadWriteLock` / `Mutex` (`kotlinx.coroutines`) |
| `OnceLock<Option<T>>` / `Lazy` | `lazy` / `volatile var` + `synchronized` (`OodleLoader` `cached`/`Two-stage` `OnceLock` parity) |
| `key-mutex::KeyMutex<K,V>` | `ConcurrentHashMap<K, Mutex>` + `runBlocking { mutex.withLock }` (`asset_conversion` `FZenPackageContext`) |
| `Condvar` / `Mutex<PoolState>` (`file_pool`) | `ReentrantLock` + `Condition` (`await`/`signal`) + `ArrayDeque` |

## 6. I/O & encoding

| Rust | Kotlin |
|------|--------|
| `byteorder::{ReadBytesExt, WriteBytesExt, LE/BE}` | `ByteBuffer.order(LITTLE_ENDIAN/BIG_ENDIAN)` helpers (`read_u8`/`read_u16_le`/`read_u32_le`/`read_i32_le`/`read_u64_le`/`read_u128_le` + `write_*`, `read_exact` loop) in `repak/ext.kt` and `retoc/ser.kt` |
| `ReadExt::read_bool` (`1→true,0→false else Bool(err)`) | `fun InputStream.read_bool(): Boolean` same `throw RepakError.Bool` |
| `read_guid` (20 B `SHA-1`) / `read_len(n)` / `write_all` / `read_array` (`u32 len` + loop) | `read_guid(): ByteArray` 20, `read_len(n): ByteArray`, `read_array(parse)`, `read_array_len(len, parse)` via `ByteBuffer` |
| `read_string` (`i32 len`; `<0` → `U16` `LE` `-len` inc `NUL`, else `u8` `NUL`) / `write_string` (empty\|\|ascii → `u32 len+1` + bytes + `0`, else `i32 -(len+1)` + `u16LE` + `0`) | `InputStream.read_string()` / `OutputStream.write_string(v)` — preserve fast-path and `position(|c==0)` truncation + `StandardCharsets.UTF_8` lossy |
| `u128 LE` (`Footer.encryption_uuid`, `FIoContainerId`) | `read_u128_le(): ULong` low 64 + high (high often `0` for fixtures) / `write_u128_le(ULong)` 16 B `Long` `LE` (low + high `0`) |
| `SeekFrom::End(-n)` / `Start(n)` | `SeekableByteChannel.position(channel.size()-n)` / `position(n)` + `SeekableByteArrayInputStream/OutputStream` (`position`/`seek`/`size`) for `StoreEntries` cursor and `SoftPackageReferencesOffset` patch |
| `Hash([u8;20])` / `FSHAHash`/`FChunkHash` | `value class Hash(val bytes: ByteArray)` / `class FSHAHash(val bytes: ByteArray)` with `contentEquals`/`contentHashCode` + `hex` `toString` |

## 7. Crypto / compression / hash

| Rust | Kotlin | Notes |
|------|--------|-------|
| `aes::Aes256::decrypt_block` ECB `chunks_mut(16)` / `encrypt_block` | `Cipher.getInstance("AES/ECB/NoPadding")` `SecretKeySpec(32B, "AES")` `doFinal` per 16 (or whole buffer `doFinal` if multiple of 16), `truncate(compressed)` | `Key.Some(SecretKeySpec)` vs `Key.None` → `Encrypted`; `AesKey.from_string` hex `0x` + `Base64.getDecoder` `STANDARD` (trim `=` then pad) 32 B check |
| `flate2::read::ZlibDecoder` / `GzDecoder` | `InflaterInputStream` / `GZIPInputStream` (`copyTo`) |  |
| `zstd::stream` `encode_all`/`Decoder` | `com.github.luben:zstd-jni:1.5.7-3` `Zstd.compress`/`ZstdInputStream` (`level 0`) | **JNI** `libzstd` bundled, not FFM |
| `lz4_flex::block::compress`/`decompress_into` | `org.lz4:lz4-java:1.8.0` `LZ4Factory.fastestInstance().fastCompressor().compress` / `fastDecompressor().decompress` (pre-alloc `uncompressed` `ByteArray`, `System.arraycopy`) | pure-Java (unsafe) |
| `oodle_loader::oodle()?.compress`/`decompress` | **FFM** `Oodle` (`Linker.nativeLinker()`, `SymbolLookup.libraryLookup(path, Arena)`, `FunctionDescriptor` `Compress` `10` args / `Decompress` `14` args / `GetCompressedBufferSizeNeeded` / `SetPrintf(void addr)`, `MethodHandle.invokeWithArguments`, `Arena.ofConfined.use` + `MemorySegment.ofArray`/`copy`) — `oodle_loader/lib.rs:1` → `lib.kt:199` | `Compressor` `Mermaid`/`Kraken` etc. (`None=3`..`Hydra=12`), `CompressionLevel` `Normal=4`/`Optimal5=9`/`HyperFast*=-1..-4`; `OodlePlatform` 5 hashes (`linux/x64` `ed7e...`, `linux/aarch64` `161a...`, `linux/arm` `83cd...`, `mac` `b09a...`, `win` `6f5d...`), `url()` `BASE/VERSION/path/name` (trim double-slash) + `HttpClient.Redirect.ALWAYS` + `~/.cache/zenpak4j` writable order; `Windows arm64` → `LibLoading("arm64 x64 emulation required")` |
| `sha1::Sha1` / `sha2::Sha256` / `hex`/`base64` | `MessageDigest.getInstance("SHA-1"/"SHA-256")` `HexFormat.of().formatHex` `Base64.getDecoder` | `hash` `pak.rs:674` `SHA-1`, `check_hash` `SHA-256` for Oodle, `hex::encode` parity |
| `blake3` `1.8.2` (`FIoChunkHash`) | `org.bouncycastle:bcprov-jdk18on:1.78.1` `MessageDigest` fallback + `blake3` JNI (`TODO` for `iostore_writer` `Toc` `chunk_metas`) |  |
| `cityhasher` `lower_utf16_cityhash` (`FPackageId`/`FIoContainerId`/`get_public_export_hash`) | Kotlin port `city_hash64` `1:1` (`CityInputCH`, `fetch64`, `rotate`, `shift_mix`, `weak_hash_len32`, `hash_len16`, `to_ascii_lowercase_ch` + `ByteBuffer LE` verification) | `FNAME_HASH_ALGORITHM_ID 0xC1640000`, `FPackageId.from_name` `lower_utf16` `city_hash64` |

## 8. Concurrency / macros

| Rust | Kotlin |
|------|--------|
| `rayon::par_iter` / `par_bridge` + `sync_channel(0)` rendezvous `pack` / `par_iter` `hash_list`/`unpack` | `parallelStream()` / `coroutineScope { async(Dispatchers.IO) }` + `kotlinx.coroutines.Channel(0)` / `LinkedBlockingQueue` / `runBlocking { awaitAll }` (`repak_cli` `pack`, `retoc_cli` `to-zen` `12` subcommands, `shader_library` `layout_write`) |
| `OnceLock` / `Mutex` / `Condvar` / `RwLock` | `lazy` / `ReentrantLock`+`Condition`+`ArrayDeque` (`file_pool`) / `Mutex` / `AtomicReference` / `synchronized` (`OodleLoader` `cached` `volatile` + `Synchronized`) |
| `key-mutex::KeyMutex` | `ConcurrentHashMap<K, Mutex>` + `mutex.withLock` |
| `#[instrument]` / `tracing` | no-op comment + `Log` `StdoutLogBackend`/`NoopLogBackend` (`logging.rs`) |
| `paste` / `formatdoc` / `pretty_assertions` | manual `String` templates + `assertEquals` |

## 9. Error handling

| Rust | Kotlin |
|------|--------|
| `thiserror::Error` (`Error::Magic(u32)`, `Bool(u8)`, `Version {used, version}`) | `sealed class RepakError/RetocError/OodleError : Exception` each variant `data class Magic(val magic: UInt) : RepakError("found magic ...")` + `companion` |
| `anyhow::Result` + `Context` | `Result<T>` + `throws` + `try/catch` `Context` message |
| `Error::Io(#[from])` / `From<io::Error>` | `cause: IOException` + `initCause` |

## 10. Detekt / style

- **Wrapper jar not stored**: `gradle/wrapper/gradle-wrapper.jar` `.gitignore`, `gradlew`/`gradlew.bat` stored with `scripts/bootstrap-wrapper.sh` (`curl` `services.gradle.org`/`github` `gradle-wrapper.jar` on demand), `distributionUrl https://services.gradle.org/distributions/gradle-9.7.0-bin.zip` (`gradle-wrapper.properties`).
- **Detekt** `1.23.8` `config/detekt.yml` `buildUponDefaultConfig false` minimal: `naming.FunctionNaming '[a-z][a-zA-Z0-9_]*'`, `PackageNaming '[a-z][a-zA-Z0-9_]*(\.[a-z][a-zA-Z0-9_]*)*'` (allows `oodle_loader`/`repak_cli` `_`), `complexity`/`style`/`comments`/`coroutines`/`empty-blocks`/`exceptions`/`performance`/`potentialBugs` `active: false` for parity ( `read_encoded` `Pak::read` long); `jvmTarget 21` + fake `java.version=21` during `Detekt` on `Java 25` host (Kotlin `1.9` `JavaVersion.parse("25.0.4.1")` bug).
- **File header** `// Rust: <crate>/src/<file>.rs:<line> <item>` per top-level item for `rg` parity.
- **Build** `Kotlin 2.4.20` `FFM` `java.lang.foreign` (`Linker`/`Arena`/`SymbolLookup`/`MemorySegment`) requires `--enable-native-access=ALL-UNNAMED` (`Test` `jvmArgs`).

## 11. Examples

```kotlin
// Rust: repak/src/ext.rs:69  if len < 0 { chars = read_array_len((-len) as usize, |r| r.read_u16::<LE>())? }
fun InputStream.read_string(): String {
    val len = read_i32_le()
    return if (len < 0) {
        val chars = read_array_len((-len)) { read_u16_le() }
        val n = chars.indexOf(0.toShort()).takeIf { it >= 0 } ?: chars.size
        String(chars.take(n).map { it.toInt().toChar() }.toCharArray())
    } else {
        val bytes = ByteArray(len); read_exact(bytes)
        val n = bytes.indexOf(0.toByte()).takeIf { it >= 0 } ?: bytes.size
        String(bytes, 0, n, StandardCharsets.UTF_8)
    }
}

// Rust: pak.rs:151 decrypt ECB per 16
fun decrypt(key: Key, bytes: ByteArray) {
    when (key) {
        is Key.None -> throw RepakError.Encrypted
        is Key.Some -> {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key.key)
            // single doFinal if multiple of 16 (parity with chunks_mut(16))
            val dec = cipher.doFinal(bytes); System.arraycopy(dec, 0, bytes, 0, bytes.size)
        }
    }
}

// Rust: entry.rs:204 read_encoded bits
val bits = input.read_u32_le()
val compression = when (val v = (bits shr 23) and 0x3f) { 0 -> null else -> v - 1 }

// Rust: oodle_loader FFM (Java 25)
val linker = Linker.nativeLinker()
val lookup = SymbolLookup.libraryLookup(libPath, arena)
val compressHandle = linker.downcallHandle(lookup.find("OodleLZ_Compress").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
val ret = compressHandle.invokeWithArguments(compressor.value, rawSeg, rawLen.toLong(), compSeg, level.value, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, 0L) as Long
```

## 12. Package & CLI

| Rust | Kotlin |
|------|--------|
| `repak` (`repak`, `oodle_loader`, `repak_cli`) | `com.github.jpabscale.zenpak4j.repak` / `...oodle_loader` / `...repak_cli` (`Clikt 5` `RepakCli` `Info`/`List`/`HashList`/`Unpack`/`Pack`/`Get`, `AesKey` hex/`Base64` `0x` + `STANDARD_NO_PAD`) |
| `retoc` (`retoc`, `retoc_cli`, `load_logger`) | `com.github.jpabscale.zenpak4j.retoc` / `...retoc_cli` (`12` subcommands `to-zen`/`to-legacy` `manifest` etc., `Config` `aes_keys` + `EngineVersion` `UE4_25`→`UE5_7`) / `...load_logger` (`ProxyDll` `d3d9`/`d3d11` `FFM` no-op on Linux) |

## 13. Stdout/stderr in an embedded process (`Console`)

Rust CLI actions print with `println!`/`print!` to the process stdout and write CLI-visible
diagnostics to stderr (`Log` Error+ lines in `StdoutLogBackend`, the `eprintln!` ContainerHeader
warning in `iostore.rs`, the fork's composite-container warnings). The port keeps those
statements verbatim but makes stdout-writing functions **extension functions on
`Console(out: PrintStream = System.out, err: PrintStream = System.err)`**, whose `println`
member is the direct translation target of the Rust `println!` macro; stderr writes take the
`err` stream explicitly (`System.err.println` → `err.println`). Rust-shaped top-level entries
remain as delegating wrappers (`fun action_x(...) = Console().action_x(...)`), so the CLIs keep
their default behavior. `Log::new_stdout(verbose, debug)` becomes `console.new_log(verbose, debug)`
(same levels, same line format, `PrintStreamLogBackend(out, err)`; Error+ keeps the
`StdoutLogBackend` split). Embedders pass their own streams and get no output on the host's
stdout or stderr. Wire format is untouched; only declaration form and call sites diverge.
Markers: `//@parity:on EXC-016` / `//@parity:off EXC-016` around the `Console` class and
`PrintStreamLogBackend`; the individual receiver declarations and `err` parameters are unwrapped
(same pattern as EXC-001's context threading).

Implementation notes (2026-09-22):

- Non-extension members that must dispatch polymorphically take the console as an explicit
  param (EXC-001-style context threading): `IoStoreTrait.print_info(depth, console)` and the
  `Output` sealed class, where `Output.Stdout(console)`/`Output.Progress(bar, console)` both
  write through `console.println`. Inside an extension the receiver is passed as `this`
  (`iostore.print_info(0, this)`, `Output.Stdout(this)`).
- `ZenScriptObjects.print()` becomes `fun Console.print(obj: ZenScriptObjects)` (decision 4:
  console stays the receiver); the call site becomes `print(script_objects)` — receiver moved
  to the argument. `Console` therefore declares no `print(msg: Any?)` member: a member would
  shadow the extension (Kotlin members beat extensions on the same receiver — verified with
  kotlinc), and the pinned Rust sources contain no bare `print!` call site to translate.
- `retoc/host.kt` holds `fun Console.new_log(...)` (the `:retoc`→`:repak` binding); the
  `Console` class lives in `:repak`, `PrintStreamLogBackend` in `retoc/logging.kt` next to
  `StdoutLogBackend` (Error+ lines keep the same stderr split, so CLI stderr stays
  byte-identical too).
- Stderr has no receiver form, so the iostore open family takes the stream explicitly:
  `open`/`open_with_container_paths`/`IoStoreBackend.open`/`open_paths`/`IoStoreContainer.open`
  gain `err: PrintStream = System.err`, and the composite-container and ContainerHeader
  warnings write through it. Actions pass the receiver's `err`; `ZenPakService` passes
  `err ?: System.err`. The CLI-only `dump-test` action keeps the default.
- `ZenPakService` printing entry points take `out: PrintStream? = null, err: PrintStream? =
  null` (null = `System.out`/`System.err`, today's behavior) and build
  `Console(out ?: System.out, err ?: System.err)`.
- Delegating wrappers exist only for CLI-shaped entry points. Internal helpers exist only in
  receiver form (`action_to_zen_reader`, `action_to_legacy_inner`/`_assets`/`_shaders`, both
  `indent_println` overloads); `:actions`/`:zenpak` thread the receiver (`this`). External
  callers of those helpers move to the receiver form when they recompile.
- The seam is enforced, not just documented: `tools/audit_parity.py` fails on direct
  `System.out` / `System.err` / `kotlin.io.print` writes in `:repak`/`:retoc`/`:actions`/
  `:zenpak` main sources (the pin-shaped `StdoutLogBackend` is allowlisted), and `ConsoleTest`
  asserts `Console` declares no `print` member (a member would shadow the `script_objects.rs`
  print extension; members beat extensions).

## Changelog

- 2026-08-19: init (pinned `trumank/repak@355b5f6`, `trumank/retoc@885a8da`, forks `jpabscale/*`).
- 2026-08-19: restructure to language-feature mapping; file names mirror Rust (`pak.rs`→`pak.kt`) per review; clarify repak-first order; group `com.github.jpabscale.zenpak4j`.
- 2026-08-19: Kotlin `2.3.0` + Gradle `9.7.0` + Java `25` toolchain for FFM (`jvmToolchain(25)`, `java.lang.foreign` final, `--enable-native-access`); wrapper `gradle-9.7.0-bin.zip` downloaded on demand (`bootstrap-wrapper.sh`, `gradle-wrapper.jar` ignored).
- 2026-08-19: Oodle FFM (`Linker`/`Arena`/`SymbolLookup`/`MemorySegment`, 5 platforms `linux/x64` `ed7e...` etc., `Redirect.ALWAYS`, `~/.cache/zenpak4j` order, `Windows arm64` `LibLoading` guidance `x64 emulation`), `repak` `data`/`entry` wire `Oodle` (`Mermaid/Normal`) via `project(":oodle-loader")`, `repak` tests `66` pass (`48 read` + `Oodle` `300 KiB`).
- 2026-08-20: detekt `1.23.8` minimal (`buildUponDefaultConfig false`, `FunctionNaming`/`PackageNaming` `_` allowed, `jvmTarget 21` + fake `java.version=21` for `Kotlin 1.9` `25.0.4.1` parse bug) `0` smells, `check` green.
- 2026-08-20: `retoc` `container_header` `StoreEntries` `16 B` fix + `asset_registry` `CityHash` `33..64` `*mul` + `compact_binary` `22522` entries + `legacy_asset`/`zen`/`zen_asset_conversion` `10` fixtures + `asset_conversion` `Randy`/`BP_Table_Lamp` → `23`→`43` tests (`AssetRegistry 8`, `CompactBinary 2`, `ContainerHeader 5`, `Toc 8`, `IStore 3`, `LegacyAsset 5`, `ShaderLibrary 6`, `ScriptObjects 2`, `Zen 1`, `ZenAssetConversion 2`, `AssetConversion 2`) `BUILD SUCCESSFUL` (added `bcprov` `Blake3` `2g` heap, `kotlinx-serialization` not needed).
- 2026-08-20: `repak-cli` full `Clikt` `608` parity (`Channel(0)` rendezvous + `parallelStream`, `AesKey` `hex`/`Base64`, `glob` `include`, `mount` guard) + `retoc-cli` `12` subcommands stub→full + `iostore_writer` `blake3` + `version_heuristics` candidate list + `load-logger` `FFM` `ProxyDll` → `43` `retoc` tests, `repak` `66`, `check` `24` tasks green.
- 2026-09-22: embedder stdout/stderr seam (`EXC-016`, §13) — `Console(out, err)` receiver extensions + one-line delegating wrappers, `out`/`err` service entry points, `PrintStreamLogBackend`/`console.new_log`, iostore `err` threading for warnings; CLI stdout/stderr byte-identical (manual before/after captures), wire format untouched.

