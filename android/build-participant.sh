#!/usr/bin/env bash
# 사용법: ./build-participant.sh <참여자ID> "<집 와이파이 이름(SSID)>"
# 예:     ./build-participant.sh tb5 "SK_WiFiGIGA30FA_5G"
# 서버 주소는 server.properties(SERVER_URL) 또는 환경변수 SERVER_URL 에서 읽는다. 안드로이드 SDK 경로는 ANDROID_HOME.
set -euo pipefail
cd "$(dirname "$0")"
ID="${1:?참여자 ID 를 입력하세요}"
SSID="${2:?와이파이 이름(SSID)을 입력하세요}"
[[ "$ID" =~ ^[A-Za-z0-9_-]{1,64}$ ]] || { echo "참여자 ID 는 영문/숫자/-/_ 64자 이내여야 합니다"; exit 1; }
DEFAULT_PARTICIPANT_ID="$ID" DEFAULT_SSID="$SSID" ./gradlew --no-daemon -q :app:testDebugUnitTest :app:assembleDebug
VER="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)"
OUT="app/build/outputs/apk/debug/wifi-presence-v${VER}-${ID}.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$OUT"
echo "완료: $OUT"
