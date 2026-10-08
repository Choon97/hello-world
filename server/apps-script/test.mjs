// Code.gs 의 동작을 가짜 SpreadsheetApp/ContentService/LockService 로 검증한다.  실행: node server/apps-script/test.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const TOKEN = 'tok-123';
const src = fs.readFileSync(new URL('./Code.gs', import.meta.url), 'utf8')
  .replace("const TOKEN = 'CHANGE_ME_TO_A_LONG_RANDOM_STRING';", `const TOKEN = '${TOKEN}';`);

function makeEnv() {
  const rows = [];                       // rows[0] = 1행(헤더)
  const formats = {};
  const sheet = {
    appendRow: (r) => rows.push(r), setFrozenRows() {},
    getRange(a, b, c, d) {
      if (typeof a === 'string') return { setNumberFormat: (f) => { formats[a] = f; } };
      return {
        getValues: () => rows.slice(a - 1, a - 1 + c).map((r) => r.slice(b - 1, b - 1 + d)),
        setValues: (v) => { rows[a - 1] = v[0].slice(); },
      };
    },
    getLastRow: () => rows.length,
  };
  let hasSheet = false;
  const ctx = {
    SpreadsheetApp: { getActiveSpreadsheet: () => ({
      getSheetByName: () => (hasSheet ? sheet : null),
      insertSheet: () => { hasSheet = true; return sheet; },
    }) },
    LockService: { getScriptLock: () => ({ waitLock() {}, releaseLock() {} }) },
    ContentService: { MimeType: { JSON: 'json' },
      createTextOutput: (t) => ({ text: t, setMimeType() { return this; } }) },
    JSON, Date, String, Number,
  };
  vm.createContext(ctx);
  vm.runInContext(src, ctx);
  const post = (payload, token = TOKEN, raw) => JSON.parse(ctx.doPost({
    parameter: token === null ? {} : { token },
    postData: { contents: raw ?? JSON.stringify(payload) },
  }).text);
  return { rows, formats, post, ctx };
}

const payload = (over = {}) => ({
  schema: 1, participantId: 'lab-01', date: '2026-10-07', timezone: 'Asia/Seoul', totalMinutes: 432,
  firstEnter: '2026-10-07T08:12:00+09:00', lastExit: null, mergeGapMinutes: 10, appVersion: '0.3.1',
  sessions: [{ start: '2026-10-07T08:12:00+09:00', end: '2026-10-07T15:24:00+09:00', minutes: 432 }], ...over,
});

let n = 0; const t = (name, fn) => { fn(); n++; console.log('ok -', name); };

t('첫 전송은 헤더를 만들고 한 줄을 추가한다', () => {
  const { rows, post, formats } = makeEnv();
  assert.deepEqual(post(payload()), { ok: true });
  assert.equal(rows.length, 2);
  assert.equal(rows[0][0], 'date');
  // vm 안에서 만든 배열이라 엄격 비교 대신 JSON 문자열로 비교
  assert.equal(JSON.stringify(rows[1].slice(0, 6)), JSON.stringify(['2026-10-07', 'lab-01', 432, '2026-10-07T08:12:00+09:00', '', 1]));
  assert.equal(formats['A:A'], '@');
});
t('같은 (날짜, 참여자) 를 다시 보내면 덮어쓴다', () => {
  const { rows, post } = makeEnv();
  post(payload()); post(payload({ totalMinutes: 500 }));
  assert.equal(rows.length, 2); assert.equal(rows[1][2], 500);
});
t('다른 날짜/다른 참여자는 새 줄', () => {
  const { rows, post } = makeEnv();
  post(payload()); post(payload({ date: '2026-10-08' })); post(payload({ participantId: 'lab-02' }));
  assert.equal(rows.length, 4);
});
t('토큰이 없거나 틀리면 거부(ok:false)', () => {
  const { rows, post } = makeEnv();
  assert.equal(post(payload(), null).ok, false);
  assert.equal(post(payload(), 'wrong').ok, false);
  assert.equal(rows.length, 0);
});
t('기본 TOKEN 값 그대로면 거부', () => {
  const ctx = makeEnv();
  const raw = fs.readFileSync(new URL('./Code.gs', import.meta.url), 'utf8');
  const c2 = { ...ctx.ctx }; vm.createContext(c2); vm.runInContext(raw, c2);
  const r = JSON.parse(c2.doPost({ parameter: { token: 'CHANGE_ME_TO_A_LONG_RANDOM_STRING' }, postData: { contents: '{}' } }).text);
  assert.equal(r.ok, false);
});
t('잘못된 입력은 거부', () => {
  const { rows, post } = makeEnv();
  assert.equal(post(payload({ participantId: '../x' })).ok, false);
  assert.equal(post(payload({ date: 'yesterday' })).ok, false);
  assert.equal(post(payload({ schema: 2 })).ok, false);
  assert.equal(post(payload({ totalMinutes: '9' })).ok, false);
  assert.equal(post(null, TOKEN, '{bad').ok, false);
  assert.equal(rows.length, 0);
});
console.log(`\n${n} tests passed`);
