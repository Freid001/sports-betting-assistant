const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const VC = load(['football-data.js', 'value-check.js']).ValueCheck;

const games = (gf, ga, n) => Array.from({ length: n }, () => ({ gf, ga }));
const event = (home, draw, away) => ({
  home_team: 'Home', away_team: 'Away',
  bookmakers: [{ key: 'skybet', markets: [{ key: 'h2h', outcomes: [
    { name: 'Home', price: home }, { name: 'Draw', price: draw }, { name: 'Away', price: away }] }] }]
});

test('goals model: evenly matched teams are equally likely to win', () => {
  const m = VC.model(games(1.5, 1.5, 10), games(1.5, 1.5, 10));
  assert.ok(Math.abs(m.home - m.away) < 1e-9);
  assert.ok(Math.abs(m.home + m.draw + m.away - 1) < 1e-9);
});

test('goals model: a stronger home side is favourite', () => {
  const m = VC.model(games(2.5, 0.5, 10), games(0.8, 2.0, 10));
  assert.ok(m.home > 0.6, 'home ' + m.home);
  assert.ok(m.over25 > 0.5);
});

test('no saved games means no model', () => {
  assert.equal(VC.model([], games(1, 1, 5)), null);
});

test('our chance moves at most a fifth of Sky Bet\'s fair chance, and still adds to 100%', () => {
  // Stats say the away side is much stronger than the odds suggest
  const a = VC.analyse(event(1.5, 4.5, 7.0), games(0.5, 2.5, 20), games(2.5, 0.5, 20), 10);
  const total = a.rows.reduce((t, r) => t + r.ours, 0);
  assert.ok(Math.abs(total - 1) < 1e-9, 'total ' + total);
  a.rows.forEach(r => {
    assert.ok(r.ours <= r.fair * 1.2 + 1e-9 && r.ours >= r.fair * 0.8 - 1e-9, r.name + ' moved too far: ' + r.fair + ' -> ' + r.ours);
    assert.ok(Math.abs(r.ours - r.fair) <= 0.05 + 1e-9, r.name + ' moved more than 5 points');
  });
});

test('confidence follows the number of games', () => {
  assert.equal(VC.analyse(event(2, 3.4, 3.8), games(1, 1, 3), games(1, 1, 3), 5).level.name, 'Very Low');
  assert.equal(VC.analyse(event(2, 3.4, 3.8), games(1, 1, 10), games(1, 1, 10), 5).level.name, 'Medium');
  assert.equal(VC.analyse(event(2, 3.4, 3.8), games(1, 1, 25), games(1, 1, 25), 5).level.name, 'Very High');
});

test('with thin data a good price is only "Possible Value"', () => {
  const a = VC.analyse(event(1.5, 4.5, 7.0), games(0.5, 2.5, 3), games(2.5, 0.5, 3), 5);
  const valued = a.rows.filter(r => r.ret >= 0.05);
  assert.ok(valued.length > 0);
  valued.forEach(r => assert.equal(r.verdict.text, 'Possible Value'));
});

test('chance tags use the guide\'s bands', () => {
  const a = VC.analyse(event(1.3, 5.5, 11), games(1.5, 1, 12), games(1, 1.5, 12), 5);
  assert.ok(['Very Likely', 'Likely'].includes(a.rows[0].tag));
  assert.ok(['Long Shot', 'Moonshot'].includes(a.rows[2].tag));
});

test('"Very Likely" is 75%+ and "Likely" 50-74%', () => {
  const a = VC.analyse(event(1.15, 8, 15), games(1.5, 1, 12), games(1, 1.5, 12), 5);
  assert.equal(a.rows[0].tag, 'Very Likely');
  const b = VC.analyse(event(1.7, 3.8, 5), games(1.5, 1, 12), games(1, 1.5, 12), 5);
  assert.equal(b.rows[0].tag, 'Likely');
});

test('half-time/full-time: all 9 reviewed, chances add up to 100%, the 3 most likely shown first', () => {
  const ev = event(2, 3.4, 3.8);
  const names = ['Home', 'Draw', 'Away'];
  const outcomes = [];
  names.forEach(h => names.forEach(f => outcomes.push({ name: h + '/' + f, price: 10 })));
  ev.bookmakers[0].markets.push({ key: 'halftime_fulltime', outcomes });
  const a = VC.analyse(ev, games(1.5, 1, 12), games(1, 1.5, 12), 5);
  const htft = a.markets.find(m => m.key === 'halftime_fulltime');
  assert.equal(htft.rows.length, 9);
  assert.equal(htft.show, 3);
  const total = htft.rows.reduce((t, r) => t + r.stats, 0);
  assert.ok(Math.abs(total - 1) < 0.01);
});

