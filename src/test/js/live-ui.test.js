const test = require('node:test');
const assert = require('node:assert/strict');
const { load, settle, plain } = require('./load-scripts');

const NOW = new Date().toISOString();
const fx = (id, extra) => Object.assign({ id, home: 'Tottenham', away: 'Ipswich', tournament: 'epl', leagueId: 'L1',
  date: NOW.slice(0, 10), kickoff: NOW, status: 'SCHEDULED', live: false, homeScore: null, awayScore: null }, extra);
const state = (extra) => Object.assign({ configured: true, refreshMinutes: 5, maxGames: 2, warning: null, lastRefresh: null,
  usage: { limit: '1000', remaining: '973', reset: '1795132800' }, tracked: [], fixtures: [] }, extra);

test('LIVE badge shows the minute as of the last refresh and the score; FT when finished', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  assert.equal(Live.badge(fx('a', { live: true, status: 'LIVE', elapsed: '23', extra: '2', homeScore: 1, awayScore: 0 })).textContent, 'LIVE 23+2\'1–0');
  assert.equal(Live.badge(fx('a', { live: 'true', status: 'LIVE', elapsed: null, minute: 'Half Time', homeScore: '0', awayScore: '0' })).textContent, 'HT0–0');
  assert.equal(Live.badge(fx('a', { live: true, status: 'LIVE', elapsed: 'null', minute: '67' })).textContent, 'LIVE 67');
  assert.equal(Live.badge(fx('a', { status: 'FINISHED', homeScore: 2, awayScore: 1 })).textContent, 'FT2–1');
  assert.equal(Live.badge(fx('a', { status: 'SCHEDULED' })), null);
  assert.equal(Live.badge(null), null);
  assert.equal(Live.badge(fx('a', { status: 'FINISHED', homeScore: 'null' })).textContent, 'FT');
});

test('the Live Stats button gets a red dot while games are followed live', () => {
  const w = load(['football-data.js', 'live.js']);
  const btn = w.document.createElement('button');
  btn.textContent = 'Live Stats';
  w.Live.setButton(btn, state({ tracked: ['a'], fixtures: [fx('a')] }));
  assert.ok(btn.querySelector('.live-dot'));
  assert.match(btn.title, /following 1 game\(s\)/);
  w.Live.setButton(btn, state({ tracked: ['a'], fixtures: [fx('a', { status: 'FINISHED' })] }));
  assert.equal(btn.querySelector('.live-dot'), null);
  assert.equal(btn.title, 'Live Stats: no games followed');
});

test('getState explains when the app needs a restart', async () => {
  const w = load(['football-data.js', 'live.js'], { routes: {} });
  await assert.rejects(w.Live.getState(), /restart/);
  const ok = load(['football-data.js', 'live.js'], { routes: { '/api/football/live': state() } });
  assert.equal((await ok.Live.getState()).maxGames, 2);
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
  assert.match(c.box.textContent, /^(Match Not Started|No Live Stats)$/);
});

test('match card: followed but not started shows Pre-Match and a stop button (points appear as the game reaches them)', async () => {
  const c = card({ '/api/football/live/match/a': { fixture: fx('a'), tracked: true, stats: null, refreshMinutes: 5 } },
    state({ tracked: ['a'], fixtures: [fx('a')] }));
  await c.run();
  const buttons = c.box.querySelectorAll('.live-timeline button');
  assert.deepEqual(buttons.map(b => b.textContent), ['Pre-Match', '■']);
  assert.deepEqual(buttons.map(b => b.disabled), [false, false]);
  assert.match(c.box.textContent, /Match Not Started/);
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

  const live34 = c.box.querySelector('.live-timeline button[data-period="LIVE"]');
  assert.equal(live34.textContent, 'LIVE 34\'', 'a LIVE point for the latest stats');
  assert.ok(live34.classList.contains('active'), 'opens on LIVE');
  const rows = c.box.querySelectorAll('.stats-table')[0].querySelectorAll('tbody tr');   // the stats (the players' table follows)
  assert.equal(rows.length, 2);
  assert.equal(rows[0].children[1].textContent, 'xG (est.)');
  assert.match(rows[0].children[1].getAttribute('data-bs-title'), /0\.12 per shot inside the box/);
  assert.ok(rows[0].children[0].classList.contains('fw-semibold'));
  assert.ok(rows[1].children[2].classList.contains('fw-semibold'));
  assert.match(c.box.textContent, /Kane 3 \(4\) ⚽/);
  assert.match(c.box.textContent, /Shots on target/);
  assert.equal(c.foot.textContent, '');
});

