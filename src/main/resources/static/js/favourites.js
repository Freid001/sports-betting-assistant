/**
 * Favourite teams: the Favourites pop-up, and checking whether a team is a favourite.
 * Saved on the server (by team id, or by name for a nation with no saved team), so they survive restarts.
 */
window.Favourites = (function () {
  var F = window.Football;
  var el = F.el;

  function load() {
    return F.getJson('/api/football/favourites').then(function (d) { return d.teams || []; });
  }

  /**
   * The favourite for a team, or null: by its id when the server matched it to a saved team (favourites carry the
   * team's id), else by name (a nation with no saved team).
   */
  function find(favs, name, id) { return favs && favs.length ? F.findTeam(favs, name, id) : null; }
  /** Is a favourite team playing in this match (an Odds API event: home_team(_id) / away_team(_id))? */
  function playing(favs, m) { return !!(find(favs, m.home_team, m.home_team_id) || find(favs, m.away_team, m.away_team_id)); }

  function crestOrFlag(t, size) {
    return F.logo(t.crest ? [t] : [], t.name, size);
  }

  /** Builds and shows the pop-up in `modalEl` (an empty .modal). onSaved(teams) after Save. */
  function openModal(modalEl, onSaved) {
    var frame = F.modalFrame(modalEl, {
      title: 'Favourites', note: 'data-count',
      body: '<p class="mb-2">Tick your favourite clubs and national teams. Their matches get a ★ on the Football list, ' +
        'and you can show only them with the ★ Favourites filter.</p>' +
        '<div class="d-flex flex-wrap gap-1 mb-2" data-chips></div>' +
        '<input type="search" class="form-control form-control-sm mb-2" placeholder="Search teams, e.g. Spurs, Wales" data-search>' +
        '<div data-groups><span class="text-muted">Loading teams…</span></div>'
    });
    var q = frame.q, modal = frame.modal;

    var chosen = [], groups = [];
    function same(a, b) { return a.id != null || b.id != null ? a.id === b.id : a.name === b.name; }
    function isChosen(t) { return chosen.some(function (c) { return same(c, t); }); }
    function toggle(t, on) {
      chosen = chosen.filter(function (c) { return !same(c, t); });
      if (on) chosen.push(t);
      drawChips();
    }
    function drawChips() {
      var box = q('data-chips');
      box.replaceChildren();
      if (!chosen.length) box.appendChild(el('span', 'text-muted', 'No favourites yet.'));
      chosen.forEach(function (t) {
        var chip = el('span', 'badge rounded-pill text-bg-light border d-inline-flex align-items-center gap-1 fw-normal');
        chip.appendChild(crestOrFlag(t, 14));
        chip.appendChild(el('span', null, t.shortName || t.name));
        var x = el('button', 'btn-close ms-1');
        x.type = 'button'; x.style.fontSize = '.5rem'; x.setAttribute('aria-label', 'Remove ' + t.name);
        x.onclick = function () { toggle(t, false); drawGroups(); };
        chip.appendChild(x);
        box.appendChild(chip);
      });
      q('data-count').textContent = chosen.length + ' favourite' + (chosen.length === 1 ? '' : 's');
    }
    function drawGroups() {
      var box = q('data-groups'), term = F.norm(q('data-search').value);
      box.replaceChildren();
      var any = false;
      groups.forEach(function (g) {
        var teams = g.teams.filter(function (t) {
          return !term || [t.name, t.shortName, t.tla].some(function (n) { return n && F.norm(n).indexOf(term) >= 0; });
        });
        if (!teams.length) return;
        any = true;
        box.appendChild(el('div', 'section-label mt-2 mb-1', g.label));
        var grid = el('div', 'row row-cols-2 row-cols-md-3 g-1');
        teams.forEach(function (t, i) {
          var col = el('div', 'col');
          var wrap = el('div', 'form-check d-flex align-items-center gap-1 mb-0');
          var cb = el('input', 'form-check-input mt-0');
          cb.type = 'checkbox'; cb.id = 'fav-' + g.label.replace(/\W/g, '') + '-' + i; cb.checked = isChosen(t);
          cb.onchange = function () { toggle(t, cb.checked); };
          var lab = el('label', 'form-check-label d-inline-flex align-items-center gap-1');
          lab.htmlFor = cb.id;
          lab.appendChild(crestOrFlag(t, 16));
          lab.appendChild(el('span', null, t.shortName || t.name));
          wrap.appendChild(cb); wrap.appendChild(lab); col.appendChild(wrap); grid.appendChild(col);
        });
        box.appendChild(grid);
      });
      if (!any) box.appendChild(el('span', 'text-muted', groups.length ? 'No teams match that search.' : 'No teams saved yet: click Refresh Stats first.'));
    }
    q('data-search').addEventListener('input', drawGroups);
    q('data-save').onclick = function () {
      F.sendJson('/api/football/favourites', { teams: chosen }, 'PUT')
        .then(function (d) {
          modal.hide();
          notify('Favourites saved: ' + (d.teams.length ? d.teams.map(function (t) { return t.shortName || t.name; }).join(', ') : 'none') + '.', 'success');
          if (onSaved) onSaved(d.teams);
        }).catch(function (e) { notify('Couldn\'t save favourites: ' + e.message, 'danger'); });
    };

    Promise.all([load(), F.getJson('/api/football/favourites/options')]).then(function (res) {
      chosen = res[0]; groups = res[1].groups || [];
      drawChips(); drawGroups();
    }).catch(function (e) {
      q('data-groups').textContent = 'Couldn\'t load teams.';
      notify('Couldn\'t load teams: ' + e.message, 'danger');
    });
  }

  return { load: load, find: find, playing: playing, openModal: openModal };
})();
