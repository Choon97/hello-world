# 재실 시간 (WiFi Presence)

지정한 집 와이파이(SSID)에 연결된 시간을 "재실 시간"으로 보고, 하루 단위로 보여주는 안드로이드 앱.

## 동작 방식
1. 포그라운드 서비스(`WifiMonitorService`)가 와이파이 연결/해제를 감시하고 `ENTER`/`EXIT` 이벤트를 `events.csv` 에 기록한다.
2. `SessionCalculator` 가 이벤트를 세션으로 만들고, **짧게 끊긴 구간(기본 10분 이하)은 합친다** (절전 모드로 인한 순간 끊김 보정).
3. 자정을 걸치는 세션은 날짜별로 잘라서 계산한다.
4. 서비스가 꺼져 있던 동안 열려 있던 세션은 마지막 생존 시각(1분 하트비트)에서 닫는다.

기본 SSID 는 `skyiptime5g0651` 이고 앱 안에서 바꿀 수 있다. 와이파이 비밀번호는 앱이 필요로 하지 않으며 저장하지도 않는다.

## 서버 주소(고정)
서버(구글 시트 Apps Script) 주소는 빌드 시 `android/server.properties` 의 `SERVER_URL=` (또는 환경변수/CI 비밀값 `SERVER_URL`)로 앱에 내장한다. 앱 화면에서는 입력하지 않으며 `https://` 만 허용. 자세한 내용은 `server/apps-script/README.md`.

## 데이터 보관
기기에는 최근 7일(+경계 하루) 이벤트만 보관하며 하루에 한 번 오래된 기록을 지운다. 진단 로그도 7일. 장기 보관은 서버(시트)에서 한다.

## 설치
- GitHub Actions → `Android APK` 실행 결과의 `wifi-presence-debug-apk` 아티팩트에서 `app-debug.apk` 를 받아 폰에 설치 (출처를 알 수 없는 앱 허용 필요), 또는
- 로컬 빌드: `ANDROID_HOME=<sdk 경로> ./gradlew :app:assembleDebug`

## 첫 실행 시 설정 (갤럭시)
1. 위치/알림 권한 허용 → 위치 **'항상 허용'** (SSID 를 읽으려면 안드로이드가 요구함. 위치값 자체는 저장 안 함)
2. 설정 → 앱 → 재실 시간 → 배터리 → **제한 없음** (삼성 절전 기능이 서비스를 죽이는 것 방지)

## 테스트
`./gradlew :app:testDebugUnitTest` — 세션 병합, 자정 분할, 중복 이벤트, 서비스 중단 보정 등을 검증한다.
