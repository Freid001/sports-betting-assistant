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

test('Odds API names find the football-data.org team', () => {
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

test('favourites match whatever name each site uses', () => {
  const favs = [{ name: 'FC Bayern München', shortName: 'Bayern', tla: 'FCB' }, { name: 'Wales', shortName: 'Wales' }];
  assert.ok(F.findTeam(favs, 'Bayern Munich'));
  assert.ok(F.findTeam(favs, 'Wales'));
  assert.equal(F.findTeam(favs, 'England'), null);
});
