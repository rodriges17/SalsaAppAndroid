#!/usr/bin/env bash
set -euo pipefail
bash tools/run-native-ui-tests.sh
if [ "${STEPBYSTEP_VERIFY_UPGRADE:-false}" = true ]; then
  bash tools/verify-apk-upgrade.sh "$RUNNER_TEMP/signing-upgrade/upgrade-baseline.apk" "$RUNNER_TEMP/distribution-apk/app-debug.apk"
fi