test('match card: full time is noted in the footer', async () => {
  const done = fx('a', { status: 'FINISHED', homeScore: 2, awayScore: 2 });
  const c = card({ '/api/football/live/match/a': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: new Date(Date.now() - 20 * 60000).toISOString(), final: 'true', rows: [{ label: 'Shots', home: '9', away: '9' }], homePlayers: [], awayPlayers: [] } } },
    state({ tracked: ['a'], fixtures: [done] }));
  await c.run();
  assert.equal(c.foot.textContent, '', 'no "Updated … ago" note');
  assert.doesNotMatch(c.box.textContent, /FT2–2/, 'the score is in the page header');
});

// ---- checklist, windows and the snapshot timeline ----

const ROWS_HT = [{ label: 'xG (est.)', home: '1.39', away: '0.31' }, { label: 'Shots on target', home: '7', away: '2' },
  { label: 'Dangerous attacks', home: '41', away: '16' }, { label: 'Corners', home: '5', away: '2' }];
const ROWS_FT = [{ label: 'xG (est.)', home: '1.99', away: '0.81' }, { label: 'Shots on target', home: '9', away: '3' },
  { label: 'Dangerous attacks', home: '59', away: '41' }, { label: 'Corners', home: '8', away: '6' }];

test('checklist: who leads each item, the leader needs 3 and more than the other side, 4 is a strong signal', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  const c = Live.checklist(ROWS_HT);
  assert.equal(c.home, 4); assert.equal(c.away, 0); assert.equal(c.of, 4);
  assert.equal(c.leader, 'home'); assert.equal(c.signal, 'strong');
  const split = Live.checklist([{ label: 'xG (est.)', home: '1', away: '2' }, { label: 'Corners', home: '3', away: '1' },
    { label: 'Shots on target', home: '2', away: '2' }, { label: 'Dangerous attacks', home: '9', away: '4' }]);
  assert.equal(split.leader, null); assert.equal(split.signal, 'none');
  assert.equal(Live.checklist([]).of, 0);
});

test('setup: is the better team winning, drawing or losing?', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  const c = Live.checklist(ROWS_HT);
  assert.equal(Live.setup(c, '1', '0'), 'winning');
  assert.equal(Live.setup(c, 0, 0), 'drawing');
  assert.equal(Live.setup(c, '0', '1'), 'losing');
  assert.equal(Live.setup(Live.checklist([]), 0, 0), null);
});

test('windows follow the framework minutes', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  const at = e => Live.windowFor({ live: true, status: 'LIVE', elapsed: String(e) });
  assert.deepEqual([10, 30, 40, 50, 65, 75, 88].map(at), ['WATCH', '25', 'HT', '45', '60', '70', '80']);
  assert.equal(Live.windowFor({ live: true, status: 'LIVE', minute: 'Half Time' }), 'HT');
  assert.equal(Live.windowFor({ status: 'FINISHED' }), 'FT');
  assert.equal(Live.windowFor({ status: 'SCHEDULED' }), null);
});

