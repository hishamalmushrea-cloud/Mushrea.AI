#!/usr/bin/env bash
# Regenerates the aggregate of the NOTICE files embedded in the resolved github release
# dependencies, in both places it has to exist:
#
#   THIRD_PARTY_LICENSES/NOTICE-aggregate.txt      (the repository copy)
#   app/src/main/assets/legal/notice_aggregate.md  (the copy bundled in the APK)
#
# LegalDisclosureComplianceTest enforces that the two are byte-for-byte identical, and the copies
# drifted by hand before (there is no Gradle copy task for legal assets), so the script does the
# copy itself rather than leaving it as a manual step. The extraction lives in the
# `generateNoticeAggregate` Gradle task; see the comment there for why the configuration is
# flavour-qualified.
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew :app:generateNoticeAggregate
cp THIRD_PARTY_LICENSES/NOTICE-aggregate.txt app/src/main/assets/legal/notice_aggregate.md
echo "Mirrored NOTICE-aggregate.txt into app/src/main/assets/legal/notice_aggregate.md"
