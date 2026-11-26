const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const w = load(['football-data.js', 'value-check.js', 'live.js', 'in-play.js']);
const { InPlay, Live } = w;
const MODEL = { lh: 2.0, la: 1.5 };
const NAMES = ['Tottenham Hotspur', 'Ipswich Town'];
const ROWS_HT = [{ label: 'xG (est.)', home: '1.39', away: '0.31' }, { label: 'Shots on target', home: '7', away: '2' },
  { label: 'Dangerous attacks', home: '41', away: '16' }, { label: 'Corners', home: '5', away: '2' }];
const close = (a, b, d) => assert.ok(Math.abs(a - b) <= (d || 0.005), a + ' is not about ' + b);
const sum = (list, market) => list.filter(b => b.market === market).reduce((t, b) => t + b.chance, 0);

test('share of goals still to come: all before kick-off, a bit over half at half-time, only stoppage time at 90', () => {
  close(InPlay.shareLeft(0), 1);
  close(InPlay.shareLeft(45), 0.56, 0.01);
  close(InPlay.shareLeft(90), 0.046, 0.005);
  assert.ok(InPlay.shareLeft(80) > (1 - InPlay.shareLeft(10)), 'the last 10 minutes have more goals than the first 10');
});

test('goals still to come blend the pre-match model with live xG, and the team behind pushes', () => {
  const ht = InPlay.rates(MODEL, { period: 'HT', homeScore: '0', awayScore: '0', xg: [1.39, 0.31] });
  assert.equal(ht.minute, 45);
  close(ht.weight, 0.625);
  close(ht.pre[0], 2.0 * InPlay.shareLeft(45));
  assert.ok(ht.live[0] > ht.pre[0], 'Spurs created more than expected');
  assert.ok(ht.live[1] < ht.pre[1], 'Ipswich created less');
  close(ht.home, 0.625 * ht.pre[0] + 0.375 * ht.live[0]);

  const behind = InPlay.rates(MODEL, { period: 'HT', homeScore: '0', awayScore: '1', xg: [1.39, 0.31] });
  close(behind.home, ht.home * 1.15);
  close(behind.away, ht.away * 0.9);
  const noStats = InPlay.rates(MODEL, { period: '60', homeScore: 0, awayScore: 0 });
  assert.equal(noStats.live, null);
  close(noStats.home, noStats.pre[0]);
});

test('bets fit the window and each market adds up to 100%', () => {
  const r = InPlay.rates(MODEL, { period: 'HT', homeScore: '1', awayScore: '0', xg: [1.39, 0.31] });
  const ht = InPlay.bets(r, NAMES, 'HT');
  assert.deepEqual([...new Set(ht.map(b => b.market))], ['Draw No Bet', 'Next Goal', 'Team To Score Again', 'Second-Half Goals']);
  close(sum(ht, 'Draw No Bet'), 1);
  close(sum(ht, 'Next Goal'), 1);
  ht.forEach(b => close(b.minPrice, 1.05 / b.chance));
  assert.deepEqual([...ht.filter(b => b.market === 'Second-Half Goals').map(b => b.name)], ['Over 0.5', 'Over 1.5', 'Over 2.5']);

  const sixty = InPlay.bets(InPlay.rates(MODEL, { period: '60', homeScore: 1, awayScore: 1 }), NAMES, '60');
  close(sum(sixty, 'Match Result'), 1);
  assert.ok(sixty.some(b => b.market === 'Total Goals' && b.name === 'Over 2.5'), 'over lines start from the current total');
  assert.deepEqual([...InPlay.bets(r, NAMES, '80').map(b => b.market)], ['Next Goal', 'Next Goal', 'Next Goal']);
  assert.equal(InPlay.bets(r, NAMES, 'WATCH').length, 0);
  assert.equal(InPlay.bets(r, NAMES, 'FT').length, 0);
});

/** Stands in for the match page's table: keeps the rows it was given. */
function tableStub() {
  const t = (rows, title) => { t.rows = rows; t.title = title; return w.document.createElement('table'); };
  return t;
}

test('render: the better team not winning goes first with ★; none when already winning', () => {
  const ev = { home_team: NAMES[0], away_team: NAMES[1] };
  const check = Live.checklist(ROWS_HT);
  const box = w.document.createElement('div');
  const table = tableStub();
  InPlay.render(box, ev, MODEL, { period: 'HT', homeScore: '0', awayScore: '0', xg: [1.39, 0.31], rows: ROWS_HT }, check, 'drawing', { table });
  assert.equal(table.title, 'In-play bet');
  assert.match(table.rows[0].label, /^★ Tottenham Hotspur/);
  assert.equal(table.rows[0].o.price, null);
  assert.equal(table.rows[0].r, null, 'no price, no verdict');
  assert.match(table.rows[0].hint, /value at .* or bigger/);
  assert.equal(box.textContent, '', 'no notes above the table');

  InPlay.render(box, ev, MODEL, { period: 'HT', homeScore: '1', awayScore: '0', xg: [1.39, 0.31], rows: ROWS_HT }, check, 'winning', { table });
  assert.ok(table.rows.every(x => !x.fits));

  InPlay.render(box, ev, MODEL, { period: 'FT', homeScore: '2', awayScore: '0', rows: [] }, Live.checklist([]), null, { table });
  assert.equal(box.textContent, 'No Bet');
});