test('match card: a played game not followed offers to load its stats once', async () => {
  const past = '2026-11-21T15:00:00Z';
  const replayed = { fixture: fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0', kickoff: past }), refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'FT', homeScore: '2', awayScore: '0', rows: ROWS_FT }] };
  const w = load(['football-data.js', 'live.js'], { routes: { '/api/football/live/replay*': replayed } });
  const box = w.document.createElement('div'), foot = w.document.createElement('span');
  const ev = { sport_key: 'soccer_epl', home_team: 'Tottenham Hotspur', away_team: 'Ipswich Town', commence_time: past };
  await w.Live.renderCard(box, foot, state(), ev);
  const btn = box.querySelector('button');
  assert.equal(btn.textContent, 'Post-Match');
  btn.click();
  await settle();
  const call = w.calls.find(c => c.url.startsWith('/api/football/live/replay'));
  assert.equal(call.method, 'POST');
  assert.match(call.url, /tournament=epl&date=2026-11-21&kickoff=.*&home=Tottenham%20Hotspur&away=Ipswich%20Town/);
  assert.ok(box.querySelector('.live-timeline'), 'the timeline is shown');
  assert.equal(w.notes[0].type, 'success');
});

test('match card: snapshots give a timeline; HT shows its checklist and stats', async () => {
  const done = fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [{ name: 'Solanke', shots: 4, onTarget: 2, goals: 1 }], awayPlayers: [] },
    snapshots: [{ period: 'HT', homeScore: '0', awayScore: '0', rows: ROWS_HT }, { period: 'FT', homeScore: '2', awayScore: '0', rows: ROWS_FT }] } },
    state({ fixtures: [Object.assign({}, done, { saved: true })] }));   // saved but no longer ticked still shows
  await c.run();
  const buttons = c.box.querySelectorAll('.live-timeline button');
  assert.deepEqual(buttons.map(b => b.textContent), ['Pre-Match', 'HT', 'FT'], 'points not saved in a finished game are hidden');
  assert.ok(buttons[2].classList.contains('active'), 'latest first');
  assert.doesNotMatch(c.box.textContent, /Full time|Settle/, 'no window notes');
  assert.match(c.box.textContent, /Tottenham Hotspur 4\/4/);
  assert.doesNotMatch(c.box.textContent, /better side|signal|Golden Rule/, 'just the checklist, no comments');
  assert.match(c.box.textContent, /Solanke 2 \(4\) ⚽/);

  buttons[1].click();
  assert.ok(buttons[1].classList.contains('active'));
  assert.doesNotMatch(c.box.textContent, /Half-time window/);
  assert.doesNotMatch(c.box.textContent, /Ideal setup/);
  assert.doesNotMatch(c.box.textContent, /Solanke/, 'shooters are full-time only');

  const picked = [];
  c.w.onLiveView = x => picked.push(x.period);
  buttons[0].click();
  assert.match(c.box.textContent, /Match Not Started/);
  assert.deepEqual([...picked], ['PRE'], 'the page switches Best Bets to the pre-match bets');
});

// ---- momentum ----

const P_HT = [{ label: 'Shots', home: '13', away: '4' }, { label: 'Shots on target', home: '7', away: '2' },
  { label: 'Dangerous attacks', home: '41', away: '16' }, { label: 'Corners', home: '5', away: '2' }, { label: 'Possession', home: '67%', away: '33%' }];
const P_FT = [{ label: 'Shots', home: '18', away: '11' }, { label: 'Shots on target', home: '9', away: '3' },
  { label: 'Dangerous attacks', home: '59', away: '41' }, { label: 'Corners', home: '8', away: '6' }, { label: 'Possession', home: '63%', away: '37%' }];

test('momentum: weighted pressure from attacking actions, and the second half from FT minus HT', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  const ht = Live.momentum(P_HT);
  assert.equal(ht.home, 41 + 13 * 3 + 7 * 5 + 5 * 2);
  assert.equal(ht.away, 16 + 4 * 3 + 2 * 5 + 2 * 2);
  const second = Live.since(P_FT, P_HT);
  assert.deepEqual(second.map(r => r.label + ' ' + r.home + '-' + r.away),
    ['Shots 5-7', 'Shots on target 2-1', 'Dangerous attacks 18-25', 'Corners 3-4']);
  const m2 = Live.momentum(second);
  assert.ok(m2.homeShare < 0.5, 'Ipswich had more of the second half');
  assert.equal(Live.momentum([]), null);
  // a little action doesn't swing it all the way: 10-0 is 75%, not 100%
  assert.equal(Live.momentum([{ label: 'Dangerous attacks', home: '5', away: '0' }, { label: 'Shots', home: '1', away: '0' },
    { label: 'Corners', home: '1', away: '0' }]).homeShare, 0.75);
  const names = ['Spurs', 'Ipswich'];
  assert.equal(Live.momentumLabel({ homeShare: 0.75 }, names), 'Spurs on top');
  assert.equal(Live.momentumLabel({ homeShare: 0.43 }, names), 'Ipswich edging it');
  assert.equal(Live.momentumLabel({ homeShare: 0.52 }, names), 'Even');
});

