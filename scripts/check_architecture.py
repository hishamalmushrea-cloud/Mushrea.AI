#!/usr/bin/env python3
"""Enforce the layer dependency rules of Mushrea Code.

Mushrea Code is a single Gradle module, so nothing at the build level stops a
foundation class from importing a feature screen. This script is that missing
guard rail: it parses every production Kotlin source file, derives the
package->package dependency graph and fails when an import points "upwards"
against the documented layer order.

Layer order (bottom -> top), see docs/architecture/ARCHITECTURE.md:

    core  <  data  <  runtime  <  device  <  feature  <  ui

Rules:
  * a layer may depend on the layers below it (and on itself);
  * a layer must not depend on the layers above it;
  * the presentation toolkit (ui.theme, ui.components, ui.ViewModelFactory,
    ui.runtimeAgentIcon, ui.runtimeTargetLabel) counts as foundation and is
    importable from any layer -- features legitimately render with it;
  * the composition root (com.mushrea.code root files, `di`, `startup`) is
    exempt because its whole job is wiring layers together.

Known, deliberate exceptions are listed in EXCEPTIONS below. Each one names the
phase that is scheduled to remove it, so the list can only shrink by design and
never grows silently.

Usage:
    python3 scripts/check_architecture.py             # fail on violations
    python3 scripts/check_architecture.py --matrix    # also print full matrix
    python3 scripts/check_architecture.py --tests     # include unit tests too
"""

from __future__ import annotations

import argparse
import collections
import os
import re
import sys
from dataclasses import dataclass
from typing import Iterable

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN_ROOT = os.path.join(REPO_ROOT, "app", "src", "main", "java")
TEST_ROOT = os.path.join(REPO_ROOT, "app", "src", "test", "java")

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.M)
IMPORT_RE = re.compile(r"^\s*import\s+(com\.mushrea\.code\.[\w.]+)", re.M)

BASE_PACKAGE = "com.mushrea.code"
LAYERS = ["core", "data", "runtime", "device", "feature", "ui"]
EXEMPT_LAYERS = {"di", "startup", "(root)"}

# Layers every other layer may import freely (the presentation toolkit).
UI_FOUNDATION = {
    "com.mushrea.code.ui.theme",
    "com.mushrea.code.ui.components",
    "com.mushrea.code.ui.ViewModelFactory",
    "com.mushrea.code.ui.runtimeAgentIcon",
    "com.mushrea.code.ui.runtimeTargetLabel",
}

FORBIDDEN = {
    "core": {"data", "runtime", "device", "feature", "ui"},
    "data": {"runtime", "device", "feature", "ui"},
    "runtime": {"device", "feature", "ui"},
    "device": {"feature", "ui"},
    "feature": {"ui"},
}


@dataclass(frozen=True)
class Exception_:
    """A dependency that is known to violate the rules today and is scheduled."""

    from_layer: str
    to_layer: str
    path_contains: str
    import_prefix: str
    reason: str
    phase: str


EXCEPTIONS: list[Exception_] = [
    Exception_(
        from_layer="core",
        to_layer="data",
        path_contains="core/locale/AppLanguage.kt",
        import_prefix="com.mushrea.code.data.connection.SecureSettingsRepository",
        reason="language preference is read straight from the encrypted store instead of a core port",
        phase="Phase 13 (Network/Remote + settings ports)",
    ),
    Exception_(
        from_layer="core",
        to_layer="runtime",
        path_contains="core/notification/PermissionActionReceiver.kt",
        import_prefix="com.mushrea.code.runtime.PermissionResponse",
        reason="permission decision model must move into the Permission & Safety Center",
        phase="Phase 5 (Permission & Safety Center)",
    ),
    Exception_(
        from_layer="core",
        to_layer="runtime",
        path_contains="core/notification/RuntimeNotificationHelper.kt",
        import_prefix="com.mushrea.code.runtime.PermissionResponse",
        reason="same permission decision model, read while building the notification action",
        phase="Phase 5 (Permission & Safety Center)",
    ),
    Exception_(
        from_layer="data",
        to_layer="feature",
        path_contains="data/settings/AppPreferencesRepository.kt",
        import_prefix="com.mushrea.code.feature.assistant.TtsTuning",
        reason="TtsTuning constructs TTSProviderConfig, which still lives in the assistant feature",
        phase="Phase 12 (Voice contracts extraction)",
    ),
    Exception_(
        from_layer="device",
        to_layer="feature",
        path_contains="device/call/CallAgentService.kt",
        import_prefix="com.mushrea.code.feature.assistant.",
        reason="call agent drives the assistant speech/TTS managers directly instead of a voice port",
        phase="Phase 12 (Voice contracts extraction)",
    ),
    Exception_(
        from_layer="runtime",
        to_layer="feature",
        path_contains="runtime/local/ClaudeWorkspaceFiles.kt",
        import_prefix="com.mushrea.code.feature.workspace.WorkspaceFolders",
        reason="WorkspaceFolders must move to core/workspace together with runtime.WorkspaceRef",
        phase="Phase 3 (Agent lifecycle unification)",
    ),
]


