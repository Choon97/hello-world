/**
 * 재실 시간 앱 -> 구글 시트 수신기 (Google Apps Script 웹 앱)
 *
 * 앱이 하루 1건씩 보내는 JSON 을 '재실' 시트에 한 줄로 저장한다.
 * (date, participantId) 가 같으면 그 줄을 덮어쓰므로 재전송해도 중복되지 않는다.
 *
 * 설치 방법은 server/apps-script/README.md 참고.
 */

// 앱의 서버 주소 뒤에 ?token=여기값 을 붙여서 보낸다. 반드시 본인만 아는 긴 문자열로 바꿀 것.
// (Apps Script 는 요청 헤더를 읽을 수 없어서 주소의 파라미터로 확인한다.)
const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';

const SHEET_NAME = '재실';
const HEADERS = [
  'date', 'participantId', 'totalMinutes', 'firstEnter', 'lastExit',
  'sessionCount', 'mergeGapMinutes', 'appVersion', 'receivedAt', 'sessionsJson',
];
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
      upsert_(getSheet_(), d);
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

function getSheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_NAME);
    sheet.appendRow(HEADERS);
    sheet.setFrozenRows(1);
    // 날짜/시각이 구글 시트에 의해 날짜 형식으로 바뀌지 않게 문자열로 고정 (A: date, D~E: 시각)
    sheet.getRange('A:A').setNumberFormat('@');
    sheet.getRange('D:E').setNumberFormat('@');
  }
  return sheet;
}

function upsert_(sheet, d) {
  const row = [
    String(d.date), String(d.participantId), d.totalMinutes,
    d.firstEnter || '', d.lastExit || '',
    (d.sessions || []).length, d.mergeGapMinutes || '', String(d.appVersion || ''),
    new Date().toISOString(), JSON.stringify(d.sessions || []),
  ];
  const last = sheet.getLastRow();
  let target = -1;
  if (last >= 2) {
    const keys = sheet.getRange(2, 1, last - 1, 2).getValues();
    for (let i = 0; i < keys.length; i++) {
      if (String(keys[i][0]) === row[0] && String(keys[i][1]) === row[1]) { target = i + 2; break; }
    }
  }
  if (target < 0) target = last + 1;
  sheet.getRange(target, 1, 1, row.length).setValues([row]);
}

function reply_(ok, error) {
  const body = ok ? { ok: true } : { ok: false, error: error };
  if (ok && error) body.message = error;
  return ContentService.createTextOutput(JSON.stringify(body)).setMimeType(ContentService.MimeType.JSON);
}
