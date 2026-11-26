const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const raw = load(['goal-alert.js']).GoalAlert;
const G = Object.assign({}, raw, { check: s => [...raw.check(s)],
  checkCards: c => JSON.parse(JSON.stringify(raw.checkCards(c))), checkPoints: k => [...raw.checkPoints(k)] });   // arrays from the sandbox, as plain arrays

test('the first score seen is only remembered, then each goal is spotted', () => {
  G.reset();
  assert.deepEqual(G.check('1–0'), []);          // opened mid-game: no old goals celebrated
  assert.deepEqual(G.check('1–0'), []);
  assert.deepEqual(G.check('1–1'), ['away']);
  assert.deepEqual(G.check('2-1'), ['home']);    // plain hyphen too
  assert.deepEqual(G.check('3–2'), ['home', 'away']);   // both between two polls
});

test('a goal ruled out (score goes down) and blank scores are not celebrated', () => {
  G.reset();
  G.check('2–0');
  assert.deepEqual(G.check('1–0'), []);
  assert.deepEqual(G.check(''), []);
  assert.deepEqual(G.check('2–0'), ['home']);
  assert.equal(G.parse('HT'), null);
});

test('new yellow and red cards are spotted per team, the first counts only remembered', () => {
  G.reset();
  assert.deepEqual(G.checkCards({ yellow: [1, 0], red: [0, 0] }), []);
  assert.deepEqual(G.checkCards({ yellow: [1, 2], red: [0, 0] }),
    [{ kind: 'yellow', side: 'away' }, { kind: 'yellow', side: 'away' }]);
  assert.deepEqual(G.checkCards({ yellow: ['2', '2'], red: ['0', '1'] }),   // a second yellow: red first
    [{ kind: 'red', side: 'away' }, { kind: 'yellow', side: 'home' }]);
  assert.deepEqual(G.checkCards({ yellow: [1, 2], red: [0, 1] }), []);   // taken back
  assert.deepEqual(G.checkCards(null), []);
});

test('kick-off, half time, second half and full time are spotted as the status moves on', () => {
  G.reset();
  assert.equal(G.checkStatus(null), null);          // page open before kick-off
  assert.equal(G.checkStatus("1'"), 'kickoff');
  assert.equal(G.checkStatus("23'"), null);
  assert.equal(G.checkStatus(null), null);          // a gap in the refreshes isn't a new kick-off
  assert.equal(G.checkStatus("24'"), null);
  assert.equal(G.checkStatus('HT'), 'ht');
  assert.equal(G.checkStatus("46'"), 'second');
  assert.equal(G.checkStatus('FT'), 'ft');
  assert.equal(G.checkStatus('FT'), null);
});

test('opening the page mid-game or after full time shows nothing for what already happened', () => {
  G.reset();
  assert.equal(G.checkStatus("67'"), null);
  assert.equal(G.checkStatus("68'"), null);
  G.reset();
  assert.equal(G.checkStatus('FT'), null);
});

test('a penalty count going up is spotted before cards', () => {
  G.reset();
  G.checkCards({ yellow: [0, 0], red: [0, 0], penalty: [0, 0] });
  assert.deepEqual(G.checkCards({ yellow: [0, 1], red: [0, 0], penalty: [1, 0] }),
    [{ kind: 'penalty', side: 'home' }, { kind: 'yellow', side: 'away' }]);
});

test('a bet window is new only once its odds appear after the page opened', () => {
  G.reset();
  assert.deepEqual(G.checkPoints(['10', 'HT']), []);
  assert.deepEqual(G.checkPoints(['10', 'HT']), []);
  assert.deepEqual(G.checkPoints(['10', 'HT', '60']), ['60']);
});
