const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const F = load(['football-data.js']).Football;

test('decimal odds show as UK bookmaker fractions', () => {
  assert.equal(F.fraction(2.0), 'Evens');
  assert.equal(F.fraction(2.5), '6/4');
  assert.equal(F.fraction(1.36), '4/11');
  assert.equal(F.fraction(3.6), '13/5');
  assert.equal(F.fraction(11.0), '10/1');
});

test('prices off the standard ladder become a simple fraction', () => {
  assert.equal(F.fraction(2.4), '7/5');
});

test('fair chances remove the bookmaker margin and add up to 100%', () => {
  const { chances, margin } = F.fairChances([2.0, 3.4, 3.8]);
  assert.ok(Math.abs(chances.reduce((a, b) => a + b, 0) - 1) < 1e-9);
  assert.ok(margin > 0.05 && margin < 0.06);
  assert.ok(chances[0] > chances[1] && chances[1] > chances[2]);
});

test('team names are compared without accents, punctuation or club words', () => {
  assert.equal(F.norm('Paris Saint-Germain FC'), 'paris saint germain');
  assert.equal(F.norm('FC Bayern München'), 'bayern munchen');
});

test('Odds API names find the saved team', () => {
  const teams = [
    { name: 'Tottenham Hotspur FC', shortName: 'Tottenham', tla: 'TOT' },
    { name: 'Manchester City FC', shortName: 'Man City', tla: 'MCI' },
    { name: 'PAE AEK', shortName: 'AEK', tla: 'AEK' }
  ];
  assert.equal(F.findTeam(teams, 'Tottenham Hotspur').tla, 'TOT');
  assert.equal(F.findTeam(teams, 'Manchester City').tla, 'MCI');
  assert.equal(F.findTeam(teams, 'AEK Athens').tla, 'AEK');   // alias
  assert.equal(F.findTeam(teams, 'Arsenal'), null);
});

test('the team the server matched (the event\'s team id) wins over the name', () => {
  const teams = [{ id: 1, name: 'Nottingham Forest', shortName: 'Nottingham' }, { id: 2, name: 'Forest Green Rovers' }];
  assert.equal(F.findTeam(teams, 'Forest', 2).id, 2);
  assert.equal(F.findTeam(teams, 'Nottingham Forest', 99).id, 1);   // an id not in the list: the name as before
  assert.equal(F.findTeam(teams, 'Nottingham Forest', null).id, 1);
});

test('every saved team (competition not on the free plan) is matched strictly', () => {
  const teams = [
    { name: 'Sparta Rotterdam', shortName: 'Sparta', tla: 'SPA' },
    { name: 'Lille OSC', shortName: 'Lille', tla: 'LIL' },
    { name: 'AZ', shortName: 'AZ', tla: 'AZ' },
    { name: 'Sport Lisboa e Benfica', shortName: 'SL Benfica', tla: 'SLB' },
    { name: 'TSG 1899 Hoffenheim', shortName: 'Hoffenheim', tla: 'TSG' }
  ];
  teams.strict = true;
  assert.equal(F.findTeam(teams, 'Sparta Prague'), null);
  assert.equal(F.findTeam(teams, 'Lillestrom'), null);
  assert.equal(F.findTeam(teams, 'SK Sturm Graz'), null);
  assert.equal(F.findTeam(teams, 'AZ Alkmaar').tla, 'AZ');
  assert.equal(F.findTeam(teams, 'Benfica').tla, 'SLB');
  assert.equal(F.findTeam(teams, 'TSG Hoffenheim').tla, 'TSG');
});

test('favourites match whatever name each site uses', () => {
  const favs = [{ name: 'FC Bayern München', shortName: 'Bayern', tla: 'FCB' }, { name: 'Wales', shortName: 'Wales' }];
  assert.ok(F.findTeam(favs, 'Bayern Munich'));
  assert.ok(F.findTeam(favs, 'Wales'));
  assert.equal(F.findTeam(favs, 'England'), null);
});

test('clockStatus: the minute, HT and FT from the time since kick-off', () => {
  const ko = '2026-11-21T15:00:00Z', at = mins => new Date(ko).getTime() + mins * 60000;
  assert.equal(F.clockStatus(ko, at(-5)), null);
  assert.equal(F.clockStatus(ko, at(9.5)), "10'");
  assert.equal(F.clockStatus(ko, at(50)), 'HT');
  assert.equal(F.clockStatus(ko, at(78)), "61'");
  assert.equal(F.clockStatus(ko, at(112)), "90'");
  assert.equal(F.clockStatus(ko, at(130)), 'FT');
});

test('the bookmaker comes from the server, and picking one saves it there', async () => {
  const { load, settle } = require('./load-scripts');
  const w = load(['football-data.js'], { routes: { '/api/odds/cache/bookmaker': { bookmaker: 'betway' },
    '/api/football/tournaments': [], 'POST /api/odds/cache/bookmaker?key=skybet': { bookmaker: 'skybet' } } });
  await w.Football.loadAll();
  assert.equal(w.Football.bookmaker(), 'betway', 'from the server');
  w.Football.setBookmaker('skybet');
  await settle();
  const call = w.calls.find(c => c.method === 'POST');
  assert.equal(call.url, '/api/odds/cache/bookmaker?key=skybet');
  assert.equal(w.Football.bookmaker(), 'skybet');
});
