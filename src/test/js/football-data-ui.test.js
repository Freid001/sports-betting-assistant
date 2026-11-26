const test = require('node:test');
const assert = require('node:assert/strict');
const { load, settle, plain } = require('./load-scripts');

const page = (opts) => load(['football-data.js'], opts);

test('odds badges are coloured by chance and show the fraction, with the chance on hover', () => {
  const F = page().Football;
  const b = F.oddsBadge(2.6, 0.38);
  assert.equal(b.textContent, '8/5');
  assert.ok(b.classList.contains('odds-ok'));
  assert.equal(b.getAttribute('data-bs-title'), '8/5 · 38.5% (38.0% no margin)', 'same as the match page');
  assert.equal(F.oddsBadge(null, 0.5).textContent, '–');
  assert.equal(F.oddsBadge(3, null).getAttribute('data-bs-title'), '2/1 · 33.3%');
});

test('with a stake set, badges show the total return instead', () => {
  const F = page({ storage: { 'football-stake': '10' } }).Football;
  assert.equal(F.stake(), 10);
  assert.equal(F.oddsBadge(2.6, 0.38).textContent, '£26.00');
  assert.match(F.legend().textContent, /total return for £10\.00/);
});

test('stake buttons set, toggle and clear the stake, and remember it', () => {
  const w = page();
  const F = w.Football;
  let changes = 0;
  const box = F.stakeInput(() => changes++);
  const buttons = box.querySelectorAll('button');
  assert.deepEqual(buttons.map(b => b.textContent), ['No bet', '£1', '£2', '£5', '£10', '£20']);

  buttons[4].click();
  assert.equal(F.stake(), 10);
  assert.equal(w.store['football-stake'], '10');
  assert.ok(buttons[4].classList.contains('active'));
  assert.equal(box.querySelector('input').value, 10);

  buttons[4].click();                       // clicking the highlighted one again clears it
  assert.equal(F.stake(), 0);
  assert.ok(buttons[0].classList.contains('active'));

  const input = box.querySelector('input');
  input.value = '7.5';
  input.dispatchEvent(new w.Event('input'));
  assert.equal(F.stake(), 7.5);
  assert.equal(changes, 3);
});

test('money, colour bands, titles and times', () => {
  const F = page().Football;
  assert.equal(F.money(2.5), '£2.50');
  assert.equal(F.money(150), '£150');
  assert.equal(F.chanceClass(0.75), 'odds-very-good');
  assert.equal(F.chanceClass(0.55), 'odds-good');
  assert.equal(F.chanceClass(0.15), 'odds-poor');
  assert.equal(F.chanceClass(0.05), 'odds-very-poor');
  assert.equal(F.chanceClass(null), 'text-bg-secondary');
  assert.equal(F.nearestFraction(3.5), '5/2');
  assert.equal(F.title('epl'), 'Premier League');
  assert.equal(F.title('copa_america'), 'copa america');
  assert.equal(F.ago(new Date(Date.now() - 7 * 60000).toISOString()), '7 min ago');
  assert.equal(F.ago(new Date(Date.now() - 5 * 3600000).toISOString()), '5 h ago');
  assert.equal(F.ago(new Date(Date.now() - 3 * 86400000).toISOString()), '3 days ago');
  assert.match(F.day('2026-11-21T15:00:00Z'), /Saturday/);
  assert.equal(F.time('2026-11-21T15:00:00Z'), new Date('2026-11-21T15:00:00Z').toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }));
});

test('logos: club crest, else a country flag, else initials', () => {
  const F = page().Football;
  const teams = [{ name: 'Arsenal FC', shortName: 'Arsenal', crest: 'https://crests/57.png' }];
  assert.equal(F.logo(teams, 'Arsenal', 20).querySelector('img').src, 'https://crests/57.png');
  assert.equal(F.logo([], 'Wales', 20).querySelector('img').src, 'https://flagcdn.com/w80/gb-wls.png');
  const none = F.logo([], 'Real Sociedad', 30);
  assert.equal(none.querySelector('img'), null);
  assert.equal(none.textContent, 'RS');
  assert.equal(F.logo([], 'Brentford').textContent, 'BRE');
});

test('a broken crest image falls back to initials', () => {
  const F = page().Football;
  const box = F.logo([{ name: 'Leeds United', crest: 'x.png' }], 'Leeds United', 20);
  box.querySelector('img').onerror();
  assert.equal(box.textContent, 'LU');
});