test('goalscorers: every player of either team who has scored, most likely first', () => {
  const w = load(['football-data.js', 'value-check.js']);
  w.TopScorers = {
    home: { team: 'Home', players: ['Striker'], scorers: [{ name: 'Striker', goals: 8, games: 10 }, { name: 'Defender', goals: 1, games: 10 }] },
    away: { team: 'Away', players: ['Winger'], scorers: [{ name: 'Winger Jones', goals: 3, games: 10 }, { name: 'Nobody', goals: 2, games: 0 }] }
  };
  const ev = event(2, 3.4, 3.8);
  ev.bookmakers[0].markets.push({ key: 'player_goal_scorer_anytime', outcomes: [
    { name: 'Yes', description: 'Striker', price: 2.2 }, { name: 'Yes', description: 'Defender', price: 9 },
    { name: 'Yes', description: 'W. Jones', price: 4 }, { name: 'Yes', description: 'Nobody', price: 5 },
    { name: 'Yes', description: 'Unknown Sub', price: 6 }] });
  const a = w.ValueCheck.analyse(ev, games(1.5, 1, 12), games(1, 1.5, 12), 5);
  const any = a.markets.find(m => m.key === 'player_goal_scorer_anytime');
  // Striker, Winger Jones (matched to "W. Jones"), Defender; not Nobody (no games) or the unknown sub (no goals saved)
  assert.deepEqual(JSON.parse(JSON.stringify(any.rows.map(r => r.name))), ['Striker', 'Winger Jones', 'Defender']);
  any.rows.forEach(r => { assert.ok(r.ours > 0 && r.ours < 1); assert.ok(r.verdict.text); assert.ok(r.tag); });
  assert.ok(any.rows[0].stats > any.rows[2].stats);   // 8 goals in 10 beats 1 in 10
});

test('correct score: scorelines from the goals model, most likely first', () => {
  const ev = event(2, 3.4, 3.8);
  ev.bookmakers[0].markets.push({ key: 'correct_score', outcomes: [
    { name: '1-0', price: 7 }, { name: '1-1', price: 6 }, { name: '0-0', price: 9 }, { name: 'Away 2-1', price: 15 }, { name: 'Any other', price: 4 }] });
  const a = VC.analyse(ev, games(1.5, 1, 12), games(1, 1.5, 12), 5);
  const cs = a.markets.find(m => m.key === 'correct_score');
  assert.deepEqual(cs.rows.map(r => r.name), ['1-1', '1-0', '0-0', 'Away 2-1']);
  assert.equal(cs.show, 3);
  const total = cs.rows.reduce((t, r) => t + r.stats, 0);
  assert.ok(Math.abs(total - 1) < 1e-9);
});

test('goal rates allow for position and how early in the season it is', () => {
  // 2 goals in 11 games: a defender is pulled well below a forward with the same record
  const def = VC.goalRate({ goals: 2, games: 11, position: 'Defender' });
  const fwd = VC.goalRate({ goals: 2, games: 11, position: 'Forward' });
  assert.ok(def < 0.12 && fwd > 0.25, `defender ${def}, forward ${fwd}`);
  // the same scoring rate counts for more after a full season than after 3 games
  const early = VC.goalRate({ goals: 2, games: 3, position: 'Defender' });
  const late = VC.goalRate({ goals: 20, games: 30, position: 'Defender' });
  assert.ok(early < 0.25 && late > 0.45, `early ${early}, late ${late}`);
  // a striker's slow start isn't held against him
  assert.ok(VC.goalRate({ goals: 0, games: 4, position: 'Forward' }) > 0.2);
  // unknown position: a middling rate
  assert.ok(Math.abs(VC.goalRate({ goals: 0, games: 0 }) - 0.12) < 1e-9);
  // shots: a forward with 3 goals but 40 shots in 10 games rates higher than one with 3 goals from 10 shots
  const busy = VC.goalRate({ goals: 3, games: 10, position: 'Forward', shots: 40, shotGames: 10 });
  const quiet = VC.goalRate({ goals: 3, games: 10, position: 'Forward', shots: 10, shotGames: 10 });
  assert.ok(busy > quiet);
  // penalties: taken out of his goals; the team's taker gets a steady bonus instead
  const withPens = VC.goalRate({ goals: 5, games: 10, position: 'Forward', pens: 3 });
  const open = VC.goalRate({ goals: 5, games: 10, position: 'Forward' });
  assert.ok(withPens < open);
  assert.ok(Math.abs(VC.goalRate({ goals: 5, games: 10, position: 'Forward', pens: 3, taker: true }) - withPens - 0.08) < 1e-9);
});

