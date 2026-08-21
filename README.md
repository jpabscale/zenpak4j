# zenpak4j

[![CI](https://github.com/jpabscale/zenpak4j/actions/workflows/ci.yml/badge.svg)](https://github.com/jpabscale/zenpak4j/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/jpabscale/zenpak4j.svg)](https://jitpack.io/#jpabscale/zenpak4j)

A **Kotlin/JVM port of [`repak`](https://github.com/trumank/repak) and [`retoc`](https://github.com/trumank/retoc)** — Unreal Engine `.pak` / `IoStore` `.utoc`/`.ucas` plus asset `to-zen`/`to-legacy` conversion. It is an almost one-to-one, **statement-parallel** port, fully done by an LLM: the Kotlin source mirrors the Rust source file-for-file, statement-for-statement, so upstream `repak`/`retoc` changes stay cheap to adopt.

> **Performance** — the in-JVM pipeline avoids per-asset subprocess round-trips: automod's `.pak` / `IoStore` stages fork `repak`/`retoc` binaries today, paying process spawn + IPC + temp-dir I/O per pak. As a library, `zenpak4j` can be loaded **in-process** by the JVM tooling that currently subprocesses the Rust binaries, eliminating the fork and allowing zero-copy `ByteArray` handoff.

> **Ported repak commit: `355b5f62f51959c7cc6dd5a51708646ef483065d`** (`fix: skip deleted PAK entries…`) and **port-lives at `jpabscale/repak@9f8dbd5`** (`Updated version.` + `ab3e873` multi-input lists).
> **Ported retoc commit: `885a8dae740cb1ce1e41ff2e74f67f9f0c118237`** (`Do not panic on non-standard FName hash`) and **port-lives at `jpabscale/retoc@e7c2711`** (`Update locked package versions` + 10 ahead of upstream `mixed-version` etc.).
>
> All Rust code is ported from these exact trees. The differential oracle — the built Rust `repak`/`retoc` binaries used to verify parity — must match these commits. When a newer upstream tip is adopted, bump these SHAs everywhere (see [Keeping up with repak/retoc](#keeping-up-with-repakretoc)).

## What it is

- **`repak`** — the ported `repak` library (Kotlin/JVM, `com.github.jpabscale.zenpak4j.repak`): `PakBuilder`/`PakReader`/`PakWriter`, `Entry` (`read`/`read_encoded` bitfield `0x3f` + `align16` + `AES ECB` `RelativeChunkOffsets`), `Footer` (`32`-byte padded compression names), `ext` `ReadExt`/`WriteExt` (`read_string` `i32` `UTF16LE`), `data` `PartialEntry` chunk `0x1F000`, `Version` `13`/`VersionMajor` `11` (`size()` table), `RepakContext` (`game_id` instance vs `OnceLock` — see `EXC-001`).
- **`oodle-loader`** — `com.github.jpabscale.zenpak4j.oodle_loader` via **Java FFM** (`java.lang.foreign` final in Java 25, `Linker`/`Arena`/`SymbolLookup`/`MemorySegment`): 5 platforms (`linux/x64` `ed7e…`, `linux/aarch64` `161a…`, `linux/arm` `83cd…`, `mac` `b09a…`, `win` `6f5d…`), `OODLE_VERSION 2.9.10` from `WorkingRobot/OodleUE`, `SHA-256` check, `~/.cache/zenpak4j` order, `Windows arm64` clear `LibLoading` guidance (`EXC-002`).
- **`repak-cli`** — a **cross-platform, JVM-based drop-in replacement** for `repak` (the `repak` binary). Fat/uber jar `repak.jar` (`java --enable-native-access=ALL-UNNAMED -jar build/libs/repak.jar`) with 6 commands `info`/`list`/`hash-list`/`unpack`/`pack`/`get` (`Clikt 5`, `AesKey` hex/`Base64`, `glob` `include`, `mount` guard, `Channel(0)` rendezvous + `parallelStream`).
- **`retoc`** — the ported `retoc` library (`com.github.jpabscale.zenpak4j.retoc`): `Toc` (`5`-byte `BE` `FIoOffsetAndLength`, `FIoChunkId` version stamping `1<<6|is_new<<7`), `IStore` (`Backend` composite `sort_container_name`), `ContainerHeader` (`742` version matrix `PreInitial→SoftPackageReferencesOffset`), `zen`/`legacy_asset` (`FZenPackageHeader`/`FLegacyPackageHeader` `0x9E2A83C1`), `asset_conversion`↔`zen_asset_conversion` (`1515`/`1639` `KeyMutex` outer recursion), `shader_library`/`asset_registry`/`compact_binary`/`name_map` (`CityHash` `lower_utf16`), etc.
- **`retoc-cli`** — `com.github.jpabscale.zenpak4j.retoc_cli` (`12` subcommands `manifest`/`info`/`list`/`verify`/`unpack`/`to-legacy`/`to-zen`/`get` etc., `Config` `aes_keys` + `EngineVersion` `UE4_25`→`UE5_7`).
- **`load-logger`** — `com.github.jpabscale.zenpak4j.load_logger` Windows `ProxyDll` `d3d9/d3d11` `FFM` no-op on Linux (keeps parity).

### Scope

In scope: `repak/repak/**`, `repak/oodle_loader/**`, `repak/repak_cli/**`, `retoc/retoc/**` (`ser`/`crc`/`name_map`/`file_pool`/`iostore`/`zen`/`legacy_asset`/`asset_conversion`/`zen_asset_conversion`/…), `retoc/retoc_cli/**`, `retoc/load_logger/**` (stub).

Out of scope (deferred):
- `retoc` `load_logger` live UE hook (`retour` detour + `patternsleuth`) — the Windows DLL proxy is ported as FFM no-op on Linux; live attach not exercised in CI.

## Why

`repak`/`retoc` are the build-time container tools used by the modding pipeline. As Rust binaries they are invoked as subprocesses per pak/`utoc`: the JVM tooling (e.g. automod) pays `fork` + `exec` + temp-dir `create_dir`/`read_dir`/`seek` per asset, and the Rust `OnceLock` globals (`GAME_ID`) and `Oodle` `OnceLock` make in-process reuse awkward. Both binaries need a runtime (Rust toolchain vs `java` `25`), but the JVM port behaves consistently on every platform and, as a library, can be loaded **in-process** by the JVM tooling that currently subprocesses the binaries — the same win `uasset4j` got over `UAssetCLI.dll` (`52s` vs `2:38` on Stellar Blade `.demo.sb`, same machine, same patches).

## Building locally

Requirements:

- **JDK 25** for compilation. The build uses a JDK 25 toolchain; if it isn't installed, the [foojay resolver](https://github.com/gradle/foojay-resolver-convention) auto-downloads it on first build. The produced bytecode targets **JVM 21**, so the jars run on any JVM 21+ (FFM `Oodle` needs `--enable-native-access=ALL-UNNAMED` at runtime on 25).
- **`curl` or `wget`** on first build only: the Gradle wrapper jar is not committed; `gradlew` fetches it from the pinned Gradle `9.7.0` release and verifies its SHA-256 (from `gradle.properties`). Windows uses the bundled `curl.exe` + `certutil`.
- Network access to Maven Central (dependencies) and `services.gradle.org` (Gradle distribution).

Build, test, and produce the fat jars:

```
./gradlew build                    # compile + run JUnit suite (repak 66 + retoc 43, fixtures via ../repak / ../retoc sibling or build/fixtures download)
./gradlew fatJars                  # build both CLIs: build/libs/repak.jar (15 M) + build/libs/retoc.jar (24 M)
./gradlew :repak-cli:jar           # or individually: repak-cli/build/libs/repak-cli-0.1.0-SNAPSHOT.jar
./gradlew :retoc-cli:jar           # retoc-cli/build/libs/retoc-cli-0.1.0-SNAPSHOT.jar
```

Run the CLIs (fat jars need no extra classpath):

```
java --enable-native-access=ALL-UNNAMED -jar build/libs/repak.jar --help
# Usage: repak [<options>] <command> [<args>]...
# Commands: info, list, hash-list, unpack, pack, get

java --enable-native-access=ALL-UNNAMED -jar build/libs/repak.jar info repak/tests/packs/pack_v11.pak
# mount point: ../mount/point/root/  version: V11  4 file entries

java --enable-native-access=ALL-UNNAMED -jar build/libs/retoc.jar --help
# Usage: retoc [<options>] <command> [<args>]...
# Commands: manifest, info, list, verify, unpack, to-legacy, to-zen, get, ...
```

As a library (JitPack):

```
//> using repository https://jitpack.io
//> using dep com.github.jpabscale:zenpak4j:<tag>
import com.github.jpabscale.zenpak4j.repak.PakBuilder
import com.github.jpabscale.zenpak4j.retoc.IoStoreWriter
val pak = PakBuilder().key(SecretKeySpec(...)).reader(FileChannel.open(path))
val bytes = pak.get("test.txt", channel) // in-process, no fork
```

## Porting discipline

The port follows a strict **parity contract** so that a ported file is a mechanical translation, not a rewrite:

- **Statement-level parity is a hard rule.** Every Rust statement, branch, call, and loop appears, in order, in the Kotlin method — translated only through the mappings in [`docs/mapping.md`](docs/mapping.md). *Functional* parity (same output) is necessary but never sufficient; a restructured port is rejected and reworked to restore the Rust shape. Only a statement-parallel port keeps upstream diffs cheap.
- **Names and paths mirror Rust.** `repak/repak/src/pak.rs` → `repak/src/main/kotlin/.../repak/pak.kt`, `retoc/retoc/src/zen.rs` → `retoc/src/main/kotlin/.../retoc/zen.kt`; `struct`/`enum`/`fn`/`field` names preserved verbatim (`snake_case` kept, `rg "fun read_encoded"` parity check). Detekt `PackageNaming` allows `_` (`oodle_loader`, `repak_cli`).
- **`tools/audit_parity.py`** audits every file marked `ported` in [`docs/port-tracker.md`](docs/port-tracker.md): every Rust `pub` member must exist on the Kotlin side. Green at milestone close.
- **Approved parity exceptions.** A deliberate, user-approved divergence (e.g. `RepakContext` instance vs `OnceLock` global, or fork-tip features absent from the pinned SHAs — `EXC-005`..`EXC-012` in [`docs/port-tracker.md`](docs/port-tracker.md)) may be recorded — and only there — in [`docs/parity-exceptions.json`](docs/parity-exceptions.json). Each entry carries approval metadata (`approved_by`, `approved_on`, `reason`) and can only be added/modified/revoked on explicit user instruction. The exception code is wrapped in `//@parity:on <id>` / `//@parity:off <id>` markers; `tools/audit_parity.py` verifies markers are balanced. `tools/sweep.py --check-stale` reports exceptions whose fixtures no longer diverge.
- **Algorithmic-complexity parity.** A Kotlin method that is functionally equivalent but asymptotically slower than its Rust counterpart is a parity violation even when every asset `MATCHES` and member names align (lookup vs `toSet()` rebuild, loop nesting, guard checks).

See [`docs/mapping.md`](docs/mapping.md) for the full translation table and [`AGENTS.md`](AGENTS.md) for agent rules.

## Testing against Rust oracles

Functional parity is enforced by differential testing against the pinned Rust oracles:

- **Repak corpus** — `repak/tests/packs/*.pak` (48 combos `V5`/`V7`/`V8A`/`V8B`/`V9`/`V11` × `compress`/`encrypt`/`encryptindex`) + `pack/root/*` + `test.png`/`test.txt`; `PakTest` `66` tests (`read` mount/version/files/content, `write` roundtrip, `rewrite_index`, `roundtrip` `none`/`zlib`/`gzip`/`zstd`/`lz4`/`oodle` `300 KiB`)
- **Retoc corpus** — `retoc/tests/UE*` (`UE4.22`/`UE4.27`/`UE5.0`/`UE5.3`/`UE5.4`/`UE5.5`/`UE5.6` + `issues/issue7`/`18`): `ContainerHeader` `5` (`104740` exact), `AssetRegistry` `8` (`43s` `UE4.22` `149k` names `HashMap`), `CompactBinary` `2` (`22522` `packagestore.manifest`), `Toc` `8`/`IStore` `3`, `LegacyAsset` `5` (`BP_Table_Lamp`/`Randy`), `Zen` `1` (`SPR_UI_Battle`), `ZenAssetConversion` `2` (`10` fixtures `UE5.4`/`5.5`/`5.6`), `AssetConversion` `2` (`Randy`/`BP_Table_Lamp` `uzenasset→legacy`), `ScriptObjects` `2` (`30977`/`23393`), `ShaderLibrary` `6` (`Global`/`NuclearNightmare`)
- **Round-trips** — `to-zen` `uasset+uexp → uzenasset` + `to-legacy` `uzenasset → uasset+uexp` must be stable; bytes after `header_size` compared
- **JUnit 5** — `repak:test` + `retoc:test` via `./gradlew test` (`--enable-native-access=ALL-UNNAMED` for FFM)
- **Corpus sweep** — `python3 tools/sweep.py` analog runs JVM `repak.jar`/`retoc.jar` vs Rust `repak`/`retoc` `info`/`list`/`unpack`/`to-zen` on every `*.pak`/`*.utoc` and byte-compares output (future `tools/` parity harness, same as `uasset4j` `tools/sweep.py`)
- **Cross-platform CI** — GitHub Actions `ci.yml` runs `gradle check` on Linux/macOS/Windows (FFM `Oodle` `~/.cache` + `verify` `SHA-256`, Windows `arm64` → `LibLoading` guidance)

The oracle binaries are the built Rust `repak`/`retoc` at the pinned commits, run via `cargo` or `../repak/target/release/repak`.

## Keeping up with repak/retoc

Because the port is statement-parallel, adopting a newer upstream release is mechanical:

1. **Re-pin** — update the pinned SHAs in this `README`, `docs/mapping.md` header, `docs/port-tracker.md`, and `gradle/libs.versions.toml` (`repakUpstreamSha`/`retocUpstreamSha`) + `gradle.properties` (`repak.pinned.sha`/`retoc.pinned.sha`).
2. **Diff upstream** — `git -C <repak clone> diff <old-sha>..<new-sha> -- repak/repak/src` and `git -C <retoc clone> diff <old-sha>..<new-sha> -- retoc/retoc/src` and port each changed Rust file. Each file is a localized, statement-level translation; most diffs are small (new `Version` variant, `EIoStoreTocVersion` gate, new `Compression`).
3. **Update the mapping** — any new Rust construct gets a `docs/mapping.md` entry before it is ported.
4. **Regenerate the oracle** — rebuild Rust `repak`/`retoc` from the new pin and re-run `gradle test` until `DIFF 0`.
5. **Update the tests** — fold in any new `Cargo` `#[test]` cases into the ported JUnit suite.
6. **Run the audit** — `python3 tools/audit_parity.py` until `PARITY AUDIT: GREEN`.

The port is pinned to `repak@355b5f6` + `retoc@885a8da`; the forks live at `../repak` (`jpabscale/repak@9f8dbd5` `ab3e873` multi-input) and `../retoc` (`jpabscale/retoc@e7c2711` `mixed-version`).

## Publishing

- **Git tag** a release as `<repak-sha>.<retoc-sha>.<ref>` (e.g. `355b5f6.885a8da.1`) to publish — both upstream pins are encoded in the tag and `<ref>` is a monotonic release counter. The Gradle project version is derived from the tag (`git describe`), so the artifact version always matches the ref it was built from.
- **JitPack** builds the library on demand from the tag. Coordinates: `com.github.jpabscale:zenpak4j:<tag>` (`com.github.jpabscale.zenpak4j:repak` / `:retoc` / `:oodle-loader` artifacts via `gradle/libs.versions.toml`).
- **GitHub Actions** (`.github/workflows/ci.yml`) builds and runs the full test suite on every push/PR, and on a tag push creates a GitHub Release whose assets are the **fat jars** `repak.jar` (`15 M`) + `retoc.jar` (`24 M`) (`gradle fatJars` → `build/libs/repak.jar`/`retoc.jar`).
- The port compiles to **JVM 21 bytecode** (regardless of the build JDK `25`), so the jars run on any JVM 21+ — FFM `Oodle` needs `--enable-native-access=ALL-UNNAMED` only when actually decompressing Oodle.

## Layout

```
repak/src/main/kotlin/com/github/jpabscale/zenpak4j/repak/     # repak lib (Version, Pak, Entry, Footer, data, ext, oodle via FFM)
oodle-loader/src/main/kotlin/.../oodle_loader/                # FFM Oodle (5 platforms, 2.9.10, SHA-256, ~/.cache)
repak-cli/src/main/kotlin/.../repak_cli/                      # CLI repak (Clikt) → repak.jar fat
retoc/src/main/kotlin/com/github/jpabscale/zenpak4j/retoc/    # retoc lib (Toc, IStore, ContainerHeader, zen/legacy_asset, asset_conversion↔zen_asset_conversion, shader_library, asset_registry, compact_binary, name_map, crc, file_pool, version, compression, manifest, verse_vm_types)
retoc-cli/src/main/kotlin/.../retoc_cli/                      # CLI retoc (12 subcommands) → retoc.jar fat
load-logger/src/main/kotlin/.../load_logger/                  # Windows ProxyDll FFM no-op on Linux
src/test/kotlin/                                              # ported JUnit 5 + fixture tests (repak 66, retoc 43)
tools/                                                        # parity harnesses (audit_parity.py, sweep.py future)
docs/mapping.md                                               # Rust → Kotlin translation contract
docs/port-tracker.md                                          # per-file port status
docs/parity-exceptions.json                                   # approved divergences (EXC-*)
```

## License

The ported code is derived from [`repak`](https://github.com/trumank/repak) (`MIT OR Apache-2.0`, Copyright (c) trumank and contributors) and [`retoc`](https://github.com/trumank/retoc) (`MIT`, Copyright (c) trumank and contributors). The repo ships [LICENSE](LICENSE) with the original texts and notices, plus its own copyright line, and every ported file carries an attribution header (`Ported from repak (MIT OR Apache-2.0)` / `Ported from retoc (MIT)`). New parts (CLI wrappers, `RepakContext`/`RetocContext` instance, fat-jar wiring) are also MIT.

The Oodle lodge (`liboo2*`) is **proprietary** (RAD Game Tools, now Epic) — `WorkingRobot/OodleUE` is a convenience mirror (`2.9.10` `ed7e...` etc.) used only for `fetch_oodle()` `HttpClient` `Redirect.ALWAYS` `SHA-256` download to `~/.cache/zenpak4j`; it is not redistributed with the published artifact. `zstd` (`BSD`/`GPLv2` `zstd-jni` `JNI`), `LZ4` (`BSD` `lz4-java`), `flate2` (`MIT`/`Apache` `java.util.zip`) are open.

**Test corpus is NOT covered by MIT.** The `retoc/tests/UE*` `815` fixtures are derived from games, testing-only, and remain the IP of their rights holders; `retoc/tests/NOTICE.md` (if present) documents this. They are internal test fixtures via sibling `../retoc` or `build/fixtures` download, never redistributed with the published artifact.

