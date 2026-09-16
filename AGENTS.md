# AGENTS.md — rules for AI agents and reviewers on this repo

This file is the single source of truth for agent behavior. It is written to be read before doing
any work here. The one-line summary: **the port is a statement-parallel translation of the pinned
trumank/repak@`355b5f6` + trumank/retoc@`885a8da` Rust sources — never a rewrite.** The `zenpak`
module (`ZenPakService`) and the `:actions` module are the shared in-process action layer governed
by EXC-014; they must never drift from the CLI behavior.

## Hard rules

### 1. Statement-level parity is the contract (NOT just functional parity)

- Every Rust statement, branch, call, and loop in a ported function must appear, **in the same
  order**, in the Kotlin function — translated only through [`docs/mapping.md`](docs/mapping.md).
- "Functional parity" (producing the same output) is **necessary but never sufficient**. A patch
  that changes the Rust structure — collapsing a helper, rewriting a `match` over a different value
  shape, reordering statements, merging two loops — is a **parity violation even if tests pass**.
- When the literal translation is awkward in Kotlin, the *first* resort is a new `docs/mapping.md`
  entry (so it becomes a canonical pattern), not a bespoke rewrite.
- Agents porting code must be able to point at the exact Rust statement each Kotlin statement
  mirrors (file headers cite `// Rust: <crate>/src/foo.rs:<line>`; keep them accurate).
- **This rule applies to *fixes*, not just initial ports.** When parity breaks, the fix must restore
  the Rust shape — do not "patch around" a functional failure to make tests pass.

**Verbatim rule to include in every porting/parity task prompt:**

> Statement-level parity is a hard rule. Every Rust statement must appear, in order, in the Kotlin
> port, translated only through docs/mapping.md. Functional parity is necessary but never
> sufficient — do not rewrite the Rust structure to make it work. When the literal translation is
> awkward, add a canonical mapping entry to docs/mapping.md first.

### 2. Establish the baseline before making changes

Before changing anything, record and report:

- `git status --short` and `git diff --stat` (the tree may hold uncommitted work — build on it,
  never revert it without asking).
- The failing repro output (exact test failure / CLI error).
- The reference "already passing" checks, and re-verify them after every change:
  - `./gradlew build` → green (includes detekt; `repak:test` 66 + `retoc:test` 43 per mapping §1).
  - `python3 tools/audit_parity.py` → `PARITY AUDIT: GREEN`.
  - Fixtures resolve on demand: `./gradlew downloadFixtures`, `$ZENPAK4J_FIXTURES`, or sibling
    checkouts (see *Fixtures* below). Never commit fixture files.

A fix that doesn't show its baseline (before→after) is incomplete. Regressions must be caught and
fixed before the task is considered done.

## Conventions that must not be violated

- **Keep Rust identifiers literally (`snake_case`)** for scriptable parity
  (`rg "fun read_encoded"`). Kotlin file names mirror Rust (`pak.rs` → `pak.kt`). Every ported
  file carries a `// Rust: <crate>/src/foo.rs:<line>` header.
- **Never global singletons**: Rust's `OnceLock` globals (`GAME_ID`) become `RepakContext` /
  `RetocContext` passed explicitly, or ThreadLocal-backed module state (EXC-005/EXC-007). All
  game-id reads happen on the calling thread, never inside worker pools.
- **ZenPakService + :actions are the single behavior source (EXC-014)**: the CLIs and the service
  run the exact same action-layer functions. New container features land in `:actions`, then get
  thin ZenPakService wrappers. Do not fork logic into the service or automod.
- **Exceptional regions** (`//@parity:on EXC-*` / `//@parity:off EXC-*`) must be reviewed whenever
  the wrapped file changes. Markers must be balanced, non-overlapping, and reference ids that
  exist in `docs/parity-exceptions.json`.
- **Approved parity exceptions live ONLY in `docs/parity-exceptions.json`.** Agents must never
  add, modify, revoke, or reorder an exception entry on their own — only an explicit user
  instruction may change this file. An unmarked divergence is a defect to fix at the Rust source.
- **Determinism deviations are documented deviations.** Where Rust iterates a `HashSet` with
  per-process randomized order (e.g. localized dependency injection), Kotlin iterates sorted so
  output is reproducible — same dependency set, stable bytes (EXC-011). Any new such deviation
  needs a ledger entry and in-source markers.
- **No new dependencies without explicit approval.**
- **Oodle natives are runtime-downloaded, hash-pinned** from WorkingRobot/OodleUE (EXC-002/013).
  Never commit native binaries. There is no win-arm64 artifact — `current_platform()` throws there,
  and oodle/zstd-dependent tests auto-skip via that gate (see PakTest/OodlePakTest/ZenPakServiceTest).
- **Fixtures are never committed.** Tests resolve them via `TestFixtures`/`RepakFixtures`
  (env → `build/fixtures` → sibling checkouts). After changing fixture layout handling, verify BOTH
  shapes: sibling checkout and extracted tarball (tests are crate-nested:
  `build/fixtures/<tool>/<tool>/tests`).
- **Do not commit unless the task explicitly says to.** This repo is pushed when the user says so.

## Review checklist (for reviewers / reviewers-as-agents)

Reject a ported file if ANY of:

1. A Kotlin function can't be diffed statement-by-statement against its Rust source (order, branch
   shape, call sites differ) — even if the output is byte-identical.
