// Code.gs 의 동작을 가짜 SpreadsheetApp/ContentService/LockService 로 검증한다.  실행: node server/apps-script/test.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const TOKEN = 'tok-123';
const raw = fs.readFileSync(new URL('./Code.gs', import.meta.url), 'utf8');
const src = raw.replace("const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';", `const TOKEN = '${TOKEN}';`);
const J = (x) => JSON.stringify(x);                 // vm 안에서 만든 배열과 비교하려고 JSON 으로 비교

function makeSheet(name, formats) {
  const rows = [];
  return {
    name, rows, formulas: {},
    appendRow: (r) => rows.push(r.slice()), setFrozenRows() {},
    getLastRow: () => rows.length,
    deleteRow: (n) => rows.splice(n - 1, 1),
    clear: () => { rows.length = 0; },
    getRange(a, b, c, d) {
      if (typeof a === 'string') return {
        setNumberFormat: (f) => { formats[name + '!' + a] = f; },
        setFormula: (f) => { this.formulas[a] = f; },
      };
      return {
        getValues: () => rows.slice(a - 1, a - 1 + c).map((r) => r.slice(b - 1, b - 1 + d)),
        setValues: (v) => { v.forEach((r, i) => { rows[a - 1 + i] = r.slice(); }); },
      };
    },
  };
}

function makeEnv(opts = {}) {
  const sheets = {}; const formats = {}; const opened = [];
  const ssObj = {
    getSheetByName: (n) => sheets[n] || null,
    insertSheet: (n) => (sheets[n] = makeSheet(n, formats)),
  };
  const code = opts.spreadsheetId
    ? src.replace("const SPREADSHEET_ID = '';", `const SPREADSHEET_ID = '${opts.spreadsheetId}';`) : src;
  const ctx = {
    SpreadsheetApp: {
      getActiveSpreadsheet: () => (opts.standalone ? null : ssObj),   // 독립 프로젝트면 null
      openById: (id) => { opened.push(id); return ssObj; },
    },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    ContentService: { MimeType: { JSON: 'json' }, createTextOutput: (t) => ({ text: t, setMimeType() { return this; } }) },
    JSON, Date, String, Number, Math,
  };
  vm.createContext(ctx); vm.runInContext(code, ctx);
  const post = (payload, token = TOKEN, body) => JSON.parse(ctx.doPost({
    parameter: token === null ? {} : { token }, postData: { contents: body ?? J(payload) },
  }).text);
  return { sheets, formats, post, ctx, opened };
}

const payload = (over = {}) => ({
  schema: 1, participantId: 'lab-01', date: '2026-10-07', timezone: 'Asia/Seoul', totalMinutes: 432,
  firstEnter: '2026-10-07T08:12:00+09:00', lastExit: null, mergeGapMinutes: 10, appVersion: '0.3.1',
  sessions: [{ start: '2026-10-07T08:12:00+09:00', end: '2026-10-07T15:24:00+09:00', minutes: 432 }], ...over,
});

let n = 0; const t = (name, fn) => { fn(); n++; console.log('ok -', name); };

