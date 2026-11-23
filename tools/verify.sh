#!/usr/bin/env bash
# Runs every automated check for the thesis app and writes logs to build_logs/.
# Usage (from anywhere):  bash ~/development/iot_conn_bench/tools/verify.sh
# Optional: FLUTTER=/path/to/flutter  SKIP_APK=1
set -u
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
mkdir -p build_logs
: > build_logs/summary.txt

FLUTTER="${FLUTTER:-$HOME/development/flutter/bin/flutter}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"

run() {
  local name=$1; shift
  printf '== %-28s ' "$name"
  ( "$@" ) > "build_logs/$name.log" 2>&1
  local code=$?
  echo "exit=$code"
  echo "$name exit=$code" >> build_logs/summary.txt
  return 0
}

{
  echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "host: $(uname -a)"
  echo "java_home: ${JAVA_HOME:-unset}"
  git -C "$ROOT" rev-parse --short HEAD 2>/dev/null | sed 's/^/git: /'
} >> build_logs/summary.txt

run 01_flutter_version "$FLUTTER" --version
run 02_pub_get "$FLUTTER" pub get
run 03_analyze "$FLUTTER" analyze
run 04_flutter_test "$FLUTTER" test
if [ -z "${SKIP_APK:-}" ]; then
  run 05_build_apk_debug "$FLUTTER" build apk --debug
fi
run 06_gradle_unit_tests bash -c "cd android && ./gradlew :app:testDebugUnitTest --console=plain --stacktrace"
run 07_runtime_dependencies bash -c "cd android && ./gradlew :app:dependencies --configuration debugRuntimeClasspath --console=plain"
run 08_compile_without_matter bash -c "cd android && ./gradlew :app:compileDebugKotlin -PenableMatter=false --console=plain"
# Re-enable Matter sources for the normal build cache state.
run 09_compile_with_matter bash -c "cd android && ./gradlew :app:compileDebugKotlin --console=plain"

# Evidence for the Matter feasibility gate: the real controller API inside the AAR Gradle resolved.
{
  AAR=$(find "$HOME/.gradle/caches" -name 'matter-android-demo-sdk-1.0.aar' 2>/dev/null | head -1)
  echo "aar: ${AAR:-NOT FOUND}"
  if [ -n "$AAR" ]; then
    T=$(mktemp -d); (cd "$T" && unzip -q -o "$AAR")
    echo "--- AAR contents"; (cd "$T" && find . -maxdepth 3 | sort | head -80)
    grep -o 'minSdkVersion="[0-9]*"' "$T/AndroidManifest.xml" 2>/dev/null
    CP=$(ls "$T"/classes.jar "$T"/libs/*.jar 2>/dev/null | tr '\n' ':')
    for c in chip.devicecontroller.ChipDeviceController 'chip.devicecontroller.ChipDeviceController$CompletionListener' \
             chip.devicecontroller.ControllerParams 'chip.devicecontroller.ControllerParams$Builder' \
             chip.devicecontroller.NetworkCredentials 'chip.devicecontroller.GetConnectedDeviceCallbackJni$GetConnectedDeviceCallback' \
             'chip.devicecontroller.ChipClusters$OnOffCluster' 'chip.devicecontroller.ChipClusters$BooleanAttributeCallback' \
             'chip.devicecontroller.ChipClusters$DefaultClusterCallback' chip.platform.AndroidChipPlatform chip.platform.AndroidBleManager chip.platform.BleCallback; do
      echo "--- $c"; javap -cp "$CP" "$c" 2>&1 | grep -E 'public|interface|\(' | head -60
    done
  fi
} > build_logs/10_matter_api.log 2>&1
echo "10_matter_api (javap) written" >> build_logs/summary.txt

APK=$(ls build/app/outputs/flutter-apk/app-debug.apk 2>/dev/null)
echo "apk: ${APK:-none} $( [ -n "$APK" ] && du -h "$APK" | cut -f1)" >> build_logs/summary.txt
echo
cat build_logs/summary.txt
echo
echo "Logs are in $ROOT/build_logs/"
