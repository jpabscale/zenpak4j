# Port tracker — repak@355b5f6 + retoc@885a8da → zenpak4j

Tracks each upstream Rust file to its Kotlin port and tests. Status: **not ported** | **stub** (skeleton, throws for unported paths) | **ported** (statement-parallel, may defer downstream deps).

> The `oodle-loader` and `load-logger` Windows `ProxyDll` are FFM ports, not `repak`/`retoc` Rust ports but tracked here for completeness. See `docs/mapping.md` §FFM.

## Port tree (dependency levels, ported bottom-up)

Deploy agents at the leaves of a level in parallel; **before moving up a level, resolve any mapping divergence** the agents introduced into `docs/mapping.md` (canonical mapping — agents must follow it and report every construct they mapped that wasn't already there). Higher-level code is stubbed as a compile-time contract first, so every level builds green.

| Lvl | Files | Depends on | Status |
|---|---|---|---|
| 0 | `repak` `Version`/`VersionMajor`/`Compression`/`MAGIC`/`Key`, `RepakContext`/`RetocContext`, `Error`/`RepakError`, `ext`/`ser` (`ReadExt`/`WriteExt` LE), `crc`, `global` | — | **ported** (`repak/lib.kt`, `repak/error.kt`, `repak/ext.kt`, `retoc/ser.kt`, `retoc/crc.kt`, `retoc/global.kt`) |
| 1 | `repak` `Footer`/`data`/`entry` (`read_encoded` bitfield `0x3f` + `align16`), `retoc` `name_map` (`CityHash` `lower_utf16`), `file_pool` (`ReentrantLock`/`Condition`), `version` (`EngineVersion` `UE4_25`→`UE5_7`), `compression` (`Zlib`/`Zstd`/`LZ4`/`Oodle` FFM) | L0 | **ported** (`repak/footer.kt`, `repak/data.kt`, `repak/entry.kt`, `retoc/name_map.kt`, `retoc/file_pool.kt`, `retoc/version.kt`, `retoc/compression.kt`) |
| 2 | `repak` `Pak` (`PHI`/`FDI` `i32::MIN`, `fnv64_path` `UTF16LE`), `oodle_loader` (`Linker`/`Arena` 5 platforms), `retoc` `manifest`/`verse_vm_types`/`script_objects`/`compact_binary` (`Ctx` counting, `varint` LEB) | L1 + `RepakContext`/`RetocContext` | **ported** (`repak/pak.kt` + `oodle_loader/lib.kt` `FFM`, `retoc/manifest.kt`, `retoc/verse_vm_types.kt`, `retoc/script_objects.kt`, `retoc/compact_binary.kt` `22522` entries) |
| 3 | `retoc` `container_header` (`StoreEntries` `member_offset`/`entry_size` + `SoftPackageReferencesOffset` patch), `lib` `Toc` (`5`-byte `BE` `FIoOffsetAndLength`, `FIoChunkId` `1<<6\|is_new<<7`), `iostore` (`Backend` `sort_container_name`), `logging`, `version_heuristics` (candidate list) | L2 | **ported** (`retoc/container_header.kt` `5` fixtures `104740` exact, `retoc/lib.kt` `Toc` `8` tests, `retoc/iostore.kt` `IStore` `3` tests, `retoc/logging.kt`, `retoc/version_heuristics.kt` `heuristic_package_version_from_legacy_package`) |
| 4 | `retoc` `zen` (`FZenPackageHeader`, `SeekableByteArray*` `header_size` patch), `legacy_asset` (`FLegacyPackageHeader` `0x9E2A83C1`), `iostore_writer` (`0x10000` `blake3` `BouncyCastle`), `shader_library` (`Global`/`NuclearNightmare` archive `layout_write`), `asset_registry` (`8` tests `UE4.22` `43s`) | L3 | **ported** (`retoc/zen.kt` `SPR_UI_Battle`, `retoc/legacy_asset.kt` `5` fixtures, `retoc/iostore_writer.kt` `blake3`, `retoc/shader_library.kt` `6` tests, `retoc/asset_registry.kt` `HashMap` fast) |
| 5 | `retoc` `asset_conversion`↔`zen_asset_conversion` (`KeyMutex` outer recursion) | L4 | **ported** (`retoc/asset_conversion.kt` `4` tests (`Randy`/`BP_Table_Lamp` + `2` `EXC-015` regressions), `retoc/zen_asset_conversion.kt` `10` fixtures `UE5.4`/`5.5`/`5.6`) |
| 6 | `repak-cli` (`Clikt` 6 commands `info`/`list`/`hash-list`/`unpack`/`pack`/`get` `Channel(0)` rendezvous), `retoc-cli` (`12` subcommands `manifest`/`to-zen`/`to-legacy` etc., `Config` `aes_keys`), `load-logger` (`ProxyDll` `FFM` no-op) + fat jars `repak.jar`/`retoc.jar` | L5 | **ported** (`repak-cli/main.kt` `15M` `repak.jar`, `retoc-cli/main.kt` `24M` `retoc.jar`, `load-logger` `FFM`) |

## M1 — repak foundation (done)

| Rust file | Kotlin file | Status | Tests |
|---|---|---|---|
| `repak/repak/src/lib.rs` | `repak/src/main/kotlin/.../repak/lib.kt` | ported | `Version::size` table, `version_major` |
| `repak/repak/src/global.rs` | `repak/src/main/kotlin/.../repak/global.kt` | ported (`EXC-001`, `EXC-005`) | `RepakContext` instance |
| `repak/repak/src/error.rs` | `repak/src/main/kotlin/.../repak/error.kt` | ported | `RepakError` sealed `24` variants |
| `repak/repak/src/ext.rs` | `repak/src/main/kotlin/.../repak/ext.kt` | ported | `read_string` `LE` `ascii`/`UTF16`, `read_bool` |
| `repak/repak/src/footer.rs` | `repak/src/main/kotlin/.../repak/footer.kt` | ported | `Footer` `32`-byte pad |
| `repak/repak/src/data.rs` | `repak/src/main/kotlin/.../repak/data.kt` | ported | `PartialEntry` `0x1F000` `compress` |
| `repak/repak/src/entry.rs` | `repak/src/main/kotlin/.../repak/entry.kt` | ported (`EXC-005`) | `read_encoded` bitfield `23/22/6` |
| `repak/repak/src/pak.rs` | `repak/src/main/kotlin/.../repak/pak.kt` | ported | `Pak::read` `PHI`/`FDI` `fnv64_path` |
| `repak/oodle_loader/src/lib.rs` | `oodle-loader/src/main/kotlin/.../oodle_loader/lib.kt` | ported (FFM) | `5` platforms `ed7e...` `OodleTest` `393→395` |
| `repak/repak/tests/test.rs` | `repak/src/test/kotlin/.../repak/PakTest.kt` | ported | `48` read + `write`/`rewrite` + `roundtrip` `oodle` `300 KiB` |

## M2 — retoc primitives (done)

| Rust file | Kotlin file | Status | Tests |
|---|---|---|---|
| `retoc/retoc/src/ser.rs` | `retoc/src/main/kotlin/.../retoc/ser.kt` | ported | `Readable`/`Writeable` `LE` `read_string_data` |
| `retoc/retoc/src/crc.rs` | `retoc/src/main/kotlin/.../retoc/crc.kt` | ported | `calc_crc` `hash_deprecated` |
| `retoc/retoc/src/name_map.rs` | `retoc/src/main/kotlin/.../retoc/name_map.kt` | ported (`EXC-008`) | `CityHash` `FNameMap` |
| `retoc/retoc/src/file_pool.rs` | `retoc/src/main/kotlin/.../retoc/file_pool.kt` | ported | `FilePool` `ReentrantLock` |
| `retoc/retoc/src/global.rs` | `retoc/src/main/kotlin/.../retoc/global.kt` | ported (`EXC-001`, `EXC-007`) | `RetocContext` |
| `retoc/retoc/src/logging.rs` | `retoc/src/main/kotlin/.../retoc/logging.kt` | ported (`EXC-016`) | `Log` `Stdout`/`Noop`/`PrintStream` |
| `retoc/retoc/src/version.rs` | `retoc/src/main/kotlin/.../retoc/version.kt` | ported | `EngineVersion` `UE4_25`→`UE5_7` |
| `retoc/retoc/src/compression.rs` | `retoc/src/main/kotlin/.../retoc/compression.kt` | ported | `Zlib`/`Zstd`/`LZ4`/`Oodle` |
| `retoc/retoc/src/manifest.rs` | `retoc/src/main/kotlin/.../retoc/manifest.kt` | ported | `PackageStoreManifest` |
| `retoc/retoc/src/verse_vm_types.rs` | `retoc/src/main/kotlin/.../retoc/verse_vm_types.kt` | ported | `VPackage` `VValue` |

## M3 — retoc iostore layer (done)

| Rust file | Kotlin file | Status | Tests |
|---|---|---|---|
| `retoc/retoc/src/container_header.rs` | `retoc/src/main/kotlin/.../retoc/container_header.kt` | ported (`EXC-007`) | `5` fixtures `104740` exact (`StoreEntries` `16 B` fix) |
| `retoc/retoc/src/lib.rs` (Toc) | `retoc/src/main/kotlin/.../retoc/lib.kt` | ported | `Toc` `8` (`package_id`, `directory_index` `BFS`, `TocTest` `Encrypted`/`Multiblock`) |
| `retoc/retoc/src/iostore.rs` | `retoc/src/main/kotlin/.../retoc/iostore.kt` | ported (`EXC-009`, `EXC-016`) | `IStore` `3` (`sort_container`) |
| `retoc/retoc/src/iostore_writer.rs` | `retoc/src/main/kotlin/.../retoc/iostore_writer.kt` | ported (`EXC-010`) | `0x10000` `blake3` `finalize` |
| `retoc/retoc/src/version_heuristics.rs` | `retoc/src/main/kotlin/.../retoc/version_heuristics.kt` | ported | `heuristic_package_version_from_legacy_package` candidate list |

## M4 — retoc heavy (done)

| Rust file | Kotlin file | Status | Tests |
|---|---|---|---|
| `retoc/retoc/src/zen.rs` | `retoc/src/main/kotlin/.../retoc/zen.kt` | ported | `SPR_UI_Battle` |
| `retoc/retoc/src/legacy_asset.rs` | `retoc/src/main/kotlin/.../retoc/legacy_asset.kt` | ported | `5` fixtures |
| `retoc/retoc/src/asset_registry.rs` | `retoc/src/main/kotlin/.../retoc/asset_registry.kt` | ported | `8` (`UE4.22` `43s` `149k` names) |
| `retoc/retoc/src/compact_binary.rs` | `retoc/src/main/kotlin/.../retoc/compact_binary.kt` | ported | `22522` `packagestore.manifest` |
| `retoc/retoc/src/script_objects.rs` | `retoc/src/main/kotlin/.../retoc/script_objects.kt` | ported (`EXC-016`) | `2` (`ScriptObjects` `30977`/`23393`) |
| `retoc/retoc/src/shader_library.rs` | `retoc/src/main/kotlin/.../retoc/shader_library.kt` | ported | `6` (`Global`/`NuclearNightmare`) |
| `retoc/retoc/src/zen_asset_conversion.rs` | `retoc/src/main/kotlin/.../retoc/zen_asset_conversion.kt` | ported (`EXC-011`) | `10` fixtures `UE5.4`/`5.5`/`5.6` |
| `retoc/retoc/src/asset_conversion.rs` | `retoc/src/main/kotlin/.../retoc/asset_conversion.kt` | ported (`EXC-015`) | `4` (`Randy`/`BP_Table_Lamp` + `2` `EXC-015` regressions) |

## M5 — CLIs + fat jars (done)

| Rust file | Kotlin file | Status | Tests |
|---|---|---|---|
| `repak/repak_cli/src/main.rs` | `actions/src/main/kotlin/com/github/jpabscale/zenpak4j/repak_actions/RepakActions.kt` | ported (`EXC-005`, `EXC-006`, `EXC-014`, `EXC-016`) | action layer in `:actions` (shared with `:zenpak`); Clikt surface in `repak-cli/main.kt` |
| `retoc/retoc_cli/src/main.rs` | `actions/src/main/kotlin/com/github/jpabscale/zenpak4j/retoc_actions/RetocActions.kt` | ported (`EXC-011`, `EXC-012`, `EXC-014`, `EXC-016`) | action layer in `:actions` (shared with `:zenpak`); Clikt surface in `retoc-cli/main.kt` |
| `retoc/load_logger/src/lib.rs` | `load-logger/src/main/kotlin/.../load_logger/lib.kt` | ported (FFM no-op) | `hook` stub |

## Re-generation

- `scripts/pin-check.sh` ← `gradle/libs.versions.toml` `repak-upstream-sha`/`retoc-upstream-sha` (`git ls-remote` `trumank/repak`/`retoc` `master`)
- `scripts/download-fixtures.sh` ← `gradle/libs.versions.toml` `repak-upstream-sha`/`retoc-upstream-sha` (`raw.githubusercontent.com` or sibling `../repak`/`../retoc`)
- `gradle/libs.versions.toml` `repak-upstream-sha`/`retoc-upstream-sha` (must match `README` pins); the `jpabscale/*` forks are retired

## Tooling

- `tools/audit_parity.py` (future) — audits every file marked `ported` in this tracker: every Rust `pub` member must exist on Kotlin side. Green at milestone close. See `uasset4j` `tools/audit_parity.py` for the reference implementation (same `rg` `pub` extraction, `Kotlin` `fun`/`class`/`val` check).
- `tools/sweep.py` analog — runs JVM `repak.jar`/`retoc.jar` vs Rust `repak`/`retoc` `info`/`list`/`unpack`/`to-zen` on every `*.pak`/`*.utoc` and byte-compares output (future, mirrors `uasset4j` `tools/sweep.py`).

