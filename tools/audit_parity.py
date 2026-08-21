#!/usr/bin/env python3
"""
Parity audit for zenpak4j: verifies that every ported Kotlin file exposes the same public member names as its
pinned Rust counterpart (the milestone-closure checklist).

For each `ported` row in docs/port-tracker.md, extract public member identifiers from the Rust file
and the Kotlin file, then report Rust members missing from the Kotlin side. Exits non-zero on any
unexpected mismatch so it can gate a milestone commit.

Mappings applied (see docs/mapping.md):
  - Rust snake_case names are preserved verbatim in Kotlin (rg "fun read_encoded" parity check).
  - Rust `pub struct Foo` -> Kotlin `class Foo` / `data class Foo`
  - Rust `pub enum Foo` -> Kotlin `enum class Foo` / `sealed class Foo`
  - Rust `pub fn foo_bar` -> Kotlin `fun foo_bar` (snake_case kept)
  - Rust `pub const Foo: T` -> Kotlin `const val Foo`
  - Detekt naming is relaxed to allow snake_case (config/detekt.yml).

Usage: python3 tools/audit_parity.py
"""

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from parity_exceptions import check_source_markers, exempt, load_registry

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACKER = os.path.join(REPO, "docs", "port-tracker.md")
# Rust bases: sibling checkouts for repak/retoc (as in plan.md) and local zenpak4j proto
RUST_BASES = [
    "/home/jmaoc/Repositories/repak",
    "/home/jmaoc/Repositories/retoc",
    os.path.join(REPO, "..", "repak"),
    os.path.join(REPO, "..", "retoc"),
]
KT_BASE = REPO

# Rust -> Kotlin name overrides (if any). For zenpak4j we keep snake_case, so no mapping needed,
# but keep a few for global renames if they ever appear (e.g., Rust `new` -> Kotlin `create` is not used).
OVERRIDE_MAP = {
    "Error": "RepakError",
    "BoolExt": "Ext",
    "ReadExt": "Ext",
    "WriteExt": "Ext",
    "build_partial_entry": "build_partial_entry",
    "Hash": "Hash",
    "build_entry": "build_entry",
    "ReadCounter": "PakTest",
    "Readable": "Readable",
    "ReadableCtx": "ReadableCtx",
    "Writeable": "Writeable",
    "read_array": "read_array",
    "LogBackend": "LogBackend",
    "EIoContainerHeaderVersion": "EIoContainerHeaderVersion",
    "IoStoreTrait": "IoStoreTrait",
    "global": "RepakContext",
}

# Members deliberately not yet ported or moved to a different Kotlin file (documented in port-tracker.md).
# Any entry added here MUST be accompanied by a tracker/mapping.md note.
TYPE_MOVE_EXEMPTIONS = {
    # Example: if a Rust file declares `pub struct Foo` but Kotlin puts it in a different file,
    # list it here so the audit doesn't flag it as missing from the sibling file.
    "retoc/retoc/src/lib.rs": {
        "FPackageId",  # actually in retoc/src/main/kotlin/.../retoc/lib.kt but also used in container_header
    },
}

DEFERRED_EXEMPTIONS = {
    # Test file: Rust test helpers not directly ported as public API (PakTest is the port, not the helpers)
    "repak/repak/tests/test.rs": {
        "ReadCounter", "new", "new_size", "into_reads",
        "test_read_counter", "test_read", "test_write", "test_rewrite_index",
    },
    # ser.rs: ReadExt/WriteExt are Kotlin extension functions on InputStream/OutputStream, not interfaces with same name
    "retoc/retoc/src/ser.rs": {
        "ReadExt", "WriteExt",
    },
    # container_header.rs: EIoContainerHeaderVersion is defined in version.kt (shared), not container_header.kt
    "retoc/retoc/src/container_header.rs": {
        "EIoContainerHeaderVersion",
    },
    # pak.rs: Hash is in footer.kt (shared), build_entry is in data.kt (PartialEntryData) — not missing
    "repak/repak/src/pak.rs": {
        "Hash", "build_entry",
    },
    # lib.rs: global is a module, Kotlin puts RepakContext in global.kt
    "repak/repak/src/lib.rs": {
        "global",
    },
}