2. A Rust construct was solved without a corresponding `docs/mapping.md` entry (or the entry was
   added after the fact rather than before).
3. `tools/audit_parity.py` reports a new mismatch.
4. The port touches a file marked `ported` in `docs/port-tracker.md` without updating the tracker.
5. New public members don't mirror the Rust names (snake_case kept verbatim; documented exceptions
   like `Display`→`toString` aside).
6. `docs/parity-exceptions.json` was modified without an explicit user instruction for that change.
7. `//@parity:on`/`//@parity:off` markers are unbalanced, nested, duplicated, reference an unknown
   EXC id — or a divergence exists in code without markers at all.
8. **Algorithmic complexity diverges from the Rust source.** A functionally-equivalent but
   asymptotically slower Kotlin method is a parity violation. Compare hot construct pairs
   (`HashMap` lookup vs linear scan, nested vs single-pass loops) and reject rewrites that trade
   O(1)/O(n) for O(n²) per call.
9. **Container-path shape mismatches.** Paths cross three shapes in this codebase: mount-prefixed
   (`../../../SB/...`, as stored in containers), stripped (`SB/Content/...`, as delivered to
   `FileWriterTrait.write_file` and automod callbacks), and `$`-encoded (`$SB$Content$...`, TOML
   filenames). Conversions between shapes must be explicit — this bug class has bitten three times.
10. **Exceptional regions** were not reviewed when the wrapped file changed.

Acceptance bar for a porting/parity task: statement-level review done, algorithmic-complexity
review done, `tools/audit_parity.py` GREEN, `./gradlew build` green, and the relevant fixtures
round-trip byte-identically (or the target stated in the task) with no regressions.

## Campaign insights

Lessons learned while reaching full parity against the rust tools — treat as operational guidance.

- **Filter semantics differ by tool.** retoc `--filter` is a substring `contains` over package
  paths (mount-prefixed form); repak `-i` globs are near-exact. When fusing both behind one API,
  normalize to the callback/stripped form first and drop non-matches early.
- **The downloaded tarballs keep the crate dir.** Extracting trumank/<tool>.tar.gz with
  `--strip-components=1` yields `<tool>/tests/...`, i.e. fixtures live at
  `build/fixtures/<tool>/<tool>/tests`. Resolvers must try both shapes.
- **Rust HashSet iteration is randomized per process** (`add_localized_package_dependencies`).
  Iterating sorted (EXC-011) makes output reproducible; matching the pinned rust binary's bytes
  for such packages requires the same ordering upstream.
- **Windows @TempDir cleanup cannot delete open files.** Any test that opens a container must
  mirror Rust Drop with `.use { }` / `close()` (see `TocIStoreTest.test_iostore_open` and the
  `AutoCloseable` additions on `IoStoreContainer`/`IoStoreBackend`).
- **act + podman copy-in semantics**: the container does NOT bind-mount the host workspace;
  `GITHUB_WORKSPACE` names a copied path populated by earlier steps. Fixture resolution therefore
  trusts `$GITHUB_WORKSPACE/build/fixtures` explicitly. Start `podman.socket` and export
  `DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock` before running act.
- **UE4-era synthetic containers fail to-legacy silently.** Containers built by to-zen lack
  ScriptObjects / LoaderInitialLoadMeta chunks; conversion failures are swallowed per-package
  (`failed_count`) and surface later as "produced no result". Don't mistake that for a resolver
  regression.
- **Report honestly when stuck.** Prefer a report that says exactly which items are done/remaining
  and why, over a completed-sounding summary that hides a dead end. A fix that cannot point at its
  root cause in the Rust source is not done.

## Reading order (context onboarding)

1. `README.md` — overview, pinned SHAs, how the port tracks upstream.
2. `docs/mapping.md` — the Rust→Kotlin translation contract (consult before any port).
3. `docs/port-tracker.md` — dependency levels L0–L6 and per-file status.
4. `docs/parity-exceptions.json` — approved divergences (read-only for agents).
5. This file — agent rules (always).
6. `tools/audit_parity.py` — how parity is verified.
7. `scripts/download-fixtures.sh` — how fixtures arrive on demand.

## Key paths

- Upstream sources (READ-ONLY references, pinned): sibling checkouts
  `~/Repositories/repak` (@355b5f6) and `~/Repositories/retoc` (@885a8da). The
  `jpabscale/repak` and `jpabscale/retoc` forks are retired — port work lives in
  this repo, port-specific behavior is approved in `docs/parity-exceptions.json`.
- Downloaded fixture trees: `build/fixtures/{repak,retoc}/` (gitignored) — see
  `scripts/download-fixtures.sh`.
- Shared action layer: `actions/src/main/kotlin/com/github/jpabscale/zenpak4j/{repak_actions,retoc_actions}/`.
- In-process service: `zenpak/src/main/kotlin/com/github/jpabscale/zenpak4j/ZenPakService.kt`.
- CLI fat jars (release artifacts): `repak-cli/build/libs/repak-cli-*.jar`,
  `retoc-cli/build/libs/retoc-cli-*.jar`; root tasks `repakJar` / `retocJar` rename them to
  `build/libs/repak.jar` / `build/libs/retoc.jar`.
- CI: `.github/workflows/ci.yml` — matrix linux/macos amd64+arm64 + windows-amd64
  (windows-arm64 excluded: no Oodle native); tag-gated release job publishes both fat jars.