test('match card: one momentum bar, since the last point (or the match so far when there is none)', async () => {
  const done = fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: P_FT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'HT', homeScore: '1', awayScore: '0', rows: P_HT }, { period: 'FT', homeScore: '2', awayScore: '0', rows: P_FT }] } },
    state({ tracked: ['g1'], fixtures: [done] }));
  await c.run();
  const bars = c.box.querySelectorAll('.momentum-bar');
  assert.equal(bars.length, 1);
  assert.match(c.box.textContent, /Since HT: Even/);   // 49 v 59 pressure: close, after smoothing
  assert.doesNotMatch(c.box.textContent, /Match so far/);
  c.box.querySelectorAll('.live-timeline button[data-period="HT"]')[0].click();   // HT: no earlier point
  assert.equal(c.box.querySelectorAll('.momentum-bar').length, 1);
  assert.match(c.box.textContent, /Match so far: Tottenham Hotspur on top/);
});

test('status: the minute while live, FT when finished, nothing before kick-off; the match page header gets it', async () => {
  const w = load(['football-data.js', 'live.js']);
  assert.equal(w.Live.status(fx('a', { live: true, status: 'LIVE', elapsed: '61' })), "61'");
  assert.equal(w.Live.status(fx('a', { live: true, status: 'LIVE', elapsed: null, minute: 'Half Time' })), 'HT');
  assert.equal(w.Live.status(fx('a', { status: 'FINISHED' })), 'FT');
  assert.equal(w.Live.status(fx('a', { status: 'SCHEDULED' })), null);

  const live = fx('a', { live: true, status: 'LIVE', elapsed: '61', homeScore: 1, awayScore: 0 });
  const c = card({ '/api/football/live/match/a': { fixture: live, stats: { updatedAt: NOW, rows: [] }, refreshMinutes: 5 } },
    state({ tracked: ['a'], fixtures: [live] }));
  const seen = [];
  c.w.onLiveFixture = x => seen.push(x);
  await c.run();
  assert.deepEqual({ ...seen[0] }, { status: "61'", score: '1–0', cards: null });
});

test('match card: the timeline can go elsewhere on the page (the match page header)', async () => {
  const done = fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'FT', homeScore: '2', awayScore: '0', rows: ROWS_FT }] } },
    state({ fixtures: [Object.assign({}, done, { saved: true })] }));
  const host = c.w.document.createElement('div');
  c.w.Live.setTimelineHost(host);
  await c.run();
  assert.equal(c.box.querySelectorAll('.live-timeline').length, 0);
  assert.equal(host.querySelectorAll('.live-timeline button').length, 2);
  host.querySelectorAll('.live-timeline button')[0].click();
  assert.match(c.box.textContent, /Match Not Started/, 'the buttons still drive the card');
});

test('timeline during a live game: only the points reached, then LIVE', async () => {
  const live = fx('g1', { live: true, status: 'LIVE', elapsed: '50', homeScore: '1', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: live, refreshMinutes: 5,
    stats: { updatedAt: NOW, rows: ROWS_HT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'HT', homeScore: '1', awayScore: '0', rows: ROWS_HT }] } },
    state({ tracked: ['g1'], fixtures: [live] }));
  await c.run();
  const buttons = c.box.querySelectorAll('.live-timeline button');
  assert.deepEqual(buttons.map(b => b.textContent), ['Pre-Match', 'HT', 'LIVE 50\'']);
  assert.deepEqual(buttons.map(b => b.disabled), [false, false, false]);
});

