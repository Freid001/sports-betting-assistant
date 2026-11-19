const test = require('node:test');
const assert = require('node:assert/strict');
const { load, settle, plain } = require('./load-scripts');

const NOW = new Date().toISOString();
const fx = (id, extra) => Object.assign({ id, home: 'Tottenham', away: 'Ipswich', tournament: 'epl', leagueId: 'L1',
  date: NOW.slice(0, 10), kickoff: NOW, status: 'SCHEDULED', live: false, homeScore: null, awayScore: null }, extra);
const state = (extra) => Object.assign({ configured: true, refreshMinutes: 5, maxGames: 2, warning: null, lastRefresh: null,
  usage: { limit: '1000', remaining: '973', reset: '1795132800' }, tracked: [], fixtures: [] }, extra);

function openPopUp(routes) {
  const w = load(['football-data.js', 'live.js'], { routes });
  const modal = w.document.createElement('div');
  w.document.body.appendChild(modal);
  let saved = null;
  w.Live.openModal(modal, s => { saved = s; });
  return { w, modal, saved: () => saved };
}

test('LIVE badge shows the minute as of the last refresh and the score; FT when finished', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  assert.equal(Live.badge(fx('a', { live: true, status: 'LIVE', elapsed: '23', extra: '2', homeScore: 1, awayScore: 0 })).textContent, 'LIVE 23+2\'1–0');
  assert.equal(Live.badge(fx('a', { live: 'true', status: 'LIVE', elapsed: null, minute: 'Half Time', homeScore: '0', awayScore: '0' })).textContent, 'LIVE HT0–0');
  assert.equal(Live.badge(fx('a', { live: true, status: 'LIVE', elapsed: 'null', minute: '67' })).textContent, 'LIVE 67');
  assert.equal(Live.badge(fx('a', { status: 'FINISHED', homeScore: 2, awayScore: 1 })).textContent, 'FT2–1');
  assert.equal(Live.badge(fx('a', { status: 'SCHEDULED' })), null);
  assert.equal(Live.badge(null), null);
  assert.equal(Live.badge(fx('a', { status: 'FINISHED', homeScore: 'null' })).textContent, 'FT');
});

test('the Live Stats button gets a red dot while ticked games are on', () => {
  const w = load(['football-data.js', 'live.js']);
  const btn = w.document.createElement('button');
  btn.textContent = 'Live Stats';
  w.Live.setButton(btn, state({ tracked: ['a'], fixtures: [fx('a')] }));
  assert.ok(btn.querySelector('.live-dot'));
  assert.match(btn.title, /1 game\(s\) ticked/);
  w.Live.setButton(btn, state({ tracked: ['a'], fixtures: [fx('a', { status: 'FINISHED' })] }));
  assert.equal(btn.querySelector('.live-dot'), null);
  assert.equal(btn.title, 'Live Stats: off');
});

test('getState explains when the app needs a restart', async () => {
  const w = load(['football-data.js', 'live.js'], { routes: {} });
  await assert.rejects(w.Live.getState(), /restart/);
  const ok = load(['football-data.js', 'live.js'], { routes: { '/api/football/live': state() } });
  assert.equal((await ok.Live.getState()).maxGames, 2);
});

test('pop-up: info box, today\'s games, tick limit and call estimate, then Save', async () => {
  const s = state({ fixtures: [fx('a'), fx('b', { home: 'Arsenal', away: 'Chelsea' }), fx('c', { home: 'Leeds', away: 'Fulham' }),
    fx('tomorrow', { kickoff: new Date(Date.now() + 2 * 86400000).toISOString() })] });
  const { w, modal, saved } = openPopUp({
    'POST /api/football/live/fixtures': s,
    'POST /api/football/live/track': (url, opts) => state({ tracked: JSON.parse(opts.body).ids, fixtures: s.fixtures })
  });
  await settle();

  const info = modal.querySelector('[data-status]').children.map(r => r.textContent);
  assert.ok(info.includes('PlanFree'));
  assert.ok(info.includes('Calls left973 of 1,000 today'));
  assert.ok(info.includes('ResetsDaily at 00:00'));
  assert.ok(info.includes('Last refreshednot yet'));

  const boxes = modal.querySelectorAll('input[type=checkbox]');
  assert.equal(boxes.length, 3, 'only today\'s games');
  boxes[0].click();
  assert.equal(modal.querySelector('[data-estimate]').textContent, '1 of 2 ticked · up to about 46 calls');
  boxes[1].click();
  assert.ok(boxes[2].disabled, 'limit reached');
  boxes[1].click();
  assert.ok(!boxes[2].disabled);
  boxes[1].click();

  modal.querySelector('[data-save]').click();
  await settle();
  const post = w.calls.find(c => c.url === '/api/football/live/track');
  assert.deepEqual(JSON.parse(post.body), { ids: ['a', 'b'] });
  assert.deepEqual(plain(w.notes), [{ message: 'Live Stats on for 2 game(s).', type: 'success' }]);
  assert.deepEqual(plain(saved().tracked), ['a', 'b']);
});

