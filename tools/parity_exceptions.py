#!/usr/bin/env python3
"""Shared loader for the approved parity-exception registry.

Registry: docs/parity-exceptions.json (relative to the repo root). This is the ONLY place
approved parity divergences may be recorded. Entries carry explicit approval metadata
(approved_by, approved_on, reason) and are honored by the parity tools (sweep.py,
audit_parity.py) as a distinct EXC bucket — never silently skipped.

Governance: only the user may instruct that an entry be added, modified, or revoked.
Agents must never edit this file on their own (see README.md Porting discipline). Entries that no longer
diverge are reported by --check-stale for revocation, so the list cannot quietly grow.
"""

import fnmatch
import json
import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_REGISTRY = os.path.join(REPO, "docs", "parity-exceptions.json")

REQUIRED_FIELDS = ("id", "tool", "scope", "approved_by", "approved_on", "reason", "status")
VALID_TOOLS = ("sweep", "audit", "both")
VALID_SCOPE_KINDS = ("behavior", "asset", "file", "member")
VALID_STATUSES = ("active", "revoked")

FILE = "file"
MEMBER = "member"


class ParityExceptionError(Exception):
    pass


def load_registry(path=None):
    """Load and validate the registry. Returns a list of exception dicts (active only)."""
    path = path or DEFAULT_REGISTRY
    if not os.path.exists(path):
        raise ParityExceptionError(f"parity-exception registry not found: {path}")
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        raise ParityExceptionError(f"malformed registry JSON {path}: {e}")

    if not isinstance(data, dict) or "exceptions" not in data:
        raise ParityExceptionError(f"{path}: expected an object with an 'exceptions' list")

    result = []
    seen_ids = set()
    for entry in data["exceptions"]:
        missing = [k for k in REQUIRED_FIELDS if k not in entry]
        if missing:
            raise ParityExceptionError(f"{path}: entry missing {missing}")
        if entry["id"] in seen_ids:
            raise ParityExceptionError(f"{path}: duplicate exception id {entry['id']}")
        seen_ids.add(entry["id"])
        if entry["tool"] not in VALID_TOOLS:
            raise ParityExceptionError(f"{path}: {entry['id']} invalid tool {entry['tool']!r}")
        if entry["status"] not in VALID_STATUSES:
            raise ParityExceptionError(f"{path}: {entry['id']} invalid status {entry['status']!r}")
        scope = entry["scope"]
        if not isinstance(scope, dict) or scope.get("kind") not in VALID_SCOPE_KINDS:
            raise ParityExceptionError(f"{path}: {entry['id']} invalid scope {scope!r}")
        if not scope.get("target"):
            raise ParityExceptionError(f"{path}: {entry['id']} scope.target is required")
        if entry["status"] == "active":
            result.append(entry)
    return result


def _matches(scope, target):
    kind, t = scope["kind"], scope["target"]
    if kind == "behavior":
        return False
    if kind == "file":
        return t == target
    if kind == "member":
        return t == target
    return t == target or target.startswith(t) or fnmatch.fnmatch(target, t)


def exempt(entries, tool, target):
    """Return the exception whose scope matches `target`, or None."""
    for e in entries:
        if e["tool"] in (tool, "both") and _matches(e["scope"], target):
            return e
    return None


def assert_registry_present():
    if not os.path.exists(DEFAULT_REGISTRY):
        raise ParityExceptionError(
            "missing docs/parity-exceptions.json — create it with an empty "
            "{'exceptions': []} before running parity tools"
        )


MARKER_ON_RE = re.compile(r"//@parity:on\s+(\S+)")
MARKER_OFF_RE = re.compile(r"//@parity:off\s+(\S+)")


def check_source_markers(root=REPO, registry=None):
    """Validate that every `//@parity:on/off <id>` marker in the Kotlin sources is balanced and
    references an id in the registry. Returns (errors, ids_used).
    Balanced means: every `on` has a matching `off` for the same id, no nesting of the same id,
    no interleaving of different ids.
    """
    registry = load_registry(registry) if registry is not None else load_registry()
    active_ids = {e["id"] for e in registry}
    errors = []
    ids_used = set()

    # Kotlin sources for zenpak4j: repak, retoc, oodle-loader, load-logger, actions, repak-cli, retoc-cli
    kt_roots = [
        os.path.join(root, "repak", "src", "main", "kotlin"),
        os.path.join(root, "retoc", "src", "main", "kotlin"),
        os.path.join(root, "oodle-loader", "src", "main", "kotlin"),
        os.path.join(root, "load-logger", "src", "main", "kotlin"),
        os.path.join(root, "actions", "src", "main", "kotlin"),
        os.path.join(root, "repak-cli", "src", "main", "kotlin"),
        os.path.join(root, "retoc-cli", "src", "main", "kotlin"),
    ]
    for kt_root in kt_roots:
        if not os.path.exists(kt_root):
            continue
        for dirpath, _, files in os.walk(kt_root):
            for name in files:
                if not name.endswith(".kt"):
                    continue
                p = os.path.join(dirpath, name)
                rel = os.path.relpath(p, root)
                stack = []
                with open(p, encoding="utf-8") as f:
                    for lineno, line in enumerate(f, 1):
                        on = MARKER_ON_RE.search(line)
                        off = MARKER_OFF_RE.search(line)
                        if on:
                            exc_id = on.group(1)
                            ids_used.add(exc_id)
                            if exc_id in stack:
                                errors.append(f"{rel}:{lineno}: nested //@parity:on {exc_id}")
                            stack.append(exc_id)
                        if off:
                            exc_id = off.group(1)
                            ids_used.add(exc_id)
                            if not stack or stack[-1] != exc_id:
                                errors.append(f"{rel}:{lineno}: unbalanced //@parity:off {exc_id}")
                            else:
                                stack.pop()
                if stack:
                    for exc_id in stack:
                        errors.append(f"{rel}: unbalanced //@parity:on {exc_id} (missing //@parity:off)")

    for exc_id in sorted(ids_used):
        if exc_id not in active_ids:
            errors.append(f"marker references unknown exception id: {exc_id} (not in docs/parity-exceptions.json)")
    return errors, ids_used
