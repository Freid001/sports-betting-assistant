/**
 * Live Stats (GOAL API): the Live Stats pop-up, LIVE badges and the Live Match Stats card.
 * The server refreshes ticked games every few minutes while they are being played; pages only read
 * the saved copy (/api/football/live), which costs no API calls.
 */
window.Live = (function () {
  var F = window.Football;
  var el = F.el;

  var timelineHost = null;   // where the timeline buttons go (the match page header); null: in the card
  var checklistHost = null, playersHost = null;
  var picked = null;
  var teamNames = null;   // short names the match page's other cards use (e.g. "Tottenham"), else the match's own
  /** The match page passes the short team names its Form and Goals Profile cards show, so every card names them alike. */
  function setTeamNames(names) { teamNames = names && names[0] && names[1] ? names : null; }
  function namesOf(ev) { return teamNames || [ev.home_team, ev.away_team]; }   // the timeline point picked by hand (kept when the page re-reads the stats every minute)   // their own cards on the match page; null: in the card
  /** The match page puts the Pre-Match / 10' / … / FT buttons in its header, as the page's navigation. */
  function setTimelineHost(node) { timelineHost = node; }
  /** The match page shows the Checklist and the players' shots in cards of their own. */
  function setHosts(h) { checklistHost = h.checklist || null; playersHost = h.players || null; }
  /** Tells the match page whether there's anything for its live cards (none before kick-off or on Pre-Match). */
  function cards(show) { if (window.onLiveCards) window.onLiveCards(show); }
  /** A short grey line in the Checklist and Player cards (Match Not Started, No Live Stats). */
  function hostsSay(text) {
    [checklistHost, playersHost].forEach(function (h) { if (h) h.replaceChildren(el('div', 'text-muted', text)); });
  }

  function yes(v) { return v === true || v === 'true'; }
  function finished(fx) { return /^(FINISH|FT|AET|AFTER_PEN)/i.test(String(fx.status || '')); }
  /**
   * Being played now, as of a recent refresh. A game GOAL API last saw at 68' isn't live any more once nothing has
   * refreshed it for 20 minutes or it kicked off over 3 hours ago (games nobody follows stop being refreshed).
   */
  function isLive(fx) {
    if (!yes(fx.live) || finished(fx)) return false;
    var now = Date.now(), ko = new Date(fx.kickoff).getTime(), seen = new Date(fx.updatedAt).getTime();
    if (!isNaN(ko) && now - ko > 3 * 3600000) return false;
    return isNaN(seen) || now - seen < 20 * 60000;
  }

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
  /**
   * Yellow and red cards per side from the live stats rows: { yellow: [h, a], red: [h, a] } (missing: 0), plus
   * penalty: [h, a] when GOAL API sends a penalty count; null without rows.
   */
  function cardCounts(rows) {
    if (!rows || !rows.length) return null;
    var out = { yellow: [0, 0], red: [0, 0] };
    rows.forEach(function (r) {
      var k = r.label === 'Yellow cards' ? 'yellow' : r.label === 'Red cards' ? 'red' : r.label === 'Penalties' ? 'penalty' : null;
      if (k) out[k] = [parseInt(r.home, 10) || 0, parseInt(r.away, 10) || 0];
    });
    return out;
  }
  function score(fx) {
    if (fx.homeScore === null || fx.homeScore === undefined || fx.homeScore === '' || fx.homeScore === 'null') return '';
    return fx.homeScore + '–' + fx.awayScore;
  }
  /** Where the game is: the minute (61', HT) while it's played, FT once it's finished, null before kick-off. */
  function status(fx) {
    if (!fx) return null;
    if (isLive(fx)) return minute(fx);
    if (finished(fx)) return 'FT';
    return null;
  }
  /** LIVE 10' 1–0 / FT 2–1, or null if the game hasn't started. */
  function badge(fx) {
    if (!fx) return null;
    var wrap = el('span', 'd-inline-flex align-items-center gap-1 ms-1');
    if (isLive(fx)) {
      wrap.appendChild(F.statusBadge(minute(fx) === 'HT' ? 'HT' : 'LIVE ' + minute(fx)) || el('span', 'badge rounded-pill status-badge status-live', 'LIVE ' + minute(fx)));
    } else if (finished(fx)) {
      wrap.appendChild(F.statusBadge('FT'));
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
    btn.title = on ? 'Live Stats on: following ' + state.tracked.length + ' game(s)' : 'Live Stats: no games followed';
  }

  function ukDate(d) { return new Date(d).toLocaleDateString('en-CA', { timeZone: 'Europe/London' }); }

  function ago(iso) {
    if (!iso) return 'not yet';
    var mins = Math.round((Date.now() - new Date(iso)) / 60000);
    return mins < 1 ? 'just now' : F.ago(iso);
  }

  // ---- the pop-up ----
  /**
   * The Live Stats pop-up (Football page): GOAL API calls left today, then two lists:
   *  - Live Games: games followed live (with the play button on their match pages), with their LIVE / FT badge.
   *  - Post-Match Games: finished games on the list (last 14 days), saved or not. Refresh loads the stats of the ones
   *    not saved yet (1 call per competition and day, 1 per game). No odds: finished games have none.
   * opts: { postMatch: [{tournament, kickoff, home, away, saved, fx}], onDone(result) }. Opening it costs nothing.
   */
  function openModal(modalEl, opts) {
    opts = opts || {};
    var finishedGames = opts.postMatch || [];
    var todo = finishedGames.filter(function (g) { return !g.saved; });
    modalEl.innerHTML =
      '<div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable"><div class="modal-content">' +
      '<div class="modal-header"><h2 class="modal-title h5">Live Stats</h2>' +
      '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button></div>' +
      '<div class="modal-body small"><div class="alert alert-info px-3 py-2 mb-3" data-status>Loading…</div>' +
      '<div data-live></div><div data-post-games></div></div>' +
      '<div class="modal-footer"><button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Close</button>' +
      '<button type="button" class="btn btn-theme" data-post>Refresh</button></div>' +
      '</div></div>';
    if (window.bootstrap) bootstrap.Modal.getOrCreateInstance(modalEl).show();
    var q = function (attr) { return modalEl.querySelector('[' + attr + ']'); };
    drawPostMatch(q('data-post-games'), finishedGames, todo);
    refreshButton(q('data-post'), todo, opts.onDone);
    return getState().then(function (state) {
      statusLines(q('data-status'), state);
      drawFollowed(q('data-live'), state);
    }).catch(function (e) {
      q('data-status').textContent = 'Couldn\'t load Live Stats.';
      notify('Couldn\'t load Live Stats: ' + e.message, 'danger');
    });
  }
  /** GOAL API calls Post-Match needs: 1 per competition and day, plus 1 per game. */
  function postMatchCalls(games) {
    var days = {};
    games.forEach(function (g) { days[g.tournament + '|' + ukDate(g.kickoff)] = 1; });
    return games.length + Object.keys(days).length;
  }
  /** Refresh: loads the HT and FT stats of the finished games not saved yet. */
  function refreshButton(btn, games, onDone) {
    btn.disabled = !games.length;
    btn.title = games.length ? 'Loads the stats of ' + games.length + ' finished game(s): about ' + postMatchCalls(games) + ' GOAL API calls, no odds'
                             : 'Every finished game already has its stats';
    btn.onclick = function () {
      btn.disabled = true;
      F.sendJson('/api/football/live/post-match', { games: games.map(function (g) {
        return { tournament: g.tournament, kickoff: g.kickoff, home: g.home, away: g.away };
      }) }).then(function (r) {
        var msg = 'Post-Match: stats loaded for ' + r.loaded + ' game(s) (' + r.calls + ' GOAL API calls).';
        if (r.missing && r.missing.length) msg += ' Not found or not finished: ' + r.missing.join(', ') + '.';
        notify(msg, r.missing && r.missing.length ? 'warning' : 'success');
        if (onDone) onDone(r);
      }).catch(function (e) {
        btn.disabled = false;
        notify('Post-Match failed: ' + e.message, 'danger');
      });
    };
  }
  function statusLines(box, state) {
    var u = state.usage || {};
    var limit = Number(u.limit || 1000).toLocaleString('en-GB');
    // Reset time comes as seconds since 1970 (midnight UTC): show it as a UK time
    var reset = u.reset && !isNaN(u.reset) ? new Date(Number(u.reset) * 1000).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/London' }) : '00:00';
    F.infoBox(box, {
      api: 'https://api.goal-api.com/v1',
      plan: !u.limit ? 'Free (assumed)' : Number(u.limit) <= 1000 ? 'Free' : 'Paid',
      left: u.remaining ? Number(u.remaining).toLocaleString('en-GB') + ' of ' + limit + ' today' : 'unknown',
      limit: limit + ' calls a day',
      reset: 'Daily at ' + reset,
      last: state.lastRefresh ? ago(state.lastRefresh) : 'not yet',
      warning: !state.configured ? 'No GOAL API key: add it on the Settings page.' : state.warning
    });
  }
  /** A table of games: day and time, competition, name, and a badge or a grey note. */
  function gameTable(box, rows) {
    var table = el('table', 'table table-sm align-middle mb-3');
    var tbody = el('tbody'); table.appendChild(tbody); box.appendChild(table);
    rows.sort(function (a, b) { return String(a.kickoff).localeCompare(String(b.kickoff)); }).forEach(function (r) {
      var tr = el('tr');
      tr.appendChild(el('td', 'text-nowrap text-muted', F.day(r.kickoff) + ', ' + F.time(r.kickoff)));
      tr.appendChild(el('td', 'text-nowrap text-muted', F.title(r.tournament)));
      tr.appendChild(el('td', null, r.name));
      var st = el('td', 'text-end text-nowrap');
      var b = r.fx ? badge(r.fx) : null;
      st.appendChild(b || el('span', 'text-muted', r.note || ''));
      tr.appendChild(st);
      tbody.appendChild(tr);
    });
  }
  /** Live Games: followed live, ticked (LIVE / FT badge) or waiting for match day. */
  function drawFollowed(box, state) {
    box.replaceChildren();
    box.appendChild(el('div', 'section-label mb-1', 'Live Games'));
    var rows = [];
    (state.tracked || []).forEach(function (id) {
      var fx = (state.fixtures || []).find(function (f) { return f.id === id; });
      var old = finished(fx || {}) && Date.now() - new Date(fx.kickoff) > 3 * 3600000;   // finished earlier: Post-Match
      if (fx && !old) rows.push({ kickoff: fx.kickoff, tournament: fx.tournament, name: fx.home + ' v ' + fx.away, fx: fx, note: 'Not started' });
    });
    (state.following || []).forEach(function (w) {
      rows.push({ kickoff: w.kickoff, tournament: w.tournament, name: w.home + ' v ' + w.away, note: 'Waiting for match day' });
    });
    if (!rows.length) {
      box.appendChild(el('p', 'text-muted mb-3', 'No games followed.'));
      return;
    }
    gameTable(box, rows);
  }
  /** Post-Match Games: finished games (last 14 days) without stats yet, and what Refresh will cost. */
  function drawPostMatch(box, games, todo) {
    box.appendChild(el('div', 'section-label mb-1', 'Post-Match Games'));
    if (!todo.length) { box.appendChild(el('p', 'text-muted mb-0', 'Every finished game has its stats.')); return; }
    box.appendChild(el('p', 'text-muted mb-1', todo.length + ' without stats yet. Refresh loads them: about ' +
      postMatchCalls(todo) + ' GOAL API calls, no odds.'));
    gameTable(box, todo.map(function (g) {
      return { kickoff: g.kickoff, tournament: g.tournament, name: g.home + ' v ' + g.away, note: 'No stats yet' };
    }));
  }

  // ---- checklist, phases and the in-play setup (Betting Framework §7) ----
  /** Checklist items we can read from the stats (the 5th, a good price for the time left, is checked in the bookmaker's app). */
  var CHECKS = [['xG (est.)', 'xG (est.)'], ['Dangerous attacks', 'Dangerous attacks'], ['Shots on target', 'Shots on target'], ['Corners', 'Corner pressure']];

  /**
   * Who leads each checklist item, each side's count, the leader (3+ items and more than the other side)
   * and the signal: 'none', 'bet' (3) or 'strong' (4).
   */
  function checklist(rows) {
    var by = {};
    (rows || []).forEach(function (r) { by[r.label] = r; });
    var out = { items: [], home: 0, away: 0 };
    CHECKS.forEach(function (c) {
      var r = by[c[0]];
      if (!r) return;
      var h = parseFloat(r.home), a = parseFloat(r.away);
      var lead = h > a ? 'home' : a > h ? 'away' : null;
      if (lead) out[lead]++;
      out.items.push({ label: c[1], home: r.home, away: r.away, lead: lead });
    });
    out.of = out.items.length;
    out.leader = out.home >= 3 && out.home > out.away ? 'home' : out.away >= 3 && out.away > out.home ? 'away' : null;
    out.signal = !out.leader ? 'none' : out[out.leader] >= 4 ? 'strong' : 'bet';
    return out;
  }

  /** Window key for a live game from its minute (null before kick-off). */
  function windowFor(fx) {
    if (!fx) return null;
    if (finished(fx)) return 'FT';
    if (minute(fx) === 'HT') return 'HT';
    var e = parseInt(fx.elapsed, 10);
    if (isNaN(e) || !isLive(fx)) return null;
    return e < 25 ? 'WATCH' : e < 35 ? '25' : e < 46 ? 'HT' : e < 60 ? '45' : e < 70 ? '60' : e < 80 ? '70' : '80';
  }
  /**
   * The last timeline point a live game has passed (10, 25, HT, 60, 80), or null before 10' or when it's not live.
   * In-play prices fetched now are saved under it, so that point shows them again later.
   */
  function pointFor(fx) {
    if (!fx || !isLive(fx)) return null;
    if (minute(fx) === 'HT') return 'HT';
    var e = parseInt(fx.elapsed, 10);
    if (isNaN(e) || e < 10) return null;
    return e < 25 ? '10' : e < 45 ? '25' : e < 60 ? 'HT' : e < 80 ? '60' : '80';
  }
  /** Is the checklist leader winning, drawing or losing? (null without a leader or a score) */
  function setup(check, homeScore, awayScore) {
    var h = parseInt(homeScore, 10), a = parseInt(awayScore, 10);
    if (!check.leader || isNaN(h) || isNaN(a)) return null;
    var lead = check.leader === 'home' ? h - a : a - h;
    return lead > 0 ? 'winning' : lead === 0 ? 'drawing' : 'losing';
  }
  function periodLabel(p) { return p === 'PRE' ? 'Pre-Match' : /^\d+$/.test(p) ? p + '\'' : p; }

  // ---- momentum: who's pushing (pressure from attacking actions) ----
  /** Weights per action: more dangerous actions count more. */
  var PRESSURE = { 'Dangerous attacks': 1, 'Shots': 3, 'Shots on target': 5, 'Corners': 2 };
  function num(v) { var n = parseFloat(v); return isNaN(n) ? 0 : n; }
  /** Each side's pressure score and the home share (null if there's nothing to go on). */
  /**
   * Pressure from attacking actions, and the home side's share of it. Each side starts with a few points
   * (MOMENTUM_START), so a handful of actions moves the bar rather than swinging it to 100%: 10-0 shows as 75%.
   */
  var MOMENTUM_START = 5;
  function momentum(rows) {
    var h = 0, a = 0;
    (rows || []).forEach(function (r) {
      var w = PRESSURE[r.label];
      if (w) { h += w * num(r.home); a += w * num(r.away); }
    });
    return h + a > 0 ? { home: h, away: a, homeShare: (h + MOMENTUM_START) / (h + a + 2 * MOMENTUM_START) } : null;
  }
  /** The stats between two points in the match: this snapshot minus the one before (counts only). */
  function since(rows, prevRows) {
    var prev = {};
    (prevRows || []).forEach(function (r) { prev[r.label] = r; });
    return (rows || []).filter(function (r) { return PRESSURE[r.label] && prev[r.label]; }).map(function (r) {
      return { label: r.label, home: String(Math.max(0, num(r.home) - num(prev[r.label].home))),
               away: String(Math.max(0, num(r.away) - num(prev[r.label].away))) };
    });
  }
  /** "Tottenham on top" (60%+), "edging it" (55-60%) or "Even". */
  function momentumLabel(m, names) {
    var hp = Math.round(m.homeShare * 100), top = hp >= 50 ? names[0] : names[1], p = Math.max(hp, 100 - hp);   // as shown
    return p >= 60 ? top + ' on top' : p >= 55 ? top + ' edging it' : 'Even';
  }

  // ---- Live Match Stats card (match page) ----
  /** A game picked with the play button that GOAL API doesn't list yet (it's looked up on match day). */
  function followingFor(state, ev) {
    if (!state || !state.following) return null;
    var t = new Date(ev.commence_time).getTime();
    return state.following.find(function (w) {
      return Math.abs(new Date(w.kickoff).getTime() - t) <= 30 * 60000 && sim(w.home, ev.home_team) + sim(w.away, ev.away_team) > 1;
    }) || null;
  }
  function tournamentOf(ev) { return String(ev.sport_key || '').replace(/^soccer_/, ''); }

  function renderCard(box, footLeft, state, ev) {
    var fx = findFor(state, ev);
    if (fx && (isTracked(state, fx) || yes(fx.saved))) {
      return F.getJson('/api/football/live/match/' + encodeURIComponent(fx.id)).then(function (m) {
        drawCard(box, footLeft, m, fx, ev, state);
        return m;
      });
    }
    if (followingFor(state, ev)) return waiting(box, footLeft, ev, state);
    return notFollowed(box, footLeft, ev, state);
  }

  /** Follow (on) or stop following this game live, then redraw the card. */
  function follow(box, footLeft, ev, on) {
    var q = 'tournament=' + encodeURIComponent(tournamentOf(ev)) + '&kickoff=' + encodeURIComponent(ev.commence_time) +
      '&home=' + encodeURIComponent(ev.home_team) + '&away=' + encodeURIComponent(ev.away_team) + '&on=' + on;
    return F.sendJson('/api/football/live/follow?' + q).then(function (state) {
      notify(on ? 'Following ' + ev.home_team + ' v ' + ev.away_team + ' live: its stats are saved at 10\', 25\', HT, 60\', 80\' and FT.'
                : 'Stopped following ' + ev.home_team + ' v ' + ev.away_team + '.', 'success');
      return renderCard(box, footLeft, state, ev);
    }).catch(function (e) { notify('Couldn\'t ' + (on ? 'follow' : 'stop following') + ' the game: ' + e.message, 'danger'); });
  }
  /** ▶ follow live / ■ stop following: icon buttons for the timeline before kick-off. */
  function followButton(box, footLeft, ev, on) {
    var btn = el('button', 'btn btn-sm btn-outline-theme', on ? '▶' : '■');
    btn.type = 'button';
    btn.setAttribute('data-follow', on ? 'on' : 'off');
    btn.title = on ? 'Follow live: the stats are refreshed during the game and saved at 10\', 25\', HT, 60\', 80\' and FT'
                   : 'Stop following this game';
    btn.onclick = function () { btn.disabled = true; follow(box, footLeft, ev, on); };
    return btn;
  }
  function putTimeline(box, bar) { if (timelineHost) timelineHost.replaceChildren(bar); else box.appendChild(bar); }

  /** Followed before GOAL API lists it: Pre-Match, then the points to come (greyed out) and a stop button. */
  function waiting(box, footLeft, ev, state) {
    cards(false);
    box.replaceChildren(el('div', 'text-muted', 'Match Not Started'));
    hostsSay('Match Not Started');
    footLeft.textContent = '';
    var bar = timeline([], { status: 'SCHEDULED' }, function (snap) { if (window.onLiveView) window.onLiveView(snap); });
    bar.appendChild(followButton(box, footLeft, ev, false));
    putTimeline(box, bar);
    return Promise.resolve(null);
  }

  /**
   * Not followed: before kick-off, Pre-Match and a ▶ button to follow it live; a game already played offers to load
   * its stats once (Post-Match, 2 GOAL API calls).
   */
  function notFollowed(box, footLeft, ev, state) {
    var started = new Date(ev.commence_time).getTime() <= Date.now();
    cards(started);
    box.replaceChildren(el('div', 'text-muted', started ? 'No Live Stats' : 'Match Not Started'));
    hostsSay(started ? 'No Live Stats' : 'Match Not Started');
    footLeft.textContent = '';
    var tournament = tournamentOf(ev);
    if (!started && tournament && timelineHost) {
      var bar = el('div', 'd-flex justify-content-center flex-wrap gap-1 mb-2 live-timeline');
      var pre = el('button', 'btn btn-sm btn-outline-theme active', periodLabel('PRE'));
      pre.type = 'button';
      pre.setAttribute('data-period', 'PRE');
      bar.appendChild(pre);
      bar.appendChild(followButton(box, footLeft, ev, true));
      timelineHost.replaceChildren(bar);
      return Promise.resolve(null);
    }
    var played = new Date(ev.commence_time).getTime() < Date.now() - 2 * 3600000;
    if (played && tournament) {
      var btn = el('button', 'btn btn-sm btn-outline-theme' + (timelineHost ? '' : ' d-block mx-auto mt-2'), 'Post-Match');
      btn.type = 'button';
      btn.title = 'Fetches this game\'s half-time and full-time stats once: 2 GOAL API calls';
      btn.onclick = function () {
        btn.disabled = true;
        replay(ev, tournament).then(function (m) {
          drawCard(box, footLeft, m, m.fixture, ev);
          notify('Loaded the stats for ' + ev.home_team + ' v ' + ev.away_team + '.', 'success');
        }).catch(function (e) {
          btn.disabled = false;
          notify('Couldn\'t load the game\'s stats: ' + e.message, 'danger');
        });
      };
      putTimeline(box, btn);   // where the timeline goes once it's loaded
    } else if (timelineHost) {
      timelineHost.replaceChildren();
    }
    return Promise.resolve(null);
  }

  function replay(ev, tournament) {
    var q = 'tournament=' + encodeURIComponent(tournament) + '&date=' + ukDate(ev.commence_time) +
      '&kickoff=' + encodeURIComponent(ev.commence_time) + '&home=' + encodeURIComponent(ev.home_team) +
      '&away=' + encodeURIComponent(ev.away_team);
    return F.sendJson('/api/football/live/replay?' + q);
  }

  /**
   * A followed (ticked) or saved game. Timeline: Pre-Match, the saved points, LIVE (the latest stats, while it's being
   * played) and the points still to come greyed out. Opens on LIVE, else the latest saved point, else Pre-Match.
   */
  function drawCard(box, footLeft, m, fx, ev) {
    var f = m.fixture || fx, s = m.stats, snaps = m.snapshots || [];
    gameEvents = m.events || [];
    box.replaceChildren();
    box.classList.remove('text-muted');
    footLeft.textContent = '';
    // The score and minute go in the page header (under the competition)
    if (window.onLiveFixture) window.onLiveFixture({ status: status(f), score: score(f), cards: cardCounts(s && s.rows) });
    var view = el('div');
    var before = function (snap) { var i = snaps.indexOf(snap); return i > 0 ? snaps[i - 1] : null; };
    var e = parseInt(f.elapsed, 10);
    var live = isLive(f) && s && s.rows && s.rows.length ? { period: windowFor(f), point: pointFor(f), live: true,
      label: 'LIVE ' + minute(f), minute: isNaN(e) ? null : e, xg: s.xg, rows: s.rows, homeScore: f.homeScore, awayScore: f.awayScore } : null;
    var pick = function (snap) {
      drawView(view, snap, ev, snap.live || snap.period === 'FT' ? s : null, snap.live ? snaps[snaps.length - 1] || null : before(snap));
    };
    // Keep the point picked by hand across the minute-by-minute re-reads; otherwise LIVE, the latest point, Pre-Match
    var chosen = picked === 'LIVE' ? live : picked === 'PRE' ? { period: 'PRE' } :
      snaps.find(function (x) { return x.period === picked; }) || null;
    var first = chosen || live || snaps[snaps.length - 1] || { period: 'PRE' };
    var bar = timeline(snaps, f, pick, live, first.live ? 'LIVE' : first.period);
    if (!status(f) && m.tracked) bar.appendChild(followButton(box, footLeft, ev, false));   // not started: can still stop
    putTimeline(box, bar);
    box.appendChild(view);
    pick(first);
  }

  /** Minute each timeline point is reached at (HT just after 45'). */
  var POINT_MINUTE = { '10': 10, '25': 25, HT: 45.5, '60': 60, '80': 80, FT: Infinity };
  /** Is this point still to come (it can still be saved)? Before kick-off they all are. */
  function ahead(p, fx) {
    if (!fx || finished(fx) || isOff(fx)) return false;
    if (!isLive(fx)) return !status(fx) && !fx.homeScore;   // not started yet
    var now = minute(fx) === 'HT' ? 45.5 : parseInt(fx.elapsed, 10);
    return isNaN(now) || now < POINT_MINUTE[p];
  }
  function isOff(fx) { return /CANCEL|POSTPON|ABANDON|SUSPEND/i.test(String(fx.status || '')); }
  /**
   * Buttons for the points in the match. Saved ones can be clicked; points still to come are greyed out until
   * they're saved; points that were missed (and can't be fetched afterwards) are hidden. live: the latest stats of a
   * game being played, as a LIVE button where the game is now. The active one: LIVE, else the latest saved point.
   */
  function timeline(snaps, fx, onPick, live, current) {
    var by = {};
    snaps.forEach(function (sn) { by[sn.period] = sn; });
    var bar = el('div', 'd-flex justify-content-center flex-wrap gap-1 mb-2 live-timeline');
    var buttons = [];
    by.PRE = { period: 'PRE' };   // before kick-off: always there (the pre-match Best Bets)
    function add(btn, snap) {
      btn.type = 'button';
      if (snap) {
        btn.onclick = function () {
          buttons.forEach(function (x) { x.classList.remove('active'); });
          btn.classList.add('active');
          picked = btn.getAttribute('data-period');
          onPick(snap);
        };
      }
      buttons.push(btn);
      bar.appendChild(btn);
    }
    // Only the points reached so far: each appears (10', 25', HT...) as the game gets there, with LIVE after them
    ['PRE', '10', '25', 'HT', '60', '80', 'FT'].forEach(function (p) {
      if (!by[p]) return;   // not reached yet, or missed (the API has no stats for past points in a game)
      var btn = el('button', 'btn btn-sm btn-outline-theme', periodLabel(p));
      btn.setAttribute('data-period', p);
      add(btn, by[p]);
    });
    if (live) addLive();
    function addLive() {
      var b = el('button', 'btn btn-sm btn-outline-live', live.label);
      b.setAttribute('data-period', 'LIVE');
      b.title = 'The latest stats (refreshed every few minutes)';
      add(b, live);
    }
    current = current || (live ? 'LIVE' : snaps.length ? snaps[snaps.length - 1].period : 'PRE');
    buttons.forEach(function (x) { if (x.getAttribute('data-period') === current) x.classList.add('active'); });
    return bar;
  }

  /** One point in the match: score, window, checklist, stats table and (live or full time) shooters. */
  function drawView(view, snap, ev, full, prev) {
    view.replaceChildren();
    if (snap.period === 'PRE') {
      view.appendChild(el('div', 'text-muted', 'Match Not Started'));
      hostsSay('Match Not Started');
      cards(false);
      if (window.onLiveView) window.onLiveView(snap);
      return;
    }
    cards(true);
    var mo = momentumBox(snap, prev, ev);
    if (mo) view.appendChild(mo);
    var check = checklist(snap.rows);
    if (checklistHost) {
      checklistHost.replaceChildren(check.of ? checklistBox(check, ev, false) : el('div', 'text-muted', 'No Checklist'));
    } else if (check.of) {
      view.appendChild(checklistBox(check, ev, true));
    }
    view.appendChild(statsTable(snap.rows, ev));
    // Timeline: what's happened up to this point; a game that wasn't followed live has none, so its shooters instead
    var upTo = eventsUpTo(gameEvents, snap);
    var shots = !gameEvents.length && full ? shooters(full, !playersHost, ev) : null;
    if (playersHost) {
      playersHost.replaceChildren(upTo.length ? timelineBox(upTo, ev) : shots && shots.childNodes.length ? shots
        : el('div', 'text-muted', gameEvents.length ? 'Nothing yet: goals, cards and shots on target show here as they happen.'
          : full ? 'No Player Stats' : 'Player stats are for full time and live games only'));
    } else if (upTo.length) {
      view.appendChild(timelineBox(upTo, ev));
    } else if (shots) {
      view.appendChild(shots);
    }
    if (window.onLiveView) window.onLiveView(snap);   // the match page's Best Bets switch to this point in the match
  }

  /** Momentum bars: the match so far, and since the previous point in the timeline (who's on top right now). */
  function momentumBox(snap, prev, ev) {
    var names = namesOf(ev);
    var all = momentum(snap.rows);
    if (!all) return null;
    var box = el('div', 'live-momentum mb-3');
    // One bar: who's on top right now, i.e. since the previous point (the whole match when there's none before)
    var recent = prev ? momentum(since(snap.rows, prev.rows)) : null;
    box.appendChild(recent ? momentumBar('Since ' + periodLabel(prev.period), recent, names) : momentumBar('Match so far', all, names));
    var tipText = 'Pressure from attacking actions: dangerous attacks x1, shots x3, shots on target x5, corners x2.';
    box.setAttribute('data-bs-toggle', 'tooltip');
    box.setAttribute('data-bs-title', tipText);
    return box;
  }
  function momentumBar(title, m, names) {
    var hp = Math.round(m.homeShare * 100);
    var row = el('div', 'mb-1');
    var top = el('div', 'd-flex justify-content-between small');
    top.appendChild(el('span', 'fw-semibold', hp + '%'));
    top.appendChild(el('span', 'text-muted', title + ': ' + momentumLabel(m, names)));
    top.appendChild(el('span', 'fw-semibold', (100 - hp) + '%'));
    row.appendChild(top);
    var bar = el('div', 'progress momentum-bar');
    var h = el('div', 'progress-bar momentum-home'), a = el('div', 'progress-bar momentum-away');
    h.style.width = hp + '%';
    a.style.width = (100 - hp) + '%';
    bar.appendChild(h); bar.appendChild(a);
    row.appendChild(bar);
    return row;
  }

  function checklistBox(check, ev, titled) {
    // In the stats card: a section title like Momentum; in its own card the card header is the title
    var box = el('div', 'live-checklist' + (titled ? ' mb-3' : ''));
    if (titled) box.appendChild(el('div', 'section-label', 'Checklist'));
    var head = el('div', 'd-flex justify-content-between fw-semibold border-bottom pb-1 mb-1');
    head.appendChild(el('span', 'text-nowrap', namesOf(ev)[0] + ' ' + check.home + '/' + check.of));
    head.appendChild(el('span', 'text-nowrap', check.away + '/' + check.of + ' ' + namesOf(ev)[1]));
    box.appendChild(head);
    check.items.forEach(function (it) {
      var row = el('div', 'd-flex justify-content-between py-1');
      // the side that leads an item: green tick and number
      row.appendChild(el('span', it.lead === 'home' ? 'fw-semibold text-good' : 'text-muted', (it.lead === 'home' ? '✓ ' : '') + it.home));
      row.appendChild(el('span', 'text-muted', it.label));
      row.appendChild(el('span', it.lead === 'away' ? 'fw-semibold text-good' : 'text-muted', it.away + (it.lead === 'away' ? ' ✓' : '')));
      box.appendChild(row);
    });
    return box;
  }

  function statsTable(rows, ev) {
    var table = el('table', 'table table-sm align-middle mb-2 stats-table');
    var thead = el('thead'), hr = el('tr', 'text-muted');
    hr.appendChild(el('th', 'text-start text-nowrap', namesOf(ev)[0]));
    hr.appendChild(el('th', 'text-center', ''));
    hr.appendChild(el('th', 'text-end text-nowrap', namesOf(ev)[1]));
    thead.appendChild(hr); table.appendChild(thead);
    var tb = el('tbody');
    (rows || []).forEach(function (r) {
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
    table.appendChild(tb);
    return table;
  }

  var gameEvents = [];   // the followed game's timeline from the server (goals, cards, shots on target...)

  /** The events up to a point in the match: all for LIVE and FT, the first half for HT, up to the minute for 10'...80'. */
  function eventsUpTo(events, snap) {
    if (!events || !events.length || !snap || snap.period === 'PRE') return [];
    if (snap.live || snap.period === 'FT') return events.slice();
    if (snap.period === 'HT') return events.filter(function (e) { return e.half === 1; });
    var p = parseInt(snap.period, 10);
    if (isNaN(p)) return events.slice();
    return events.filter(function (e) {
      return e.kind !== 'ft' && (p <= 45 ? e.half === 1 && e.elapsed <= p : e.half === 1 || e.elapsed <= p);
    });
  }

  var EVENT_ICONS = { goal: '⚽', yellow: '🟨', red: '🟥', penalty: '🥅', shot: '🎯' };
  /**
   * Timeline: newest at the top. Home team's events on the left, away team's on the right, the minute in the
   * middle; kick-off, half time, second half and full time across the middle.
   */
  function timelineBox(events, ev) {
    var names = namesOf(ev);
    var box = el('div', 'live-timeline');
    events.slice().sort(function (a, b) { return (b.order || 0) - (a.order || 0); }).forEach(function (e) {
      if (['kickoff', 'ht', 'second', 'ft'].indexOf(e.kind) >= 0) {
        var words = { kickoff: 'Kick-off', ht: 'Half time', second: 'Second half', ft: 'Full time' }[e.kind];
        var mid = el('div', 'd-flex align-items-center gap-2 my-2 text-muted');
        mid.appendChild(el('hr', 'flex-grow-1 my-0'));
        mid.appendChild(el('span', 'fw-semibold text-nowrap', words + (e.score && e.kind !== 'kickoff' ? ' · ' + e.score : '')));
        mid.appendChild(el('hr', 'flex-grow-1 my-0'));
        box.appendChild(mid);
        return;
      }
      var row = el('div', 'd-flex align-items-center py-1' + (e.kind === 'shot' ? ' text-muted' : ''));
      var left = el('div', 'w-50 text-end pe-2'), right = el('div', 'w-50 text-start ps-2');
      var text = el('span');
      var what = e.kind === 'goal' ? (e.player || 'Goal') : e.kind === 'shot' ? 'Shot on target · ' + e.player
        : e.kind === 'yellow' ? 'Yellow card' : e.kind === 'red' ? 'Red card' : 'Penalty';
      var icon = EVENT_ICONS[e.kind] || '';
      var team = e.side === 'away' ? names[1] : names[0];
      if (e.kind === 'goal') {
        text.appendChild(el('span', 'fw-bold', what));
        if (e.score) text.appendChild(el('span', 'ms-1 badge text-bg-light border', e.score));
      } else {
        text.appendChild(el('span', e.kind === 'red' || e.kind === 'penalty' ? 'fw-semibold' : '', what));
      }
      if (e.kind !== 'goal' && e.kind !== 'shot') text.title = team;
      if (e.side === 'away') {
        right.appendChild(document.createTextNode(icon + ' '));
        right.appendChild(text);
      } else {
        left.appendChild(text);
        left.appendChild(document.createTextNode(' ' + icon));
      }
      var minute = el('span', 'badge rounded-pill text-bg-secondary flex-shrink-0', e.minute || '');
      minute.style.minWidth = '3.2rem';
      row.appendChild(left);
      row.appendChild(minute);
      row.appendChild(right);
      box.appendChild(row);
    });
    return box;
  }

  /** Players with shots on target (live, or full time). */
  /**
   * Players with shots: shots on target (shots) and ⚽ per goal, in the same home | away table as the stats
   * (home players on the left, away on the right).
   */
  function shooters(s, titled, ev) {
    var wrap = el('div');
    var hp = s.homePlayers || [], ap = s.awayPlayers || [];
    if (!hp.length && !ap.length) return wrap;
    if (titled) wrap.appendChild(el('div', 'section-label', 'Shots on target (shots)'));
    var table = el('table', 'table table-sm align-middle mb-0 stats-table');
    var thead = el('thead'), hr = el('tr');
    hr.appendChild(el('th', 'text-start text-nowrap w-50', namesOf(ev)[0]));
    hr.appendChild(el('th', 'text-end text-nowrap w-50', namesOf(ev)[1]));
    thead.appendChild(hr); table.appendChild(thead);
    var tb = el('tbody');
    var line = function (p) { return p ? p.name + ' ' + p.onTarget + ' (' + p.shots + ')' + (p.goals > 0 ? ' ⚽'.repeat(Math.min(p.goals, 3)) : '') : ''; };
    for (var i = 0; i < Math.max(hp.length, ap.length); i++) {
      var tr = el('tr');
      tr.appendChild(el('td', 'text-start', line(hp[i]) || (i === 0 ? '–' : '')));
      tr.appendChild(el('td', 'text-end', line(ap[i]) || (i === 0 ? '–' : '')));
      tb.appendChild(tr);
    }
    table.appendChild(tb);
    wrap.appendChild(table);
    return wrap;
  }

  return { getState: getState, findFor: findFor, isTracked: isTracked, monitoring: monitoring, isLive: isLive,
           finished: finished, followingFor: followingFor, badge: badge, status: status, setTimelineHost: setTimelineHost, setHosts: setHosts, setTeamNames: setTeamNames, score: score, setButton: setButton, openModal: openModal, cardCounts: cardCounts, renderCard: renderCard,
           checklist: checklist, eventsUpTo: eventsUpTo, timelineBox: timelineBox, windowFor: windowFor, pointFor: pointFor, setup: setup, momentum: momentum, since: since,
           momentumLabel: momentumLabel };
})();
