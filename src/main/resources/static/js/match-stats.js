// Match stats card: form, league positions, goals profile and head to head from football-data.org
// (via our cached /api/football/data endpoints). Needs football-data.js (window.Football).
window.MatchStats = (function () {
  var F = window.Football, el = F.el;

  // Our tournament names -> football-data.org competition codes.
  var COMP_CODES = { epl: 'PL', uefa_champs_league: 'CL', fifa_world_cup: 'WC', uefa_european_championship: 'EC' };

  function getJson(url) {
    return fetch(url).then(function (r) {
      return r.json().then(function (body) {
        if (!r.ok) { var e = new Error(body.message || ('HTTP ' + r.status)); e.status = r.status; throw e; }
        return body;
      });
    });
  }

  /** One finished match from a team's point of view. */
  function view(m, teamId) {
    var home = m.homeTeam.id === teamId;
    var gf = home ? m.score.fullTime.home : m.score.fullTime.away;
    var ga = home ? m.score.fullTime.away : m.score.fullTime.home;
    var htf = m.score.halfTime ? (home ? m.score.halfTime.home : m.score.halfTime.away) : null;
    var hta = m.score.halfTime ? (home ? m.score.halfTime.away : m.score.halfTime.home) : null;
    return { m: m, home: home, gf: gf, ga: ga, htf: htf, hta: hta,
             result: gf > ga ? 'W' : gf < ga ? 'L' : 'D',
             opponent: home ? m.awayTeam : m.homeTeam };
  }

  function finished(list, teamId) {
    return (list.matches || [])
      .filter(function (m) { return m.status === 'FINISHED' && m.score && m.score.fullTime && m.score.fullTime.home !== null; })
      .sort(function (a, b) { return b.utcDate.localeCompare(a.utcDate); })
      .map(function (m) { return view(m, teamId); });
  }

  /** Goals profile over a list of match views. */
  function profile(views) {
    var n = views.length;
    function pct(f) { return n ? Math.round(100 * views.filter(f).length / n) + '%' : '–'; }
    function avg(f) { return n ? (views.reduce(function (t, v) { return t + f(v); }, 0) / n).toFixed(1) : '–'; }
    return {
      n: n,
      scored: avg(function (v) { return v.gf; }),
      conceded: avg(function (v) { return v.ga; }),
      over25: pct(function (v) { return v.gf + v.ga > 2.5; }),
      btts: pct(function (v) { return v.gf > 0 && v.ga > 0; }),
      cleanSheets: pct(function (v) { return v.ga === 0; }),
      failedToScore: pct(function (v) { return v.gf === 0; }),
      leadingAtHt: pct(function (v) { return v.htf !== null && v.htf > v.hta; })
    };
  }

  function formTiles(views) {
    var wrap = el('span', 'd-inline-flex gap-1');
    views.slice(0, 5).reverse().forEach(function (v) {   // oldest -> newest, newest on the right
      var t = el('span', 'form-tile form-' + v.result, v.result);
      t.setAttribute('data-bs-toggle', 'tooltip');
      t.setAttribute('data-bs-title', (v.home ? 'H' : 'A') + ' v ' + (v.opponent.shortName || v.opponent.name) +
        ' ' + v.gf + '–' + v.ga + '\n' + (v.m.competition ? v.m.competition.name : '') + ', ' +
        new Date(v.m.utcDate).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }));
      wrap.appendChild(t);
    });
    if (!views.length) wrap.appendChild(el('span', 'text-muted', '–'));
    return wrap;
  }

  /** "2nd, Premier League, 28 pts" for a team in a standings response, or null. */
  function position(standings, teamId) {
    if (!standings || !standings.standings) return null;
    var total = standings.standings.filter(function (s) { return s.type === 'TOTAL'; });
    for (var i = 0; i < total.length; i++) {
      var row = total[i].table.find(function (r) { return r.team.id === teamId; });
      if (row) return { pos: row.position, pts: row.points, played: row.playedGames, gd: row.goalDifference,
                        name: standings.competition.name, group: total[i].group };
    }
    return null;
  }
  function ordinal(n) { var s = ['th', 'st', 'nd', 'rd'], v = n % 100; return n + (s[(v - 20) % 10] || s[v] || s[0]); }

  /** Most recent league (not cup) a team played in, e.g. {code:'PL', name:'Premier League'}. */
  function domesticLeague(list) {
    var m = (list.matches || []).filter(function (x) { return x.competition && x.competition.type === 'LEAGUE'; })
      .sort(function (a, b) { return b.utcDate.localeCompare(a.utcDate); })[0];
    return m ? m.competition : null;
  }

  function row(tbody, label, home, away, hint) {
    var tr = el('tr');
    var th = el('td', 'text-muted', label);
    if (hint) { th.setAttribute('data-bs-toggle', 'tooltip'); th.setAttribute('data-bs-title', hint); th.style.cursor = 'help'; }
    tr.appendChild(th);
    [home, away].forEach(function (v) {
      var td = el('td', 'text-center');
      if (v instanceof Node) td.appendChild(v); else td.textContent = v;
      tr.appendChild(td);
    });
    tbody.appendChild(tr);
  }

  function table(headers) {
    var t = el('table', 'table table-sm align-middle mb-0 stats-table');
    var tr = el('tr');
    headers.forEach(function (h, i) { tr.appendChild(el('th', i ? 'text-center' : 'text-muted fw-normal', h)); });
    var thead = el('thead'); thead.appendChild(tr); t.appendChild(thead);
    var tb = el('tbody'); t.appendChild(tb);
    return { table: t, body: tb };
  }

  /**
   * A team's top scorers across the given competitions, e.g. Haaland: 5 CL goals, 12 PL goals.
   * One cached call per competition (shared by every match in it).
   */
  function keyPlayers(box, team, comps) {
    box.appendChild(el('div', 'section-label', team.shortName || team.name));
    var list = el('div', 'text-muted', 'Loading…'); box.appendChild(list);
    Promise.all(comps.map(function (c) {
      return getJson('/api/football/data/competitions/' + c.code + '/scorers').then(function (d) {
        return (d.scorers || []).filter(function (s) { return s.team && s.team.id === team.id; })
          .map(function (s) { return { s: s, comp: c.code, compName: c.name || (d.competition && d.competition.name) }; });
      }).catch(function () { return []; });
    })).then(function (lists) {
      // Merge per player: goals by competition
      var players = {};
      [].concat.apply([], lists).forEach(function (x) {
        var p = players[x.s.player.id] = players[x.s.player.id] || { name: x.s.player.name, total: 0, parts: [], tips: [] };
        p.total += x.s.goals || 0;
        p.parts.push((x.s.goals || 0) + ' ' + x.comp);
        p.tips.push(x.compName + ': ' + (x.s.goals || 0) + ' goals, ' + (x.s.assists || 0) + ' assists, ' +
          (x.s.penalties || 0) + ' penalties in ' + (x.s.playedMatches || 0) + ' games');
      });
      var top = Object.keys(players).map(function (k) { return players[k]; })
        .sort(function (a, b) { return b.total - a.total; }).slice(0, 3);
      list.replaceChildren();
      list.className = '';
      if (!top.length) { list.appendChild(el('div', 'text-muted', 'No players in the scorer tables yet.')); return; }
      var t = el('table', 'table table-sm mb-0 stats-table'), tb = el('tbody');
      top.forEach(function (p) {
        var tr = el('tr');
        var name = el('td', null, p.name);
        name.setAttribute('data-bs-toggle', 'tooltip'); name.setAttribute('data-bs-title', p.tips.join('\n')); name.style.cursor = 'help';
        tr.appendChild(name);
        tr.appendChild(el('td', 'text-end text-nowrap', p.parts.join(' · ')));
        tb.appendChild(tr);
      });
      t.appendChild(tb); list.appendChild(t);
    });
  }

  function render(box, ev, homeTeam, awayTeam) {
    box.replaceChildren();
    if (!homeTeam || !awayTeam) {
      ['form', 'h2h', 'keyPlayers', 'valueCheck'].forEach(function (id) {
        document.getElementById(id).replaceChildren(el('span', 'text-muted', 'Not available for this match.'));
      });
      box.appendChild(el('p', 'text-muted mb-0', 'Stats aren\'t available for this match: one of the teams isn\'t covered by football-data.org\'s free plan.'));
      return;
    }
    box.appendChild(el('span', 'text-muted', 'Loading…'));

    Promise.all([
      getJson('/api/football/data/teams/' + homeTeam.id + '/matches'),
      getJson('/api/football/data/teams/' + awayTeam.id + '/matches')
    ]).then(function (lists) {
      var homeAll = finished(lists[0], homeTeam.id), awayAll = finished(lists[1], awayTeam.id);
      var homeVenue = homeAll.filter(function (v) { return v.home; }).slice(0, 10);
      var awayVenue = awayAll.filter(function (v) { return !v.home; }).slice(0, 10);
      // Too few home/away games? Fall back to all venues.
      var venueNote = '';
      if (homeVenue.length < 5 || awayVenue.length < 5) {
        homeVenue = homeAll.slice(0, 10); awayVenue = awayAll.slice(0, 10);
        venueNote = 'last ' + Math.min(homeVenue.length, awayVenue.length) + ' games, any venue';
      } else {
        venueNote = 'last ' + homeVenue.length + ' home games for ' + (homeTeam.shortName || homeTeam.name) +
                    ', last ' + awayVenue.length + ' away games for ' + (awayTeam.shortName || awayTeam.name);
      }
      var hp = profile(homeVenue), ap = profile(awayVenue);
      if (window.ValueCheck) {
        ValueCheck.render(document.getElementById('valueCheck'), ev, homeVenue, awayVenue);
      }

      box.replaceChildren();
      var names = ['', homeTeam.shortName || homeTeam.name, awayTeam.shortName || awayTeam.name];

      // Form and positions
      var t1 = table(names);
      row(t1.body, 'Form (last 5)', formTiles(homeAll), formTiles(awayAll), 'All competitions, newest on the right. Hover a tile for the score.');
      var posHome = el('span', 'text-muted', '…'), posAway = el('span', 'text-muted', '…');
      var posCl = el('span', 'text-muted', '…'), posClAway = el('span', 'text-muted', '…');
      row(t1.body, 'League', posHome, posAway);
      var code = COMP_CODES[ev.compKey];
      // A league match (e.g. Premier League) is already the "League" row; only cups/tournaments get their own row.
      var leagueMatch = code && [domesticLeague(lists[0]), domesticLeague(lists[1])].every(function (c) { return c && c.code === code; });
      if (code && !leagueMatch) row(t1.body, ev.comp, posCl, posClAway);
      t1.table.classList.replace('mb-3', 'mb-0');
      document.getElementById('form').replaceChildren(t1.table);   // its own card above Match stats

      // Goals profile
      var note = document.getElementById('goalsNote');   // shown in the card footer, left side
      if (note) note.textContent = 'Last ' + venueNote.replace(/^last /, '');
      var t2 = table(names);
      row(t2.body, 'Avg scored', hp.scored, ap.scored);
      row(t2.body, 'Avg conceded', hp.conceded, ap.conceded);
      row(t2.body, 'Over 2.5 goals', hp.over25, ap.over25, 'Games with 3 or more goals in total');
      row(t2.body, 'Both teams scored', hp.btts, ap.btts);
      row(t2.body, 'Clean sheets', hp.cleanSheets, ap.cleanSheets);
      row(t2.body, 'Failed to score', hp.failedToScore, ap.failedToScore);
      row(t2.body, 'Leading at half-time', hp.leadingAtHt, ap.leadingAtHt);
      t2.table.classList.replace('mb-3', 'mb-0');
      box.appendChild(t2.table);

      // Head to head and key players have their own cards below Match stats
      var h2hBox = document.getElementById('h2h');

      // Key players: top scorers for each team (free plan includes competition scorer tables)
      var kp = el('div', 'row g-3');
      document.getElementById('keyPlayers').replaceChildren(kp);
      var kpHome = el('div', 'col-sm-6'), kpAway = el('div', 'col-sm-6');
      kp.appendChild(kpHome); kp.appendChild(kpAway);

      // League positions (domestic + this competition)
      function fill(span, comp, teamId) {
        if (!comp) { span.textContent = '–'; return; }
        getJson('/api/football/data/competitions/' + comp.code + '/standings').then(function (st) {
          var p = position(st, teamId);
          span.className = '';
          span.textContent = p ? ordinal(p.pos) + ' · ' + p.pts + ' pts' + (p.group && /^GROUP_/.test(p.group) ? ' (' + p.group.replace('GROUP_', 'Group ') + ')' : '') : '–';
          span.title = p ? (p.name + ': ' + p.played + ' played, goal difference ' + (p.gd > 0 ? '+' : '') + p.gd) : '';
        }).catch(function () { span.textContent = 'n/a'; });
      }
      var hl = domesticLeague(lists[0]), al = domesticLeague(lists[1]);
      fill(posHome, hl, homeTeam.id); fill(posAway, al, awayTeam.id);
      if (hl) posHome.after(el('div', 'small text-muted', hl.name));
      if (al) posAway.after(el('div', 'small text-muted', al.name));
      if (code && !leagueMatch) { fill(posCl, { code: code }, homeTeam.id); fill(posClAway, { code: code }, awayTeam.id); }

      // Key players: this competition plus the team's league (once, if they're the same)
      function comps(league) { return [code && { code: code, name: ev.comp }, league && league.code !== code ? league : null].filter(Boolean); }
      keyPlayers(kpHome, homeTeam, comps(hl));
      keyPlayers(kpAway, awayTeam, comps(al));

      // Head to head: find this fixture's football-data id, then its previous meetings.
      (code ? getJson('/api/football/data/competitions/' + code + '/matches?status=SCHEDULED')
            : Promise.reject(new Error('no competition'))).then(function (up) {
        var fx = (up.matches || []).find(function (m) {
          return m.homeTeam.id === homeTeam.id && m.awayTeam.id === awayTeam.id;
        });
        if (!fx) throw new Error('fixture not found');
        return getJson('/api/football/data/matches/' + fx.id + '/head2head');
      }).then(function (h) {
        h2hBox.replaceChildren();
        var past = (h.matches || []).filter(function (m) { return m.status === 'FINISHED'; })
          .sort(function (a, b) { return b.utcDate.localeCompare(a.utcDate); }).slice(0, 5);
        var agg = h.aggregates;
        if (past.length) {
          var t = el('table', 'table table-sm mb-0 stats-table'), tb = el('tbody');
          past.forEach(function (m) {
            var tr = el('tr');
            tr.appendChild(el('td', 'text-muted text-nowrap', new Date(m.utcDate).toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })));
            tr.appendChild(el('td', 'text-end', m.homeTeam.shortName || m.homeTeam.name));
            tr.appendChild(el('td', 'text-center fw-semibold text-nowrap', m.score.fullTime.home + ' – ' + m.score.fullTime.away));
            tr.appendChild(el('td', null, m.awayTeam.shortName || m.awayTeam.name));
            tr.appendChild(el('td', 'text-muted small', m.competition ? m.competition.name : ''));
            tb.appendChild(tr);
          });
          t.appendChild(tb); h2hBox.appendChild(t);
        } else if (agg && agg.numberOfMatches) {
          // Free plan: only the totals, not the individual results.
          h2hBox.appendChild(el('p', 'mb-0', agg.numberOfMatches + ' previous meetings, ' + agg.totalGoals + ' goals (' +
            (agg.totalGoals / agg.numberOfMatches).toFixed(1) + ' per game).'));
          h2hBox.appendChild(el('p', 'text-muted small mb-0', 'Individual results need a paid football-data.org plan.'));
        } else {
          h2hBox.appendChild(el('p', 'text-muted mb-0', 'No previous meetings found.'));
        }
      }).catch(function (e) {
        h2hBox.replaceChildren(el('p', 'text-muted mb-0', e.status === 404
          ? 'Not saved yet. Click Refresh stats on the Football page.' : 'No head-to-head data found.'));
      });
    }).catch(function (e) {
      ['form', 'h2h', 'keyPlayers', 'valueCheck'].forEach(function (id) {
        document.getElementById(id).replaceChildren(el('span', 'text-muted', '–'));
      });
      box.replaceChildren(el('p', 'text-muted mb-0', e.status === 404 || e.status === 429
        ? 'Stats not saved yet. Click "Refresh stats" on the Football page.'
        : 'Couldn\'t load stats: ' + e.message));
    });
  }

  /**
   * Home team's recent home games and away team's recent away games (falls back to any venue if too few).
   * Resolves to { homeAll, awayAll, homeVenue, awayVenue, anyVenue } from the saved results.
   */
  function loadViews(homeTeam, awayTeam) {
    return Promise.all([
      getJson('/api/football/data/teams/' + homeTeam.id + '/matches'),
      getJson('/api/football/data/teams/' + awayTeam.id + '/matches')
    ]).then(function (lists) {
      var homeAll = finished(lists[0], homeTeam.id), awayAll = finished(lists[1], awayTeam.id);
      var homeVenue = homeAll.filter(function (v) { return v.home; }).slice(0, 10);
      var awayVenue = awayAll.filter(function (v) { return !v.home; }).slice(0, 10);
      var anyVenue = homeVenue.length < 5 || awayVenue.length < 5;
      if (anyVenue) { homeVenue = homeAll.slice(0, 10); awayVenue = awayAll.slice(0, 10); }
      return { homeAll: homeAll, awayAll: awayAll, homeVenue: homeVenue, awayVenue: awayVenue, anyVenue: anyVenue };
    });
  }

  return { render: render, loadViews: loadViews };
})();
