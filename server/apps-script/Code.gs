/**
 * 재실 시간 앱 -> 구글 시트 수신기 (Google Apps Script 웹 앱)
 *
 * 앱이 하루 1건씩 보내는 JSON 을 아래 탭에 저장한다. (기존 '시트1' 등 다른 탭은 건드리지 않는다)
 *   일별 : 하루(날짜×참여자)당 한 줄   ← 주로 보는 표
 *   세션 : 집에 머문 구간 하나당 한 줄
 *   요약 : 날짜×참여자 합계표 (setup 실행 시 생성, 자동 계산)
 *   설명 : 열 설명
 * 같은 (날짜, 참여자) 를 다시 받으면 해당 줄(들)을 덮어쓰므로 재전송해도 중복되지 않는다.
 *
 * 설치 방법은 server/apps-script/README.md 참고.
 */

// 앱의 서버 주소 뒤에 ?token=여기값 을 붙여서 보낸다. 반드시 본인만 아는 긴 문자열로 바꿀 것.
// (Apps Script 는 요청 헤더를 읽을 수 없어서 주소의 파라미터로 확인한다.)
const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';

const DAILY = '일별';
const SESSIONS = '세션';
const SUMMARY = '요약';
const GUIDE = '설명';
const DAILY_HEADERS = ['날짜', '요일', '참여자', '재실(분)', '재실(시:분)', '첫귀가', '마지막외출', '세션수', '앱버전', '수신시각'];
const SESSION_HEADERS = ['날짜', '참여자', '시작', '종료', '길이(분)'];
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
const PID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

function doPost(e) {
  try {
    if (!TOKEN || TOKEN === 'CHANGE_ME_TO_A_LONG_RANDOM_STRING') return reply_(false, 'TOKEN 을 먼저 설정하세요');
    if (!e || !e.parameter || e.parameter.token !== TOKEN) return reply_(false, 'unauthorized');
    if (!e.postData || !e.postData.contents) return reply_(false, 'empty body');

    const d = JSON.parse(e.postData.contents);
    if (d.schema !== 1) return reply_(false, 'unsupported schema');
    if (!PID_RE.test(String(d.participantId))) return reply_(false, 'bad participantId');
    if (!DATE_RE.test(String(d.date))) return reply_(false, 'bad date');
    if (typeof d.totalMinutes !== 'number') return reply_(false, 'bad totalMinutes');

    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      const ss = SpreadsheetApp.getActiveSpreadsheet();
      upsertDaily_(getSheet_(ss, DAILY, DAILY_HEADERS, ['A:A', 'F:G']), d);
      replaceSessions_(getSheet_(ss, SESSIONS, SESSION_HEADERS, ['A:A', 'C:D']), d);
    } finally {
      lock.releaseLock();
    }
    return reply_(true);
  } catch (err) {
    return reply_(false, String(err));
  }
}

/** 브라우저로 주소를 열었을 때 동작 확인용. */
function doGet() {
  return reply_(true, 'receiver is running');
}

/**
 * (선택) 편집기에서 한 번 실행: 탭/헤더를 미리 만들고 '요약', '설명' 탭을 만든다.
 * 실행하지 않아도 첫 전송 때 '일별', '세션' 탭은 자동으로 만들어진다.
 */