test('match card: with the timeline in the page header, the Load button goes there and the timeline replaces it', async () => {
  const past = '2026-11-21T15:00:00Z';
  const replayed = { fixture: fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0', kickoff: past }), refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'FT', homeScore: '2', awayScore: '0', rows: ROWS_FT }] };
  const w = load(['football-data.js', 'live.js'], { routes: { '/api/football/live/replay*': replayed } });
  const box = w.document.createElement('div'), foot = w.document.createElement('span'), host = w.document.createElement('div');
  w.Live.setTimelineHost(host);
  await w.Live.renderCard(box, foot, state(), { sport_key: 'soccer_epl', home_team: 'Tottenham Hotspur', away_team: 'Ipswich Town', commence_time: past });
  assert.equal(box.querySelector('button'), null);
  host.querySelector('button').click();
  await settle();
  assert.deepEqual(host.querySelectorAll('button').map(b => b.textContent), ['Pre-Match', 'FT']);
});

test('match card: Checklist and player shots in cards of their own', async () => {
  const done = fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [{ name: 'Solanke', shots: 4, onTarget: 2, goals: 1 }], awayPlayers: [] },
    snapshots: [{ period: 'HT', homeScore: '1', awayScore: '0', rows: ROWS_HT }, { period: 'FT', homeScore: '2', awayScore: '0', rows: ROWS_FT }] } },
    state({ fixtures: [Object.assign({}, done, { saved: true })] }));
  const d = c.w.document, timeline = d.createElement('div'), checklist = d.createElement('div'), players = d.createElement('div');
  c.w.Live.setTimelineHost(timeline);
  c.w.Live.setHosts({ checklist, players });
  await c.run();
  assert.match(checklist.textContent, /Tottenham Hotspur 4\/4/);
  assert.doesNotMatch(c.box.textContent, /Checklist|Solanke/, 'not in the stats card');
  assert.match(players.textContent, /No timeline for this game/);   // not followed live: no events
  timeline.querySelector('button[data-period="HT"]').click();
  assert.match(players.textContent, /No timeline for this game/);
  timeline.querySelector('button[data-period="PRE"]').click();
  assert.equal(checklist.textContent, 'Match Not Started');
  assert.equal(players.textContent, 'Match Not Started');
});

test('pointFor: the last timeline point a live game has passed', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  const at = (elapsed, extra) => Object.assign(fx('a', { live: true, status: 'LIVE', elapsed }), extra || {});
  assert.equal(Live.pointFor(at('5')), null);
  assert.equal(Live.pointFor(at('12')), null);   // no 10' window
  assert.equal(Live.pointFor(at('27')), null);
  assert.equal(Live.pointFor(at('30')), '30');
  assert.equal(Live.pointFor(at(null, { minute: 'Half Time' })), 'HT');
  assert.equal(Live.pointFor(at('52')), 'HT');
  assert.equal(Live.pointFor(at('65')), '60');
  assert.equal(Live.pointFor(at('88')), '80');
  assert.equal(Live.pointFor(fx('a', { status: 'FINISHED' })), null);
});

test('match card: an upcoming game not followed shows Pre-Match and a play button that follows it', async () => {
  const ko = new Date(Date.now() + 3 * 86400000).toISOString();
  const ev = { sport_key: 'soccer_epl', home_team: 'Tottenham Hotspur', away_team: 'Ipswich Town', commence_time: ko };
  const following = { following: [{ tournament: 'epl', kickoff: ko, home: 'Tottenham Hotspur', away: 'Ipswich Town' }] };
  const w = load(['football-data.js', 'live.js'], { routes: { '/api/football/live/follow*': state(following) } });
  const box = w.document.createElement('div'), foot = w.document.createElement('span'), host = w.document.createElement('div');
  w.Live.setTimelineHost(host);
  await w.Live.renderCard(box, foot, state(), ev);
  assert.deepEqual(host.querySelectorAll('button').map(b => b.textContent), ['Pre-Match', '▶']);
  assert.equal(box.textContent, 'Match Not Started');

  host.querySelector('button[data-follow="on"]').click();
  await settle();
  const call = w.calls.find(c => c.url.startsWith('/api/football/live/follow'));
  assert.equal(call.method, 'POST');
  assert.match(call.url, /tournament=epl&kickoff=.*&home=Tottenham%20Hotspur&away=Ipswich%20Town&on=true/);
  assert.deepEqual(host.querySelectorAll('button').map(b => b.textContent), ['Pre-Match', '■']);
  assert.equal(w.notes[0].type, 'success');
});

