// Match stats card: form, league positions, goals profile and head to head (GOAL API, saved by Refresh Stats and
// read through our /api/football/data endpoints). Needs football-data.js (window.Football).
window.MatchStats = (function () {
  var F = window.Football, el = F.el;

  // Our tournament names -> competition codes (as in Competitions.java).
  var COMP_CODES = { epl: 'PL', england_league1: 'EL1', uefa_champs_league: 'CL', uefa_europa_league: 'EL',
                     uefa_europa_conference_league: 'ECL', uefa_nations_league: 'UNL', fifa_world_cup: 'WC',
                     uefa_european_championship: 'EC' };
  var FORM_GAMES = 8;   // tiles in the Form row

  var getJson = F.getJson;

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

  function formTiles(views, right) {
    // up to 8 tiles as two lines of 4 (the away team's against the right edge)
    var wrap = el('div', 'd-flex flex-wrap gap-1 form-tiles' + (right ? ' justify-content-end ms-auto' : ''));
    views.slice(0, FORM_GAMES).reverse().forEach(function (v) {   // oldest -> newest, newest on the right
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

  /**
   * One row of a team comparison, laid out like Live Match Stats and the Checklist: home value on the left, what it
   * is in the middle (grey), away value on the right.
   */
  function row(tbody, label, home, away, hint) {
    var tr = el('tr');
    var cell = function (v, cls) {
      var td = el('td', cls);
      if (v instanceof Node) td.appendChild(v); else td.textContent = v;
      return td;
    };
    tr.appendChild(cell(home, 'text-start text-nowrap'));
    var th = el('td', 'text-center text-muted text-nowrap', label);
    if (hint) { th.setAttribute('data-bs-toggle', 'tooltip'); th.setAttribute('data-bs-title', hint); th.style.cursor = 'help'; }
    tr.appendChild(th);
    tr.appendChild(cell(away, 'text-end text-nowrap'));
    tbody.appendChild(tr);
  }

  /** A team comparison table: home team name on the left, away on the right (headers ['', home, away]). */
  function table(headers) {
    var t = el('table', 'table table-sm align-middle mb-0 stats-table');
    var tr = el('tr');
    tr.appendChild(el('th', 'text-start text-nowrap', headers[1]));
    tr.appendChild(el('th', 'text-center', ''));
    tr.appendChild(el('th', 'text-end text-nowrap', headers[2]));
    var thead = el('thead'); thead.appendChild(tr); t.appendChild(thead);
    var tb = el('tbody'); t.appendChild(tb);
    return { table: t, body: tb };
  }

  /**
   * A team's top 5 scorers: goals and assists added up across the given competitions (this competition and
   * the team's league). One saved scorer table per competition, no API calls.
   */
  function keyPlayers(box, team, comps, side) {
    box.appendChild(el('div', 'section-label', team.shortName || team.name));
    var list = el('div', 'text-muted', 'Loading…'); box.appendChild(list);
    // The team's squad (saved by Refresh Stats) fills in positions the scorer tables leave out, and gives the
    // odds a team and position for players who haven't scored yet
    var squad = getJson('/api/football/data/teams/' + team.id + '/squad').then(function (d) {
      return (d.squad || []).map(function (p) {
        // games / goals: his season so far (GOAL API's squad list; null in squads saved before these were kept)
        return { name: p.name, position: F.positionName(p.position), games: p.games, goals: p.goals, assists: p.assists,
                 stats: p.stats };   // stats: his season stats, for the player pop-up
      });
    }).catch(function () { return []; });
    // The team's games this season (league tables): GOAL API's scorer lists have no games played, so a scorer's
    // goals per game uses his team's games (a little low for players who've missed games, which the value check's
    // blend with his position's typical rate evens out)
    var played = Promise.all(comps.map(function (c) {
      return getJson('/api/football/data/competitions/' + c.code + '/standings').then(function (st) {
        var row = position(st, team.id);
        return row && row.played ? row.played : 0;
      }).catch(function () { return 0; });
    })).then(function (n) { return n.reduce(function (a, b) { return a + b; }, 0); });
    Promise.all(comps.map(function (c) {
      return getJson('/api/football/data/competitions/' + c.code + '/scorers').then(function (d) {
        return (d.scorers || []).filter(function (s) { return s.team && s.team.id === team.id; });
      }).catch(function () { return []; });
    }).concat([squad, played])).then(function (lists) {
      var teamGames = lists.pop();
      var squadList = lists.pop();
      var squadPosition = function (name) {
        var p = squadList.find(function (x) { return F.samePlayer(x.name, name); });
        return p ? p.position : null;
      };
      // One row per player, goals and assists added up across competitions
      var players = {};
      [].concat.apply([], lists).forEach(function (s) {
        var p = players[s.player.id] = players[s.player.id] || { name: s.player.name, position: s.player.position, goals: 0, assists: 0, games: 0 };
        p.goals += s.goals || 0;
        p.assists += s.assists || 0;
        p.games += s.playedMatches || 0;
      });
      // GOAL API's scorer lists only have the league's top 20: the squad list has every player's goals and games
      // so far, so anyone else who's played gets a goals per game (and a goalscorer verdict) too
      squadList.forEach(function (sp) {
        if (!sp.games) return;
        var have = Object.keys(players).find(function (k) { return F.samePlayer(players[k].name, sp.name); });
        if (have) {
          if (!players[have].games) players[have].games = sp.games;   // his own games, rather than the team's
          players[have].stats = sp.stats;
        } else {
          players['squad:' + sp.name] = { name: sp.name, position: sp.position, goals: sp.goals || 0, assists: sp.assists || 0,
                                          games: sp.games, stats: sp.stats };
        }
      });
      Object.keys(players).forEach(function (k) {   // no position in the scorer tables: the squad's
        if (!players[k].position) players[k].position = squadPosition(players[k].name);
        if (!players[k].games) players[k].games = teamGames;   // still no games played: the team's
      });
      var everyone = Object.keys(players).map(function (k) { return players[k]; })
        .sort(function (a, b) { return b.goals - a.goals || b.assists - a.assists; });
      var top = everyone.filter(function (p) { return p.goals > 0; }).slice(0, 5);   // top 5 scorers per team on the card
      list.replaceChildren();
      list.className = '';
      // Share these players with the odds card, so its goalscorer markets show the same players in the same order
      window.TopScorers = window.TopScorers || {};
      // scorers: every player of this team in the scorer tables, for the Value check's goalscorer markets
      window.TopScorers[side] = { team: team.shortName || team.name, players: top.map(function (p) { return p.name; }),
                                  scorers: everyone.map(function (p) {
                                    // shots, penalties scored and the games they're over (his season stats), for goalRate
                                    var st = p.stats || {}, n = function (k) { var v = parseInt(st[k], 10); return isNaN(v) ? null : v; };
                                    // fewer shots than goals: those aren't his club season's (e.g. his England record)
                                    var club = n('shotsTotal') === null || n('shotsTotal') >= (p.goals || 0);
                                    return { name: p.name, goals: p.goals, assists: p.assists, games: p.games, position: F.positionName(p.position),
                                             shots: club ? n('shotsTotal') : null, shotGames: club ? n('matchPlayed') : null, pens: club ? n('penScored') : null };
                                  }),
                                  squad: squadList };
      if (window.ValueCheck) window.ValueCheck.rerender();
      if (typeof window.onTopScorers === 'function') window.onTopScorers();
      if (!top.length) { list.appendChild(el('div', 'text-muted', 'No players in the scorer tables yet.')); return; }
      var t = el('table', 'table table-sm mb-0 stats-table');
      var head = el('tr', 'text-muted');
      head.appendChild(el('th', 'fw-normal', ''));
      head.appendChild(el('th', 'fw-normal text-end', 'Goals'));
      head.appendChild(el('th', 'fw-normal text-end', 'Assists'));
      var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
      var tb = el('tbody');
      top.forEach(function (p) {
        var tr = el('tr');
        var who = el('td', null, p.name);
        if (p.position) {   // e.g. "Forward", once Refresh Stats has saved it
          who.append(' ');
          who.appendChild(el('span', 'text-muted small', F.positionName(p.position)));
        }
        tr.appendChild(who);
        tr.appendChild(el('td', 'text-end', String(p.goals)));
        tr.appendChild(el('td', 'text-end', String(p.assists)));
        tb.appendChild(tr);
      });
      t.appendChild(tb); list.appendChild(t);
    });
  }

  var NO_DATA = 'No data available for this game.';   // a team with no saved results
  var NOT_SAVED = 'Refresh Stats';                     // nothing saved yet: Refresh Stats on the Football page

  function render(box, ev, homeTeam, awayTeam) {
    box.replaceChildren();
    // a team without an id isn't in the saved teams: there are no results for it
    if (!homeTeam || !awayTeam || homeTeam.id == null || awayTeam.id == null) {
      ['form', 'h2h', 'keyPlayers', 'valueCheck'].forEach(function (id) {
        document.getElementById(id).replaceChildren(el('span', 'text-muted', NO_DATA));
      });
      box.appendChild(el('span', 'text-muted', NO_DATA));
      return;
    }
    box.appendChild(el('span', 'text-muted', 'Loading…'));

    Promise.all([
      getJson('/api/football/data/teams/' + homeTeam.id + '/matches'),
      getJson('/api/football/data/teams/' + awayTeam.id + '/matches'),
      profileOf(homeTeam), profileOf(awayTeam)
    ]).then(function (lists) {
      var homeAll = finished(lists[0], homeTeam.id), awayAll = finished(lists[1], awayTeam.id);
      var homeVenue = homeAll.filter(function (v) { return v.home; }).slice(0, 10);
      var awayVenue = awayAll.filter(function (v) { return !v.home; }).slice(0, 10);
      var profiles = [lists[2], lists[3]];
      // Too few home/away games? Fall back to all venues.
      var venueNote = '';
      if (homeVenue.length < 5 || awayVenue.length < 5) {
        homeVenue = homeAll.slice(0, 10); awayVenue = awayAll.slice(0, 10);
        homeVenue.anyVenue = true;   // the model adds home advantage back
        venueNote = 'last ' + Math.min(homeVenue.length, awayVenue.length) + ' games, any venue';
      } else {
        venueNote = 'last ' + homeVenue.length + ' home games for ' + (homeTeam.shortName || homeTeam.name) +
                    ', last ' + awayVenue.length + ' away games for ' + (awayTeam.shortName || awayTeam.name);
      }
      homeVenue.profile = profiles[0]; awayVenue.profile = profiles[1];   // season shots, keepers, injuries
      var hp = profile(homeVenue), ap = profile(awayVenue);
      if (window.ValueCheck) {
        ValueCheck.render(document.getElementById('valueCheck'), ev, homeVenue, awayVenue);
      }

      box.replaceChildren();
      var names = ['', homeTeam.shortName || homeTeam.name, awayTeam.shortName || awayTeam.name];

      // Form and positions
      var t1 = table(names);
      row(t1.body, 'Form', formTiles(homeAll), formTiles(awayAll, true), 'Last ' + FORM_GAMES + ' games in all competitions, newest on the right. Hover a tile for the score.');
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
      keyPlayers(kpHome, homeTeam, comps(hl), 'home');
      keyPlayers(kpAway, awayTeam, comps(al), 'away');

      // Head to head: this game's previous meetings (the server linked the odds to the game: ev.game_id)
      (ev.game_id != null ? getJson('/api/football/data/matches/' + ev.game_id + '/head2head')
                          : Promise.reject(new Error('game not matched'))).then(function (h) {
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
            tr.appendChild(el('td', 'text-center fw-semibold text-nowrap', m.score.fullTime.home + '–' + m.score.fullTime.away));
            tr.appendChild(el('td', null, m.awayTeam.shortName || m.awayTeam.name));
            tr.appendChild(el('td', 'text-muted small', m.competition ? m.competition.name : ''));
            tb.appendChild(tr);
          });
          t.appendChild(tb); h2hBox.appendChild(t);
        } else if (agg && agg.numberOfMatches) {
          // only the totals saved
          h2hBox.appendChild(el('p', 'mb-0', agg.numberOfMatches + ' previous meetings, ' + agg.totalGoals + ' goals (' +
            (agg.totalGoals / agg.numberOfMatches).toFixed(1) + ' per game).'));
        } else {
          h2hBox.appendChild(el('p', 'text-muted mb-0', 'No previous meetings found.'));
        }
      }).catch(function (e) {
        h2hBox.replaceChildren(el('p', 'text-muted mb-0', e.status === 404
          ? NOT_SAVED : 'No head-to-head data found.'));
      });
    }).catch(function (e) {
      var notSaved = e.status === 404 || e.status === 429;
      ['form', 'h2h', 'keyPlayers', 'valueCheck'].forEach(function (id) {
        document.getElementById(id).replaceChildren(el('span', 'text-muted', notSaved ? NOT_SAVED : '–'));
      });
      box.replaceChildren(el('span', 'text-muted', notSaved ? NOT_SAVED : 'Couldn\'t load stats: ' + e.message));
      // the page's other cards that wait for these stats (Top Scorers, Best Bets) stop waiting
      if (typeof window.onNoStats === 'function') window.onNoStats(notSaved ? NOT_SAVED : '–');
    });
  }

  /**
   * Home team's recent home games and away team's recent away games (falls back to any venue if too few).
   * Resolves to { homeAll, awayAll, homeVenue, awayVenue, anyVenue } from the saved results.
   */
  function loadViews(homeTeam, awayTeam) {
    return Promise.all([
      getJson('/api/football/data/teams/' + homeTeam.id + '/matches'),
      getJson('/api/football/data/teams/' + awayTeam.id + '/matches'),
      profileOf(homeTeam), profileOf(awayTeam)
    ]).then(function (lists) {
      var homeAll = finished(lists[0], homeTeam.id), awayAll = finished(lists[1], awayTeam.id);
      var homeVenue = homeAll.filter(function (v) { return v.home; }).slice(0, 10);
      var awayVenue = awayAll.filter(function (v) { return !v.home; }).slice(0, 10);
      var anyVenue = homeVenue.length < 5 || awayVenue.length < 5;
      if (anyVenue) { homeVenue = homeAll.slice(0, 10); awayVenue = awayAll.slice(0, 10); }
      homeVenue.profile = lists[2]; awayVenue.profile = lists[3];
      homeVenue.anyVenue = anyVenue;   // the model adds home advantage back
      return { homeAll: homeAll, awayAll: awayAll, homeVenue: homeVenue, awayVenue: awayVenue, anyVenue: anyVenue };
    });
  }

  /**
   * A team's season profile for the goals model (ValueCheck.teamProfile): shots per game, keepers' shots faced,
   * injured players, from its saved squad. Null without one (the model then uses recent goals only).
   */
  function profileOf(team) {
    if (!window.ValueCheck || !ValueCheck.teamProfile) return Promise.resolve(null);
    return getJson('/api/football/data/teams/' + team.id + '/squad').then(function (d) {
      return ValueCheck.teamProfile((d.squad || []).map(function (p) {
        return { name: p.name, position: F.positionName(p.position), stats: p.stats };
      }));
    }).catch(function () { return null; });
  }

  return { render: render, loadViews: loadViews };
})();