function setup() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  getSheet_(ss, DAILY, DAILY_HEADERS, ['A:A', 'F:G']);
  getSheet_(ss, SESSIONS, SESSION_HEADERS, ['A:A', 'C:D']);

  let sum = ss.getSheetByName(SUMMARY);
  if (!sum) sum = ss.insertSheet(SUMMARY);
  sum.clear();
  // 날짜(행) × 참여자(열) 재실 분 합계. 구글 시트가 자동으로 계산한다.
  sum.getRange('A1').setFormula(
    "=QUERY('" + DAILY + "'!A2:E, \"select A, sum(D) where A is not null group by A pivot C order by A desc label A '날짜'\", 0)");

  let guide = ss.getSheetByName(GUIDE);
  if (!guide) guide = ss.insertSheet(GUIDE);
  guide.clear();
  const rows = [
    ['탭', '열', '설명'],
    [DAILY, '날짜 / 요일 / 참여자', '하루(날짜×참여자)당 한 줄. 참여자는 앱에 입력한 ID'],
    [DAILY, '재실(분) / 재실(시:분)', '그날 지정한 와이파이에 연결돼 있던 시간(짧은 끊김은 합친 값)'],
    [DAILY, '첫귀가 / 마지막외출', '그날 처음 귀가한 시각 / 마지막으로 외출한 시각 (24시간제, 폰의 현지 시각). 어제부터 이어졌거나 아직 안 나갔으면 빈칸'],
    [DAILY, '세션수', '집에 머문 구간의 개수'],
    [SESSIONS, '날짜 / 참여자 / 시작 / 종료 / 길이(분)', '집에 머문 구간 하나당 한 줄. 자정을 넘기면 날짜별로 잘려 있고 종료가 24:00 으로 표시됨'],
    [SUMMARY, '-', '날짜×참여자 재실 분 합계표 (자동 계산, 직접 수정하지 말 것)'],
    ['공통', '-', '앱이 하루가 끝난 뒤 어제 기록을 보내므로 오늘 날짜는 아직 없음. 같은 날짜가 다시 오면 덮어씀'],
  ];
  guide.getRange(1, 1, rows.length, 3).setValues(rows);
}

function getSheet_(ss, name, headers, textRanges) {
  let sheet = ss.getSheetByName(name);
  if (!sheet) {
    sheet = ss.insertSheet(name);
    sheet.appendRow(headers);
    sheet.setFrozenRows(1);
    // 날짜/시각이 구글 시트에 의해 날짜 형식으로 바뀌지 않게 문자열로 고정
    textRanges.forEach(function (r) { sheet.getRange(r).setNumberFormat('@'); });
  }
  return sheet;
}

/** 'YYYY-MM-DDTHH:mm:ss+09:00' -> 'HH:mm' (폰의 현지 시각). dayDate 보다 다음 날이면 '24:00'. */
function hm_(iso, dayDate) {
  if (!iso) return '';
  const s = String(iso);
  if (s.substring(0, 10) > dayDate) return '24:00';
  return s.substring(11, 16);
}

function hhmm_(minutes) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return h + ':' + (m < 10 ? '0' + m : m);
}

function weekday_(date) {
  const p = date.split('-').map(Number);
  return WEEKDAYS[new Date(Date.UTC(p[0], p[1] - 1, p[2])).getUTCDay()];
}

function upsertDaily_(sheet, d) {
  const date = String(d.date);
  const pid = String(d.participantId);
  const row = [
    date, weekday_(date), pid, d.totalMinutes, hhmm_(d.totalMinutes),
    hm_(d.firstEnter, date), hm_(d.lastExit, date),
    (d.sessions || []).length, String(d.appVersion || ''), new Date().toISOString(),
  ];
  const last = sheet.getLastRow();
  let target = -1;
  if (last >= 2) {
    const keys = sheet.getRange(2, 1, last - 1, 3).getValues();   // 날짜, 요일, 참여자
    for (let i = 0; i < keys.length; i++) {
      if (String(keys[i][0]) === date && String(keys[i][2]) === pid) { target = i + 2; break; }
    }
  }
  if (target < 0) target = last + 1;
  sheet.getRange(target, 1, 1, row.length).setValues([row]);
}

/** 같은 (날짜, 참여자) 의 기존 세션 줄을 지우고 새로 쓴다. */
function replaceSessions_(sheet, d) {
  const date = String(d.date);
  const pid = String(d.participantId);
  let last = sheet.getLastRow();
  if (last >= 2) {
    const keys = sheet.getRange(2, 1, last - 1, 2).getValues();
    for (let i = keys.length - 1; i >= 0; i--) {       // 아래에서 위로 지워야 행 번호가 안 밀린다
      if (String(keys[i][0]) === date && String(keys[i][1]) === pid) sheet.deleteRow(i + 2);
    }
  }
  const rows = (d.sessions || []).map(function (s) {
    return [date, pid, hm_(s.start, date), hm_(s.end, date), s.minutes];
  });
  if (rows.length) sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, 5).setValues(rows);
}

function reply_(ok, error) {
  const body = ok ? { ok: true } : { ok: false, error: error };
  if (ok && error) body.message = error;
  return ContentService.createTextOutput(JSON.stringify(body)).setMimeType(ContentService.MimeType.JSON);
}
