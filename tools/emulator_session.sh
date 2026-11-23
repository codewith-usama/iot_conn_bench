#!/usr/bin/env bash
# Builds the app, boots an Android emulator, installs + launches the app, then executes
# simple UI commands dropped as files into build_logs/ctl/ (used to capture README screenshots).
# Usage: bash tools/emulator_session.sh [AVD_NAME]      Stop: Ctrl-C (or a "quit" command)
set -u
cd "$(dirname "$0")/.."
ROOT=$(pwd)
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"; EMU="$SDK/emulator/emulator"
FLUTTER="${FLUTTER:-$HOME/development/flutter/bin/flutter}"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
mkdir -p build_logs/ctl docs/screenshots
LOG=build_logs/emulator_session.log
: > "$LOG"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

AVD="${1:-$($EMU -list-avds | grep -m1 Medium_Phone || $EMU -list-avds | head -1)}"
log "AVD=$AVD"
if ! "$ADB" devices | grep -q '^emulator-'; then
  log "booting emulator"
  nohup "$EMU" -avd "$AVD" -no-snapshot-save >build_logs/emulator.out 2>&1 &
fi

log "building debug APK (Matter enabled)"
VARIANT=matter
if ! "$FLUTTER" build apk --debug >build_logs/build_apk.log 2>&1; then
  log "BUILD FAILED with Matter enabled (see build_logs/build_apk.log); retrying with enableMatter=false"
  VARIANT=no-matter
  if ! "$FLUTTER" build apk --debug --android-project-arg=enableMatter=false >build_logs/build_apk_nomatter.log 2>&1; then
    log "BUILD FAILED without Matter too (see build_logs/build_apk_nomatter.log)"; VARIANT=none
  fi
fi
echo "$VARIANT" > build_logs/emulator_build_variant.txt
log "build variant: $VARIANT"

"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
log "emulator booted: $("$ADB" shell getprop ro.product.model | tr -d '\r') API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"

if [ "$VARIANT" != none ]; then
  "$ADB" install -r build/app/outputs/flutter-apk/app-debug.apk >>"$LOG" 2>&1 && log "installed"
  "$ADB" shell am start -n com.thesis.iotconnbench/.MainActivity >>"$LOG" 2>&1 && log "launched"
fi

log "ready: waiting for commands in build_logs/ctl/"
while true; do
  for f in $(ls build_logs/ctl/cmd_*.txt 2>/dev/null | sort); do
    read -r c a1 a2 a3 a4 a5 < "$f"; n=$(basename "$f" .txt)
    case "$c" in
      tap)   "$ADB" shell input tap "$a1" "$a2"; r=$? ;;
      swipe) "$ADB" shell input swipe "$a1" "$a2" "$a3" "$a4" "${a5:-300}"; r=$? ;;
      key)   "$ADB" shell input keyevent "$a1"; r=$? ;;
      text)  "$ADB" shell input text "$a1"; r=$? ;;
      shot)  "$ADB" exec-out screencap -p > "docs/screenshots/$a1.png"; r=$? ;;
      demo)  "$ADB" shell settings put global sysui_demo_allowed 1
             "$ADB" shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
             "$ADB" shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 >/dev/null
             "$ADB" shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
             "$ADB" shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null; r=$? ;;
      restart) "$ADB" shell am force-stop com.thesis.iotconnbench; "$ADB" shell am start -n com.thesis.iotconnbench/.MainActivity >/dev/null; r=$? ;;
      logcat) "$ADB" logcat -d -t 400 > build_logs/logcat.txt; r=$? ;;
      build) if [ "$a1" = nomatter ]; then X=--android-project-arg=enableMatter=false; V=no-matter; else X=; V=matter; fi
             "$FLUTTER" build apk --debug $X > build_logs/build_apk.log 2>&1; r=$?
             if [ $r = 0 ]; then echo "$V" > build_logs/emulator_build_variant.txt
               "$ADB" install -r build/app/outputs/flutter-apk/app-debug.apk >>"$LOG" 2>&1
               "$ADB" shell am start -n com.thesis.iotconnbench/.MainActivity >/dev/null; fi ;;
      quit)  rm -f "$f"; log "quit"; exit 0 ;;
      *)     r=99 ;;
    esac
    echo "$r" > "build_logs/ctl/result_${n#cmd_}.txt"; log "$c $a1 $a2 $a3 $a4 -> $r"; rm -f "$f"
  done
  sleep 0.5
done
