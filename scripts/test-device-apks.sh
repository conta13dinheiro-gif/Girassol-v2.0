#!/usr/bin/env bash
set -euo pipefail
apk_root="${1:?Usage: scripts/test-device-apks.sh apk-directory}"
mkdir -p ci-device-reports
mapfile -t app_files < <(find "$apk_root" -name app-x86_64-debug.apk)
mapfile -t test_files < <(find "$apk_root" -name app-debug-androidTest.apk)
[[ ${#app_files[@]} == 1 && ${#test_files[@]} == 1 ]]
adb install -r "${app_files[0]}"
adb install -r "${test_files[0]}"
cleanup() {
  adb logcat -d > ci-device-reports/logcat.txt || true
  adb exec-out screencap -p > ci-device-reports/screen.png || true
}
trap cleanup EXIT
adb shell am instrument -w -r com.enhikyy.girassol.test/androidx.test.runner.AndroidJUnitRunner | tee ci-device-reports/instrumentation.txt
python3 - <<'PY'
from pathlib import Path
import re
result=Path('ci-device-reports/instrumentation.txt').read_text()
if not re.search(r'OK \(\d+ tests?\)',result) or 'FAILURES!!!' in result or 'INSTRUMENTATION_FAILED' in result:
    raise SystemExit('Android instrumentation did not report a complete successful test run')
PY