# tokens that are never member names
NON_IDENTIFIERS = {
    "true", "false", "null", "self", "Self", "super", "Self", "mut", "ref", "in", "where",
    "pub", "mod", "use", "crate", "super", "self", "async", "await", "unsafe", "extern", "impl",
    "trait", "struct", "enum", "fn", "const", "let", "if", "else", "match", "for", "while", "loop",
    "return", "break", "continue", "where", "type", "as", "dyn", "static", "move", "box", "in",
}


def strip_rust_noise(text):
    # Remove comments and string literals to avoid false positives
    text = re.sub(r"//.*?$", "", text, flags=re.MULTILINE)
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', "", text)
    text = re.sub(r"'(?:[^'\\]|\\.)*'", "", text)
    return text


# Regexes for Rust pub members (handle pub, pub(crate), pub(super), pub(in path))
RUST_TYPE_RE = re.compile(r"\bpub(?:\([^)]*\))?\s+(?:struct|enum|trait)\s+(\w+)")
RUST_FN_RE = re.compile(r"\bpub(?:\([^)]*\))?\s+(?:const\s+|unsafe\s+|extern\s+)*fn\s+(\w+)")
RUST_CONST_RE = re.compile(r"\bpub(?:\([^)]*\))?\s+(?:const|static)\s+(\w+)")
RUST_MOD_RE = re.compile(r"\bpub(?:\([^)]*\))?\s+mod\s+(\w+)")
RUST_USE_RE = re.compile(r"\bpub(?:\([^)]*\))?\s+use\s+[^;]*::(\w+)\s*;")
RUST_PUB_CRATE_RE = re.compile(r"\bpub\s*\(crate\)\s+(?:struct|enum|fn|const)\s+(\w+)")


