const test = require('node:test');
const assert = require('node:assert/strict');
const { load, settle, plain } = require('./load-scripts');

const PL = { code: 'PL', name: 'Premier League', type: 'LEAGUE' };
const CL = { code: 'CL', name: 'UEFA Champions League', type: 'CUP' };
const SPURS = { id: 1, name: 'Tottenham Hotspur FC', shortName: 'Tottenham' };
const IPSWICH = { id: 2, name: 'Ipswich Town FC', shortName: 'Ipswich Town' };
const OTHER = { id: 9, name: 'Other FC', shortName: 'Other' };

let day = 1;
function game(home, away, hg, ag, comp, ht) {
  return { id: day, status: 'FINISHED', utcDate: '2026-10-' + String(day++).padStart(2, '0') + 'T15:00:00Z', competition: comp || PL,
           homeTeam: home, awayTeam: away, score: { fullTime: { home: hg, away: ag }, halfTime: ht || { home: 0, away: 0 } } };
}
const homeGames = n => Array.from({ length: n }, (_, i) => game(SPURS, OTHER, 2, i % 2, PL, { home: 1, away: 0 }));
const awayGames = n => Array.from({ length: n }, () => game(OTHER, IPSWICH, 1, 1));
const table = (rows) => ({ competition: { name: 'Premier League' }, standings: [{ type: 'TOTAL', table: rows }] });

function page(routes, ev, home, away) {
  const w = load(['football-data.js', 'value-check.js', 'match-stats.js'], { routes });
  ['form', 'h2h', 'keyPlayers', 'valueCheck', 'goalsNote'].forEach(id => {
    const d = w.document.createElement('div'); d.id = id; w.document.body.appendChild(d);
  });
  const box = w.document.createElement('div');
  w.document.body.appendChild(box);
  w.MatchStats.render(box, ev, home, away);
  const $ = id => w.document.getElementById(id);
  return { w, box, $ };
}

const event = (compKey, comp, gameId) => ({ compKey, comp, game_id: gameId, home_team: 'Tottenham Hotspur', away_team: 'Ipswich Town',
  bookmakers: [{ key: 'skybet', markets: [{ key: 'h2h', outcomes: [
    { name: 'Tottenham Hotspur', price: 1.6 }, { name: 'Draw', price: 4.2 }, { name: 'Ipswich Town', price: 5.5 }] }] }] });

const scorers = { competition: { name: 'Premier League' }, scorers: [
  { player: { id: 10, name: 'Kane' }, team: { id: 1 }, goals: 9, assists: 2, penalties: 1, playedMatches: 10 },
  { player: { id: 11, name: 'Son' }, team: { id: 1 }, goals: 4 },
  { player: { id: 12, name: 'Delap' }, team: { id: 2 }, goals: 6 }] };

test('league match: form, one league row, goals profile from home/away games, key players, head-to-head totals', async () => {
  const { box, $, w } = page({
    '/api/football/data/teams/1/matches': { matches: homeGames(6) },
    '/api/football/data/teams/2/matches': { matches: awayGames(6) },
    '/api/football/data/competitions/PL/standings': table([
      { position: 1, points: 28, playedGames: 12, goalDifference: 15, team: { id: 1 } },
      { position: 18, points: 9, playedGames: 12, goalDifference: -10, team: { id: 2 } }]),
    '/api/football/data/competitions/PL/scorers': scorers,
    '/api/football/data/matches/77/head2head': { aggregates: { numberOfMatches: 4, totalGoals: 10 }, matches: [] }
  }, event('epl', 'Premier League', 77), SPURS, IPSWICH);
  await settle();

  const formRows = $('form').querySelectorAll('tbody tr').map(r => r.children[1].textContent);
  assert.deepEqual(formRows, ['Form', 'League'], 'no separate Premier League row for a league match');
  assert.equal($('form').querySelectorAll('.form-tile').length, 12);   // 6 games each (up to 8 shown)
  assert.match($('form').textContent, /1st · 28 pts/);
  assert.match($('form').textContent, /18th · 9 pts/);
  assert.equal($('goalsNote').textContent, 'Last 6 home games for Tottenham, last 6 away games for Ipswich Town');
  const profile = box.querySelectorAll('tbody tr').map(r => r.textContent);
  assert.ok(profile.includes('2.0Avg scored1.0'));
  assert.ok(profile.includes('100%Leading at half-time0%'));
  // Top Scorers: Goals and Assists columns, no competition codes or tooltips
  const rows = $('keyPlayers').querySelectorAll('tbody tr').map(r => r.children.map(c => c.textContent));
  assert.deepEqual(plain(rows), [['Kane', '9', '2'], ['Son', '4', '0'], ['Delap', '6', '0']]);
  assert.deepEqual(plain($('keyPlayers').querySelectorAll('thead th').map(th => th.textContent)), ['', 'Goals', 'Assists', '', 'Goals', 'Assists']);
  assert.equal($('keyPlayers').querySelectorAll('[data-bs-toggle]').length, 0);
  assert.match($('h2h').textContent, /4 previous meetings, 10 goals \(2\.5 per game\)/);
  assert.match($('valueCheck').textContent, /Match Result/);
  // GOAL API's scorer lists have no games played: the team's games from the league table (12) stand in
  const ts = w.TopScorers;
  assert.deepEqual(plain(ts.home.scorers.map(p => [p.name, p.games])), [['Kane', 10], ['Son', 12]]);
  assert.deepEqual(plain(ts.away.scorers.map(p => [p.name, p.games])), [['Delap', 12]]);
});

