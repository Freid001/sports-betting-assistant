const test = require('node:test');
const assert = require('node:assert/strict');
const { load, settle, plain } = require('./load-scripts');

const OPTIONS = { groups: [
  { label: 'Premier League', teams: [
    { name: 'Ipswich Town FC', shortName: 'Ipswich Town', tla: 'IPS', crest: 'ips.png', type: 'club' },
    { name: 'Tottenham Hotspur FC', shortName: 'Tottenham', tla: 'TOT', crest: 'tot.png', type: 'club' }] },
  { label: 'International', teams: [{ name: 'Wales', shortName: 'Wales', tla: null, crest: null, type: 'international' }] }
] };

function open(routes) {
  const w = load(['football-data.js', 'favourites.js'], { routes });
  const modal = w.document.createElement('div');
  w.document.body.appendChild(modal);
  let saved = null;
  w.Favourites.openModal(modal, t => { saved = t; });
  return { w, modal, saved: () => saved, q: s => modal.querySelector('[' + s + ']') };
}

test('pop-up lists teams by group, with your favourites as chips', async () => {
  const { modal, q } = open({ '/api/football/favourites': { teams: [{ name: 'Wales', shortName: 'Wales' }] },
                              '/api/football/favourites/options': OPTIONS });
  await settle();
  assert.deepEqual(modal.querySelectorAll('.section-label').map(l => l.textContent), ['Premier League', 'International']);
  assert.equal(modal.querySelectorAll('input[type=checkbox]').length, 3);
  assert.equal(q('data-chips').textContent, 'Wales');
  assert.equal(q('data-count').textContent, '1 favourite');
  assert.ok(modal.querySelectorAll('input[type=checkbox]')[2].checked);
});

test('search narrows the list by name, short name or code', async () => {
  const { modal, q, w } = open({ '/api/football/favourites': { teams: [] }, '/api/football/favourites/options': OPTIONS });
  await settle();
  const search = q('data-search');
  search.value = 'spurs';
  search.dispatchEvent(new w.Event('input'));
  assert.match(q('data-groups').textContent, /No teams match/);
  search.value = 'TOT';
  search.dispatchEvent(new w.Event('input'));
  assert.equal(modal.querySelectorAll('input[type=checkbox]').length, 1);
  search.value = 'wal';
  search.dispatchEvent(new w.Event('input'));
  assert.equal(q('data-groups').textContent.includes('Wales'), true);
  assert.equal(q('data-chips').textContent, 'No favourites yet.');
});

test('ticking adds a chip, × removes it, and Save stores the list', async () => {
  const { w, modal, q, saved } = open({
    '/api/football/favourites': { teams: [] },
    '/api/football/favourites/options': OPTIONS,
    'PUT /api/football/favourites': (url, opts) => JSON.parse(opts.body)
  });
  await settle();
  const boxes = modal.querySelectorAll('input[type=checkbox]');
  boxes[0].click();
  boxes[1].click();
  assert.equal(q('data-count').textContent, '2 favourites');
  q('data-chips').querySelector('.btn-close').click();          // remove Ipswich again
  assert.equal(q('data-count').textContent, '1 favourite');
  assert.ok(!modal.querySelectorAll('input[type=checkbox]')[0].checked);

  q('data-save').click();
  await settle();
  const put = w.calls.find(c => c.method === 'PUT');
  assert.deepEqual(JSON.parse(put.body).teams.map(t => t.name), ['Tottenham Hotspur FC']);
  assert.deepEqual(plain(w.notes), [{ message: 'Favourites saved: Tottenham.', type: 'success' }]);
  assert.equal(saved().length, 1);
});

test('saving nothing says so, and failures are shown in red', async () => {
  const ok = open({ '/api/football/favourites': { teams: [] }, '/api/football/favourites/options': OPTIONS,
                    'PUT /api/football/favourites': { teams: [] } });
  await settle();
  ok.q('data-save').click();
  await settle();
  assert.equal(ok.w.notes[0].message, 'Favourites saved: none.');

  const fails = open({ '/api/football/favourites': { teams: [] }, '/api/football/favourites/options': OPTIONS,
                       'PUT /api/football/favourites': { status: 500, body: {} } });
  await settle();
  fails.q('data-save').click();
  await settle();
  assert.deepEqual(plain(fails.w.notes), [{ message: 'Couldn\'t save favourites: HTTP 500', type: 'danger' }]);

  const down = open({ '/api/football/favourites/options': { status: 500, body: {} } });
  await settle();
  assert.equal(down.q('data-groups').textContent, 'Couldn\'t load teams.');
  assert.equal(down.w.notes[0].type, 'danger');
});

test('no saved teams yet points to Refresh Stats', async () => {
  const { q } = open({ '/api/football/favourites': { teams: [] }, '/api/football/favourites/options': { groups: [] } });
  await settle();
  assert.match(q('data-groups').textContent, /click Refresh Stats first/);
});

test('find matches a favourite under any spelling, and nothing when there are none', async () => {
  const w = load(['football-data.js', 'favourites.js'], { routes: { '/api/football/favourites': { teams: [{ name: 'Tottenham Hotspur FC', shortName: 'Tottenham' }] } } });
  const favs = await w.Favourites.load();
  assert.ok(w.Favourites.find(favs, 'Tottenham Hotspur'));
  assert.equal(w.Favourites.find(favs, 'Arsenal'), null);
  assert.equal(w.Favourites.find([], 'Arsenal'), null);
  const old = load(['football-data.js', 'favourites.js'], { routes: {} });
  await assert.rejects(old.Favourites.load(), /restart/);
});
