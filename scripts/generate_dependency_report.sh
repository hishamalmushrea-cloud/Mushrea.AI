#!/usr/bin/env bash
# Regenerates THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt from the
# actual resolved Gradle dependency graph, so the list backing THIRD_PARTY_NOTICES.md's Gradle
# dependency summary is generated and verifiable rather than hand-maintained. Run this after any
# dependency change and re-check THIRD_PARTY_NOTICES.md against the diff.
#
# Two things this script gets right that its first version did not (both found when it was run on
# CI for the first time in Phase 2):
#
#  * the configuration. The app builds two product flavours (`github`, `fdroid`), so the
#    variant-agnostic `releaseRuntimeClasspath` configuration does not exist and the command failed
#    outright — which is why the committed list had been produced some other way and had drifted.
#    The published release is the `github` one, and the file it replaces already reflected it (it
#    listed the Firebase-only dependencies), so that is the default. Pass another configuration as
#    the first argument if you ever need the fdroid graph.
#  * the version. Gradle prints a version conflict as `group:artifact:requested -> resolved`, and the
#    version that ships is the resolved one. The first version took the coordinate at the start of
#    the line, so the list named what a library *asked for* rather than what the build resolves
#    (e.g. androidx.fragment 1.1.0 while the build resolves 1.8.5) — an authoritative-looking list
#    that understated the versions. The parse below resolves the arrow.
set -euo pipefail

cd "$(dirname "$0")/.."

OUTPUT="THIRD_PARTY_LICENSES/release-dependencies-releaseRuntimeClasspath.txt"
CONFIGURATION="${1:-githubReleaseRuntimeClasspath}"

./gradlew :app:dependencies --configuration "$CONFIGURATION" --console=plain |
  awk '
    {
      line = $0
      if (match(line, /[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.-]*/)) {
        coord = substr(line, RSTART, RLENGTH)
        rest = substr(line, RSTART + RLENGTH)
        if (match(rest, /-> *[0-9][A-Za-z0-9_.-]*/)) {
          resolved = substr(rest, RSTART, RLENGTH)
          sub(/-> */, "", resolved)
          sub(/:[^:]*$/, "", coord)
          coord = coord ":" resolved
        }
        print coord
      }
    }
  ' |
  sort -u -t: -k1,2 |
  sort -u \
    > "$OUTPUT"

echo "Wrote $(wc -l < "$OUTPUT" | tr -d ' ') resolved dependency coordinates to $OUTPUT (configuration: $CONFIGURATION)"