test('squad players outside the top-scorer list get their own goals and games, for goalscorer verdicts', async () => {
  const { $, w } = page({
    '/api/football/data/teams/1/matches': { matches: homeGames(6) },
    '/api/football/data/teams/2/matches': { matches: awayGames(6) },
    // the server gives a player the same id in the squad and the scorer tables (Kane: 10)
    '/api/football/data/teams/1/squad': { squad: [{ id: 10, name: 'H. Kane', position: 'Forwards', games: 9, goals: 9 },
      { id: 11, name: 'Richarlison', position: 'Forwards', games: 8, goals: 3 }, { id: 12, name: 'Bench', position: 'Defenders', games: 0, goals: 0 }] },
    '/api/football/data/competitions/PL/standings': table([{ position: 1, points: 28, playedGames: 12, goalDifference: 15, team: { id: 1 } }]),
    '/api/football/data/competitions/PL/scorers': { scorers: [{ player: { id: 10, name: 'Kane' }, team: { id: 1 }, goals: 9 }] }
  }, event('epl', 'Premier League'), SPURS, IPSWICH);
  await settle();
  const ts = w.TopScorers.home.scorers.map(p => [p.name, p.goals, p.games, p.position]);
  assert.deepEqual(plain(ts), [['Kane', 9, 9, 'Forward'], ['Richarlison', 3, 8, 'Forward']]);   // not Bench: no games
  assert.match($('keyPlayers').textContent, /Richarlison/);   // in Top Scorers now too
});

test('cup match with few home/away games: any-venue note, a cup row, and individual head-to-head results', async () => {
  const meetings = [game(SPURS, IPSWICH, 3, 1, CL), game(IPSWICH, SPURS, 0, 0, CL)];
  const { $ } = page({
    '/api/football/data/teams/1/matches': { matches: homeGames(3).concat(awayGames(0)) },
    '/api/football/data/teams/2/matches': { matches: awayGames(2) },
    '/api/football/data/competitions/PL/standings': table([{ position: 3, points: 20, playedGames: 12, goalDifference: 0, team: { id: 1 } }]),
    '/api/football/data/competitions/CL/standings': { competition: { name: 'UEFA Champions League' },
      standings: [{ type: 'TOTAL', group: 'GROUP_B', table: [{ position: 2, points: 7, playedGames: 4, goalDifference: 3, team: { id: 1 } }] }] },
    '/api/football/data/competitions/CL/scorers': { scorers: [{ player: { id: 10, name: 'Kane' }, team: { id: 1 }, goals: 3 }] },
    '/api/football/data/competitions/PL/scorers': scorers,
    '/api/football/data/matches/88/head2head': { matches: meetings }
  }, event('uefa_champs_league', 'Champions League', 88), SPURS, IPSWICH);
  await settle();

  assert.match($('goalsNote').textContent, /games, any venue/);
  assert.deepEqual($('form').querySelectorAll('tbody tr').map(r => r.children[1].textContent), ['Form', 'League', 'Champions League']);
  assert.match($('form').textContent, /2nd · 7 pts \(Group B\)/);
  assert.match($('form').textContent, /n\/a|–/);   // Ipswich: no CL table row
  // Goals and assists are added up across the cup and the league
  assert.deepEqual(plain($('keyPlayers').querySelectorAll('tbody tr')[0].children.map(c => c.textContent)), ['Kane', '12', '2']);
  assert.equal($('h2h').querySelectorAll('tr').length, 2);
  assert.match($('h2h').textContent, /3–1/);
});