test('match card: a point picked by hand stays picked when the stats are re-read', async () => {
  const live = fx('g1', { live: true, status: 'LIVE', elapsed: '50', homeScore: '1', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: live, refreshMinutes: 5,
    stats: { updatedAt: NOW, rows: ROWS_HT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'HT', homeScore: '1', awayScore: '0', rows: ROWS_HT }] } },
    state({ tracked: ['g1'], fixtures: [live] }));
  await c.run();
  const active = () => c.box.querySelector('.live-timeline button.active').getAttribute('data-period');
  assert.equal(active(), 'LIVE');
  c.box.querySelector('.live-timeline button[data-period="HT"]').click();
  await c.run();   // the minute-by-minute re-read
  assert.equal(active(), 'HT');
});

test('match card: the page hides its live cards before kick-off and on Pre-Match, and shows them for a point', async () => {
  const done = fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '0' });
  const c = card({ '/api/football/live/match/g1': { fixture: done, refreshMinutes: 5,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [], awayPlayers: [] },
    snapshots: [{ period: 'FT', homeScore: '2', awayScore: '0', rows: ROWS_FT }] } },
    state({ fixtures: [Object.assign({}, done, { saved: true })] }));
  const shown = [];
  c.w.onLiveCards = x => shown.push(x);
  await c.run();
  c.box.querySelector('.live-timeline button[data-period="PRE"]').click();
  assert.deepEqual(shown, [true, false]);

  const w = load(['football-data.js', 'live.js']);
  const seen = [];
  w.onLiveCards = x => seen.push(x);
  const ev = { sport_key: 'soccer_epl', home_team: 'A', away_team: 'B', commence_time: new Date(Date.now() + 86400000).toISOString() };
  await w.Live.renderCard(w.document.createElement('div'), w.document.createElement('span'), state(), ev);
  assert.deepEqual(seen, [false], 'not started: hidden');
});