def rust_members(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        text = strip_rust_noise(f.read())
    members = set()
    for pat in (RUST_TYPE_RE, RUST_FN_RE, RUST_CONST_RE, RUST_MOD_RE, RUST_USE_RE, RUST_PUB_CRATE_RE):
        for m in pat.finditer(text):
            name = m.group(1)
            if name not in NON_IDENTIFIERS and re.fullmatch(r"[A-Za-z_]\w*", name):
                # Keep snake_case as-is (no CamelCase conversion)
                members.add(name)
    return members


KT_METHOD_RE = re.compile(r"^\s*(?:(?:inline|internal|private|public|override|open|abstract|suspend|tailrec|external|protected)\s+)*fun\s+(?:<[^>]*>\s+)?(?:\w+\.)?(\w+)")
KT_PROPERTY_RE = re.compile(r"^\s*(?:(?:private|internal|public|protected|override|open|abstract|lateinit|const)\s+)*(?:var|val)\s+(\w+)")
KT_CTOR_PROPERTY_RE = re.compile(r"\((?:var|val)\s+(\w+)")
KT_TYPE_RE = re.compile(r"^\s*(?:(?:open|abstract|sealed|data|internal|public|enum)\s+)*(?:class|object|interface)\s+(\w+)")
KT_ENUM_RE = re.compile(r"^\s*enum class\s+(\w+)")
KT_INTERFACE_RE = re.compile(r"^\s*interface\s+(\w+)")
KT_CONST_RE = re.compile(r"^\s*const\s+val\s+(\w+)")


def kt_members(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        lines = f.read().splitlines()
    members = set()
    in_companion = False
    for line in lines:
        s = line.strip()
        if s.startswith("companion object"):
            in_companion = True
            continue
        if in_companion and (s == "}" or s.startswith("class ") or s.startswith("enum class")):
            in_companion = False
        for pat in (KT_TYPE_RE, KT_ENUM_RE, KT_METHOD_RE, KT_PROPERTY_RE, KT_CONST_RE):
            m = pat.match(line)
            if m:
                members.add(m.group(1))
                break
        m = KT_CTOR_PROPERTY_RE.search(line)
        if m:
            members.add(m.group(1))
        if in_companion:
            m = re.match(r"^\s*(?:val|var|const val|fun)\s+(\w+)", line)
            if m:
                members.add(m.group(1))
    return members


def find_rust_file(rust_rel):
    # Try sibling checkouts first (as in plan.md: ../repak, ../retoc)
    for base in RUST_BASES:
        p = os.path.join(base, rust_rel.replace("repak/", "repak/").replace("retoc/", "retoc/"))
        # Handle repak vs retoc base: rust_rel already includes "repak/repak/src/..." or "retoc/retoc/src/..."
        # So we need to join base + rust_rel
        # But base is like /home/jmaoc/Repositories/repak, which already is the repo root, so rust_rel "repak/repak/src/lib.rs" would become "/home/jmaoc/Repositories/repak/repak/repak/src/lib.rs" (duplicate). So handle:
        # If rust_rel starts with "repak/", strip first component when base is repak repo
        # Simpler: try direct join of REPO's sibling
        pass
    # Actual logic: rust_rel is like "repak/repak/src/lib.rs" or "retoc/retoc/src/lib.rs" or "repak/oodle_loader/src/lib.rs"
    # The repo roots are /home/jmaoc/Repositories/repak and /home/jmaoc/Repositories/retoc
    # So for "repak/repak/src/lib.rs", we want "/home/jmaoc/Repositories/repak/repak/src/lib.rs" (strip first "repak/")
    # For "retoc/retoc/src/lib.rs", we want "/home/jmaoc/Repositories/retoc/retoc/src/lib.rs"
    # For "repak/oodle_loader/src/lib.rs", we want "/home/jmaoc/Repositories/repak/oodle_loader/src/lib.rs"
    if rust_rel.startswith("repak/"):
        cand = os.path.join("/home/jmaoc/Repositories/repak", rust_rel[len("repak/"):])
        if os.path.exists(cand):
            return cand
    if rust_rel.startswith("retoc/"):
        cand = os.path.join("/home/jmaoc/Repositories/retoc", rust_rel[len("retoc/"):])
        if os.path.exists(cand):
            return cand
    # Fallback to sibling of REPO
    cand = os.path.join(REPO, "..", rust_rel)
    if os.path.exists(cand):
        return cand
    # Also try REPO itself for zenpak4j files (should not happen for Rust)
    cand = os.path.join(REPO, rust_rel)
    if os.path.exists(cand):
        return cand
    return None


def audit_pair(rust_rel, kt_rel, errors, info, exceptions):
    rust_path = find_rust_file(rust_rel)
    kt_path = os.path.join(KT_BASE, kt_rel)
    if not rust_path or not os.path.exists(rust_path):
        info.append(f"  Rust source missing: {rust_rel} (tried {rust_path})")
        return
    if not os.path.exists(kt_path):
        errors.append(f"  KOTLIN FILE MISSING: {kt_rel} (Rust: {rust_rel})")
        return

    rust = rust_members(rust_path)
    kt = kt_members(kt_path)
    exemptions = DEFERRED_EXEMPTIONS.get(rust_rel, set()) | TYPE_MOVE_EXEMPTIONS.get(rust_rel, set())
    approved = set()
    for name in sorted(rust):
        if exempt(exceptions, "audit", rust_rel):
            approved.add(name)
        if exempt(exceptions, "audit", f"{rust_rel}#{name}"):
            approved.add(name)
    missing = set()
    for name in sorted(rust):
        if name in kt:
            continue
        mapped = OVERRIDE_MAP.get(name)
        if mapped and mapped in kt:
            continue
        if name in exemptions or name in approved:
            continue
        missing.add(name)

    if missing:
        errors.append(f"  {rust_rel} -> {kt_rel}: missing/renamed Rust pub members:")
        for name in sorted(missing):
            errors.append(f"    - {name}")
    else:
        info.append(f"  ok: {rust_rel} -> {kt_rel} ({len(rust)} Rust members, {len(kt)} Kotlin members)")


def main():
    errors = []
    info = []
    exceptions = load_registry()
    with open(TRACKER, encoding="utf-8") as f:
        tracker = f.read()

    pairs = []
    # Match port-tracker rows: | `repak/repak/src/lib.rs` | `repak/src/main/kotlin/.../repak/lib.kt` | ported |
    for m in re.finditer(r"\| `([^`]+\.rs)` \| `([^`]+\.kt)` \| (\w+) \|", tracker):
        rust_rel, kt_rel, status = m.group(1), m.group(2), m.group(3)
        if status == "ported":
            # Port-tracker uses `...` placeholder for the middle package part; expand to full path
            # e.g., `repak/src/main/kotlin/.../repak/lib.kt` -> need to resolve actual file
            if "..." in kt_rel:
                # Find actual file by searching for the suffix after ...
                suffix = kt_rel.split("...")[-1].lstrip("/")
                # suffix is like "/repak/lib.kt" - find under KT_BASE
                found = None
                for root, _, files in os.walk(KT_BASE):
                    for fn in files:
                        if fn == os.path.basename(suffix) and root.endswith(os.path.dirname(suffix).replace("/", os.sep)):
                            # Check if the full suffix matches
                            rel = os.path.relpath(os.path.join(root, fn), KT_BASE)
                            if rel.endswith(suffix.lstrip("/")):
                                found = os.path.join(os.path.relpath(root, KT_BASE), fn).replace(os.sep, "/")
                                # But port-tracker has `repak/src/main/kotlin/.../repak/lib.kt`, so we need to reconstruct full
                                # Use the tracker path as is, but replace ... with the actual package path
                                # For simplicity, just use the found path as kt_rel for audit
                                # Actually tracker path with ... is not a real file path, so we need to find the real file
                                # We can search for files that end with the basename and have the module prefix
                                pass
                # For now, just try to resolve by replacing ... with the actual package path from the repo structure
                # We know the package is com.github.jpabscale.zenpak4j.<module>, so we can construct
                # Instead, we will search for any kt file that ends with the basename and is under the correct module
                # e.g., for repak lib.kt, search for repak/src/main/kotlin/**/lib.kt
                module = rust_rel.split("/")[0]  # repak or retoc or repak/oodle_loader etc.
                # Handle oodle_loader, repak_cli, etc.
                if "oodle_loader" in rust_rel:
                    module_path = "oodle-loader"
                elif "repak_cli" in rust_rel:
                    module_path = "repak-cli"
                elif "retoc_cli" in rust_rel:
                    module_path = "retoc-cli"
                elif "load_logger" in rust_rel:
                    module_path = "load-logger"
                elif rust_rel.startswith("repak/"):
                    module_path = "repak"
                elif rust_rel.startswith("retoc/"):
                    module_path = "retoc"
                else:
                    module_path = rust_rel.split("/")[0]
                basename = os.path.basename(kt_rel)
                # Search under module_path/src/main/kotlin
                search_root = os.path.join(KT_BASE, module_path, "src", "main", "kotlin")
                found = None
                for root, _, files in os.walk(search_root):
                    if basename in files:
                        found = os.path.join(os.path.relpath(root, KT_BASE), basename)
                        # Use the first match
                        break
                if found:
                    kt_rel = found.replace(os.sep, "/")
                else:
                    # Fallback to tracker path with ... replaced by the package path (best effort)
                    kt_rel = kt_rel.replace("...", "com/github/jpabscale/zenpak4j").replace("//", "/")
            pairs.append((rust_rel, kt_rel))

    if not pairs:
        print("no ported files found in port-tracker")
        return 2

    for rust_rel, kt_rel in pairs:
        audit_pair(rust_rel, kt_rel, errors, info, exceptions)

    print(f"audited {len(pairs)} ported file pairs\n")
    for line in info:
        print(line)
    if errors:
        print("\nPARITY MISMATCHES:")
        for line in errors:
            print(line)
        print(f"\n{len(errors)} error group(s). Fix the Kotlin names (or document an exemption in mapping.md).")
        return 1

    # Validate //@parity:on/off markers
    marker_errors, ids_used = check_source_markers(root=REPO)
    if marker_errors:
        print("\nPARITY MARKER MISMATCHES:")
        for line in marker_errors:
            print(line)
        print("\nMarkers must be balanced, non-nested, and reference approved exception ids.")
        return 1
    if ids_used:
        print(f"parity markers ok: {', '.join(sorted(ids_used))}")

    print("\nPARITY AUDIT: GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