test('verdict badges are just the label, with no tooltip', () => {
  const { load } = require('./load-scripts');
  const V = load(['football-data.js', 'value-check.js']).ValueCheck;
  const b = V.verdictBadge({ text: 'Fair Price', cls: 'odds-ok' }, 'Low');
  assert.equal(b.textContent, 'Fair Price');
  assert.equal(b.getAttribute('data-bs-toggle'), null);
});

test('recommended bets: value only (+5% or better), 1/3 or bigger, no avoided markets; Fair Price listed apart', () => {
  const { load } = require('./load-scripts');
  const V = load(['football-data.js', 'value-check.js']).ValueCheck;
  const v = t => ({ text: t });
  const list = [
    { key: 'h2h', name: 'Fair likely', ret: -0.02, chance: 0.6, price: 1.55, verdict: v('Fair Price') },
    { key: 'h2h', name: 'Fair coin flip', ret: 0.01, chance: 0.4, price: 2.5, verdict: v('Fair Price') },
    { key: 'totals', name: 'Too short', ret: 0.01, chance: 0.95, price: 1.05, verdict: v('Fair Price') },
    { key: 'totals', name: 'Possible', ret: 0.1, chance: 0.1, price: 11, verdict: v('Possible Value') },
    { key: 'h2h', name: 'Good', ret: 0.08, chance: 0.3, price: 3.6, verdict: v('Good Value') },
    { key: 'h2h', name: 'Poor', ret: -0.3, chance: 0.3, price: 2.2, verdict: v('Poor Price') },
    { key: 'correct_score', name: 'Avoided', ret: 0.2, chance: 0.1, price: 12, verdict: v('Great Value') }];
  assert.deepEqual(V.recommend(list).map(p => p.name), ['Good', 'Possible']);
  assert.deepEqual(V.fairBets(list).map(p => p.name), ['Fair likely', 'Fair coin flip']);   // not "Too short" (under 1/3)
});

test('team profile: season shots, keepers and injuries from the squad stats feed the goals model', () => {
  const squad = [
    { name: 'Striker', position: 'Forward', stats: { matchPlayed: '10', goals: '6', shotsTotal: '80' } },
    { name: 'Winger', position: 'Midfielder', stats: { matchPlayed: '10', goals: '2', shotsTotal: '70', injured: 'Yes' } },
    { name: 'Keeper', position: 'Goalkeeper', stats: { matchPlayed: '10', saves: '30', goalsConceded: '10' } }];
  const p = VC.teamProfile(squad);
  assert.equal(p.games, 10);
  assert.equal(p.shotsPerGame, 15);
  assert.ok(Math.abs(p.shotGoals - 15 * 0.105) < 1e-9);
  assert.equal(p.goals, 8);
  assert.ok(Math.abs(p.keeperGoals - 4 * 0.30) < 1e-9);   // 40 shots on target faced in 10 games
  assert.deepEqual([...p.injured], ['Winger']);
  assert.ok(p.attackFactor < 1 && p.attackFactor >= 0.8);
  assert.equal(VC.teamProfile([{ name: 'x', stats: { matchPlayed: '2' } }]), null);   // too early

  const games = (gf, ga, n) => Array.from({ length: n }, () => ({ gf, ga }));
  const plain = VC.model(games(1, 1, 8), games(1, 1, 8));
  const h = games(1, 1, 8), a = games(1, 1, 8);
  h.profile = Object.assign({}, p, { injured: [], attackFactor: 1 });   // a team shooting a lot: more expected goals
  const shooting = VC.model(h, a);
  assert.ok(shooting.lh > plain.lh);
  h.profile = p;                                                        // ...but fewer with a key player injured
  assert.ok(VC.model(h, a).lh < shooting.lh);
  assert.equal(VC.model(games(1, 1, 8), games(1, 1, 8)).lh, plain.lh);  // no profiles: as before
  // a squad that covers too few of the team's goals (players filed under other teams): its shots aren't used
  h.profile = Object.assign({}, p, { goals: 3, injured: [], attackFactor: 1 });
  assert.equal(VC.model(h, a).lh, plain.lh);
  // a player with no shots recorded counts his goals, not nothing
  const patchy = VC.teamProfile([{ name: 'A', stats: { matchPlayed: '2', goals: '5' } },   // a sub, no shots recorded
    { name: 'B', stats: { matchPlayed: '10', goals: '1', shotsTotal: '20' } }]);
  assert.ok(Math.abs(patchy.shotGoals - (5 + 20 * 0.105) / 10) < 1e-9);
});