def layer_of(package: str) -> str:
    parts = package.split(".")
    if len(parts) <= len(BASE_PACKAGE.split(".")):
        return "(root)"
    return parts[len(BASE_PACKAGE.split("."))]


def sub_package(package: str, depth: int) -> str:
    parts = package.split(".")
    return ".".join(parts[len(BASE_PACKAGE.split(".")):depth])


def is_ui_foundation(package: str) -> bool:
    return any(package == prefix or package.startswith(prefix + ".") for prefix in UI_FOUNDATION)


def iter_sources(roots: Iterable[str]) -> Iterable[str]:
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in sorted(filenames):
                if name.endswith(".kt"):
                    yield os.path.join(dirpath, name)


def matches_exception(exc: Exception_, from_layer: str, to_layer: str, path: str, imported: str) -> bool:
    return (
        exc.from_layer == from_layer
        and exc.to_layer == to_layer
        and exc.path_contains in path
        and imported.startswith(exc.import_prefix)
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--matrix", action="store_true", help="print the full layer dependency matrix")
    parser.add_argument("--tests", action="store_true", help="scan unit tests as well (they may import anything)")
    args = parser.parse_args()

    roots = [MAIN_ROOT] + ([TEST_ROOT] if args.tests else [])
    matrix: collections.Counter = collections.Counter()
    violations: list[str] = []
    accepted: list[tuple[Exception_, str]] = []
    files_scanned = 0

    for path in iter_sources(roots):
        files_scanned += 1
        with open(path, encoding="utf-8") as handle:
            source = handle.read()
        package_match = PACKAGE_RE.search(source)
        if not package_match:
            continue
        source_package = package_match.group(1)
        from_layer = layer_of(source_package)
        for line_number, line in enumerate(source.splitlines(), 1):
            import_match = IMPORT_RE.match(line)
            if not import_match:
                continue
            imported = import_match.group(1)
            to_layer = layer_of(imported)
            matrix[(from_layer, to_layer)] += 1

            if from_layer in EXEMPT_LAYERS or to_layer == from_layer:
                continue
            if to_layer in EXEMPT_LAYERS:
                continue
            if from_layer not in FORBIDDEN or to_layer not in FORBIDDEN[from_layer]:
                continue
            if to_layer == "ui" and is_ui_foundation(imported):
                continue

            relative = os.path.relpath(path, REPO_ROOT)
            exception = next(
                (e for e in EXCEPTIONS if matches_exception(e, from_layer, to_layer, relative, imported)),
                None,
            )
            if exception is not None:
                accepted.append((exception, f"{relative}:{line_number} -> {imported}"))
                continue
            violations.append(f"{relative}:{line_number}: {from_layer} -> {to_layer}: import {imported}")

    print("Architecture check - Mushrea Code")
    print(f"  sources scanned : {files_scanned}")
    print(f"  layer order     : {' < '.join(LAYERS)}")
    print(f"  exempt layers   : {', '.join(sorted(EXEMPT_LAYERS))}")

    if args.matrix:
        header = "".join(f"{layer:>9}" for layer in LAYERS + ["di", "startup", "(root)"])
        print("\n  dependency matrix (rows import from columns)")
        print(f"  {'':>8}{header}")
        for from_layer in LAYERS + ["di", "startup", "(root)"]:
            row = "".join(f"{matrix.get((from_layer, to_layer), 0):>9}" for to_layer in LAYERS + ["di", "startup", "(root)"])
            print(f"  {from_layer:>8}{row}")

    if accepted:
        print(f"\n  accepted exceptions ({len(accepted)} imports, each scheduled for removal):")
        for exception, where in sorted(set(accepted), key=lambda item: item[1]):
            print(f"    - {where}")
            print(f"      {exception.reason} [{exception.phase}]")

    if violations:
        print(f"\n  VIOLATIONS ({len(violations)}):")
        for violation in violations:
            print(f"    {violation}")
        print("\n  Fix the import, or add a dated exception in EXCEPTIONS with the phase that removes it.")
        return 1

    print("\n  result: OK - no unapproved layer violations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
