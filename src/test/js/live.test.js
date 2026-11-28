const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const Live = load(['football-data.js', 'live.js']).Live;

const KO = '2026-11-21T15:00:00.000Z';
const state = {
  tracked: ['g1'],
  fixtures: [
    { id: 'g1', eventId: 'e1', home: 'Tottenham', away: 'Ipswich', kickoff: KO, status: 'LIVE', live: true },
    { id: 'g2', eventId: 'e2', home: 'Manchester Utd', away: 'Everton', kickoff: KO, status: 'SCHEDULED', live: false },
    { id: 'g3', eventId: null, home: 'Manchester City', away: 'Fulham', kickoff: '2026-11-21T12:30:00.000Z', status: 'FINISHED', live: false }
  ]
};
const odds = (id) => ({ id: id, home_team: 'any', away_team: 'names', commence_time: KO });

test('a GOAL API game is an Odds API match when the server linked both to one game (no name guessing)', () => {
  assert.equal(Live.findFor(state, odds('e1')).id, 'g1');
  assert.equal(Live.findFor(state, odds('e2')).id, 'g2');
  assert.equal(Live.findFor(state, odds('e3')), null);   // not linked: not found
  assert.equal(Live.findFor(state, { home_team: 'Tottenham', away_team: 'Ipswich', commence_time: KO }), null);
});

test('only ticked games count as followed, and monitoring stops when they finish', () => {
  assert.ok(Live.isTracked(state, state.fixtures[0]));
  assert.ok(!Live.isTracked(state, state.fixtures[1]));
  assert.ok(Live.monitoring(state));
  assert.ok(!Live.monitoring({ tracked: ['g3'], fixtures: state.fixtures }));
});

test('live and finished are read from GOAL API\'s status (also after a reload, when flags are text)', () => {
  assert.ok(Live.isLive({ live: 'true', status: 'LIVE' }));
  assert.ok(!Live.isLive({ live: false, status: 'SCHEDULED' }));
  assert.ok(Live.finished({ status: 'FINISHED' }));
  assert.ok(!Live.isLive({ live: true, status: 'FINISHED' }));
});