test('pop-up: no games today says so and Save is off', async () => {
  const { modal } = openPopUp({ 'POST /api/football/live/fixtures': state({ warning: 'Paused: only 10 calls left' }) });
  await settle();
  assert.match(modal.querySelector('[data-games]').textContent, /No games today/);
  assert.ok(modal.querySelector('[data-save]').disabled);
  assert.match(modal.querySelector('[data-status]').textContent, /Paused: only 10 calls left/);
});

test('pop-up: Stop all clears the ticks', async () => {
  const { w, modal } = openPopUp({
    'POST /api/football/live/fixtures': state({ tracked: ['a'], fixtures: [fx('a')] }),
    'POST /api/football/live/track': state()
  });
  await settle();
  assert.ok(modal.querySelector('input[type=checkbox]').checked);
  modal.querySelector('[data-stop]').click();
  await settle();
  assert.deepEqual(JSON.parse(w.calls.find(c => c.url === '/api/football/live/track').body), { ids: [] });
  assert.equal(w.notes[0].message, 'Live Stats off.');
});

test('pop-up: errors are shown in red', async () => {
  const noKey = openPopUp({ 'POST /api/football/live/fixtures': state({ configured: false, usage: {} }) });
  await settle();
  assert.match(noKey.modal.querySelector('[data-status]').textContent, /No GOAL API key/);
  assert.match(noKey.modal.querySelector('[data-status]').textContent, /Calls leftunknown/);

  const down = openPopUp({ 'POST /api/football/live/fixtures': { status: 500, body: {} } });
  await settle();
  assert.deepEqual(plain(down.w.notes), [{ message: 'Couldn\'t load Live Stats games: HTTP 500', type: 'danger' }]);

  const saveFails = openPopUp({ 'POST /api/football/live/fixtures': state({ fixtures: [fx('a')] }) });
  await settle();
  saveFails.modal.querySelector('[data-save]').click();
  await settle();
  assert.equal(saveFails.w.notes[0].type, 'danger');
  assert.match(saveFails.w.notes[0].message, /restart/);
});

function card(routes, st) {
  const w = load(['football-data.js', 'live.js'], { routes });
  const box = w.document.createElement('div');
  const foot = w.document.createElement('span');
  const ev = { home_team: 'Tottenham Hotspur', away_team: 'Ipswich Town', commence_time: NOW };
  return { w, box, foot, run: () => w.Live.renderCard(box, foot, st, ev) };
}

test('match card: not ticked explains how to follow the game', async () => {
  const c = card({}, state({ fixtures: [fx('a')] }));
  assert.equal(await c.run(), null);
  assert.match(c.box.textContent, /Not followed/);
});

test('match card: ticked but not started says when it kicks off', async () => {
  const c = card({ '/api/football/live/match/a': { fixture: fx('a'), stats: null, refreshMinutes: 5 } },
    state({ tracked: ['a'], fixtures: [fx('a')] }));
  await c.run();
  assert.match(c.box.textContent, /Ticked\. Kicks off at .*refreshed every 5 min/);
  assert.equal(c.foot.textContent, '');
});

test('match card: live stats table with xG first, the bigger number in bold, and players with shots', async () => {
  const live = fx('a', { live: true, status: 'LIVE', elapsed: '34', homeScore: 1, awayScore: 0 });
  const stats = { updatedAt: NOW, final: false,
    rows: [{ label: 'xG (est.)', home: '1.34', away: '0.79' }, { label: 'Corners', home: '2', away: '5' }],
    homePlayers: [{ name: 'Kane', shots: 4, onTarget: 3, goals: 1 }], awayPlayers: [] };
  const c = card({ '/api/football/live/match/a': { fixture: live, stats, refreshMinutes: 5 } },
    state({ tracked: ['a'], fixtures: [live] }));
  await c.run();

  assert.match(c.box.textContent, /LIVE 34'1–0/);
  const rows = c.box.querySelectorAll('tbody tr');
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children[1].textContent, 'xG (est.)');
  assert.match(rows[0].children[1].getAttribute('data-bs-title'), /0\.12 per shot inside the box/);
  assert.ok(rows[0].children[0].classList.contains('fw-semibold'));
  assert.ok(rows[1].children[2].classList.contains('fw-semibold'));
  assert.match(c.box.textContent, /Kane 3 \(4\) ⚽/);
  assert.match(c.box.textContent, /Shots on target/);
  assert.equal(c.foot.textContent, 'Updated just now');
});

test('match card: full time is noted in the footer', async () => {
  const done = fx('a', { status: 'FINISHED', homeScore: 2, awayScore: 2 });
  const c = card({ '/api/football/live/match/a': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: new Date(Date.now() - 20 * 60000).toISOString(), final: 'true', rows: [{ label: 'Shots', home: '9', away: '9' }], homePlayers: [], awayPlayers: [] } } },
    state({ tracked: ['a'], fixtures: [done] }));
  await c.run();
  assert.equal(c.foot.textContent, 'Updated 20 min ago · full time');
  assert.match(c.box.textContent, /FT2–2/);
});
