const test = require('node:test');
const assert = require('node:assert/strict');
const { load } = require('./load-scripts');

const Live = load(['football-data.js', 'live.js']).Live;

const KO = '2026-11-21T15:00:00.000Z';
const state = {
  tracked: ['g1'],
  fixtures: [
    { id: 'g1', home: 'Tottenham', away: 'Ipswich', kickoff: KO, status: 'LIVE', live: true },
    { id: 'g2', home: 'Manchester Utd', away: 'Everton', kickoff: KO, status: 'SCHEDULED', live: false },
    { id: 'g3', home: 'Manchester City', away: 'Fulham', kickoff: '2026-11-21T12:30:00.000Z', status: 'FINISHED', live: false }
  ]
};
const odds = (home, away, time) => ({ home_team: home, away_team: away, commence_time: time || KO });

test('GOAL API games are matched to Odds API matches by kick-off and team names', () => {
  assert.equal(Live.findFor(state, odds('Tottenham Hotspur', 'Ipswich Town')).id, 'g1');
  assert.equal(Live.findFor(state, odds('Manchester United', 'Everton')).id, 'g2');
  assert.equal(Live.findFor(state, odds('Manchester City', 'Fulham', '2026-11-21T12:30:00Z')).id, 'g3');
});

test('a different kick-off time is not the same game', () => {
  assert.equal(Live.findFor(state, odds('Tottenham Hotspur', 'Ipswich Town', '2026-11-22T15:00:00Z')), null);
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