t('일별: 헤더와 한 줄(요일/시:분/첫귀가) 이 사람이 읽기 좋게 저장된다', () => {
  const { sheets, post, formats } = makeEnv();
  assert.deepEqual(post(payload()), { ok: true });
  const d = sheets['일별'].rows;
  assert.equal(d.length, 2);
  assert.equal(J(d[0]), J(['날짜', '요일', '참여자', '재실(분)', '재실(시:분)', '첫귀가', '마지막외출', '세션수', '앱버전', '수신시각', '상태']));
  const expectedWeekday = ['일', '월', '화', '수', '목', '금', '토'][new Date(Date.UTC(2026, 9, 7)).getUTCDay()];
  assert.equal(J(d[1].slice(0, 9)), J(['2026-10-07', expectedWeekday, 'lab-01', 432, '7:12', '08:12', '', 1, '0.3.1']));
  assert.equal(d[1][10], '확정');
  assert.equal(d[0][10], '상태');
  assert.equal(formats['일별!A:A'], '@');
  assert.equal(formats['일별!F:G'], '@');
});
t('수동 전송(partial)은 중간으로 표시되고, 다음날 확정값이 같은 줄을 덮어쓴다', () => {
  const { sheets, post } = makeEnv();
  post(payload({ partial: true, totalMinutes: 100 }));
  assert.equal(sheets['일별'].rows[1][10], '중간');
  post(payload({ partial: false, totalMinutes: 432 }));
  assert.equal(sheets['일별'].rows.length, 2);
  assert.equal(sheets['일별'].rows[1][3], 432);
  assert.equal(sheets['일별'].rows[1][10], '확정');
});
t('세션: 세션 하나당 한 줄 (시작/종료는 HH:mm)', () => {
  const { sheets, post } = makeEnv();
  post(payload({ sessions: [
    { start: '2026-10-07T08:12:00+09:00', end: '2026-10-07T12:00:00+09:00', minutes: 228 },
    { start: '2026-10-07T13:00:00+09:00', end: '2026-10-08T00:00:00+09:00', minutes: 660 },
  ] }));
  const s = sheets['세션'].rows;
  assert.equal(s.length, 3);
  assert.equal(J(s[1]), J(['2026-10-07', 'lab-01', '08:12', '12:00', 228]));
  assert.equal(J(s[2]), J(['2026-10-07', 'lab-01', '13:00', '24:00', 660]));   // 자정은 24:00
});
t('같은 (날짜, 참여자) 재전송: 일별은 덮어쓰고 세션은 교체한다', () => {
  const { sheets, post } = makeEnv();
  post(payload({ sessions: [{ start: '2026-10-07T08:00:00+09:00', end: '2026-10-07T09:00:00+09:00', minutes: 60 },
                           { start: '2026-10-07T10:00:00+09:00', end: '2026-10-07T11:00:00+09:00', minutes: 60 }] }));
  post(payload({ totalMinutes: 500, sessions: [{ start: '2026-10-07T08:00:00+09:00', end: '2026-10-07T16:20:00+09:00', minutes: 500 }] }));
  assert.equal(sheets['일별'].rows.length, 2);
  assert.equal(sheets['일별'].rows[1][3], 500);
  assert.equal(sheets['세션'].rows.length, 2);       // 헤더 + 새 세션 1줄 (예전 2줄은 삭제됨)
  assert.equal(sheets['세션'].rows[1][4], 500);
});
t('다른 참여자의 세션은 건드리지 않는다', () => {
  const { sheets, post } = makeEnv();
  post(payload({ participantId: 'lab-02' }));
  post(payload());
  post(payload({ totalMinutes: 1, sessions: [] }));  // lab-01 재전송, 세션 없음
  const s = sheets['세션'].rows;
  assert.equal(s.length, 2);
  assert.equal(s[1][1], 'lab-02');
  assert.equal(sheets['일별'].rows.length, 3);
});
t('다른 날짜/다른 참여자는 새 줄', () => {
  const { sheets, post } = makeEnv();
  post(payload()); post(payload({ date: '2026-10-08' })); post(payload({ participantId: 'lab-02' }));
  assert.equal(sheets['일별'].rows.length, 4);
});
t('토큰이 없거나 틀리면 거부(ok:false) 하고 아무것도 쓰지 않는다', () => {
  const { sheets, post } = makeEnv();
  assert.equal(post(payload(), null).ok, false);
  assert.equal(post(payload(), 'wrong').ok, false);
  assert.equal(Object.keys(sheets).length, 0);
});
t('기본 TOKEN 값 그대로면 거부', () => {
  const ctx = { ...makeEnv().ctx }; vm.createContext(ctx); vm.runInContext(raw, ctx);
  const r = JSON.parse(ctx.doPost({ parameter: { token: 'CHANGE_ME_TO_A_LONG_RANDOM_STRING' }, postData: { contents: '{}' } }).text);
  assert.equal(r.ok, false);
});
t('잘못된 입력은 거부', () => {
  const { sheets, post } = makeEnv();
  assert.equal(post(payload({ participantId: '../x' })).ok, false);
  assert.equal(post(payload({ date: 'yesterday' })).ok, false);
  assert.equal(post(payload({ schema: 2 })).ok, false);
  assert.equal(post(payload({ totalMinutes: '9' })).ok, false);
  assert.equal(post(null, TOKEN, '{bad').ok, false);
  assert.equal(Object.keys(sheets).length, 0);
});
t('시트에 연결 안 된 독립 프로젝트 + SPREADSHEET_ID 없음: 원인을 알려주며 거부', () => {
  const { sheets, post } = makeEnv({ standalone: true });
  const r = post(payload());
  assert.equal(r.ok, false);
  assert.ok(r.error.includes('SPREADSHEET_ID'), r.error);
  assert.equal(Object.keys(sheets).length, 0);
});
t('독립 프로젝트 + SPREADSHEET_ID 지정: openById 로 열어 정상 저장', () => {
  const { sheets, post, opened } = makeEnv({ standalone: true, spreadsheetId: 'ID123' });
  assert.deepEqual(post(payload()), { ok: true });
  assert.equal(J(opened), J(['ID123']));
  assert.equal(sheets['일별'].rows.length, 2);
});
t('setup(): 요약 수식과 설명 탭을 만들고, 기존 시트1 은 건드리지 않는다', () => {
  const { sheets, ctx } = makeEnv();
  ctx.SpreadsheetApp.getActiveSpreadsheet().insertSheet('시트1');
  ctx.setup();
  assert.ok(sheets['일별'] && sheets['세션'] && sheets['요약'] && sheets['설명']);
  assert.ok(sheets['요약'].formulas['A1'].startsWith("=QUERY('일별'!A2:E"));
  assert.equal(sheets['시트1'].rows.length, 0);
  assert.ok(sheets['설명'].rows.length >= 6);
  ctx.setup();                                       // 두 번 실행해도 안전
  assert.equal(sheets['설명'].rows.length >= 6, true);
});
console.log(`\n${n} tests passed`);