test('Live Stats pop-up: GOAL API calls left and the games followed live', async () => {
  const st = state({
    tracked: ['g1'], fixtures: [fx('g1', { live: true, status: 'LIVE', elapsed: '34', homeScore: 1, awayScore: 0 })],
    following: [{ tournament: 'epl', kickoff: '2026-11-29T16:30:00Z', home: 'Chelsea', away: 'Arsenal' }] });
  const w = load(['football-data.js', 'live.js'], { routes: {
    '/api/football/live': st, '/api/odds/cache/quota': { remaining: '420', used: '80', ok: true } } });
  const modal = w.document.createElement('div');
  await w.Live.openModal(modal);
  assert.match(modal.textContent, /973 of 1,000 today/);
  assert.doesNotMatch(modal.textContent, /Odds API/);
  const rows = modal.querySelectorAll('tbody tr').map(r => r.textContent);
  assert.equal(rows.length, 2);
  assert.match(rows[0], /Tottenham v Ipswich.*LIVE 34'/);
  assert.match(rows[1], /Chelsea v Arsenal.*Waiting for match day/);
  assert.equal(modal.querySelectorAll('input[type=checkbox]').length, 0, 'no tick boxes');
  assert.equal(modal.querySelector('[data-save]'), null, 'no Save');
});

test('Live Stats pop-up: nothing followed says how to follow', async () => {
  const w = load(['football-data.js', 'live.js'], { routes: { '/api/football/live': state(), '/api/odds/cache/quota': { status: 500, body: {} } } });
  const modal = w.document.createElement('div');
  await w.Live.openModal(modal);
  assert.match(modal.textContent, /No games followed\./);
  assert.doesNotMatch(modal.textContent, /Follow a game with/);
});

test('Live Stats pop-up: games that finished hours ago are not listed', async () => {
  const old = new Date(Date.now() - 5 * 3600000).toISOString();
  const w = load(['football-data.js', 'live.js'], { routes: {
    '/api/football/live': state({ tracked: ['g1'], fixtures: [fx('g1', { status: 'FINISHED', kickoff: old })] }),
    '/api/odds/cache/quota': { remaining: '420', used: '80' } } });
  const modal = w.document.createElement('div');
  await w.Live.openModal(modal);
  assert.match(modal.textContent, /No games followed\./);
});

test('a game last seen live long ago is not shown as LIVE (nothing refreshes games nobody follows)', () => {
  const { Live } = load(['football-data.js', 'live.js']);
  const ago = h => new Date(Date.now() - h * 3600000).toISOString();
  assert.ok(Live.isLive(fx('a', { live: true, status: 'LIVE', elapsed: '68', kickoff: ago(1), updatedAt: ago(0.1) })));
  assert.equal(Live.isLive(fx('a', { live: true, status: 'LIVE', elapsed: '68', kickoff: ago(1.5), updatedAt: ago(0.5) })), false, 'not refreshed for 30 min');
  assert.equal(Live.isLive(fx('a', { live: true, status: 'LIVE', elapsed: '68', kickoff: ago(16) })), false, 'kicked off yesterday');
  assert.equal(Live.badge(fx('a', { live: true, status: 'LIVE', elapsed: '68', kickoff: ago(16), homeScore: 1, awayScore: 2 })), null,
    'no stale LIVE 68\' badge: the list falls back to FT from the clock');
});

test('Live Stats pop-up: Post-Match Games lists the finished games; Refresh loads the ones without stats (no odds)', async () => {
  const games = [
    { tournament: 'uefa_champs_league', kickoff: '2026-11-25T20:00:00Z', home: 'Sporting Lisbon', away: 'Manchester United', saved: false },
    { tournament: 'uefa_champs_league', kickoff: '2026-11-25T20:00:00Z', home: 'Club Brugge', away: 'Liverpool', saved: false },
    { tournament: 'epl', kickoff: '2026-11-21T15:00:00Z', home: 'Tottenham Hotspur', away: 'Ipswich Town', saved: true,
      fx: fx('g9', { status: 'FINISHED', homeScore: '2', awayScore: '0' }) }];
  const w = load(['football-data.js', 'live.js'], { routes: { '/api/football/live': state(),
    '/api/football/live/post-match': { loaded: 2, skipped: 0, missing: [], calls: 3 } } });
  const modal = w.document.createElement('div');
  let done = null;
  await w.Live.openModal(modal, { postMatch: games, onDone: r => { done = r; } });
  const labels = modal.querySelectorAll('.section-label').map(x => x.textContent);
  assert.deepEqual(labels, ['Live Games', 'Post-Match Games']);
  const post = modal.querySelector('[data-post-games]');
  assert.match(post.textContent, /2 without stats yet\. Refresh loads them: about 3 GOAL API calls, no odds\./);
  const rows = post.querySelectorAll('tbody tr').map(r => r.textContent);
  assert.equal(rows.length, 2, 'games with stats are not listed');
  assert.doesNotMatch(post.textContent, /Tottenham/);
  assert.match(rows[0], /No stats yet/);
  assert.equal(modal.querySelector('.modal-footer').textContent, 'CloseRefresh');

  modal.querySelector('[data-post]').click();
  await settle();
  const call = w.calls.find(c => c.url === '/api/football/live/post-match');
  assert.equal(call.method, 'POST');
  assert.equal(JSON.parse(call.body).games.length, 2, 'only the ones without stats');
  assert.equal(w.notes[0].message, 'Post-Match: stats loaded for 2 game(s) (3 GOAL API calls).');
  assert.equal(done.loaded, 2);
});

test('status labels are the same colours everywhere: a minute red, HT yellow, FT dark green', () => {
  const w = load(['football-data.js', 'live.js']);
  const cls = t => w.Football.statusBadge(t) && w.Football.statusBadge(t).className;
  assert.match(cls('34\''), /status-live/);
  assert.match(cls('45+2\''), /status-live/);
  assert.match(cls('LIVE 34\''), /status-live/);
  assert.match(cls('HT'), /status-ht/);
  assert.match(cls('FT'), /status-ft/);
  assert.match(cls('Pre-Match'), /status-pre/);
  assert.equal(w.Football.statusBadge('Kicks off in 3 h'), null);
  const b = l => w.Live.badge(fx('a', l)).children[0].className;
  assert.match(b({ live: true, status: 'LIVE', elapsed: '34' }), /status-live/);
  assert.match(b({ live: true, status: 'LIVE', minute: 'HT' }), /status-ht/);
  assert.match(b({ status: 'FINISHED' }), /status-ft/);
});

test('Timeline card: newest first, home left / away right, and only what had happened by the point picked', async () => {
  const live = fx('g1', { status: 'FINISHED', homeScore: '2', awayScore: '1' });
  const events = [
    { kind: 'kickoff', minute: "0'", half: 1, elapsed: 0, order: 0 },
    { kind: 'goal', side: 'home', player: 'Solanke', score: '1–0', minute: "23'", half: 1, elapsed: 23, order: 102300 },
    { kind: 'yellow', side: 'away', player: 'Mumba', minute: "40'", half: 1, elapsed: 40, order: 104000 },
    { kind: 'red', side: 'away', player: 'Mumba', minute: "40'", half: 1, elapsed: 40, order: 104000 },
    { kind: 'ht', score: '1–0', minute: 'HT', half: 1, elapsed: 45, order: 104501 },
    { kind: 'shot', side: 'away', player: 'Delap', minute: "55'", half: 2, elapsed: 55, order: 205500 },
    { kind: 'goal', side: 'away', player: null, score: '1–1', minute: "70'", half: 2, elapsed: 70, order: 207000 },
    { kind: 'goal', side: 'home', player: 'Kudus', assist: 'Maddison', info: 'Penalty', score: '2–1', minute: "88'", half: 2, elapsed: 88, order: 208800 },
    { kind: 'ft', score: '2–1', minute: 'FT', half: 2, elapsed: 90, order: 209002 }];
  const c = card({ '/api/football/live/match/g1': { fixture: live, refreshMinutes: 1, events,
    stats: { updatedAt: NOW, final: true, rows: ROWS_FT, homePlayers: [{ name: 'Solanke', shots: 4, onTarget: 2, goals: 1 }], awayPlayers: [] },
    snapshots: [{ period: 'HT', homeScore: '1', awayScore: '0', rows: ROWS_HT }, { period: '60', homeScore: '1', awayScore: '0', rows: ROWS_HT },
      { period: 'FT', homeScore: '2', awayScore: '1', rows: ROWS_FT }] } },
    state({ fixtures: [Object.assign({}, live, { saved: true })] }));
  const d = c.w.document, timeline = d.createElement('div'), checklist = d.createElement('div'), players = d.createElement('div');
  c.w.Live.setTimelineHost(timeline);
  c.w.Live.setHosts({ checklist, players });
  await c.run();
  const t = players.textContent;
  assert.match(t, /FT2–1/);
  assert.match(t, /GOAL2–1Tottenham Hotspur|GOAL2–1Spurs|GOAL2–1/);
  assert.ok(t.indexOf('Kudus') < t.indexOf('Solanke'), 'newest first');
  assert.ok(t.indexOf('RED') < t.indexOf('YELLOW'), 'same minute: the red (after the second yellow) on top');
  assert.match(t, /SOT.*Delap/);
  assert.match(t, /Scorer not known yet/);
  assert.match(t, /Kudus\(pen\)Assist: Maddison/);
  assert.doesNotMatch(t, /Solanke 2 \(4\)/, 'the timeline, not the shooters table');
  timeline.querySelector('button[data-period="HT"]').click();
  assert.match(players.textContent, /Solanke/);
  assert.match(players.textContent, /HT1–0/);
  assert.doesNotMatch(players.textContent, /Delap|Kudus/);
  timeline.querySelector('button[data-period="60"]').click();
  assert.match(players.textContent, /Delap/);
  assert.doesNotMatch(players.textContent, /Kudus|FT2/);
});