test('teams not covered by the free plan', () => {
  const { box, $ } = page({}, event('epl', 'Premier League'), SPURS, null);
  assert.equal(box.textContent, 'No data available for this game.');
  assert.equal($('h2h').textContent, 'No data available for this game.');
});

test('a team that is not in the saved teams (no id) makes no calls', () => {
  const { box, $ } = page({}, event('england_league1', 'English League One'), SPURS, { name: 'Doncaster Rovers', crest: 'x' });
  assert.equal(box.textContent, 'No data available for this game.');
  assert.equal($('form').textContent, 'No data available for this game.');
});

test('nothing saved yet asks for Refresh Stats; other errors are shown', async () => {
  const notSaved = page({ '/api/football/data/teams/1/matches': { status: 404, body: { message: 'Not saved yet' } } },
    event('epl', 'Premier League'), SPURS, IPSWICH);
  await settle();
  assert.equal(notSaved.box.textContent, 'Refresh Stats');
  assert.equal(notSaved.$('form').textContent, 'Refresh Stats');

  const broken = page({ '/api/football/data/teams/1/matches': { status: 500, body: {} },
                        '/api/football/data/teams/2/matches': { matches: [] } },
    event('epl', 'Premier League'), SPURS, IPSWICH);
  await settle();
  assert.match(broken.box.textContent, /Couldn't load stats: HTTP 500/);
});

test('form shows the last 8 games at most', async () => {
  const { $ } = page({
    '/api/football/data/teams/1/matches': { matches: homeGames(12) },
    '/api/football/data/teams/2/matches': { matches: awayGames(3) }
  }, event('epl', 'Premier League'), SPURS, IPSWICH);
  await settle();
  const rows = $('form').querySelectorAll('tbody tr');
  assert.equal(rows[0].children[0].querySelectorAll('.form-tile').length, 8);   // home | label | away
  assert.equal(rows[0].children[2].querySelectorAll('.form-tile').length, 3);
});

test('head to head: fixture not found, not saved, or no meetings; and no scorers', async () => {
  const base = {
    '/api/football/data/teams/1/matches': { matches: homeGames(5) },
    '/api/football/data/teams/2/matches': { matches: awayGames(5) },
    '/api/football/data/competitions/PL/standings': { status: 404, body: {} },
    '/api/football/data/competitions/PL/scorers': { scorers: [] }
  };
  const noFixture = page(base, event('epl', 'Premier League'), SPURS, IPSWICH);   // the odds not matched to a game
  await settle();
  assert.equal(noFixture.$('h2h').textContent, 'No head-to-head data found.');
  assert.match(noFixture.$('keyPlayers').textContent, /No players in the scorer tables yet/);
  assert.match(noFixture.$('form').textContent, /n\/a/);

  const notSaved = page(Object.assign({ '/api/football/data/matches/6/head2head': { status: 404, body: {} } }, base),
    event('epl', 'Premier League', 6), SPURS, IPSWICH);
  await settle();
  assert.equal(notSaved.$('h2h').textContent, 'Refresh Stats');

  const none = page(Object.assign({
    '/api/football/data/matches/5/head2head': { aggregates: { numberOfMatches: 0 } } }, base),
    event('epl', 'Premier League', 5), SPURS, IPSWICH);
  await settle();
  assert.equal(none.$('h2h').textContent, 'No previous meetings found.');

  const otherComp = page(base, event('some_other_cup', 'Some Other Cup'), SPURS, IPSWICH);   // not one of our competitions
  await settle();
  assert.equal(otherComp.$('h2h').textContent, 'No head-to-head data found.');
});

test('loadViews gives home games at home and away games away, or any venue when there are too few', async () => {
  const routes = {
    '/api/football/data/teams/1/matches': { matches: homeGames(6).concat([{ status: 'SCHEDULED', utcDate: '2026-12-01', homeTeam: SPURS, awayTeam: OTHER, score: {} }]) },
    '/api/football/data/teams/2/matches': { matches: awayGames(6) }
  };
  const w = load(['football-data.js', 'match-stats.js'], { routes });
  const v = await w.MatchStats.loadViews(SPURS, IPSWICH);
  assert.equal(v.anyVenue, false);
  assert.equal(v.homeVenue.length, 6);
  assert.ok(v.homeVenue.every(x => x.home));
  assert.ok(v.awayVenue.every(x => !x.home));

  routes['/api/football/data/teams/2/matches'] = { matches: awayGames(2) };
  const few = await w.MatchStats.loadViews(SPURS, IPSWICH);
  assert.equal(few.anyVenue, true);
});
