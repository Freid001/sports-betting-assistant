// The Football page (templates/football.html): the match list by day, filters, Refresh Odds / Refresh Stats,
// Live Stats. Needs football-data.js, live.js and favourites.js loaded first.
(function () {
  var F = window.Football;
  var $ = function (id) { return document.getElementById(id); };
  var matches = [];
  var lastOddsAt = null; // most recent odds refresh, for the Refresh Odds pop-up
  var lastStats = null;  // latest Refresh Stats status
  var teamsByComp = {};
  var favs = [];           // favourite teams (saved on the server)
  var liveState = null;    // Live Stats (GOAL API): ticked games, scores and minutes as of the last refresh

  var el = F.el, ago = F.ago, title = F.title;   // shared helpers from football-data.js
  try { localStorage.removeItem('football-shortlist'); } catch (e) { /* old star shortlist, replaced by Favourites */ }

  function load() {
    return F.loadAll().then(function (data) {
      var ts = data.tournaments;
      var cached = ts.filter(function (t) { return t.cached; });
      matches = data.matches;
      lastOddsAt = cached.reduce(function (last, t) { return !last || t.fetchedAt > last ? t.fetchedAt : last; }, null);
      var sel = $('filterComp'), keep = sel.value;
      sel.innerHTML = '<option value="">All competitions</option>';
      ts.forEach(function (t) { var o = el('option', null, title(t.name)); o.value = title(t.name); sel.appendChild(o); });
      sel.value = keep;
      render();
      // Badges (one saved GOAL API list per competition), then redraw with logos.
      Promise.all(cached.map(function (t) {
        return F.loadTeams(t.name).then(function (teams) { teamsByComp[t.name] = teams; });
      })).then(render);
    }).catch(function (e) { notify('Couldn\'t load matches: ' + e.message, 'danger'); });
  }

  /** The odds of the bookmaker picked in Refresh Odds (null if none saved for this match). */
  function skybet(ev) { return F.pickBook(ev); }
  function market(bk, key) { return bk && bk.markets.find(function (m) { return m.key === key; }); }

  function render() {
    var list = $('list');
    list.innerHTML = '';
    var comp = $('filterComp').value, onlyFavs = $('filterFav').checked;
    var phases = { pre: $('filterPre').checked, live: $('filterLive').checked, post: $('filterPost').checked };
    var shown = matches.filter(function (m) {
      return (!comp || m.comp === comp) && (!onlyFavs || isFav(m)) && phases[phaseOf(m)];
    });
    if (!shown.length) {
      list.appendChild(el('div', 'alert alert-light border', matches.length ? (onlyFavs && !favs.length ? 'No favourites yet: click Favourites to pick your teams.' : 'No matches match these filters.') : 'No matches cached yet. Click Refresh.'));
      return;
    }
    var day = null, tbody = null;
    shown.forEach(function (m) {
      var start = new Date(m.commence_time);
      var d = start.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' });
      if (d !== day) {
        day = d;
        // One card per day: dark header with the date, matches inside (same look as the match-page widgets)
        var card = el('div', 'card shadow-sm mb-3');
        card.appendChild(el('div', 'card-header py-2 fw-semibold', d));
        var wrap = el('div', 'table-responsive');
        // Same column widths in every day's card, so the columns line up from one day to the next
        var table = el('table', 'table table-sm table-hover align-middle mb-0 small match-list');
        var cols = el('colgroup');
        ['1.5rem', '3.5rem', '7rem', '10rem', 'auto', '5.5rem', '5.5rem', '5.5rem'].forEach(function (w) {
          var c = el('col'); if (w !== 'auto') c.style.width = w; cols.appendChild(c);
        });
        table.appendChild(cols);
        var head = el('tr', 'text-muted');
        ['', 'Time', 'Status', 'Competition', 'Match', 'Home', 'Draw', 'Away'].forEach(function (h, i) {
          head.appendChild(el('th', i >= 5 ? 'text-center' : null, h));
        });
        var thead = el('thead'); thead.appendChild(head); table.appendChild(thead);
        tbody = el('tbody'); table.appendChild(tbody);
        wrap.appendChild(table); card.appendChild(wrap); list.appendChild(card);
      }
      var bk = skybet(m), h2h = market(bk, 'h2h'), tot = market(bk, 'totals');
      function price(name) { var o = h2h && h2h.outcomes.find(function (x) { return x.name === name; }); return o ? o.price : null; }
      var ph = price(m.home_team), pd = price('Draw'), pa = price(m.away_team);
      var tr = el('tr');
      // ★ when a favourite team is playing (set in the Favourites pop-up, not here)
      var fav = isFav(m);
      var td0 = el('td', fav ? 'text-dark' : 'text-muted', fav ? '★' : '☆');
      td0.title = fav ? 'Favourite team playing' : 'No favourite team playing (set them in Favourites)';
      tr.appendChild(td0);
      tr.appendChild(el('td', 'text-nowrap', start.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })));
      // Status: empty until kick-off, then LIVE 10' 1–0 / HT 1–0 / FT 2–1 (score and minute as of GOAL API's last
      // refresh), or, without GOAL API's data for it, the clock's 10' / HT / FT
      var statusCell = el('td', 'text-nowrap');
      var lb = Live.badge(Live.findFor(liveState, m));
      var clock = F.clockStatus(m.commence_time);
      if (lb) { lb.classList.remove('ms-1'); statusCell.appendChild(lb); }
      else if (clock) statusCell.appendChild(F.statusBadge(clock));   // same colours as live ones
      tr.appendChild(statusCell);
      // the competition in its own column (cut short with … if it's too long; the full name on hover)
      var compCell = el('td', 'text-muted text-truncate', m.comp || '');
      compCell.title = m.comp || '';
      tr.appendChild(compCell);
      var teams = teamsByComp[m.compKey] || [];
      var matchCell = el('td', 'fw-semibold');
      var line = el('span', 'd-inline-flex align-items-center gap-1');
      line.appendChild(F.logo(teams, m.home_team, 20, m.home_team_id));
      line.appendChild(el('span', null, m.home_team));
      line.appendChild(el('span', 'text-muted fw-normal mx-1', 'v'));
      line.appendChild(F.logo(teams, m.away_team, 20, m.away_team_id));
      line.appendChild(el('span', null, m.away_team));
      matchCell.appendChild(line);
      tr.appendChild(matchCell);
      var chances = (ph && pd && pa) ? F.fairChances([ph, pd, pa]).chances : [null, null, null];
      [ph, pd, pa].forEach(function (p, i) {
        var td = el('td', 'text-center'); td.appendChild(F.oddsBadge(p, chances[i])); tr.appendChild(td);
      });
      // Value check: best-value outcome + confidence, worked out from saved results (same as the match page)
      // Click anywhere on the row to open the match page
      var href = '/football/match/' + encodeURIComponent(m.id);
      tr.style.cursor = 'pointer';
      tr.addEventListener('click', function (e) {
        if (e.target.closest('a,button')) return;
        location.href = href;
      });
      tbody.appendChild(tr);
    });
  }

  // Pre-Match / Live / Post-Match ticks are remembered in this browser
  var PHASE_BOXES = ['filterPre', 'filterLive', 'filterPost'];
  try {
    var savedPhases = JSON.parse(localStorage.getItem('listPhases') || 'null');
    if (savedPhases) PHASE_BOXES.forEach(function (id) { if (id in savedPhases) $(id).checked = !!savedPhases[id]; });
  } catch (e) { /* defaults: Pre-Match and Live */ }
  ['filterComp', 'filterFav'].concat(PHASE_BOXES).forEach(function (id) {
    $(id).addEventListener('change', function () {
      try {
        var keep = {};
        PHASE_BOXES.forEach(function (b) { keep[b] = $(b).checked; });
        localStorage.setItem('listPhases', JSON.stringify(keep));
      } catch (e) { /* not remembered */ }
      render();
    });
  });

  /** Spinner on a button while it works: busy(btn, true) / busy(btn, false). */
  function busy(btn, on) {
    var sp = btn.querySelector('.spinner-border');
    if (on && !sp) {
      sp = el('span', 'spinner-border spinner-border-sm me-1');
      sp.style.width = sp.style.height = '.7rem';
      sp.setAttribute('aria-hidden', 'true');
      btn.prepend(sp);
    } else if (!on && sp) {
      sp.remove();
    }
    btn.disabled = on;
    btn.setAttribute('aria-busy', on ? 'true' : 'false');
  }

  function showError(msg) { notify(msg, 'danger'); }

  /** Shows the confirmation pop-up; calls onYes only if Refresh is clicked. */
  /**
   * lead: one sentence; points: short facts shown as a list;
   * status: function returning a Promise of lines for the grey box at the top (when it was last refreshed, credits...).
   */
  function confirmRefresh(title, lead, points, onYes, status) {
    $('confirmTitle').textContent = title;
    var sb = $('confirmStatus');
    sb.replaceChildren(el('span', 'text-muted', 'Checking…'));
    status().then(function (info) { F.infoBox(sb, info); }).catch(function () { sb.replaceChildren(el('span', 'text-muted', 'Couldn\'t check the current status.')); });
    var body = $('confirmBody');
    body.replaceChildren();
    if (lead) body.appendChild(el('div', 'section-label mb-1', lead));   // optional heading
    if (points.length) {
      var ul = el('ul', 'mb-0 text-muted small ps-3');
      points.forEach(function (p) { ul.appendChild(el('li', 'mb-1', p)); });
      body.appendChild(ul);
    }
    var modal = bootstrap.Modal.getOrCreateInstance($('confirmModal'));
    $('confirmOk').disabled = false;   // Refresh Odds disables it while nothing is ticked
    $('confirmOk').onclick = function () { modal.hide(); onYes(); };
    modal.show();
  }

  $('refresh').addEventListener('click', function () {
    var btn = this;
    var all = allMarketsOption();
    confirmRefresh('Refresh Odds',
      null, [],
      function () { if (all.picked()) refreshOdds(btn, all.picked()); },
      function () {
        return F.getJson('/api/odds/cache/quota').catch(function () { return {}; })
          .then(function (q) {
            var known = q.remaining !== undefined && q.remaining !== null;
            var total = known ? Math.round(q.remaining) + Math.round(q.used || 0) : 500;
            return {
              api: 'https://api.the-odds-api.com/v4',
              plan: known ? (total <= 500 ? 'Free' : 'Paid') : 'Free (assumed)',
              left: known ? Math.round(q.remaining) + ' of ' + total + ' this month' : 'unknown until the first refresh',
              limit: total + ' credits a month',
              reset: 'On the 1st of each month',
              last: lastOddsAt ? ago(lastOddsAt) : 'not yet'
            };
          });
      });
    $('confirmBody').append(all.box);   // what Settings says to fetch, and its cost
    $('confirmOk').disabled = !all.picked();   // until the settings have loaded
  });

  var EXTRA_MARKET_NAMES = F.EXTRA_MARKET_NAMES;   // shared with Settings

  /**
   * The Refresh Odds pop-up: what Settings says to fetch (bookmaker, match result, which extra markets for which
   * matches) and the most it can cost, with a link to change it in Settings. Extra markets cost up to 1 credit per
   * market per match (nothing where the bookmaker doesn't offer it); markets the bookmaker doesn't offer are skipped.
   */
  function allMarketsOption() {
    var soon = matches.filter(function (m) {
      var t = new Date(m.commence_time).getTime();
      return t > Date.now() && t < Date.now() + 30 * 86400000;
    });
    var scopes = { off: [], favs: soon.filter(isFav), all: soon };
    // Match result: one request per competition with fixtures, 2 credits each (h2h + totals)
    var comps = soon.reduce(function (set, m) { if (set.indexOf(m.compKey) < 0) set.push(m.compKey); return set; }, []).length;
    var resultCredits = 2 * comps, book = F.bookmaker(), state = null;
    var box = el('div', 'small');
    box.appendChild(el('span', 'text-muted', 'Checking…'));

    function line(label, text) {
      var d = el('div', 'mb-2');
      d.append(el('div', 'section-label mb-1', label), el('div', null, text));
      return d;
    }

    Promise.all([F.getJson('/api/odds/cache/extra-markets'), F.refreshOptions()]).then(function (r) {
      var offered = r[0] || {}, opts = r[1];
      if (!offered[book]) book = Object.keys(offered)[0] || book;
      var name = F.bookmakerName(book), has = offered[book] || [];
      var chosen = opts.scope === 'off' ? [] : (scopes[opts.scope] || []);
      var markets = opts.scope === 'off' ? [] : F.sortMarkets((opts.markets || []).filter(function (m) { return has.indexOf(m) >= 0; }));
      var n = chosen.length, extraCredits = markets.length * n;
      var sum = (opts.matchResult ? resultCredits : 0) + extraCredits;
      box.replaceChildren();
      box.appendChild(line('Bookmaker', name));
      box.appendChild(line('Match Result (all matches)', opts.matchResult
        ? 'Home / Draw / Away · up to ' + resultCredits + ' credits (' + comps + ' competition' + (comps === 1 ? '' : 's') + ')' : 'Off'));
      var scopeName = opts.scope === 'favs' ? '★ Favourites\' matches' : 'All matches';
      box.appendChild(line('Extra Markets (next 30 days)', opts.scope === 'off' ? 'Off'
        : !markets.length ? 'None ticked'
        : scopeName + ' (' + n + '): ' + markets.map(function (m) { return EXTRA_MARKET_NAMES[m] || m; }).join(', ') +
          ' · up to ' + extraCredits + ' credits'));
      box.appendChild(el('div', 'fw-semibold mb-2', sum ? 'Up to ' + sum + ' credits in total.' : 'Nothing to fetch.'));
      var link = el('a', 'text-muted', 'Change these in Settings');
      link.href = '/settings';
      box.appendChild(link);
      state = { bookmaker: book, matchResult: !!opts.matchResult, markets: n ? markets : [],
                ids: opts.scope === 'favs' ? chosen.map(function (m) { return m.id; }) : null };
      $('confirmOk').disabled = !sum;
    }).catch(function (e) { box.replaceChildren(el('span', 'text-muted', 'Couldn\'t load the Refresh Odds settings: ' + e.message)); });

    return { box: box, picked: function () { return state; } };
  }

  /** Refresh Odds: the match result for every match (if ticked), then the ticked extra markets for each match. */
  function refreshOdds(btn, extra) {
    var markets = extra.markets, book = extra.bookmaker, name = F.bookmakerName(book);
    busy(btn, true);
    F.setBookmaker(book);   // the pages now show this bookmaker's odds
    var first = !extra.matchResult ? Promise.resolve() : (function () {
      progress('odds', 'Refreshing ' + name + ' match result odds from The Odds API…');
      return F.sendJson('/api/football/refresh?bookmakers=' + encodeURIComponent(book)).then(function (res) {
        var credits = res.reduce(function (t, x) { return t + (x.credits || 0); }, 0);
        notify(name + ' match result odds refreshed. Used ' + credits + ' credit(s).', 'success');
      });
    })();
    first.then(function () {
      if (!markets.length) return load();
      progress('odds', 'Fetching ' + markets.length + ' extra market(s), one request per match. This can take a minute…');
      return F.sendJson('/api/football/refresh-markets?bookmaker=' + encodeURIComponent(book) +
        '&markets=' + encodeURIComponent(markets.join(',')) +
        (extra.ids ? '&ids=' + encodeURIComponent(extra.ids.join(',')) : '')).then(function (list) {
        notify(allMarketsSummary(list, name), list.some(function (x) { return x.stopped || x.errors; }) ? 'warning' : 'success');
        return load();
      });
    }).catch(function (e) { showError('Refresh Odds failed: ' + e.message); })
      .then(function () { progress('odds', null); busy(btn, false); });
  }

  /** e.g. "Extra markets fetched for 38 matches: 76 credits. Sky Bet returned: Both Teams to Score (38), ..." */
  function allMarketsSummary(list, name) {
    var matchCount = 0, credits = 0, markets = {}, notes = [];
    list.forEach(function (x) {
      matchCount += x.matches; credits += x.credits;
      Object.keys(x.markets || {}).forEach(function (k) { markets[k] = (markets[k] || 0) + x.markets[k]; });
      if (x.stopped) notes.push(x.stopped);
      if (x.errors) notes.push(x.errors + ' match(es) failed (' + x.lastError + ').');
    });
    var got = Object.keys(markets).map(function (k) { return (EXTRA_MARKET_NAMES[k] || k) + ' (' + markets[k] + ')'; });
    return 'Extra markets fetched for ' + matchCount + ' matches: ' + credits + ' credits. ' +
      (got.length ? name + ' returned: ' + got.join(', ') + '.' : name + ' returned none of the extra markets.') +
      (notes.length ? ' ' + notes.join(' ') : '');
  }

  document.getElementById('navStake').appendChild(F.stakeInput(render));   // in the top banner

  // ---- Refresh Stats: runs in the background on the server, we poll its progress ----
  var statsTimer = null;
  function showStats(st) {
    if (st.running) {
      var pct = st.total ? Math.round(100 * st.done / st.total) : 0;
      // Blue pop-up that stays (no ×, no time-out) until the refresh has finished
      progress('stats', 'Refreshing stats from GOAL API: ' + st.done + ' of ~' + st.total + ' calls (' + pct + '%). ' +
        (st.step ? st.step + '. ' : '') + 'You can keep using the app.');
      busy($('refreshStats'), true);
      if (!statsTimer) statsTimer = setInterval(pollStats, 2000);
      return;
    }
    if (statsTimer) {
      clearInterval(statsTimer); statsTimer = null;
      notify(st.errors && st.errors.length ? 'Stats refreshed, but ' + st.errors.length + ' call(s) failed.' : 'Stats refreshed.',
             st.errors && st.errors.length ? 'warning' : 'success');
    }
    progress('stats', null);
    busy($('refreshStats'), false);
  }
  function pollStats() {
    F.getJson('/api/football/stats/refresh').then(showStats).catch(function () {});
  }
  $('refreshStats').addEventListener('click', function () {
    confirmRefresh('Refresh Stats',
      'Results, Tables and Top Scorers',
      ['Results, form, league tables, top scorers and squads from GOAL API.',
       'About 6 calls per competition, a few more per league its clubs play in, and 1 per squad the first time.',
       'Runs in the background, so you can keep using the app.'],
      function () {
        var btn = $('refreshStats');
        busy(btn, true);
        F.sendJson('/api/football/stats/refresh').then(showStats)
          .catch(function (e) { busy(btn, false); showError('Refresh Stats failed: ' + e.message); });
      }, function () {
        return Promise.all([F.getJson('/api/football/stats/refresh').catch(function () { return {}; }),
                            F.getJson('/api/football/live').catch(function () { return {}; })])
          .then(function (r) {
            var st = r[0], usage = r[1].usage || {};
            return {
              api: 'https://api.goal-api.com/v1',
              plan: 'Free',
              left: usage.remaining ? usage.remaining + ' calls today' : 'Not known yet',
              limit: '1,000 calls a day',
              reset: 'Every day',
              last: st.running ? 'running now' : st.finishedAt ? F.ago(st.finishedAt) : 'not yet'
            };
          });
      });
  });
  // ---- Live Stats: pop-up listing the games followed live (from their match pages) and the APIs' usage; list badges
  //      come from the saved copy (no API calls) ----
  /** pre (not kicked off), live (kicked off, not finished) or post (finished), for the list's tick boxes. */
  function phaseOf(m) {
    if (isOver(m)) return 'post';
    return new Date(m.commence_time) <= new Date() ? 'live' : 'pre';
  }
  /** Finished: full time from Live Stats, or (without live data) about 2 hours after kick-off by the clock. */
  function isOver(m) {
    var fx = Live.findFor(liveState, m);
    if (fx && Live.isLive(fx)) return false;   // still being played (e.g. a long stoppage)
    if (fx && Live.finished(fx)) return true;
    return F.clockStatus(m.commence_time) === 'FT';
  }
  var liveTimer = null;
  function loadLive() {
    return Live.getState().then(function (s) {
      liveState = s;
      Live.setButton($('liveBtn'), s);   // red dot while games are followed live
      render();
      // While following games, re-read the saved scores every minute (the server refreshes them every few minutes)
      if (Live.monitoring(s) && !liveTimer) liveTimer = setInterval(loadLive, 60000);
      if (!Live.monitoring(s) && liveTimer) { clearInterval(liveTimer); liveTimer = null; }
    }).catch(function () { /* app not restarted yet, or no key: list works without it */ });
  }
  /** Finished games in the list (last 14 days), and whether their stats are saved: Post-Match in the Live Stats pop-up. */
  function finishedGames() {
    var now = Date.now();
    return matches.filter(function (m) {
      var t = new Date(m.commence_time).getTime();
      return t < now - 2 * 3600000 && t > now - 14 * 86400000;
    }).map(function (m) {
      var fx = Live.findFor(liveState, m);
      return { eventId: m.id, tournament: String(m.sport_key || '').replace(/^soccer_/, ''), kickoff: m.commence_time,
               home: m.home_team, away: m.away_team, saved: !!(fx && fx.saved === true), fx: fx };
    });
  }
  $('liveBtn').addEventListener('click', function () {
    Live.openModal($('liveModal'), { postMatch: finishedGames(), onDone: function () { loadLive(); } });
  });

  // ---- Favourites ----
  function isFav(m) { return Favourites.playing(favs, m); }
  function loadFavs() {
    return Favourites.load().then(function (f) { favs = f; render(); }).catch(function () { /* restart needed */ });
  }
  $('favBtn').addEventListener('click', function () {
    Favourites.openModal($('favModal'), function (f) { favs = f; render(); });
  });

  pollStats();
  load().then(loadLive).then(loadFavs);
})();
