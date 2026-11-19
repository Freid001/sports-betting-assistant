/**
 * Live Stats (GOAL API): the Live Stats pop-up, LIVE badges and the Live Match Stats card.
 * The server refreshes ticked games every few minutes while they are being played; pages only read
 * the saved copy (/api/football/live), which costs no API calls.
 */
window.Live = (function () {
  var F = window.Football;
  var el = F.el;
  var MATCH_MINUTES = 115;   // a game, half-time and stoppages: how long it is refreshed for

  function yes(v) { return v === true || v === 'true'; }
  function finished(fx) { return /^(FINISH|FT|AET|AFTER_PEN)/i.test(String(fx.status || '')); }
  function isLive(fx) { return yes(fx.live) && !finished(fx); }

  function getState() { return F.getJson('/api/football/live'); }

  // ---- matching a GOAL API game to an Odds API match: same kick-off (±30 min) and similar team names ----
  function sim(a, b) {
    a = F.norm(a); b = F.norm(b);
    if (!a || !b) return 0;
    if (a === b) return 3;
    if (a.indexOf(b) >= 0 || b.indexOf(a) >= 0) return 2;
    var ta = a.split(' '), tb = b.split(' ');
    if (ta[0] === tb[0] && ta[0].length >= 4) return 1;
    if (a.slice(0, 4) === b.slice(0, 4)) return 1;
    return 0;
  }
  function findFor(state, ev) {
    if (!state || !state.fixtures) return null;
    var t = new Date(ev.commence_time).getTime(), best = null, bestScore = 1;
    state.fixtures.forEach(function (fx) {
      if (Math.abs(new Date(fx.kickoff).getTime() - t) > 30 * 60000) return;
      var s = sim(fx.home, ev.home_team) + sim(fx.away, ev.away_team);
      if (s > bestScore) { best = fx; bestScore = s; }
    });
    return best;
  }
  function isTracked(state, fx) { return !!(fx && state && state.tracked.indexOf(fx.id) >= 0); }
  /** Monitoring is on while a ticked game hasn't finished. */
  function monitoring(state) {
    return !!state && state.tracked.some(function (id) {
      var fx = state.fixtures.find(function (f) { return f.id === id; });
      return fx && !finished(fx);
    });
  }

  // ---- labels ----
  /** Minute as of the last refresh: 10', 45+2', HT. */
  function minute(fx) {
    var e = parseInt(fx.elapsed, 10);
    if (!isNaN(e)) return e + (fx.extra && parseInt(fx.extra, 10) ? '+' + parseInt(fx.extra, 10) : '') + '\'';
    var m = String(fx.minute || '');
    if (/half/i.test(m) || /^HT$/i.test(m) || /HALF_TIME/i.test(String(fx.period))) return 'HT';
    return m;
  }
  function score(fx) {
    if (fx.homeScore === null || fx.homeScore === undefined || fx.homeScore === '' || fx.homeScore === 'null') return '';
    return fx.homeScore + '–' + fx.awayScore;
  }
  /** LIVE 10' 1–0 / FT 2–1, or null if the game hasn't started. */
  function badge(fx) {
    if (!fx) return null;
    var wrap = el('span', 'd-inline-flex align-items-center gap-1 ms-1');
    if (isLive(fx)) {
      wrap.appendChild(el('span', 'badge text-bg-danger', 'LIVE ' + minute(fx)));
    } else if (finished(fx)) {
      wrap.appendChild(el('span', 'badge text-bg-secondary', 'FT'));
    } else {
      return null;
    }
    if (score(fx)) wrap.appendChild(el('span', 'fw-semibold live-score', score(fx)));
    return wrap;
  }

  /** Red dot on the button while monitoring. */
  function setButton(btn, state) {
    var dot = btn.querySelector('.live-dot');
    var on = monitoring(state);
    if (on && !dot) { dot = el('span', 'live-dot'); btn.prepend(dot); }
    if (!on && dot) dot.remove();
    btn.title = on ? 'Live Stats on: ' + state.tracked.length + ' game(s) ticked' : 'Live Stats: off';
  }

  function ukDate(d) { return new Date(d).toLocaleDateString('en-CA', { timeZone: 'Europe/London' }); }

  function ago(iso) {
    if (!iso) return 'not yet';
    var mins = Math.round((Date.now() - new Date(iso)) / 60000);
    return mins < 1 ? 'just now' : F.ago(iso);
  }

  // ---- the pop-up ----
  /**
   * Builds and shows the Live Stats pop-up in `modalEl` (an empty .modal).
   * onSaved(state) is called after Save or Stop.
   */
  function openModal(modalEl, onSaved) {
    var frame = F.modalFrame(modalEl, {
      title: 'Live Stats', note: 'data-estimate', buttons: [['Stop all', 'data-stop']],
      body: '<div class="alert alert-info px-3 py-2 mb-3" data-status>Loading games…</div><div data-games></div>'
    });
    var q = frame.q, modal = frame.modal;

    var state = null, ticked = [];
    function statusLines() {
      var u = state.usage || {};
      var limit = Number(u.limit || 1000).toLocaleString('en-GB');
      // Reset time comes as seconds since 1970 (midnight UTC): show it as a UK time
      var reset = u.reset && !isNaN(u.reset) ? new Date(Number(u.reset) * 1000).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/London' }) : '00:00';
      F.infoBox(q('data-status'), {
        api: 'https://api.goal-api.com/v1',
        plan: !u.limit ? 'Free (assumed)' : Number(u.limit) <= 1000 ? 'Free' : 'Paid',
        left: u.remaining ? Number(u.remaining).toLocaleString('en-GB') + ' of ' + limit + ' today' : 'unknown',
        limit: limit + ' calls a day',
        reset: 'Daily at ' + reset,
        last: state.lastRefresh ? ago(state.lastRefresh) : 'not yet',
        warning: !state.configured ? 'No GOAL API key: add GOAL_API_KEY to .env and restart.' : state.warning
      });
    }
    /** Calls the ticked games will use: per game, 1 stats call per refresh; per competition and day, 1 scores call per refresh. */
    function estimate() {
      var per = Math.ceil(MATCH_MINUTES / state.refreshMinutes);
      var groups = {};
      var games = state.fixtures.filter(function (f) { return ticked.indexOf(f.id) >= 0 && !finished(f); });
      games.forEach(function (f) { groups[f.leagueId + '|' + f.date] = 1; });
      var calls = games.length * per + Object.keys(groups).length * per;
      q('data-estimate').textContent = ticked.length + ' of ' + state.maxGames + ' ticked' +
        (games.length ? ' · up to about ' + calls + ' calls' : '');
      modalEl.querySelectorAll('input[type=checkbox]').forEach(function (cb) {
        cb.disabled = !cb.checked && ticked.length >= state.maxGames;
      });
    }
    function drawGames() {
      var box = q('data-games');
      box.replaceChildren();
      // Only today's games (UK date): you tick games on the day you want to follow them.
      var games = state.fixtures.filter(function (f) { return ukDate(f.kickoff) === ukDate(new Date()); });
      if (!games.length) {
        box.appendChild(el('p', 'mb-1 fw-semibold', 'No games today.'));
        box.appendChild(el('p', 'text-muted mb-0', 'There are no games in your competitions today. On a match day, open Live Stats to tick the games to follow.'));
        q('data-save').disabled = true;
        return;
      }
      box.appendChild(el('p', 'mb-2', 'Tick today\'s games to follow (up to ' + state.maxGames + '). Nothing is fetched until a ticked game kicks off; then its score, minute and stats are refreshed every ' +
        state.refreshMinutes + ' minutes until it finishes, and show on the Football list and match pages.'));
      box.appendChild(el('div', 'section-label text-start mt-2 mb-1', 'Today, ' + F.day(new Date().toISOString())));
      var table = el('table', 'table table-sm align-middle mb-2');
      var tbody = el('tbody'); table.appendChild(tbody); box.appendChild(table);
      games.forEach(function (fx) {
        var tr = el('tr');
        var td0 = el('td'); td0.style.width = '2rem';
        var cb = el('input', 'form-check-input');
        cb.type = 'checkbox'; cb.id = 'lv-' + fx.id; cb.checked = ticked.indexOf(fx.id) >= 0;
        cb.addEventListener('change', function () {
          var i = ticked.indexOf(fx.id);
          if (cb.checked && i < 0) ticked.push(fx.id);
          if (!cb.checked && i >= 0) ticked.splice(i, 1);
          estimate();
        });
        td0.appendChild(cb); tr.appendChild(td0);
        tr.appendChild(el('td', 'text-nowrap text-muted', F.time(fx.kickoff)));
        tr.appendChild(el('td', 'text-nowrap text-muted', F.title(fx.tournament)));
        var name = el('td');
        var lab = el('label', 'form-check-label', fx.home + ' v ' + fx.away);
        lab.htmlFor = cb.id; lab.style.cursor = 'pointer';
        name.appendChild(lab);
        tr.appendChild(name);
        var st = el('td', 'text-end text-nowrap');
        var b = badge(fx); if (b) st.appendChild(b);
        tr.appendChild(st);
        tbody.appendChild(tr);
      });
    }
    function show(s) {
      state = s;
      ticked = s.tracked.slice();
      statusLines(); drawGames(); estimate();
    }
    function save(ids, message) {
      F.sendJson('/api/football/live/track', { ids: ids }).then(function (s) {
        modal.hide();
        notify(message(s), 'success');
        if (onSaved) onSaved(s);
      }).catch(function (e) { notify('Couldn\'t save Live Stats: ' + e.message, 'danger'); });
    }
    q('data-save').onclick = function () {
      save(ticked, function (s) {
        return s.tracked.length ? 'Live Stats on for ' + s.tracked.length + ' game(s).' : 'Live Stats off.';
      });
    };
    q('data-stop').onclick = function () { save([], function () { return 'Live Stats off.'; }); };

    // Games for today + 2 days: 1 call per competition, reused by the server for 30 minutes.
    F.sendJson('/api/football/live/fixtures').then(show).catch(function (e) {
      q('data-status').textContent = 'Couldn\'t load games.';
      notify('Couldn\'t load Live Stats games: ' + e.message, 'danger');
    });
  }

  // ---- Live Match Stats card (match page) ----
  function renderCard(box, footLeft, state, ev) {
    var fx = findFor(state, ev);
    if (!fx || !isTracked(state, fx)) {
      box.replaceChildren(el('span', 'text-muted',
        'Not followed. On the day, tick this game in Live Stats on the Football page to see its score and stats here.'));
      footLeft.textContent = '';
      return Promise.resolve(null);
    }
    return F.getJson('/api/football/live/match/' + encodeURIComponent(fx.id)).then(function (m) {
      var f = m.fixture || fx, s = m.stats;
      box.replaceChildren();
      box.classList.remove('text-muted');
      var head = el('div', 'text-center mb-2');
      var b = badge(f);
      if (b) head.appendChild(b);
      else head.appendChild(el('span', 'text-muted', 'Ticked. Kicks off at ' + F.time(f.kickoff) +
        '; the score and stats appear once it starts (refreshed every ' + m.refreshMinutes + ' min).'));
      box.appendChild(head);
      if (s && s.rows && s.rows.length) {
        var table = el('table', 'table table-sm align-middle mb-2 stats-table');
        var thead = el('thead'), hr = el('tr', 'text-muted');
        hr.appendChild(el('th', 'text-start w-25', ev.home_team));
        hr.appendChild(el('th', 'text-center', ''));
        hr.appendChild(el('th', 'text-end w-25', ev.away_team));
        thead.appendChild(hr); table.appendChild(thead);
        var tb = el('tbody');
        s.rows.forEach(function (r) {
          var h = parseFloat(r.home), a = parseFloat(r.away);
          var tr = el('tr');
          tr.appendChild(el('td', 'text-start' + (h > a ? ' fw-semibold' : ''), r.home));
          var lab = el('td', 'text-center text-muted', r.label);
          if (r.label === 'xG (est.)') {
            lab.setAttribute('data-bs-toggle', 'tooltip');
            lab.setAttribute('data-bs-title', 'Estimated from shots: 0.12 per shot inside the box, 0.035 per shot outside, 0.76 per penalty. GOAL API has no real xG.');
            lab.style.cursor = 'help';
          }
          tr.appendChild(lab);
          tr.appendChild(el('td', 'text-end' + (a > h ? ' fw-semibold' : ''), r.away));
          tb.appendChild(tr);
        });
        table.appendChild(tb); box.appendChild(table);
        // Players with shots on target
        var hp = s.homePlayers || [], ap = s.awayPlayers || [];
        if (hp.length || ap.length) {
          box.appendChild(el('div', 'section-label', 'Shots on target (shots)'));
          var row = el('div', 'row');
          [[hp, 'text-start'], [ap, 'text-end']].forEach(function (side) {
            var col = el('div', 'col-6 ' + side[1]);
            if (!side[0].length) col.appendChild(el('div', 'text-muted', '–'));
            side[0].forEach(function (p) {
              col.appendChild(el('div', null, p.name + ' ' + p.onTarget + ' (' + p.shots + ')' + (p.goals > 0 ? ' ⚽'.repeat(Math.min(p.goals, 3)) : '')));
            });
            row.appendChild(col);
          });
          box.appendChild(row);
        }
      }
      footLeft.textContent = s ? 'Updated ' + ago(s.updatedAt) + (yes(s.final) ? ' · full time' : '') : '';
      return m;
    });
  }

  return { getState: getState, findFor: findFor, isTracked: isTracked, monitoring: monitoring, isLive: isLive,
           finished: finished, badge: badge, setButton: setButton, openModal: openModal, renderCard: renderCard };
})();
