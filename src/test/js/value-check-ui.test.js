const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const games = (gf, ga, n) => Array.from({ length: n }, () => ({ gf, ga }));
const event = (prices) => ({
  home_team: 'Home', away_team: 'Away',
  bookmakers: [{ key: 'skybet', markets: prices ? [{ key: 'h2h', outcomes: [
    { name: 'Home', price: prices[0] }, { name: 'Draw', price: prices[1] }, { name: 'Away', price: prices[2] }] }] : [] }]
});

test('the card shows a verdict per outcome, the goals markets and expected goals', () => {
  const w = load(['football-data.js', 'value-check.js']);
  const box = w.document.createElement('div');
  w.ValueCheck.render(box, event([2.1, 3.4, 3.6]), games(1.8, 1, 12), games(1.2, 1.4, 12));
  const labels = box.querySelectorAll('.section-label').map(l => l.textContent);
  assert.deepEqual(labels, ['Match Result']);   // only the match result is priced
  const rows = box.querySelectorAll('tbody tr').map(r => r.children[0].textContent);
  assert.deepEqual(rows, ['Home', 'Draw', 'Away']);
  assert.match(box.textContent, /Tick more markets in Refresh Odds/);
  assert.match(box.textContent, /Expected goals \d\.\d–\d\.\d/);
  assert.match(box.textContent, /(Very Likely|Likely|Coin Flip|Outsider|Long Shot|Moonshot)/);
  assert.doesNotMatch(box.textContent, / · \d/);   // no ranges on the badges
});

test('every priced market gets reviewed; corners, scorers and correct score are listed as not checked', () => {
  const w = load(['football-data.js', 'value-check.js']);
  const box = w.document.createElement('div');
  const ev = event([2.1, 3.4, 3.6]);
  const line = (p, over, under) => [{ name: 'Over', point: p, price: over }, { name: 'Under', point: p, price: under }];
  ev.bookmakers[0].markets.push(
    { key: 'alternate_totals', outcomes: [].concat(line(0.5, 1.05, 12), line(1.5, 1.3, 3.4), line(2.5, 1.9, 1.9), line(3.5, 3.2, 1.33), line(4.5, 7, 1.1)) },
    { key: 'btts', outcomes: [{ name: 'Yes', price: 1.7 }, { name: 'No', price: 2.1 }] },
    { key: 'h2h_3_way_h1', outcomes: [{ name: 'Home', price: 2.8 }, { name: 'Draw', price: 2.1 }, { name: 'Away', price: 4.5 }] },
    { key: 'alternate_totals_corners', outcomes: line(9.5, 1.9, 1.9) },
    { key: 'player_goal_scorer_anytime', outcomes: [{ name: 'Yes', description: 'Striker', price: 2.5 }] });
  const modal = w.document.createElement('div');   // the match page's "Show all" pop-up
  modal.id = 'marketModal';
  w.document.body.appendChild(modal);
  w.ValueCheck.render(box, ev, games(1.8, 1, 12), games(1.2, 1.4, 12));
  const labels = box.querySelectorAll('.section-label').map(l => l.textContent);
  assert.deepEqual(labels, ['Match Result', 'Over/Under Goals', 'Both Teams to Score', 'First-Half Result']);
  assert.doesNotMatch(box.textContent, /\[object/);
  const rows = box.querySelectorAll('tbody tr').map(r => r.children[0].textContent);
  // the main goals line (closest to 50/50) on the card, the others behind "Show all"
  assert.deepEqual(rows.slice(3, 5), ['Over 2.5', 'Under 2.5']);
  assert.match(box.textContent, /Show all 5 lines/);
  // goalscorers need the scorer tables (not loaded in this test); corners have no model yet
  assert.match(box.textContent, /Not checked yet: Over\/Under Corners, Anytime Goalscorer\./);
});

test('without results or without Sky Bet odds the card says so', () => {
  const w = load(['football-data.js', 'value-check.js']);
  const box = w.document.createElement('div');
  w.ValueCheck.render(box, event([2, 3, 4]), [], []);
  assert.match(box.textContent, /Not enough results yet/);
  w.ValueCheck.render(box, event(null), games(1, 1, 5), games(1, 1, 5));
  assert.match(box.textContent, /No Sky Bet match odds saved/);
});

test('changing the stake redraws the card', () => {
  const w = load(['football-data.js', 'value-check.js']);
  w.ValueCheck.rerender();   // nothing drawn yet: does nothing
  const box = w.document.createElement('div');
  w.ValueCheck.render(box, event([2.1, 3.4, 3.6]), games(1.8, 1, 12), games(1.2, 1.4, 12));
  box.replaceChildren();
  w.ValueCheck.rerender();
  assert.ok(box.children.length > 0);
});

test('pick badges and the confidence badge', () => {
  const w = load(['football-data.js', 'value-check.js']);
  const a = w.ValueCheck.analyse(event([2.1, 3.4, 3.6]), games(1.8, 1, 12), games(1.2, 1.4, 12), 5);
  const pair = w.ValueCheck.pair(a.rows[0], true);
  assert.match(pair.textContent, /^Home/);
  assert.equal(w.ValueCheck.pair(a.rows[0], false).textContent.startsWith('Home'), false);
  const cb = w.ValueCheck.confidenceBadge(a);
  assert.equal(cb.textContent, 'High');
  assert.match(cb.getAttribute('data-bs-title'), /12 home\/away games/);
});

test('verdicts cover every band', () => {
  const w = load(['football-data.js', 'value-check.js']);
  const seen = new Set();
  // Sweep prices against a fixed model so each verdict appears at least once
  for (let p = 1.2; p < 15; p += 0.1) {
    for (const n of [3, 12]) {
      const a = w.ValueCheck.analyse(event([p, 3.5, 4.0]), games(2, 1, n), games(1, 2, n), 5);
      a.rows.forEach(r => seen.add(r.verdict.text));
    }
  }
  for (const v of ['Great Value', 'Good Value', 'Possible Value', 'Fair Price', 'Small Cost', 'Poor Price']) {
    assert.ok(seen.has(v), 'never saw ' + v + ' in ' + [...seen]);
  }
});