test('team crests are loaded once per competition, and an error gives an empty list', async () => {
  const w = page({ routes: { '/api/football/teams/epl': { teams: [{ name: 'Arsenal FC' }] }, '/api/football/teams/x': { status: 500, body: {} } } });
  assert.deepEqual(plain(await w.Football.loadTeams('epl')), [{ name: 'Arsenal FC' }]);
  await w.Football.loadTeams('epl');
  assert.equal(w.calls.filter(c => c.url === '/api/football/teams/epl').length, 1);
  assert.deepEqual(plain(await w.Football.loadTeams('x')), []);
});

test('loadAll merges every saved tournament, soonest first', async () => {
  const w = page({ routes: {
    '/api/football/tournaments': [{ name: 'epl', key: 'soccer_epl', cached: true }, { name: 'fifa_world_cup', key: 'soccer_fifa_world_cup', cached: false }],
    '/api/odds/cache/soccer_epl': { fetchedAt: new Date().toISOString(), events: [
      { id: 'b', commence_time: '2026-11-21T17:30:00Z' }, { id: 'a', commence_time: '2026-11-21T15:00:00Z' }] }
  } });
  const all = await w.Football.loadAll();
  assert.deepEqual(plain(all.matches.map(m => m.id)), ['a', 'b']);
  assert.equal(all.matches[0].comp, 'Premier League');
  assert.equal(all.matches[0].compKey, 'epl');
  assert.ok(all.notes.some(n => n === 'FIFA World Cup: no data yet'));
});

test('the blue info box always shows the same rows, plus a warning if there is one', () => {
  const w = page();
  const box = w.document.createElement('div');
  w.Football.infoBox(box, { api: 'https://x', plan: 'Free', left: '973 of 1,000 today', limit: '1,000 calls a day', reset: 'Daily at 00:00', last: 'not yet', warning: 'Paused' });
  const rows = box.children.map(r => r.textContent);
  assert.deepEqual(rows.slice(0, 6), ['APIhttps://x', 'PlanFree', 'Calls left973 of 1,000 today', 'Limit1,000 calls a day', 'ResetsDaily at 00:00', 'Last refreshednot yet']);
  assert.equal(rows[6], 'Paused');
  w.Football.infoBox(box, {});
  assert.ok(box.children.every(r => r.textContent.endsWith('unknown')));
});

test('hovering a badge creates its tooltip', () => {
  const w = page();
  let created = 0;
  w.bootstrap.Tooltip.getOrCreateInstance = () => { created++; return { show() {} }; };
  const b = w.Football.oddsBadge(2, 0.5);
  w.document.listeners.mouseover.forEach(f => f({ target: b }));
  w.document.listeners.mouseover.forEach(f => f({ target: w.document.createElement('span') }));
  assert.equal(created, 1);
});

test('getJson explains failures: the server\'s message, a restart for a missing route, else the status', async () => {
  const w = page({ routes: {
    '/ok': { a: 1 },
    '/not-saved': { status: 404, body: { message: 'Not saved yet' } },
    '/broken': { status: 500, body: {} }
  } });
  assert.equal((await w.Football.getJson('/ok')).a, 1);
  await assert.rejects(w.Football.getJson('/not-saved'), e => e.message === 'Not saved yet' && e.status === 404);
  await assert.rejects(w.Football.getJson('/new-route'), e => /needs a restart/.test(e.message) && e.status === 404);
  await assert.rejects(w.Football.getJson('/broken'), e => e.message === 'HTTP 500' && e.status === 500);
});

test('sendJson posts JSON, or nothing, with the method asked for', async () => {
  const w = page({ routes: { 'POST /a': { ok: 1 }, 'PUT /b': (url, opts) => JSON.parse(opts.body) } });
  await w.Football.sendJson('/a');
  assert.equal(w.calls[0].method, 'POST');
  assert.equal(w.calls[0].body, undefined);
  assert.deepEqual(plain(await w.Football.sendJson('/b', { teams: [1] }, 'PUT')), { teams: [1] });
});

test('the shared pop-up frame has a title, body, note, extra buttons, Cancel and Save, and shows itself', () => {
  const w = page();
  const modalEl = w.document.createElement('div');
  const frame = w.Football.modalFrame(modalEl, { title: 'Live Stats', body: '<div data-games>x</div>', note: 'data-estimate',
    buttons: [['Stop all', 'data-stop']] });
  assert.equal(modalEl.querySelector('.modal-title').textContent, 'Live Stats');
  assert.equal(frame.q('data-games').textContent, 'x');
  assert.ok(frame.q('data-estimate'));
  assert.deepEqual(modalEl.querySelectorAll('.modal-footer button').map(b => b.textContent), ['Stop all', 'Cancel', 'Save']);
  assert.ok(frame.modal.shown);
});