test('prices: fractions, evens and decimals; anything else is not a price', () => {
  assert.equal(InPlay.parsePrice('9/4'), 3.25);
  assert.equal(InPlay.parsePrice(' Evens '), 2);
  assert.equal(InPlay.parsePrice('2.5'), 2.5);
  assert.equal(InPlay.parsePrice('1'), null);
  assert.equal(InPlay.parsePrice('abc'), null);
  assert.equal(InPlay.parsePrice(''), null);
});

test('live prices are matched to bets: result by team, goals by Over and line', () => {
  const markets = [{ key: 'h2h', outcomes: [{ name: 'Tottenham Hotspur', price: 1.36 }, { name: 'Draw', price: 4.5 }] },
    { key: 'alternate_totals', outcomes: [{ name: 'Over', price: 1.5, point: 1.5 }, { name: 'Over', price: 2.6, point: 2.5 }] }];
  assert.equal(InPlay.livePrice(markets, { keys: ['h2h'], name: 'Tottenham Hotspur' }), 1.36);
  assert.equal(InPlay.livePrice(markets, { keys: ['alternate_totals', 'totals'], name: 'Over', point: 2.5 }), 2.6);
  assert.equal(InPlay.livePrice(markets, { keys: ['draw_no_bet'], name: 'Draw' }), null);
  assert.equal(InPlay.livePrice(null, { keys: ['h2h'], name: 'Draw' }), null);
});

test('render: live odds give the price and the same verdicts as the markets; Refresh shows for a live game', () => {
  const ev = { home_team: NAMES[0], away_team: NAMES[1] };
  const box = w.document.createElement('div');
  const table = tableStub();
  const markets = [{ key: 'alternate_totals', outcomes: [{ name: 'Over', price: 1.5, point: 1.5 }] }];
  let refreshed = 0;
  InPlay.render(box, ev, MODEL, { period: 'HT', homeScore: '1', awayScore: '0', xg: [1.39, 0.31], rows: ROWS_HT, live: true },
    Live.checklist(ROWS_HT), 'winning', { table, markets, fetchedAt: new Date().toISOString(), confidence: 'Low', onRefresh: () => refreshed++ });
  const over = table.rows.find(x => /Over 0\.5 · Second-Half Goals/.test(x.label));
  assert.equal(over.o.price, 1.5, 'second-half over 0.5 at 1-0 is the match over 1.5');
  assert.ok(over.r.verdict.text);
  assert.equal(table.rows[0], over, 'priced bets with the best return come first');
  assert.match(box.textContent, /Live odds from/);
  box.querySelector('button').click();
  assert.equal(refreshed, 1);
});

test('render: without odds for the point a dash (pre-match prices are not used), with them a verdict', () => {
  const ev = { home_team: NAMES[0], away_team: NAMES[1] };
  const box = w.document.createElement('div');
  const table = tableStub();
  InPlay.render(box, ev, MODEL, { period: 'HT', homeScore: '1', awayScore: '0', xg: [1.39, 0.31], rows: ROWS_HT },
    Live.checklist(ROWS_HT), 'winning', { table, confidence: 'Low' });
  const over = table.rows.find(x => /Over 0\.5 · Second-Half Goals/.test(x.label));
  assert.equal(over.o.price, null);
  assert.equal(over.r, null);
  assert.match(over.hint, /No odds for this point in the match/);

  const live = [{ key: 'alternate_totals', outcomes: [{ name: 'Over', price: 1.5, point: 1.5 }] }];
  InPlay.render(box, ev, MODEL, { period: 'HT', homeScore: '1', awayScore: '0', xg: [1.39, 0.31], rows: ROWS_HT, live: true },
    Live.checklist(ROWS_HT), 'winning', { table, markets: live, confidence: 'Low', onRefresh: () => {} });
  const liveOver = table.rows.find(x => /Over 0\.5 · Second-Half Goals/.test(x.label));
  assert.equal(liveOver.o.price, 1.5);
  assert.ok(liveOver.r.verdict);
});

test('chanceFor: our chance for the bookmaker in-play outcomes, from the score so far and the goals to come', () => {
  const r = { score: [1, 0], home: 0.8, away: 0.5 };
  const h2h = ['Tottenham Hotspur', 'Draw', 'Ipswich Town'].map(name => InPlay.chanceFor(r, NAMES, 'h2h', { name }));
  close(h2h[0] + h2h[1] + h2h[2], 1);
  assert.ok(h2h[0] > 0.6 && h2h[2] < 0.15, 'leading 1-0: home likely');
  close(InPlay.chanceFor(r, NAMES, 'draw_no_bet', { name: 'Tottenham Hotspur' }), h2h[0] / (h2h[0] + h2h[2]));
  assert.equal(InPlay.chanceFor(r, NAMES, 'alternate_totals', { name: 'Over', point: 0.5 }), 1, 'already over');
  close(InPlay.chanceFor(r, NAMES, 'totals', { name: 'Over', point: 1.5 }), 1 - Math.exp(-1.3));
  close(InPlay.chanceFor(r, NAMES, 'btts', { name: 'Yes' }), 1 - Math.exp(-0.5));
  assert.equal(InPlay.chanceFor(r, NAMES, 'player_goal_scorer_anytime', { name: 'Kane' }), null);
});