test('team shots are only used when nearly every regular has them recorded', () => {
  const regular = (name, shots) => ({ name, position: 'Midfielder', stats: Object.assign({ matchPlayed: '10', goals: '1' }, shots === null ? {} : { shotsTotal: String(shots) }) });
  const full = VC.teamProfile([regular('a', 20), regular('b', 20), regular('c', 20), regular('d', 20)]);
  assert.equal(full.shotsPerGame, 8);
  const gaps = VC.teamProfile([regular('a', 20), regular('b', 20), regular('c', null), regular('d', null)]);
  assert.equal(gaps.shotsPerGame, null);   // half the team without shots: not used
  assert.equal(gaps.shotGoals, null);
});

test('recent games at any venue: home advantage is added back (25 Nov backtest)', () => {
  const games = (gf, ga, n) => Array.from({ length: n }, () => ({ gf, ga }));
  const venue = VC.model(games(1.5, 1, 6), games(1.5, 1, 6));
  const h = games(1.5, 1, 6); h.anyVenue = true;
  const any = VC.model(h, games(1.5, 1, 6));
  assert.ok(Math.abs(any.lh - venue.lh * 1.15) < 1e-9);
  assert.ok(Math.abs(any.la - venue.la / 1.15) < 1e-9);
  assert.ok(any.home > venue.home);
});

test('prediction log: logged bets settle from the score, and the report scores ours against the bookmaker', () => {
  const r = { home: 2, away: 1 };
  assert.equal(VC.settle({ key: 'h2h', name: 'Forest' }, 'Forest', 'Chelsea', r), true);
  assert.equal(VC.settle({ key: 'h2h', name: 'Draw' }, 'Forest', 'Chelsea', r), false);
  assert.equal(VC.settle({ key: 'alternate_totals', name: 'Over 2.5' }, 'Forest', 'Chelsea', r), true);
  assert.equal(VC.settle({ key: 'btts', name: 'Yes' }, 'Forest', 'Chelsea', r), true);
  assert.equal(VC.settle({ key: 'player_goal_scorer_anytime', name: 'Delap' }, 'Forest', 'Chelsea', r), null);

  const rows = [
    { point: 'PRE', home: 'Forest', away: 'Chelsea', result: r,
      chances: { home: 0.5, draw: 0.3, away: 0.2 }, bookmaker: { home: 0.4, draw: 0.3, away: 0.3 },
      recommended: [{ key: 'h2h', name: 'Forest', price: 2.5 }, { key: 'btts', name: 'No', price: 2 },
                    { key: 'player_goal_scorer_anytime', name: 'Delap', price: 3 }] },
    { point: '60', home: 'Forest', away: 'Chelsea', result: r, chances: { home: 0.7, draw: 0.2, away: 0.1 } },
    { point: 'PRE', home: 'A', away: 'B', result: null, chances: { home: 0.4, draw: 0.3, away: 0.3 } }];
  const rep = VC.report(rows);
  const pre = rep.points.find(p => p.point === 'PRE');
  assert.equal(pre.games, 1);
  assert.equal(pre.right, 1);
  assert.ok(Math.abs(pre.logLoss - -Math.log(0.5)) < 1e-9);
  assert.ok(Math.abs(pre.bookLogLoss - -Math.log(0.4)) < 1e-9);   // ours better here (lower)
  assert.deepEqual(rep.points.map(p => p.point), ['PRE', '60']);
  assert.equal(rep.bets.n, 2);              // the goalscorer bet can't be settled from the score
  assert.equal(rep.bets.unsettled, 1);
  assert.ok(Math.abs(rep.bets.profit - 0.5) < 1e-9);   // +1.5 (won at 2.5) - 1 (BTTS No lost)
  assert.equal(rep.pending, 1);
  assert.equal(rep.calibration.reduce((t, b) => t + b.n, 0), 3);   // pre-match only: 3 outcomes of 1 game
});

test('our view: expected goals fitted to the chances the verdicts use, so the Prediction agrees with the Markets', () => {
  const stats = { hs: 1.2, as: 1.1, inputs: {} };
  const v = VC.fitView(stats, { home: 0.42, draw: 0.25, away: 0.33, over25: 0.58 });   // Man Utd v Brentford, our chances
  assert.ok(v.fitted);
  assert.ok(v.home > v.away && v.home > v.draw);                // the same favourite as the Markets
  assert.ok(Math.abs(v.home - 0.42) < 0.02 && Math.abs(v.over25 - 0.58) < 0.02);
  assert.ok(v.btts > 0.5);
  assert.equal(v.hs, 1.2);                                      // each side's usual scoring kept (scorer scaling)
  const c = VC.chances(1.5, 1.5);
  assert.ok(Math.abs(c.home - c.away) < 1e-9 && Math.abs(c.home + c.draw + c.away - 1) < 1e-9);
});
