// The match page (templates/football-match.html): the header, Prediction, Bets (Recommended / Markets), the
// live timeline and bet windows, Team Profile, Top Players and the player pop-up. Needs football-data.js,
// value-check.js, match-stats.js, live.js, in-play.js and goal-alert.js loaded first.
(function () {
  var F = window.Football, el = F.el;
  var $ = function (id) { return document.getElementById(id); };
  var matchId = $('page').getAttribute('data-match-id');
  var ev = null, views = null, teamIds = [null, null];   // the match, and the home/away results the goals model uses (from the stats script)
  var noStats = false;           // a team has no saved results: no chances to work out
  var liveView = null, lastBest = null, teamsCache = null;   // teamsCache: badges for the GOAL! banner
    // the point in the match picked in Live Match Stats
  var shortNames = [];   // the teams' short names, as Form and Goals Profile show them (e.g. "Tottenham")
  /** A team as the other cards name it ("Tottenham Hotspur FC" -> "Tottenham"); other teams' first word. */
  function shortTeam(name) {
    var n = F.norm(name);
    var hit = shortNames.find(function (s) { var k = F.norm(s); return k && (n === k || n.indexOf(k) === 0); });
    return hit || String(name).split(' ')[0];
  }
  var liveOdds = {};   // in-play prices saved for this match, by point in the match: {"HT": {fetchedAt, credits, markets}}
  /** The point in the match a timeline view stands for (10, 25, HT, 60, 80, FT), and the prices saved for it. */
  function pointOf(snap) { return snap.point || snap.period; }
  function oddsAt(snap) {
    var saved = liveOdds[pointOf(snap)] || null, now = liveOdds.LIVE;   // LIVE: fetched by hand (Test: Fetch Live Odds)
    if (snap.live && now && (!saved || now.fetchedAt > saved.fetchedAt)) return now;
    return saved;
  }
  // Live Stats found the game: its score under the competition, and FT / HT / 61' instead of "Started"
  // The Checklist, Live Match Stats and Player cards: hidden before kick-off and on Pre-Match (nothing to show)
  // Pre-Match shows the pre-match cards (form, goals profile, head to head, top scorers); a point in the match
  // (10', HT, LIVE...) shows that point's live cards (checklist, live stats, player shots) instead
  window.onLiveCards = function (show) {
    ['liveStats', 'players'].forEach(function (id) { $(id).closest('.card').classList.toggle('d-none', !show); });   // (the checklist isn't shown)
    ['form', 'stats', 'discipline', 'h2h', 'topScorers', 'topFoulers', 'topDefenders'].forEach(function (id) { $(id).closest('.card').classList.toggle('d-none', show); });
  };
  // Each poll's live score: a goal since the last poll gets the GOAL! banner and sound (goal-alert.js)
  window.onLiveFixture = function (fx) {
    var moved = window.GoalAlert && ev ? GoalAlert.checkStatus(fx.status) : null;
    if (!fx.status) return;
    showScore(fx.score, fx.status);
    if (moved && moved !== 'ft') GoalAlert.phaseBanner(moved, fx.score);   // KICK-OFF, HALF TIME, SECOND HALF
    cardCheck(fx.cards);   // a penalty or a card, then the goal it led to
    goalCheck(fx.score);
    if (moved === 'ft') GoalAlert.phaseBanner('ft', fx.score);   // after a last-minute goal
  };
  function cardCheck(cards) {
    if (!ev || !window.GoalAlert) return;
    GoalAlert.checkCards(cards).forEach(function (c) {
      var team = c.side === 'home' ? ev.home_team : ev.away_team;
      GoalAlert.card(c.kind, team, teamsCache ? F.logo(teamsCache, team, 48) : null);
    });
  }
  function goalCheck(score) {
    if (!ev || !window.GoalAlert) return;
    GoalAlert.check(score).forEach(function (side) {
      var team = side === 'home' ? ev.home_team : ev.away_team;
      GoalAlert.celebrate(team, teamsCache ? F.logo(teamsCache, team, 48) : null, $('score'));
    });
  }
  // A point picked on the Live Match Stats timeline: the header shows the score and time at that point (HT 1–0),
  // and Recommended Bets switch to it (Pre-Match: the pre-match bets, no score)
  window.onLiveView = function (snap) {
    liveView = snap.period === 'PRE' ? null : snap;
    // full time: the markets are closed, so no Bets card (Pre-Match and the bet windows still have it)
    $('bestBets').closest('.card').classList.toggle('d-none', snap.period === 'FT');
    // the header always shows the game as it is now (score, 46', HT, FT): picking a bet window doesn't change it
    if (snap.period === 'PRE' && ev && new Date(ev.commence_time) > new Date()) showScore('', countdown(ev.commence_time));   // not started
    draw();   // Markets and Recommended Bets switch to this point
  };
  function showScore(score, status) {
    if (!ev) return;
    $('score').textContent = score || '';
    $('score').classList.toggle('d-none', !score);
    // FT / HT / 61' as the coloured status label (same as the Football list); anything else as text
    var label = F.statusBadge(status);
    $('kickoff').replaceChildren(document.createTextNode(F.day(ev.commence_time) + ', ' + F.time(ev.commence_time) + ' · '),
      label || document.createTextNode(status));
  }

  // The stats script hands its results to ValueCheck.render; here they feed the Markets card instead.
  // (drawn just after, so the Goals Profile table it adds a row to is there)
  ValueCheck.render = function (box, e, homeVenue, awayVenue) { views = { home: homeVenue, away: awayVenue }; setTimeout(draw, 0); };
  window.onTopScorers = function () { draw(); topScorers(); discipline(); };   // goalscorer checks need the scorer tables
  // the stats couldn't load (e.g. Refresh Stats not run yet): Top Scorers says why, Recommended Bets says No Bet
  window.onNoStats = function (text) {
    noStats = true;
    $('topScorers').replaceChildren(el('span', 'text-muted', text));
    draw();
  };

  function countdown(iso) {
    var mins = Math.round((new Date(iso) - Date.now()) / 60000);
    if (mins < 0) return F.clockStatus(iso);   // from the clock (no live stats): 10', HT, FT
    if (mins < 60) return 'Kicks off in ' + mins + ' min';
    if (mins < 48 * 60) return 'Kicks off in ' + Math.round(mins / 60) + ' h';
    return 'Kicks off in ' + Math.round(mins / 1440) + ' days';
  }

  var tagBadge = F.chanceBadge;   // the chance label (Likely, Outsider...) with the % on hover (football-data.js)
  var confidenceName = null;   // the match's confidence (Low, Medium...), shown on hover over every verdict
  function verdictBadge(r) {
    return r ? ValueCheck.verdictBadge(r.verdict, confidenceName) : el('span', 'text-muted', '–');
  }

  /** An outcome's label, the same way the Value check names its rows. */
  function label(key, o) {
    if (key === 'halftime_fulltime') return o.name.replace('/', ' / ');
    if (key.indexOf('player_') === 0) return o.description || o.name;
    return [o.description, o.name, o.point].filter(function (x) { return x !== undefined && x !== null && x !== ''; }).join(' ');
  }
  function lineOf(o) { return (o.description || '') + '|' + o.point; }

  /**
   * One market as rows {label, o (priced outcome), r (Value check row or null), chance} in display order, and how
   * many go on the card (the rest in "Show all").
   */
  function marketRows(mk, checked) {
    var rows = mk.outcomes.map(function (o) {
      var name = label(mk.key, o);
      var r = (checked || []).find(function (x) {
        return mk.key.indexOf('player_') === 0 ? F.samePlayer(x.name, name) : x.name === name;
      }) || null;
      return { label: r && mk.key.indexOf('player_') === 0 ? r.name : name, o: o, r: r, chance: r ? r.ours : 1 / o.price };
    });
    // No Value check row: the bookmaker's chance with its margin taken out within each line or market
    var groups = {};
    rows.forEach(function (x) { if (!x.r) (groups[lineOf(x.o)] = groups[lineOf(x.o)] || []).push(x); });
    Object.keys(groups).forEach(function (g) {
      var list = groups[g];
      if (list.length < 2 || mk.key.indexOf('player_') === 0) return;
      var fair = F.fairChances(list.map(function (x) { return x.o.price; }));
      if (fair) list.forEach(function (x, i) { x.chance = fair.chances[i]; });
    });
    var show = rows.length;
    // Every market: best verdict first, then the more likely (our chance), then the bigger odds
    rows.sort(byVerdict);
    var ou = rows.length && rows.every(function (x) { return x.o.name === 'Over' || x.o.name === 'Under'; });
    if (mk.key.indexOf('player_') === 0) {
      // Players: the ones the Value check could check (they have a verdict, so they come first); else the top 5
      var checkedCount = rows.filter(function (x) { return x.r; }).length;
      show = checkedCount || Math.min(5, rows.length);
    } else if (rows.length > 6) {
      show = 5;
    }
    var lines = ou ? Object.keys(rows.reduce(function (set, x) { set[lineOf(x.o)] = 1; return set; }, {})).length : 0;
    return { rows: rows, show: show, more: ou && show < rows.length ? 'Show all ' + lines + ' lines' : null };
  }

  /** Order of the verdicts, best first; outcomes the Value check couldn't check come last. */
  var VERDICT_ORDER = ['Great Value', 'Good Value', 'Possible Value', 'Fair Price', 'Small Cost', 'Poor Price'];
  function verdictRank(x) {
    var i = x.r ? VERDICT_ORDER.indexOf(x.r.verdict.text) : -1;
    return i < 0 ? VERDICT_ORDER.length : i;
  }
  /** Best verdict first, then the more likely (our chance, e.g. 41% before 38%), then the bigger odds. */
  function byVerdict(a, b) { return verdictRank(a) - verdictRank(b) || (b.chance || 0) - (a.chance || 0) || (b.o.price || 0) - (a.o.price || 0); }

  /**
   * Outcome | Odds | Chance | Verdict. Every table uses the same fixed column widths, so the columns line up from
   * one market to the next (and in Recommended Bets).
   */
  var COLS = ['auto', '5rem', '5.5rem', '6.75rem'];   // Odds wide enough for returns too, e.g. "£2,050" at £50
  // Player markets add Team and Pos after the player's name (e.g. "Zian Flemming  Ipswich  FWD")
  var PLAYER_COLS = ['auto', '4.75rem', '2.75rem', '5rem', '5.5rem', '6.75rem'];
  var TYPED_COLS = ['auto', '4.25rem', '5rem', '5.5rem', '6.75rem'];   // a merged table: + Type (e.g. HT & FT)
  var STAKE_COLS = ['auto', '7.25rem', '5rem', '5.5rem', '6.75rem'];   // Recommended Bets: + Stake (left of Odds)
  function table(rows, title, players, withStake) {
    var typed = rows.some(function (x) { return x.type; });   // a merged table (Goalscorer, Half-Time)
    var t = el('table', 'table table-sm align-middle mb-0 stats-table market-table');
    if (!(typed && players)) {   // fixed widths so the columns line up from one market to the next
      t.style.tableLayout = 'fixed';
      var cg = el('colgroup');
      (players ? PLAYER_COLS : typed ? TYPED_COLS : withStake ? STAKE_COLS : COLS).forEach(function (w) { var c = el('col'); if (w !== 'auto') c.style.width = w; cg.appendChild(c); });
      t.appendChild(cg);
    }
    var head = el('tr');
    // The market's name is the first heading (e.g. "Match Result  Odds  Chance  Verdict")
    var heads = [title || ''].concat(typed ? ['Type'] : [], players ? ['Team', 'Pos'] : [], withStake ? ['Suggested Stake'] : [],
                                     ['Odds', 'Chance', 'Verdict']);
    var badgeFrom = heads.indexOf(withStake ? 'Suggested Stake' : 'Odds');
    heads.forEach(function (h, i) {
      var th = el('th', i === 0 ? null : 'text-nowrap' + (i >= badgeFrom ? ' text-center' : ''), h);
      if (h === 'Suggested Stake') th.title = 'The stake the Betting Framework suggests for it: not a bet placed';
      head.appendChild(th);
    });
    var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
    var tb = el('tbody');
    rows.forEach(function (x) {
      var tr = el('tr');
      if (players) {
        var info = scorerInfo(x.label) || {};
        tr.appendChild(playerCell(info.name || x.label));
        if (typed) tr.appendChild(el('td', 'text-nowrap', x.type));   // First or Anytime
        tr.appendChild(el('td', 'text-muted text-truncate', info.team ? shortTeam(info.team) : '–'));   // e.g. "Ipswich Town"
        var pos = el('td', 'text-muted', info.position ? POS_SHORT[info.position] || info.position : '–');
        if (info.position) pos.title = info.position;
        tr.appendChild(pos);
      } else {
        tr.appendChild(el('td', null, x.label));
        if (typed) tr.appendChild(el('td', 'text-nowrap', x.type));   // e.g. HT, FT or HT & FT
      }
      if (withStake) {   // the Betting Framework's stake for it (the same as the Bets page), the reason on hover
        var st = x.r ? ValueCheck.stakeFor({ verdict: x.r.verdict, confidence: confidenceName, chance: x.r.ours,
                                              player: String(x.key || '').indexOf('player_') === 0 }) : null;
        var sc = el('td', 'text-center fw-semibold', st ? '£' + st.amount : '–');
        if (st) { sc.title = st.why; sc.style.cursor = 'help'; }
        tr.appendChild(sc);
      }
      // Odds: coloured by the bookmaker's own chance (margin removed). On hover the price's raw chance and the same
      // without the bookmaker's margin, e.g. "10/3 · 23.1% (21.7% without margin)". Our chance is on the Chance label.
      if (!x.o.price) {
        // no odds for this point in the match: a dash, and no verdict
        var ref = el('span', 'text-muted', '–');
        if (x.hint) { ref.setAttribute('data-bs-toggle', 'tooltip'); ref.setAttribute('data-bs-title', x.hint); ref.style.cursor = 'help'; }
        var none = el('td', 'text-center'); none.appendChild(ref); tr.appendChild(none);
        var ch = el('td', 'text-center'); ch.appendChild(x.chance == null ? el('span', 'text-muted', '–') : tagBadge(x.chance)); tr.appendChild(ch);
        var why = el('span', 'text-muted', '–');
        why.setAttribute('data-bs-toggle', 'tooltip');
        why.setAttribute('data-bs-title', 'No verdict without odds for this point in the match.');
        why.style.cursor = 'help';
        var vd = el('td', 'text-center'); vd.appendChild(why); tr.appendChild(vd);
        tb.appendChild(tr);
        return;
      }
      var bookChance = x.r ? x.r.fair : x.chance;
      var oddsBadge = F.oddsBadge(x.o.price, bookChance);   // hover: "10/3 · 23.1% (21.7% no margin)", as everywhere
      var odds = el('td'); odds.appendChild(oddsBadge); tr.appendChild(odds);
      var chance = el('td'); chance.appendChild(tagBadge(x.chance)); tr.appendChild(chance);
      var verdict = el('td'); verdict.appendChild(verdictBadge(x.r)); tr.appendChild(verdict);
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    if (!(typed && players)) return t;
    var wrap = el('div', 'table-responsive');   // the merged table sizes itself; scrolls sideways if too narrow
    wrap.appendChild(t);
    return wrap;
  }

  var POS_SHORT = { Goalkeeper: 'GK', Defender: 'DEF', Midfielder: 'MID', Forward: 'FWD' };

  /** A player's squad entry (with his season stats) from either team's squad, and his team's name; null if none. */
  function playerFor(name) {
    var ts = window.TopScorers || {}, out = null;
    ['home', 'away'].forEach(function (side) {
      ((ts[side] || {}).squad || []).forEach(function (p) {
        if (!out && p.stats && F.samePlayer(p.name, name)) out = { p: p, team: ts[side].team };
      });
    });
    return out;
  }
  /** A table cell with the player's name: a link to his stats pop-up when we have his season stats. */
  function playerCell(name) {
    var td = el('td');
    var found = playerFor(name);
    if (!found) { td.textContent = name; return td; }
    var a = el('a', 'link-body-emphasis text-decoration-none', name);
    a.href = '#';
    a.style.borderBottom = '1px dotted currentColor';
    a.title = 'Season stats';
    a.addEventListener('click', function (e) { e.preventDefault(); showPlayer(found.p, found.team); });
    td.appendChild(a);
    return td;
  }

  /**
   * How a stat compares with a typical top-flight player in his position: Exceptional, Good, Average, Poor or Very
   * Poor. Rough benchmarks [poor below, average from, good from, exceptional from] per position (F, M, D, G; '*' for
   * any); low: less is better (fouls, cards, goals conceded). Null when there's too little to judge.
   */
  var GRADES = {
    goals:         { F: [0.12, 0.25, 0.4, 0.55], M: [0.03, 0.08, 0.16, 0.25], D: [0.01, 0.03, 0.07, 0.11] },
    assists:       { F: [0.04, 0.1, 0.18, 0.28], M: [0.04, 0.1, 0.18, 0.28], D: [0.02, 0.05, 0.1, 0.16] },
    shots:         { F: [1.2, 2, 2.8, 3.6], M: [0.5, 1, 1.7, 2.4], D: [0.2, 0.5, 0.9, 1.3] },
    conversion:    { '*': [0.06, 0.1, 0.15, 0.22] },
    keyPasses:     { F: [0.3, 0.7, 1.2, 1.8], M: [0.4, 0.9, 1.5, 2.2], D: [0.2, 0.4, 0.8, 1.2] },
    passAcc:       { F: [0.62, 0.7, 0.78, 0.85], M: [0.75, 0.82, 0.87, 0.91], D: [0.75, 0.82, 0.87, 0.91], G: [0.55, 0.65, 0.75, 0.85] },
    dribbles:      { '*': [0.35, 0.45, 0.55, 0.65] },
    tackles:       { F: [0.3, 0.6, 1, 1.5], M: [0.8, 1.3, 1.9, 2.6], D: [1, 1.5, 2.1, 2.8] },
    interceptions: { F: [0.1, 0.25, 0.5, 0.8], M: [0.4, 0.7, 1.1, 1.6], D: [0.6, 1, 1.5, 2.1] },
    clearances:    { M: [0.3, 0.7, 1.2, 2], D: [1, 2, 3.2, 4.5] },
    duels:         { '*': [0.4, 0.47, 0.54, 0.6] },
    rating:        { '*': [6.3, 6.6, 7.0, 7.4] },
    saveRate:      { G: [0.6, 0.67, 0.73, 0.79] },
    fouls:         { low: true, '*': [1.8, 1.3, 0.9, 0.5] },
    yellows:       { low: true, '*': [0.3, 0.2, 0.12, 0.06] },
    conceded:      { low: true, G: [1.8, 1.4, 1.1, 0.8] }
  };
  var GRADE_LABELS = [
    { text: 'Very Poor', cls: 'bg-danger-subtle text-danger-emphasis' },
    { text: 'Poor', cls: 'bg-warning-subtle text-warning-emphasis' },
    { text: 'Average', cls: 'text-bg-light border' },
    { text: 'Good', cls: 'bg-success-subtle text-success-emphasis' },
    { text: 'Exceptional', cls: 'text-bg-success' }
  ];
  // how much 'typical player' we blend in before trusting his own numbers: attempts for percentages; for per-game
  // counts, enough games to expect ~4 of the thing (rare things like assists need longer to say anything)
  var GRADE_PRIOR = { conversion: 30, passAcc: 60, dribbles: 15, duels: 30, saveRate: 20 };
  function statGrade(metric, v, position) {
    var g = GRADES[metric];
    if (!g || v === null || v === undefined) return null;
    var key = { Forward: 'F', Midfielder: 'M', Defender: 'D', Goalkeeper: 'G' }[position];
    var t = (key && g[key]) || g['*'];
    if (!t) return null;
    var pctMetric = /conversion|passAcc|dribbles|duels|saveRate/.test(metric);
    var raw = v, orig = v, note = '';
    if (Array.isArray(v)) {
      // small samples are noisy (0 assists in 9 games happens to plenty of decent forwards), so pull the figure
      // towards a typical player's until there's enough to go on; with lots of games it barely moves
      var made = v[0], tries = v[1], typical = (t[1] + t[2]) / 2;
      if (made === null || !tries) return null;
      var k = GRADE_PRIOR[metric] || Math.max(8, 4 / typical);
      raw = made / tries;
      v = (made + typical * k) / (tries + k);
    }
    if (isNaN(v)) return null;
    var levelOf = function (x) { var l = 0; for (var i = 0; i < 4; i++) if (g.low ? x <= t[i] : x >= t[i]) l = i + 1; return l; };
    var level = levelOf(v);
    if (Array.isArray(orig) && levelOf(raw) !== level)
      note = ' Only ' + orig[1] + (pctMetric ? ' attempts' : ' games') + ' so far, so this leans towards average until there\'s more to go on.';
    var fmt = function (x) { return metric === 'rating' ? x.toFixed(1) : pctMetric ? Math.round(x * 100) + '%' : String(x); };
    var why = 'Typical ' + (key && g[key] ? position.toLowerCase() : 'player') + ': average ' + (g.low ? fmt(t[1]) + ' or less' : 'from ' + fmt(t[1])) +
      ', good ' + (g.low ? fmt(t[2]) + ' or less' : 'from ' + fmt(t[2])) + ', exceptional ' + (g.low ? fmt(t[3]) + ' or less' : 'from ' + fmt(t[3])) + '.' + note;
    return Object.assign({ why: why }, GRADE_LABELS[level]);
  }

  /**
   * The player pop-up: his season so far from GOAL API's squad list, in sections (only what it filled in):
   * Appearances, Attacking, Passing, Defending, Discipline and, for keepers, Goalkeeping.
   */
  function showPlayer(p, team) {
    var st = p.stats || {};
    var n = function (k) { var v = parseFloat(st[k]); return isNaN(v) ? null : v; };
    var per = function (a, b) { return a !== null && b ? (a / b).toFixed(2) : null; };
    var pct = function (a, b) { return a !== null && b ? Math.round(100 * a / b) + '%' : null; };
    $('playerModalTitle').textContent = p.name;
    // his photo (GOAL API's image server only): bottom right of the stats, hidden if there's none or it doesn't load
    var url = /^https:\/\/media\.goal-api\.com\//.test(st.image || '') ? st.image : null;
    var sub = [shortTeam(team), p.position, st.number ? '#' + st.number : null, st.age ? 'age ' + st.age : null,
               /^(true|1|yes)$/i.test(st.isCaptain || '') ? 'Captain' : null,
               st.injured && !/^no$/i.test(st.injured) ? 'Injured' : null].filter(Boolean);
    $('playerModalSub').textContent = sub.join(' · ');
    var body = $('playerModalBody');
    body.replaceChildren();
    // per game / percentages, for the labels (3+ games needed)
    // what each label is judged on: [count, games] or [made, attempts], so small samples can be weighed
    var g = n('matchPlayed'), pg = function (k) { var v = n(k); return v !== null && g >= 3 ? [v, g] : null; };
    var ratio = function (a, b) { return a !== null && b ? [a, b] : null; };
    var pos = p.position;
    // [label, value shown, metric for the label, the number it's judged on]
    var sections = [
      ['Appearances', [['Games', n('matchPlayed')], ['Minutes', n('minutes')],
        ['Rating', st.rating || null, 'rating', parseFloat(st.rating)],
        ['Subbed off', n('substituteOut')], ['On the bench', n('substitutesOnBench')]]],
      ['Attacking', [['Goals', n('goals')], ['Goals per game', per(n('goals'), n('matchPlayed')), 'goals', pg('goals')],
        ['Assists', n('assists')], ['Assists per game', per(n('assists'), n('matchPlayed')), 'assists', pg('assists')],
        ['Shots', n('shotsTotal')], ['Shots per game', per(n('shotsTotal'), n('matchPlayed')), 'shots', pg('shotsTotal')],
        ['Shots that went in', n('shotsTotal') ? pct(n('goals') || 0, n('shotsTotal')) : null, 'conversion',
          n('shotsTotal') >= 5 ? ratio(n('goals') || 0, n('shotsTotal')) : null],
        ['Penalties scored', n('penScored')], ['Penalties missed', n('penMissed')], ['Penalties won', n('penWon')],
        ['Hit the woodwork', n('woodworks')]]],
      ['Passing', [['Passes', n('passes')], ['Accurate passes', n('passesAccuracy') !== null ? n('passesAccuracy') +
        (n('passes') ? ' (' + pct(n('passesAccuracy'), n('passes')) + ')' : '') : null, 'passAcc',
        n('passes') >= 50 ? ratio(n('passesAccuracy'), n('passes')) : null],
        ['Key passes', n('keyPasses'), 'keyPasses', pg('keyPasses')],
        ['Crosses', n('crossesTotal')], ['Dribbles won', n('dribbleSucc') !== null ? n('dribbleSucc') +
        (n('dribbleAttempts') ? ' of ' + n('dribbleAttempts') : '') : null, 'dribbles',
        n('dribbleAttempts') >= 5 ? ratio(n('dribbleSucc'), n('dribbleAttempts')) : null]]],
      ['Defending', [['Tackles', n('tackles'), 'tackles', pg('tackles')], ['Interceptions', n('interceptions'), 'interceptions', pg('interceptions')],
        ['Blocks', n('blocks')], ['Clearances', n('clearances'), 'clearances', pg('clearances')],
        ['Duels won', n('duelsWon') !== null ? n('duelsWon') +
        (n('duelsTotal') ? ' of ' + n('duelsTotal') + ' (' + pct(n('duelsWon'), n('duelsTotal')) + ')' : '') : null, 'duels',
        n('duelsTotal') >= 10 ? ratio(n('duelsWon'), n('duelsTotal')) : null],
        ['Lost the ball', n('dispossessed')]]],
      ['Discipline', [['Fouls', n('foulsCommitted'), 'fouls', pg('foulsCommitted')], ['Yellow cards', n('yellowCards'), 'yellows', pg('yellowCards')],
        ['Red cards', n('redCards')], ['Penalties given away', n('penCommitted')]]]
    ];
    if (p.position === 'Goalkeeper' || n('saves')) {
      var faced = (n('saves') || 0) + (n('goalsConceded') || 0);
      sections.push(['Goalkeeping', [['Saves', n('saves')], ['Save rate', faced ? pct(n('saves') || 0, faced) : null, 'saveRate',
        faced >= 10 ? ratio(n('saves') || 0, faced) : null], ['Saves inside the box', n('insideBoxSaves')],
        ['Goals conceded', n('goalsConceded'), 'conceded', pg('goalsConceded')]]]);
    }
    var any = false;
    var grid = el('div', 'row g-3');   // sections side by side (three columns), so it fits without scrolling
    body.appendChild(grid);
    sections.forEach(function (sec) {
      var rows = sec[1].filter(function (r) { return r[1] !== null && r[1] !== undefined && r[1] !== ''; });
      if (!rows.length) return;
      any = true;
      var col = el('div', 'col-md-6 col-lg-4');   // three columns on a wide screen
      grid.appendChild(col);
      col.appendChild(el('div', 'section-label', sec[0]));
      var t = el('table', 'table table-sm mb-2 stats-table'), tb = el('tbody');
      rows.forEach(function (r) {
        var tr = el('tr');
        tr.appendChild(el('td', 'text-muted', r[0]));
        tr.appendChild(el('td', 'text-end fw-semibold', String(r[1])));
        var grade = r[2] ? statGrade(r[2], r[3], pos) : null;   // Exceptional ... Very Poor, for his position
        var gc = el('td', 'text-end');
        if (grade) {
          var b = el('span', 'badge ' + grade.cls, grade.text);
          b.title = grade.why;
          gc.appendChild(b);
        }
        tr.appendChild(gc);
        tb.appendChild(tr);
      });
      t.appendChild(tb);
      col.appendChild(t);
    });
    if (url) {
      // centred in its column, in Bootstrap's thumbnail frame, with his name and number under it
      var spot = el('div', 'col-md-6 col-lg-4 ms-auto d-flex flex-column justify-content-center align-items-center py-2');
      var img = el('img', 'img-thumbnail shadow-sm');
      img.src = url; img.alt = p.name; img.width = 150; img.height = 150;
      img.style.objectFit = 'cover';
      img.referrerPolicy = 'no-referrer';
      img.onerror = function () { spot.remove(); };
      spot.appendChild(img);
      spot.appendChild(el('div', 'small text-muted mt-2 text-center', p.name + (st.number ? ' · #' + st.number : '')));
      grid.appendChild(spot);
    }
    var filed = String(st.filedUnder || '').toLowerCase(), club = String(team || '').toLowerCase();
    var elsewhere = filed && club && filed.indexOf(club) < 0 && club.indexOf(filed) < 0;   // e.g. Cole Palmer under England
    if (!any) body.appendChild(el('div', 'text-muted', elsewhere
      ? 'GOAL API lists him under ' + st.filedUnder + ', not ' + team + ', so it has no club season stats for him. His goals and assists are in Top Players.'
      : 'No stats for him yet this season.'));
    else body.appendChild(el('div', 'small text-muted mt-1', 'Labels compare him with a typical top-flight ' +
      (pos ? pos.toLowerCase() : 'player') + ' (per game, 3+ games). With only a few games a label leans towards Average. Hover a label for the range.'));
    if (elsewhere && any) {
      body.appendChild(el('div', 'text-muted mt-2', 'GOAL API lists him under ' + st.filedUnder + ': these are the stats it has for him there.'));
    }
    // From another pop-up (e.g. Show all): close it first, then come back to it when the player pop-up closes
    var open = document.querySelector('.modal.show:not(#playerModal)');
    var player = bootstrap.Modal.getOrCreateInstance($('playerModal'));
    if (!open) { player.show(); return; }
    open.addEventListener('hidden.bs.modal', function () {
      $('playerModal').addEventListener('hidden.bs.modal', function () {
        bootstrap.Modal.getOrCreateInstance(open).show();
      }, { once: true });
      player.show();
    }, { once: true });
    bootstrap.Modal.getOrCreateInstance(open).hide();
  }

  /**
   * A player's team and position: from the scorer tables (from the Top Scorers card), or else either team's squad
   * (saved by Refresh Stats), so players who haven't scored yet get them too. Null if he's in neither.
   */
  function scorerInfo(name) {
    var ts = window.TopScorers || {}, out = null;
    ['home', 'away'].forEach(function (side) {
      ((ts[side] || {}).scorers || []).forEach(function (p) {
        if (!out && F.samePlayer(p.name, name)) out = { name: p.name, team: ts[side].team, position: p.position };
      });
    });
    ['home', 'away'].forEach(function (side) {
      ((ts[side] || {}).squad || []).forEach(function (p) {
        if (!out && F.samePlayer(p.name, name)) out = { team: ts[side].team, position: p.position };
        else if (out && !out.position && F.samePlayer(p.name, name)) out.position = p.position;
      });
    });
    return out;
  }

  /**
   * Top Scorers, Top Foulers and Top Defenders: the top 10 players of both teams together, one table each like
   * Goalscorer (Player | Team | Pos | the numbers), names open the player pop-up. Scorers come from the scorer tables
   * (and squads); fouls and defending from the squads' season stats (Refresh Stats). Waits for both teams.
   */
  function topScorers() {
    var ts = window.TopScorers || {};
    if (!ts.home || !ts.away) return;   // wait for both teams
    // from the squads' season stats
    var squadRows = function (pick) {
      var out = [];
      ['home', 'away'].forEach(function (side) {
        ((ts[side] || {}).squad || []).forEach(function (p) {
          var st = p.stats || {}, num = function (k) { var v = parseInt(st[k], 10); return isNaN(v) ? 0 : v; };
          var cols = pick(num);
          if (cols && cols.some(function (v) { return parseFloat(v) > 0; })) out.push({ name: p.name, position: p.position, team: ts[side].team, cols: cols });
        });
      });
      return out;
    };
    var noStats = 'No player stats yet: run Refresh Stats on the Football page.';
    // Top Scorers, with their shooting: goals and assists from the scorer tables, shots from the squads (GOAL API
    // gives total shots, not on target). Conversion only ranks players with 5+ shots, so 1 from 1 isn't top.
    var scorers = [];
    var find = function (name, team) {
      for (var i = 0; i < scorers.length; i++) if (scorers[i].team === team && F.samePlayer(scorers[i].name, name)) return scorers[i];
      return null;
    };
    ['home', 'away'].forEach(function (side) {
      ((ts[side] || {}).scorers || []).forEach(function (p) {
        if (p.goals > 0) scorers.push({ name: p.name, position: p.position, team: ts[side].team, goals: p.goals, assists: p.assists || 0 });
      });
      ((ts[side] || {}).squad || []).forEach(function (p) {
        var st = p.stats || {}, num = function (k) { var v = parseInt(st[k], 10); return isNaN(v) ? 0 : v; };
        var shots = num('shotsTotal');
        if (!shots && !num('goals')) return;
        var x = find(p.name, ts[side].team);
        if (!x) { x = { name: p.name, position: p.position, team: ts[side].team, goals: num('goals'), assists: num('assists') }; scorers.push(x); }
        x.position = x.position || p.position;
        // fewer shots than goals: not his club season (e.g. his England record), so no shooting numbers
        if (shots >= x.goals) { x.shots = shots; x.games = num('matchPlayed'); }
      });
    });
    scorers.forEach(function (x) {
      var conv = x.shots ? Math.round(100 * x.goals / x.shots) : null;
      x.cols = [x.goals, x.assists, x.shots || '–', x.shots && x.games ? (x.shots / x.games).toFixed(1) : '–', conv !== null ? conv + '%' : '–'];
      x.sort = [x.goals, x.assists, x.shots || 0, x.shots && x.games ? x.shots / x.games : 0, x.shots >= 5 ? conv : -1];
    });
    leaderboard('topScorers', scorers, ['Goals', 'Assists', 'Shots', 'Per game', 'Conv.'], 'No players in the scorer tables yet.',
      // most goals; level on goals: the better finisher (conversion, 5+ shots), then more shots
      function (c, x) { return x.goals * 1e6 + (x.shots >= 5 ? Math.round(1000 * x.goals / x.shots) : 0) * 1000 + Math.min(x.shots || 0, 999); });
    leaderboard('topFoulers', squadRows(function (n) { return [n('foulsCommitted'), n('yellowCards'), n('redCards')]; }),
      ['Fouls', 'Yellow', 'Red'], noStats, function (c) { return c[0] * 1e4 + c[1] * 100 + c[2]; });   // most fouls, then cards
    leaderboard('topDefenders', squadRows(function (n) {
      return [n('tackles'), n('interceptions'), n('clearances'), n('blocks')];
    }), ['Tackles', 'Int', 'Clr', 'Blocks'], noStats, function (c) { return c[0] + c[1] + c[2] + c[3]; });
  }

  /**
   * One top-10 table: rows [{name, position, team, cols, sort?}], by score (default: the first column, then the next).
   * Click a number column's heading to rank by that column instead (highest first); click it again to go back.
   */
  function leaderboard(id, rows, heads, empty, score, sortCol) {
    var box = $(id);
    box.replaceChildren();
    var all = rows.slice();
    var val = function (x, c) { var v = x.sort ? x.sort[c] : parseFloat(x.cols[c]); return isNaN(v) || v === null ? -1 : v; };
    var by = score || function (c) { return c[0] * 1000 + (c[1] || 0); };
    rows = rows.slice().sort(sortCol != null
      ? function (a, b) { return val(b, sortCol) - val(a, sortCol) || by(b.cols, b) - by(a.cols, a); }
      : function (a, b) { return by(b.cols, b) - by(a.cols, a); }).slice(0, 10);
    if (!rows.length) { box.appendChild(el('span', 'text-muted', empty)); return; }
    var t = el('table', 'table table-sm align-middle mb-0 stats-table');
    var head = el('tr');
    var FULL = { Int: 'Interceptions', Clr: 'Clearances', Yellow: 'Yellow cards', Red: 'Red cards',
                 'Per game': 'Shots per game', 'Conv.': 'Conversion: goals per shot (5+ shots to rank)', Shots: 'Total shots' };
    ['Player', 'Team', 'Pos'].concat(heads).forEach(function (h, i) {
      var c = i - 3;
      var th = el('th', 'text-nowrap' + (c >= 0 ? ' text-center' : ''));
      if (c >= 0) {
        var a = el('a', 'link-body-emphasis text-decoration-none', h + (sortCol === c ? ' ▼' : ''));
        a.href = '#';
        a.title = (FULL[h] || h) + (sortCol === c ? ' · click to go back to the usual order' : ' · click to rank by this');
        a.addEventListener('click', function (e) {
          e.preventDefault();
          leaderboard(id, all, heads, empty, score, sortCol === c ? null : c);
        });
        th.appendChild(a);
      } else {
        th.textContent = h;
      }
      head.appendChild(th);
    });
    var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
    var tb = el('tbody');
    rows.forEach(function (x) {
      var tr = el('tr');
      tr.appendChild(playerCell(x.name));
      tr.appendChild(el('td', 'text-muted text-nowrap', shortTeam(x.team)));
      var pos = el('td', 'text-muted', x.position ? POS_SHORT[x.position] || x.position : '–');
      if (x.position) pos.title = x.position;
      tr.appendChild(pos);
      x.cols.forEach(function (v, c) { tr.appendChild(el('td', 'text-center' + (sortCol === c ? ' fw-semibold' : ''), String(v))); });
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    box.appendChild(t);
  }

  function draw() {
    if (!ev) return;
    var box = $('markets'), bk = F.pickBook(ev), name = F.bookmakerName();
    $('marketsTitle').textContent = 'Bets · ' + name + ' · Pre-Match';
    box.replaceChildren();
    if (!bk) {
      box.appendChild(el('p', 'text-muted mb-0', 'No ' + name + ' odds saved for this match yet. Refresh Odds with ' + name + ' picked.'));
      $('bestBets').replaceChildren(el('span', 'text-muted', 'No odds to check yet.'));
      showPredictionCard(views ? ValueCheck.analyse(ev, views.home, views.away, ValueCheck.DEFAULT_STAKE) : null);   // needs no odds
      return;
    }
    var a = views ? ValueCheck.analyse(ev, views.home, views.away, F.stake() || ValueCheck.DEFAULT_STAKE) : null;
    confidenceName = a ? a.level.name : null;
    var checked = {};
    if (a) {
      checked.h2h = a.rows;
      (a.markets || []).forEach(function (mk) { checked[mk.key] = mk.rows; });
    }
    var all = [];   // every checked outcome, for Recommended Bets
    // Related markets share one table with a Type column (one row per bet), where the first of them would be:
    // First / Anytime Goalscorer, and the results: full time (FT), first half (HT) and half-time/full-time (HT & FT)
    var MERGED = [
      { title: 'Goalscorer', types: { player_first_goal_scorer: 'First', player_goal_scorer_anytime: 'Anytime' } },
      { title: 'Result', types: { h2h: 'FT', h2h_3_way_h1: 'HT', halftime_fulltime: 'HT & FT' } }
    ];
    var shown = {};
    var inPlay = liveView && liveView.period;   // a point in the match: the card shows that point's odds instead
    bk.markets.forEach(function (mk) {
      var title = F.MARKET_NAMES[mk.key] || mk.key;
      var m = marketRows(mk, checked[mk.key]);
      m.rows.forEach(function (x) { if (x.r) all.push({ market: title, key: mk.key, x: x }); });
      if (inPlay) return;   // Recommended Bets still gets the pre-match outcomes (for Pre-Match)
      var merge = MERGED.find(function (g) { return g.types[mk.key]; });
      var parts = merge ? bk.markets.filter(function (x) { return merge.types[x.key]; }) : [];
      if (parts.length > 1) {
        if (shown[merge.title]) return;
        shown[merge.title] = true;
        var rows = [];
        parts.forEach(function (pm) {
          marketRows(pm, checked[pm.key]).rows.forEach(function (x) { rows.push(Object.assign({ type: merge.types[pm.key] }, x)); });
        });
        rows.sort(byVerdict);   // best verdict first, then most likely, whichever type
        // 5 rows on the card (players: best verdict first), the rest in the Show all pop-up
        m = { rows: rows, show: Math.min(5, rows.length) };
        title = merge.title;
      }
      var players = mk.key.indexOf('player_') === 0;
      var t = table(m.rows.slice(0, m.show), title, players);
      if (box.children.length) t.classList.add('mt-3');   // a gap after the market above
      box.appendChild(t);
      if (m.rows.length > m.show) {
        var more = el('a', 'small d-inline-block mt-1 link-secondary', m.more || 'Show all ' + m.rows.length);
        more.href = '#';
        more.addEventListener('click', function (e) {
          e.preventDefault();
          $('marketModalTitle').textContent = title + ' · ' + name;
          $('marketModalBody').replaceChildren(table(m.sortAll ? m.sortAll(m.rows) : m.rows, title, players));
          bootstrap.Modal.getOrCreateInstance($('marketModal')).show();
        });
        box.appendChild(more);
      }
    });
    var h2h = bk.markets.find(function (mk) { return mk.key === 'h2h'; });
    var margin = h2h ? F.fairChances(h2h.outcomes.map(function (o) { return o.price; })) : null;
    $('marketsNote').textContent = '';
    if (inPlay) inPlayMarkets(box, bk, liveView, name, a);
    bestBets(a, all, margin);
  }

  /**
   * Recommended Bets: value outcomes (Great / Good / Possible Value) across every market, best average return first.
   * None: "No Bet", as the framework says.
   */
  function bestBets(a, all, margin) {
    lastBest = [a, all, margin];
    var box = $('bestBets');
    box.replaceChildren();
    if (!a) {
      box.appendChild(el('span', 'text-muted', views ? 'Not enough results yet to estimate chances.'
        : noStats ? 'No Bet' : 'Loading…'));
      return;
    }
    var live = liveView && liveView.period ? liveView : null;   // a point in the match (FT says there's nothing left)
    if (live) return inPlayBets(a, live);
    // The same bets, in the same order, as this match on the Recommended Bets page (ValueCheck.recommend)
    var checked = all.map(function (b) {
      return { key: b.key, ret: b.x.r.ret, chance: b.x.r.ours, price: b.x.o.price, verdict: b.x.r.verdict, b: b };
    });
    var picks = ValueCheck.recommend(checked);
    if (!picks.length) {
      box.appendChild(el('div', 'text-muted', 'No Bet'));
    } else {
      var rows = picks.map(function (p) { return Object.assign({}, p.b.x, { label: p.b.x.label + ' · ' + p.b.market, key: p.key }); });
      box.appendChild(table(rows, 'Bet', false, true));
    }

    showPredictionCard(a);
    // the prediction log (before kick-off only): scored against the result later, for the accuracy report
    if (a && new Date(ev.commence_time) > new Date()) {
      var preRec = ValueCheck.record(ev, a, teamIds[0], teamIds[1]);
      if (preRec) preRec.predictionRows = plainRows(rowsOf(function () { prediction(a); }));   // the card, kept at kick-off
      ValueCheck.log(preRec);
    }
    if (a) logWindows(a);   // and the bet windows saved so far
    showPrediction(a);
  }

  function periodName(p) { return p === 'PRE' ? 'Pre-Match' : /^\d+$/.test(p) ? p + '\'' : p; }

  /**
   * In-play Recommended Bets for the point picked in Live Match Stats: our chances from the score, the time left and the
   * live stats, and the lowest price worth taking (no live odds yet: compare with the bookmaker's app).
   */
  /** Markets the bookmaker is asked for in-play (the rest close at kick-off). */
  var IN_PLAY_KEYS = ['h2h', 'draw_no_bet', 'alternate_totals', 'totals', 'btts'];
  /**
   * The Markets card at a point in the match: our in-play chance for each outcome (score so far + goals still to
   * come), with the prices saved for that point and their verdicts, or "–" for odds and verdict when none were saved.
   * FT: the markets are closed.
   */
  /**
   * In-play anytime goalscorer: his share of his team's goals (pre-match: his expected goals / the team's) times the
   * goals his team still has to come (InPlay.rates), so the chance he scores in the time left. Doesn't know who's on
   * the pitch (a player subbed off or on the bench still gets a chance). Null if he isn't in the scorer tables.
   */
  function scorerNow(a, r, o) {
    if (!a || !o || !o.description) return null;
    var p = ValueCheck.scorerChances(a.view || a.model).find(function (x) { return F.samePlayer(x.name, o.description); });
    if (!p || !(p.lam > 0)) return null;
    var left = p.side === 'home' ? r.home : r.away;
    return 1 - Math.exp(-(p.x / p.lam) * left);
  }
  function inPlayMarkets(box, bk, snap, name, a) {
    var point = pointOf(snap), saved = oddsAt(snap);
    var r = a && snap.period !== 'PRE' ? InPlay.rates(a.view || a.model, snap) : null, names = [ev.home_team, ev.away_team];
    $('marketsTitle').textContent = 'Bets · ' + name + ' · ' + periodName(point);
    if (point === 'FT') { box.appendChild(el('div', 'text-muted', 'Markets Closed')); $('marketsNote').textContent = ''; return; }
    var markets = saved ? saved.markets : bk.markets.filter(function (mk) { return IN_PLAY_KEYS.indexOf(mk.key) >= 0; });
    markets.forEach(function (mk) {
      var title = F.MARKET_NAMES[mk.key] || mk.key;
      var rows = saved ? marketRows(mk, null).rows : mk.outcomes.map(function (o) {
        return { label: label(mk.key, o), o: { name: o.name, point: o.point, price: null }, chance: null, r: null,
                 hint: 'No odds saved for this point in the match.' };
      });
      rows.forEach(function (x) {
        var ours = r ? (mk.key === 'player_goal_scorer_anytime' ? scorerNow(a, r, x.o) : InPlay.chanceFor(r, names, mk.key, x.o)) : null;
        if (ours === null || isNaN(ours)) { if (!saved) x.chance = null; return; }
        if (x.o.price) {   // the bookmaker's chance (margin removed) stays on the odds badge; ours on Chance + verdict
          var ret = ours * x.o.price - 1;
          x.r = { ours: ours, fair: x.chance, ret: ret, verdict: ValueCheck.label(ret, ours, a.level.name) };
        }
        x.chance = ours;
      });
      rows = rows.filter(function (x) { return x.chance === null || (x.chance > 0.005 && x.chance < 0.995); });   // settled ones
      if (saved) rows.sort(byVerdict);   // best verdict first; without odds, the bookmaker's own order (by line)
      var show = rows.length > 6 ? 5 : rows.length;
      var t = table(rows.slice(0, show), title);
      if (box.children.length) t.classList.add('mt-3');
      box.appendChild(t);
      if (rows.length > show) {
        var more = el('a', 'small d-inline-block mt-1 link-secondary', 'Show all ' + rows.length);
        more.href = '#';
        more.addEventListener('click', function (e) {
          e.preventDefault();
          $('marketModalTitle').textContent = title + ' · ' + name + ' · ' + periodName(point);
          $('marketModalBody').replaceChildren(table(rows, title));
          bootstrap.Modal.getOrCreateInstance($('marketModal')).show();
        });
        box.appendChild(more);
      }
    });
    // a window without odds: the bookmaker had its markets suspended then (the app tries again for a few minutes)
    $('marketsNote').textContent = saved ? 'Odds from ' + F.ago(saved.fetchedAt) + '.'
      : !snap.live ? 'No ' + name + ' odds for this point: its in-play markets were suspended.' : '';
  }

  /**
   * A bet window's Recommended Bets as first worked out, from the prediction log: shown as they were, never
   * redone (a later model or a code change doesn't rewrite what a snapshot said). {"30": [rows], ...}
   */
  var frozenBets = {}, frozenPred = {}, frozenAsked = false;
  function loadFrozenBets() {
    if (frozenAsked || !ev) return;
    frozenAsked = true;
    F.getJson('/api/predictions').then(function (body) {
      ((body && body.predictions) || []).forEach(function (p) {
        if (p.matchId !== ev.id) return;
        if (p.point !== 'PRE' && Array.isArray(p.recommended)) frozenBets[p.point] = p.recommended;
        if (Array.isArray(p.predictionRows)) frozenPred[p.point] = p.predictionRows;
      });
      if (Object.keys(frozenBets).length || Object.keys(frozenPred).length) draw();
    }).catch(function () { /* worked out as usual */ });
  }
  /** The rows the in-play Recommended Bets would list for a window, without drawing them (for the log). */
  function windowBets(a, snap) {
    var rows = [], check = Live.checklist(snap.rows);
    InPlay.render(el('div'), ev, a.view || a.model, snap, check, Live.setup(check, snap.homeScore, snap.awayScore), {
      table: function (r) { rows = r; return el('table'); }, confidence: a.level.name,
      markets: oddsAt(snap) ? oddsAt(snap).markets : null
    });
    return rows.map(function (x) {
      return { label: x.label, o: { price: x.o.price }, chance: x.chance, fits: x.fits, hint: x.hint,
               r: x.r && { ours: x.r.ours, fair: x.r.fair, ret: x.r.ret, verdict: x.r.verdict } };
    });
  }

  function inPlayBets(a, snap) {
    var box = $('bestBets');
    var check = Live.checklist(snap.rows);
    var holder = el('div');
    box.appendChild(holder);
    loadFrozenBets();
    var kept = !snap.live && frozenBets[pointOf(snap)];
    if (kept) {   // a bet window: what it recommended at the time
      if (kept.length) holder.appendChild(table(kept, 'In-play bet'));
      else holder.appendChild(el('div', 'text-muted', 'No Bet'));
      showPredictionCard(a);
      showPrediction(a);
      return;
    }
    var r = InPlay.render(holder, ev, a.view || a.model, snap, check, Live.setup(check, snap.homeScore, snap.awayScore), {
      table: table, confidence: a.level.name,
      markets: oddsAt(snap) ? oddsAt(snap).markets : null   // each point's odds: fetched when it's saved (30', HT...)
    });
    showPredictionCard(a);
    showPrediction(a);
  }

  var liveMatch = null, predTab = 'merged';
  /** The rows a prediction function would draw on the card, without drawing them. */
  function rowsOf(fn) {
    var keep = predCapture, rows = [];
    predCapture = rows;
    try { fn(); } finally { predCapture = keep; }
    return rows;
  }
  /** Rows as plain data, to keep in the prediction log: [label, text or [{player, text}]]. */
  function plainRows(rows) {
    return (rows || []).map(function (r) {
      return [r[0], Array.isArray(r[1]) ? r[1].map(function (x) { return x && x.player ? { player: x.player, text: x.text } : x; }) : r[1]];
    });
  }
  /** Kept rows, with the live ones' "how we worked this out" links where they still say the same. */
  function withLinks(kept, live) {
    return kept.map(function (k) {
      var same = (live || []).find(function (r) { return r[0] === k[0] && JSON.stringify(plainRows([r])[0][1]) === JSON.stringify(k[1]); });
      return same || [k[0], k[1]];
    });
  }

  /**
   * The Prediction card follows the point picked on the timeline:
   *  - Pre-Match: the pre-match prediction (as it stood at kick-off), no tabs.
   *  - a bet window (30', HT, 60', 80') or LIVE: two tabs. The window's own (e.g. "30'"): what had been predicted
   *    going into it, the pre-match lines with the earlier windows' lines swapped in (marked with where each is
   *    from), and how each did. "Rest of game": what this window (or LIVE) newly predicts.
   *  - FT: the lines as they finished (every window's), no tabs.
   * Settled lines: green ✓ (came true) or red ✗.
   */
  function showPredictionCard(a) {
    var card = $('prediction'), tabs = $('predictionTabs');
    var pre = rowsOf(function () { prediction(a); });
    if (frozenPred.PRE && new Date(ev.commence_time) <= new Date()) pre = withLinks(frozenPred.PRE, pre);
    if (!a || !a.model) { prediction(a); tabs.classList.add('d-none'); return; }
    var point = liveView ? (liveView.live ? 'LIVE' : liveView.period) : 'PRE';
    var draw = function (title, rows, note) {
      rows.forEach(function (r) { settle(r, r.from || 'PRE'); });
      $('predictionTitle').textContent = title;
      card.replaceChildren();   // (working out the windows' lines touches the card)
      predictionSummary(card, rows);
      if (note) card.appendChild(el('div', 'small text-muted', note));
    };
    if (point === 'PRE') {
      tabs.classList.add('d-none');
      draw('Prediction · Pre-Match', pre.map(function (r) { return r.slice(); }), null);
      return;
    }
    // the windows up to the point picked (FT: all of them; LIVE: all so far, then the live lines on top)
    var windows = liveSnaps.filter(function (sn) { return ['30', 'HT', '60', '80'].indexOf(sn.period) >= 0; });
    var upTo = windows.findIndex(function (sn) { return sn.period === point; });
    if (upTo >= 0) windows = windows.slice(0, upTo + 1);
    var merged = pre.map(function (r) { var x = r.slice(); x.from = 'PRE'; return x; }), own = [];
    var swapIn = function (rows, from) {
      rows.forEach(function (r) {
        var x = r.slice(); x.from = from;
        var i = merged.findIndex(function (y) { return y[0] === r[0]; });
        if (i >= 0) merged[i] = x; else merged.push(x);
      });
    };
    // the window's own tab: what had been predicted GOING INTO it (pre-match + the earlier windows), how each did;
    // Rest of game: what this window (or LIVE) newly predicts. FT: every window's.
    windows.forEach(function (sn) {
      var rows = rowsOf(function () { inPlayPrediction(a, sn); });
      if (frozenPred[sn.period]) rows = withLinks(frozenPred[sn.period], rows);
      if (sn.period === point) own = rows;
      else swapIn(rows, sn.period);
    });
    if (point === 'LIVE') {   // where the game is now (not kept: it moves with every refresh)
      own = rowsOf(function () { inPlayPrediction(a, liveView); });
    }
    if (point === 'FT') {
      tabs.classList.add('d-none');
      draw('Prediction · FT', merged, 'How the prediction finished: the pre-match lines, updated by each bet window.');
      return;
    }
    var name = point === 'LIVE' ? (liveView.label || 'LIVE') : periodName(point);
    tabs.classList.remove('d-none');
    tabs.querySelector('[data-pred="merged"]').textContent = name;
    tabs.querySelectorAll('[data-pred]').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-pred') === predTab);
      b.onclick = function () { predTab = b.getAttribute('data-pred'); showPredictionCard(a); };
    });
    if (predTab === 'rest') {
      draw('Prediction', own.map(function (r) { var x = r.slice(); x.from = point; x.noTag = true; return x; }),
        'The rest of the game from ' + name + ': the score and time left then, and the stats so far.');
    } else {
      draw('Prediction', merged, 'Going into ' + name + ': what we\'d predicted up to then (pre-match, and the bet windows before it), and how each did. The new prediction is in Rest of game.');
    }
  }

  /** Minute each bet window stands for, and whether an event (from the Timeline) came after it. */
  var WINDOW_MINUTE = { PRE: 0, '30': 30, HT: 45, '60': 60, '80': 80 };
  function after(e, from) {
    if (from === 'PRE') return true;
    if (from === 'LIVE') return liveView && liveView.minute != null ? (e.elapsed || 0) > liveView.minute : false;
    if (from === 'HT') return e.half === 2;
    return (e.half === 2 && WINDOW_MINUTE[from] < 45) || (e.elapsed || 0) > WINDOW_MINUTE[from];
  }
  /**
   * Is a line settled? Sets r.settled (true / false) or, for player lists, each player's. Settled as soon as the
   * answer is known: the HT score at half time, BTTS yes once both have scored, over 2.5 at the third goal, a
   * scorer when he scores, the rest at full time. Lines from a bet window count from that point (Another goal: one
   * more than the score then; Next goal: the first one after it).
   */
  function settle(r, from) {
    r.settled = null;
    var fx = liveMatch && liveMatch.fixture;
    if (!fx) return;
    var ft = Live.finished(fx), h = parseInt(fx.homeScore, 10) || 0, w = parseInt(fx.awayScore, 10) || 0;
    var events = (liveMatch.events || []), names = [shortTeam(ev.home_team), shortTeam(ev.away_team)];
    var text = typeof r[1] === 'string' ? r[1] : '';
    var nums = /(\d+)\s*–\s*(\d+)/.exec(text);
    var side = text.indexOf(names[0]) === 0 ? 'home' : text.indexOf(names[1]) === 0 ? 'away' : null;
    var htSnap = liveSnaps.find(function (x) { return x.period === 'HT'; });
    var base = from === 'LIVE' ? liveView : liveSnaps.find(function (x) { return x.period === from; });
    var baseGoals = base ? (parseInt(base.homeScore, 10) || 0) + (parseInt(base.awayScore, 10) || 0) : 0;
    var label = r[0];
    if (label === 'HT Score' && nums && htSnap) r.settled = +nums[1] === +htSnap.homeScore && +nums[2] === +htSnap.awayScore;
    else if (label === 'FT Score' && nums && ft) r.settled = +nums[1] === h && +nums[2] === w;
    else if (label === 'Result' && ft) r.settled = /^Draw/.test(text) ? h === w : side === 'home' ? h > w : side === 'away' ? w > h : null;
    else if (label === 'Goals') {
      var g = /(Over|Under)\s+([\d.]+)/.exec(text);
      if (g) { var over = h + w > +g[2]; if (over || ft) r.settled = g[1] === 'Over' ? over : !over; }
    } else if (label === 'Both teams to score') {
      var both = h > 0 && w > 0;
      if (both || ft) r.settled = /^Yes/.test(text) ? both : !both;
    } else if (label === 'Another goal') {
      var more = h + w > baseGoals;
      if (more || ft) r.settled = /^Yes/.test(text) ? more : !more;
    } else if (label === 'Next goal') {
      var next = events.find(function (e) { return e.kind === 'goal' && after(e, from); });
      if (next) r.settled = /^No more/.test(text) ? false : next.side === side;
      else if (ft) r.settled = /^No more/.test(text);
    } else if (label === 'Another yellow card') {
      var card = events.some(function (e) { return (e.kind === 'yellow' || e.kind === 'red') && after(e, from); });
      if (card || ft) r.settled = /^Yes/.test(text) ? card : !card;
    } else if ((label === 'Most cards' || label === 'Most fouls') && ft && side) {
      var row = ((liveMatch.stats && liveMatch.stats.rows) || []).find(function (x) { return x.label === (label === 'Most cards' ? 'Yellow cards' : 'Fouls'); });
      if (row) { var hv = parseInt(row.home, 10) || 0, wv = parseInt(row.away, 10) || 0; r.settled = side === 'home' ? hv > wv : wv > hv; }
    } else if ((label === 'Likely scorers' || label === 'Likely booked') && Array.isArray(r[1])) {
      var kinds = label === 'Likely scorers' ? ['goal'] : ['yellow', 'red'];
      r[1] = r[1].map(function (x) {
        if (!x || !x.player) return x;
        var did = events.some(function (e) { return kinds.indexOf(e.kind) >= 0 && e.player && F.samePlayer(e.player, x.player) && after(e, from); });
        return Object.assign({}, x, { settled: did ? true : ft ? false : null });
      });
    }
  }

  /**
   * Prediction at a point in the match (a bet window or LIVE): the rest of the game from the score and the time
   * left (InPlay.rates: the pre-match model, the live xG, who's chasing): final result, more goals,
   * next goal, likely final score, and cards still to come (each team's cards a game, for the time left, plus the
   * cards so far). A bet window's prediction goes in the prediction log (kept as first made). Its lines are
   * collected with rowsOf() and drawn by showPredictionCard(); nothing at full time or before the stats are in.
   */
  function inPlayPrediction(a, snap) {
    if (!a || !a.model || snap.period === 'FT') return;
    var pois = ValueCheck.pois, names = [ev.home_team, ev.away_team];
    var r = InPlay.rates(a.view || a.model, snap), lh = r.home, la = r.away, h0 = r.score[0], a0 = r.score[1];
    var pc = function (p) { return Math.round(p * 100) + '%'; };
    var grid = function (f) { return F.grid(lh, la, f); };   // chance of a scoreline condition (football-data.js)
    var H = grid(function (i, j) { return h0 + i > a0 + j; }), D = grid(function (i, j) { return h0 + i === a0 + j; }), A = 1 - H - D;
    var none = Math.exp(-(lh + la));
    // the final score: agrees with the result and the "Another goal" call (see likelyScore)
    var moreGoals = 1 - Math.exp(-(lh + la)) >= 0.5;
    var ft = likelyScore(lh, la, h0, a0, [function (h, w) { return (h + w > h0 + a0) === moreGoals; }]);
    // cards to come: each team's yellow cards a game (squad season stats) for the share of the game left
    var ts = window.TopScorers || {}, left = InPlay.shareLeft(r.minute || 0);
    var perGame = function (side) {
      var y = 0, games = 0;
      ((ts[side] || {}).squad || []).forEach(function (p) {
        var st = p.stats || {}; y += parseFloat(st.yellowCards) || 0; games = Math.max(games, parseFloat(st.matchPlayed) || 0);
      });
      return games >= 3 ? y / games : null;
    };
    var yh = perGame('home'), ya = perGame('away');
    var next = (1 - none) * Math.max(lh, la) / (lh + la);
    var top3 = [[shortTeam(names[0]) + ' Win', H], ['Draw', D], [shortTeam(names[1]) + ' Win', A]].sort(function (x, y) { return y[1] - x[1]; })[0];
    var sum = [];
    if ((r.minute || 0) < 45 && snap.period !== 'HT') {
      // goals still to come before half-time: the first half's share of what's left
      var t = r.minute || 0, leftAll = InPlay.shareLeft(t), leftHT = Math.max(0, leftAll - InPlay.shareLeft(45));
      var f = leftAll > 0 ? leftHT / leftAll : 0, hts = htOnTheWay(ft.h, ft.a, h0, a0, f);   // on the way to the FT score
      sum.push(['HT Score', shortTeam(names[0]) + ' ' + hts.h + ' – ' + hts.a + ' ' + shortTeam(names[1])]);
    }
    sum = sum.concat([
      ['FT Score', shortTeam(names[0]) + ' ' + ft.h + ' – ' + ft.a + ' ' + shortTeam(names[1])],
      ['Result', top3[0] + ' (' + pc(top3[1]) + ')'],
      ['Another goal', (1 - none >= 0.5 ? 'Yes' : 'No') + ' (' + pc(Math.max(1 - none, none)) + ')'],
      ['Next goal', none > next ? 'No more goals (' + pc(none) + ')' : shortTeam(lh >= la ? names[0] : names[1]) + ' (' + pc(next) + ')']
    ]);
    if (yh !== null && ya !== null) {
      var more = 1 - Math.exp(-(yh + ya) * left);
      sum.push(['Another yellow card', (more >= 0.5 ? 'Yes' : 'No') + ' (' + pc(Math.max(more, 1 - more)) + ')', function () {
        explain('Another yellow card', function (box) {
          step(box, 1, 'Each team\'s season', [shortTeam(names[0]) + ' ' + yh.toFixed(2) + ' and ' + shortTeam(names[1]) + ' ' + ya.toFixed(2) +
            ' yellow cards a game (squads\' player stats: a guide).']);
          step(box, 2, 'The time left', ['About ' + Math.round(left * 100) + '% of a game\'s cards are still to come at ' + r.minute + '\' ' +
            '(allowing for stoppage time and more cards late on), so ' + ((yh + ya) * left).toFixed(2) + ' more expected.',
            'Chance of at least one more: 1 − e^(−' + ((yh + ya) * left).toFixed(2) + ') = ' + pcx(more) + '.']);
        });
      }]);
    }
    // the working behind the rest of the game's lines: the score, the time left, the pre-match view and the live xG
    var inPlayHow = function (label, finalLine) {
      return function () {
        explain(label, function (box) {
          var C = ValueCheck.CONSTANTS, n = [shortTeam(names[0]), shortTeam(names[1])];
          step(box, 1, 'Before kick-off', ['Our view expected ' + n[0] + ' ' + (a.view || a.model).lh.toFixed(2) + ' – ' +
            (a.view || a.model).la.toFixed(2) + ' ' + n[1] + ' goals over the whole game (see the Pre-Match prediction).']);
          step(box, 2, 'Where the game is', ['Score ' + h0 + '–' + a0 + ' at ' + r.minute + '\': about ' + Math.round(left * 100) +
            '% of the game\'s goals are still to come.',
            'Pre-match pace for the time left: ' + r.pre[0].toFixed(2) + ' – ' + r.pre[1].toFixed(2) + '.',
            r.live ? 'Live xG pace (from shots so far): ' + r.live[0].toFixed(2) + ' – ' + r.live[1].toFixed(2) + ', counted as ' +
              Math.round((1 - r.weight) * 100) + '% (more as the game goes on).' : 'No live xG yet: the pre-match pace only.',
            h0 !== a0 ? 'The side behind chases (more goals for them), the side ahead protects its lead (fewer).' : null,
            'So we expect ' + n[0] + ' ' + lh.toFixed(2) + ' – ' + la.toFixed(2) + ' ' + n[1] + ' more goals.']);
          step(box, 3, 'The chance', [finalLine]);
        });
      };
    };
    sum.forEach(function (row) {
      if (row[2]) return;
      var t = row[0];
      if (t === 'FT Score' || t === 'HT Score') row.push(inPlayHow(t, 'The likeliest ' + (t === 'HT Score' ? 'half-time' : 'final') +
        ' score (Poisson on the goals still to come, on top of ' + h0 + '–' + a0 + ') that agrees with the Result and Another goal calls.'));
      else if (t === 'Result') row.push(inPlayHow('Result', 'Adding every way the goals still to come can fall: ' + shortTeam(names[0]) + ' Win ' +
        pcx(H) + ', Draw ' + pcx(D) + ', ' + shortTeam(names[1]) + ' Win ' + pcx(A) + '.'));
      else if (t === 'Another goal') row.push(inPlayHow('Another goal', 'No more goals: e^(−' + (lh + la).toFixed(2) + ') = ' + pcx(none) +
        ', so at least one more: ' + pcx(1 - none) + '.'));
      else if (t === 'Next goal') row.push(inPlayHow('Next goal', 'A goal comes ' + pcx(1 - none) + ' of the time, and it\'s ' + shortTeam(names[0]) +
        '\'s ' + pcx(lh / (lh + la)) + ' of those (their share of the goals expected).'));
    });
    predictionSummary(null, sum);   // (collected by rowsOf)
    // logged (and its Recommended Bets kept) once the window's odds are in; without them, logWindows logs it once a
    // later window has passed
    if (oddsAt(snap)) {
      var rec = windowRecord(a, snap);
      if (rec) rec.predictionRows = plainRows(sum);   // what this window predicted, kept as it was
      ValueCheck.log(rec);
    }
  }

  /**
   * The most likely scoreline (goals still to come: lh, la; already: h0, a0) among those giving the most likely
   * result, so the score never contradicts the Result row. {h, a, p}.
   */
  function likelyScore(lh, la, h0, a0, also) {
    var pois = ValueCheck.pois, P = { H: 0, D: 0, A: 0 };
    for (var i = 0; i <= 6; i++) for (var j = 0; j <= 6; j++) {
      P[h0 + i > a0 + j ? 'H' : h0 + i < a0 + j ? 'A' : 'D'] += pois(i, lh) * pois(j, la);
    }
    var want = P.H >= P.D && P.H >= P.A ? 'H' : P.A >= P.D ? 'A' : 'D';
    // also: the card's other calls the score must agree with (e.g. Over 2.5, BTTS No), dropped from the end if
    // no scoreline fits them all
    var conds = (also || []).slice();
    for (;;) {
      var best = null;
      for (var x = 0; x <= 6; x++) for (var y = 0; y <= 6; y++) {
        var fh = h0 + x, fa = a0 + y, side = fh > fa ? 'H' : fh < fa ? 'A' : 'D';
        if (side !== want || !conds.every(function (c) { return c(fh, fa); })) continue;
        var p = pois(x, lh) * pois(y, la);
        if (!best || p > best.p) best = { h: fh, a: fa, p: p };
      }
      if (best || !conds.length) return best;
      conds.pop();
    }
  }

  /**
   * The half-time score on the way to a full-time score: each of a side's goals still to come falls before the break
   * with chance f (independently), so the likeliest split of each side's goals (binomial), added to the goals so far.
   * Keeps HT and FT telling one story (not HT 0-0 then three second-half goals).
   */
  function htOnTheWay(ftH, ftA, h0, a0, f) {
    var split = function (n) {   // most likely number of n goals before the break
      var best = 0, bestP = -1, c = 1;
      for (var k = 0; k <= n; k++) {
        if (k > 0) c = c * (n - k + 1) / k;
        var p = c * Math.pow(f, k) * Math.pow(1 - f, n - k);
        if (p > bestP) { bestP = p; best = k; }
      }
      return best;
    };
    return { h: h0 + split(ftH - h0), a: a0 + split(ftA - a0) };
  }

  // ---- "How we worked this out": the Prediction card's percentages open the working behind them ----

  var pcx = function (p) { return (Math.round(p * 1000) / 10) + '%'; };
  /** Opens the pop-up: title, then build(box) fills it. */
  function explain(title, build) {
    $('marketModalTitle').textContent = 'How we worked this out · ' + title;
    var box = el('div');
    build(box);
    $('marketModalBody').replaceChildren(box);
    bootstrap.Modal.getOrCreateInstance($('marketModal')).show();
  }
  /**
   * TEMPORARY: the Test: Fetch Live Odds button. Asks the bookmaker for exactly what's ticked in Settings (as a bet
   * window would) right now, and shows them on the LIVE view: Markets and Recommended Bets use them (not saved on the
   * server, so the bet windows are untouched; gone on reload).
   */
  $('testLiveOdds').addEventListener('click', function () {
    var btn = this;
    if (!ev) return;
    btn.disabled = true;
    F.getJson('/api/settings').then(function (s) {
      var o = s.refresh || {}, markets = (o.matchResult === false ? [] : ['h2h']).concat(o.markets || []);
      if (markets.indexOf('alternate_totals') >= 0) markets.push('totals');   // as a bet window: the main goal line too
      if (!markets.length) throw new Error('Nothing ticked under Odds in Settings.');
      return F.sendJson('/api/odds/cache/' + ev.sport_key + '/events/' + encodeURIComponent(ev.id) + '/live-test?bookmaker=' +
        F.bookmaker() + '&markets=' + encodeURIComponent(markets.join(','))).then(function (r) { return { asked: markets, r: r }; });
    }).then(function (x) {
      var got = (x.r.markets || []).map(function (m) { return m.key; });
      var missing = x.asked.filter(function (k) { return got.indexOf(k) < 0; });
      liveOdds.LIVE = x.r;
      draw();
      notify('Live odds fetched · ' + x.r.credits + ' credit' + (x.r.credits === 1 ? '' : 's') + ' · shown on LIVE' +
        (missing.length ? ' · not returned: ' + missing.map(function (k) { return F.EXTRA_MARKET_NAMES[k] || k; }).join(', ') : ''),
        missing.length ? 'warning' : 'success');
    }).catch(function (e) { notify('Live odds test: ' + e.message, 'danger'); })
      .then(function () { btn.disabled = false; });
  });
  /** One numbered step: a heading and its lines. */
  function step(box, n, title, lines) {
    box.appendChild(el('div', 'section-label', n + '. ' + title));
    var ul = el('ul', 'mb-2 ps-3');
    lines.filter(Boolean).forEach(function (l) { ul.appendChild(el('li', null, l)); });
    box.appendChild(ul);
  }
  /** What the stats model looked at for this match: recent games, shots, keepers, injuries, home advantage. */
  function statsLines(a) {
    var inp = a.model.inputs || {}, h = inp.home || {}, w = inp.away || {}, C = ValueCheck.CONSTANTS;
    var n = [shortTeam(ev.home_team), shortTeam(ev.away_team)], any = views && views.home && views.home.anyVenue;
    var lines = [
      any ? 'Recent games at any venue (too few home / away games yet): ' + a.games + ' each, with home advantage added back (home ×' +
            C.ANY_VENUE_HOME + ', away ÷' + C.ANY_VENUE_HOME + ').'
          : 'Recent home games for ' + n[0] + ' and away games for ' + n[1] + ': ' + a.games + ' each.',
      n[0] + ' scored ' + (h.recent || 0).toFixed(2) + ' and conceded ' + (h.conceded || 0).toFixed(2) + ' a game; ' +
        n[1] + ' scored ' + (w.recent || 0).toFixed(2) + ' and conceded ' + (w.conceded || 0).toFixed(2) + '.'
    ];
    [[n[0], h], [n[1], w]].forEach(function (t) {
      var pr = t[1].profile;
      if (!pr) { lines.push(t[0] + ': no squad stats (Refresh Stats), so recent goals only.'); return; }
      lines.push(t[0] + ' shots: ' + (t[1].shotsUsed ? pr.shotsPerGame.toFixed(1) + ' a game, blended ' + Math.round(C.SHOT_WEIGHT * 100) +
        '% with recent scoring (steadier than goals).' : 'not used (too many players without shots recorded in GOAL API).'));
      if (pr.keeperGoals !== null) lines.push(t[0] + ' keepers: shots faced suggest ' + pr.keeperGoals.toFixed(2) +
        ' goals against a game, counted as ' + Math.round(C.KEEPER_WEIGHT * 100) + '% of conceding.');
      if (pr.injured.length) lines.push(t[0] + ' injured: ' + pr.injured.join(', ') + ' (attack −' + Math.round(100 * (1 - pr.attackFactor)) + '%).');
    });
    lines.push('So our stats expect ' + n[0] + ' ' + a.model.lh.toFixed(2) + ' – ' + a.model.la.toFixed(2) + ' ' + n[1] +
      ' goals: each side\'s scoring averaged with the other\'s conceding.');
    return lines;
  }
  /**
   * A team market line (result, over / under 2.5, both teams to score): stats → bookmaker → our chance → the card.
   * which: 'home', 'draw', 'away', 'over', 'under', 'yes', 'no'.
   */
  function explainTeamLine(a, which, label, shown) {
    var m = a.model, C = ValueCheck.CONSTANTS;
    var stats = { home: m.home, draw: m.draw, away: m.away, over: m.over25, under: 1 - m.over25, yes: m.btts, no: 1 - m.btts }[which];
    var row = null;
    if (['home', 'draw', 'away'].indexOf(which) >= 0) row = (a.rows || [])[['home', 'draw', 'away'].indexOf(which)];
    var want = { over: 'Over 2.5', under: 'Under 2.5', yes: 'Yes', no: 'No' }[which];
    if (want) (a.markets || []).forEach(function (mk) {
      if (row) return;
      if ((which === 'yes' || which === 'no') ? mk.key === 'btts' : (mk.key === 'totals' || mk.key === 'alternate_totals')) {
        row = mk.rows.find(function (r) { return r.name === want; }) || null;
      }
    });
    explain(label, function (box) {
      step(box, 1, 'Our stats', statsLines(a).concat(['Stats alone give ' + label + ': ' + pcx(stats) + ' (Poisson on those expected goals).']));
      if (row) {
        var cap = Math.min(C.MAX_NUDGE[a.level.name], row.fair * C.MAX_RELATIVE);
        step(box, 2, 'The bookmaker', [F.bookmakerName() + ' has it at ' + F.fraction(row.price) + ': ' + pcx(1 / row.price) +
          ' with its margin, ' + pcx(row.fair) + ' once the margin is taken out.']);
        step(box, 3, 'Our chance', ['We move ' + Math.round(C.MODEL_WEIGHT * 100) + '% of the way from the bookmaker\'s ' + pcx(row.fair) +
          ' towards our stats\' ' + pcx(stats) + ', but no more than ' + (cap * 100).toFixed(1) + ' points (confidence ' + a.level.name +
          ', ' + a.games + ' games each: the stats can only move the bookmaker\'s view a little).', 'Our chance: ' + pcx(row.ours) +
          '. The verdict compares this with the odds: ' + row.verdict.text + '.']);
        step(box, 4, 'The card', ['The Prediction card uses our view: the expected goals (' + shortTeam(ev.home_team) + ' ' +
          a.view.lh.toFixed(2) + ' – ' + a.view.la.toFixed(2) + ' ' + shortTeam(ev.away_team) + ') that best fit our chances for the ' +
          'result and over / under 2.5, so every line agrees: ' + label + ' ' + shown + '.']);
      } else {
        step(box, 2, 'No odds', ['No ' + F.bookmakerName() + ' odds for this market, so the card uses our view as it stands: ' + label + ' ' + shown + '.']);
      }
    });
  }

  /** The Prediction card: a few lines, label | our call (e.g. "Match result | Chelsea Win (42%)"). */
  var predCapture = null;   // while set (rowsOf): the rows are collected here instead of drawn
  function predictionSummary(card, rows) {
    if (predCapture) { predCapture.push.apply(predCapture, rows); return; }
    var t = el('table', 'table table-sm align-middle mb-1 stats-table'), tb = el('tbody');
    var mark = function (node, ok) {   // a settled call: green and ✓ if it came true, red and ✗ if not
      if (ok === true || ok === false) {
        node.classList.add(ok ? 'text-success' : 'text-danger');
        node.appendChild(el('span', 'ms-1', ok ? '✓' : '✗'));
      }
    };
    rows.forEach(function (r) {
      var tr = el('tr');
      var lab = el('td', 'text-muted text-nowrap align-top', r[0]);
      if (r.from && !r.noTag) lab.appendChild(el('span', 'small ms-1', '· ' + periodName(r.from)));   // the window it's from
      tr.appendChild(lab);
      var v = el('td', 'text-end fw-semibold');
      // a click on the call (or a player's percentage) opens how we worked it out
      var howLink = function (text, how) {
        if (!how) return document.createTextNode(text);
        var a = el('a', 'link-body-emphasis text-decoration-none', text);
        a.href = '#'; a.title = 'How we worked this out';
        a.style.borderBottom = '1px dotted currentColor';
        a.addEventListener('click', function (e) { e.preventDefault(); how(); });
        return a;
      };
      [].concat(r[1]).forEach(function (line) {   // a list: one per line; {player, text, how}: his name opens his stats
        var div = el('div');
        if (line && line.player) {
          var cell = playerCell(line.player);
          while (cell.firstChild) div.appendChild(cell.firstChild);
          div.appendChild(document.createTextNode(' '));
          div.appendChild(howLink((line.text || '').trim(), line.how));
          mark(div, line.settled);
        } else {
          // only the percentage is the link, not the team or call beside it
          var m = r[2] && /^(.*?)(\d+(?:\.\d+)?%)(.*)$/.exec(String(line));
          if (m) {
            if (m[1]) div.appendChild(document.createTextNode(m[1]));
            div.appendChild(howLink(m[2], r[2]));
            if (m[3]) div.appendChild(document.createTextNode(m[3]));
          } else {
            div.appendChild(howLink(line, r[2]));
          }
          mark(div, r.settled);
        }
        v.appendChild(div);
      });
      tr.appendChild(v);
      tb.appendChild(tr);
    });
    t.appendChild(tb);
    card.appendChild(t);
  }

  /**
   * A bet window's prediction for the log (null for LIVE, which moves every minute, or without the teams' ids):
   * the final result from the score and time left then, more goals, and the bookmaker's prices at that point.
   */
  function windowRecord(a, snap) {
    if (!a || !a.model || snap.live || ['30', 'HT', '60', '80'].indexOf(String(snap.period)) < 0) return null;
    if (teamIds[0] == null || teamIds[1] == null) return null;
    var pois = ValueCheck.pois, r = InPlay.rates(a.view || a.model, snap), lh = r.home, la = r.away, h0 = r.score[0], a0 = r.score[1];
    var grid = function (f) { return F.grid(lh, la, f); };   // chance of a scoreline condition (football-data.js)
    var H = grid(function (i, j) { return h0 + i > a0 + j; }), D = grid(function (i, j) { return h0 + i === a0 + j; });
    var rec = { matchId: ev.id, point: String(snap.period), kickoff: ev.commence_time, competition: ev.compKey,
                home: ev.home_team, away: ev.away_team, homeTeamId: teamIds[0], awayTeamId: teamIds[1],
                version: ValueCheck.MODEL_VERSION, score: [h0, a0], minute: r.minute,
                chances: { home: H, draw: D, away: 1 - H - D, moreGoals: 1 - Math.exp(-(lh + la)), lh: lh, la: la } };
    rec.recommended = windowBets(a, snap);   // kept with the window: shown as it was from now on
    if (!frozenBets[rec.point] && oddsAt(snap)) frozenBets[rec.point] = rec.recommended;
    var saved = oddsAt(snap), h2h = saved && (saved.markets || []).find(function (mk) { return mk.key === 'h2h'; });
    if (h2h) {
      var price = function (n) { var o = h2h.outcomes.find(function (x) { return x.name === n; }); return o ? o.price : null; };
      var p3 = [price(ev.home_team), price('Draw'), price(ev.away_team)];
      if (p3.every(Boolean)) {
        var fc = F.fairChances(p3).chances;
        rec.odds = { home: p3[0], draw: p3[1], away: p3[2] };
        rec.bookmaker = { home: fc[0], draw: fc[1], away: fc[2] };
      }
    }
    return rec;
  }
  var FIRST_HALF_SHARE = 0.45;   // share of a match's goals scored before half-time (as the value check uses)
  var liveSnaps = [];   // the followed game's saved timeline points (from Live Match Stats)
  /** Logs every bet window saved so far (the server keeps the first of each; this page sends each once). */
  function logWindows(a) {
    liveSnaps.forEach(function (snap, i) {
      // wait for the window's odds (they come a few seconds after it), unless a later window has passed (none coming)
      var later = liveSnaps.slice(i + 1).some(function (x) { return x.period !== 'FT'; }) || liveSnaps.some(function (x) { return x.period === 'FT'; });
      if (oddsAt(snap) || later) ValueCheck.log(windowRecord(a, snap));
    });
  }



  /**
   * The last row of the Goals Profile table: our goals model's predicted goals for each team in this match, e.g.
   * "Predicted Goals  2.0  1.5", with how it's worked out on hover. Average goals each team is expected to score
   * (from goals scored and conceded in recent home / away games), not shot-based xG.
   */
  /**
   * Prediction (Pre-Match): our chances, including markets the app gets no odds for:
   * the result, likely score, goals; cards (each team's yellow and red cards a game from its squad's season stats);
   * players likely to score, to have a shot, and to be booked.
   */
  function prediction(a) {
    var card = $('prediction');
    card.replaceChildren();
    $('predictionTitle').textContent = 'Prediction · Pre-Match';
    if (!a || !a.model) { card.appendChild(el('span', 'text-muted', noStats ? 'No data available for this game.' : 'Waiting for the stats…')); return; }
    // our view (fitted to the chances the verdicts use), so the card agrees with Markets and Recommended Bets
    var m = a.view || a.model, pois = ValueCheck.pois, names = [ev.home_team, ev.away_team];
    var pc = function (p) { return Math.round(p * 100) + '%'; };
    var under = function (line, l) { var p = 0; for (var k = 0; k <= Math.floor(line); k++) p += pois(k, l); return p; };
    var total = m.lh + m.la;
    var ts = window.TopScorers || {};
    // players: likely to score (the goalscorer model) and to be booked
    var perGame = function (key, min) {
      var out = [];
      ['home', 'away'].forEach(function (side) {
        ((ts[side] || {}).squad || []).forEach(function (p) {
          var st = p.stats || {}, g = parseFloat(st.matchPlayed) || 0, v = parseFloat(st[key]);
          if (g >= min && !isNaN(v) && v > 0) out.push({ name: p.name, side: side, chance: 1 - Math.exp(-v / g), rate: v / g });
        });
      });
      return out.sort(function (a, b) { return b.chance - a.chance; });
    };
    // the card: our call on each, in a few lines
    var o25 = 1 - under(2.5, total);
    var result = [[short(names[0]) + ' Win', m.home], ['Draw', m.draw], [short(names[1]) + ' Win', m.away]].sort(function (x, y) { return y[1] - x[1]; })[0];
    // the FT score agrees with every call below: the result, over / under 2.5 and both teams to score
    var over = o25 >= 0.5, btts = m.btts >= 0.5;
    var ft = likelyScore(m.lh, m.la, 0, 0, [
      function (h, w) { return (h + w > 2.5) === over; },
      function (h, w) { return (h > 0 && w > 0) === btts; }]);
    var ht = htOnTheWay(ft.h, ft.a, 0, 0, FIRST_HALF_SHARE);   // the half-time score on the way to it
    var resKey = result[1] === m.home ? 'home' : result[1] === m.away ? 'away' : 'draw';
    var scoreHow = function (title, line) {
      return function () {
        explain(title, function (box) {
          step(box, 1, 'Expected goals', ['Our view: ' + short(names[0]) + ' ' + m.lh.toFixed(2) + ' – ' + m.la.toFixed(2) + ' ' + short(names[1]) +
            ' (fitted to our chances for the result and over / under 2.5; see Result).']);
          step(box, 2, 'The score', [line]);
        });
      };
    };
    var sum = [
      ['HT Score', short(names[0]) + ' ' + ht.h + ' – ' + ht.a + ' ' + short(names[1]), scoreHow('HT Score',
        'The likeliest half-time score on the way to the FT score: each goal comes before the break about ' +
        Math.round(FIRST_HALF_SHARE * 100) + '% of the time, so we take each side\'s likeliest split.')],
      ['FT Score', short(names[0]) + ' ' + ft.h + ' – ' + ft.a + ' ' + short(names[1]), scoreHow('FT Score',
        'The likeliest scoreline (Poisson on those expected goals) that agrees with every call on the card: ' + result[0] + ', ' +
        (over ? 'over' : 'under') + ' 2.5 goals and both teams to score: ' + (btts ? 'yes' : 'no') + '. It has a ' + pcx(ft.p) + ' chance on its own.')],
      ['Result', result[0] + ' (' + pc(result[1]) + ')', function () { explainTeamLine(a, resKey, result[0], pc(result[1])); }],
      ['Goals', (o25 >= 0.5 ? 'Over 2.5' : 'Under 2.5') + ' (' + pc(Math.max(o25, 1 - o25)) + ')',
        function () { explainTeamLine(a, o25 >= 0.5 ? 'over' : 'under', o25 >= 0.5 ? 'Over 2.5 goals' : 'Under 2.5 goals', pc(Math.max(o25, 1 - o25))); }],
      ['Both teams to score', (m.btts >= 0.5 ? 'Yes' : 'No') + ' (' + pc(Math.max(m.btts, 1 - m.btts)) + ')',
        function () { explainTeamLine(a, m.btts >= 0.5 ? 'yes' : 'no', 'Both teams to score: ' + (m.btts >= 0.5 ? 'yes' : 'no'), pc(Math.max(m.btts, 1 - m.btts))); }]
    ];
    // most cards / fouls: each team's season rate from its squad
    var teamRate = function (side, key) {
      var v = 0, games = 0;
      ((ts[side] || {}).squad || []).forEach(function (p) {
        var st = p.stats || {}; v += parseFloat(st[key]) || 0; games = Math.max(games, parseFloat(st.matchPlayed) || 0);
      });
      return games >= 3 && v > 0 ? v / games : null;
    };
    // the chance that side gets more than the other in this match (Poisson on each side's rate a game; level
    // is possible, so the two don't add up to 100%). The rates are in Discipline Profile.
    var more = function (l1, l2) {
      var p = 0, c1 = 0;
      for (var x = 0; x <= 40; x++) { c1 += x > 0 ? pois(x - 1, l2) : 0; p += pois(x, l1) * c1; }
      return p;
    };
    var most = function (label, key) {
      var h = teamRate('home', key), w = teamRate('away', key);
      if (h === null || w === null) return;
      var home = h >= w;
      var p = home ? more(h, w) : more(w, h);
      sum.push([label, short(home ? names[0] : names[1]) + ' (' + pc(p) + ')', function () {
        explain(label, function (box) {
          step(box, 1, 'Each team\'s season', [short(names[0]) + ': ' + h.toFixed(2) + ' a game; ' + short(names[1]) + ': ' + w.toFixed(2) +
            ' a game (from the squads\' player stats; GOAL API misses some players, so a guide).']);
          step(box, 2, 'This match', ['Treating each side\'s count as a Poisson with those averages, ' + short(home ? names[0] : names[1]) +
            ' gets more than ' + short(home ? names[1] : names[0]) + ' ' + pcx(p) + ' of the time (the rest: the same, or fewer).']);
        });
      }]);
    };
    most('Most cards', 'yellowCards');
    most('Most fouls', 'foulsCommitted');
    // the top 3 of each, one per line
    var C = ValueCheck.CONSTANTS;
    var top3 = function (list, how) { return list.slice(0, 3).map(function (x) { return { player: x.name, text: '(' + pc(x.chance) + ')', how: function () { how(x); } }; }); };
    var scorers = top3(ValueCheck.scorerChances(m), function (x) {
      explain(x.name + ' to score', function (box) {
        var typical = C.POSITION_RATE[x.position] !== undefined ? C.POSITION_RATE[x.position] : C.UNKNOWN_RATE;
        step(box, 1, 'His season', [
          x.goals + ' goals in ' + x.games + ' games' + (x.pens ? ' (' + x.pens + ' penalties, taken out: they don\'t come at a steady rate)' : '') + '.',
          'A typical ' + (x.position || 'player').toLowerCase() + ' scores ' + typical + ' a game: counted as ' + C.PRIOR_GAMES +
            ' extra games, so a hot or cold start doesn\'t count for too much.',
          x.shots > 0 && x.shotGames > 0 ? x.shots + ' shots in ' + x.shotGames + ' games (each worth about ' + C.SHOT_VALUE +
            ' goals), blended half and half with his goals.' : 'No shots recorded: goals only.',
          x.taker ? 'His team\'s penalty taker: +' + C.PENALTY_RATE + ' goals a game.' : null,
          'So he scores about ' + x.rate.toFixed(2) + ' goals a game.']);
        step(box, 2, 'This match', ['His team is expected to score ' + x.lam.toFixed(2) + ' here, against its usual ' + x.avg.toFixed(2) +
          ', so his rate becomes ' + x.x.toFixed(2) + '.', 'Chance of at least one goal: 1 − e^(−' + x.x.toFixed(2) + ') = ' + pcx(x.chance) + '.']);
      });
    }), booked = top3(perGame('yellowCards', 3), function (x) {
      explain(x.name + ' booked', function (box) {
        step(box, 1, 'His season', [x.rate.toFixed(2) + ' yellow cards a game.',
          'Chance of at least one booking: 1 − e^(−' + x.rate.toFixed(2) + ') = ' + pcx(x.chance) + ' (if he plays the whole game).']);
      });
    });
    if (scorers.length) sum.push(['Likely scorers', scorers]);
    if (booked.length) sum.push(['Likely booked', booked]);
    predictionSummary(card, sum);
    function short(n) { return shortTeam(n); }
  }

  /**
   * Discipline Profile: each team's season so far a game, side by side like Goals Profile: fouls, yellow cards,
   * red cards, and penalties given away, from the squads' player stats (Refresh Stats). Prediction's Most cards and
   * Most fouls come from the same numbers.
   */
  function discipline() {
    var box = $('discipline'), ts = window.TopScorers || {};
    if (!ts.home || !ts.away) return;   // wait for both teams
    box.replaceChildren();
    var rate = function (side, key) {
      var v = 0, games = 0, any = false;
      ((ts[side] || {}).squad || []).forEach(function (pl) {
        var st = pl.stats || {}, x = parseFloat(st[key]);
        if (!isNaN(x)) { v += x; any = true; }
        games = Math.max(games, parseFloat(st.matchPlayed) || 0);
      });
      return games >= 3 && any ? v / games : null;
    };
    var rows = [['Fouls', 'foulsCommitted', 1], ['Yellow cards', 'yellowCards', 1], ['Red cards', 'redCards', 2],
                ['Penalties given away', 'penCommitted', 2]].map(function (d) {
      return { label: d[0], home: rate('home', d[1]), away: rate('away', d[1]), dp: d[2] };
    }).filter(function (r) { return r.home !== null || r.away !== null; });
    if (!rows.length) { box.appendChild(el('span', 'text-muted', 'No player stats yet: run Refresh Stats on the Football page.')); return; }
    var t = el('table', 'table table-sm align-middle mb-0 stats-table profile-table');
    var hr = el('tr');
    hr.appendChild(el('th', 'text-start text-nowrap', shortTeam(ev.home_team)));
    hr.appendChild(el('th', 'text-center text-muted fw-normal', 'A game'));
    hr.appendChild(el('th', 'text-end text-nowrap', shortTeam(ev.away_team)));
    var th = el('thead'); th.appendChild(hr); t.appendChild(th);
    var tb = el('tbody');
    rows.forEach(function (r) {
      var tr = el('tr');
      var cell = function (v, other, cls) {   // the higher number in bold, like Goals Profile
        var td = el('td', cls + (v !== null && other !== null && v > other ? ' fw-bold' : ''), v === null ? '–' : v.toFixed(r.dp));
        return td;
      };
      tr.appendChild(cell(r.home, r.away, 'text-start'));
      tr.appendChild(el('td', 'text-center text-muted', r.label));   // like Goals Profile's labels
      tr.appendChild(cell(r.away, r.home, 'text-end'));
      tb.appendChild(tr);
    });
    // Predicted Cards: each team's yellow + red cards a game (like Predicted Goals), the match total on hover
    var cards = function (side) {
      var y = rate(side, 'yellowCards'), red = rate(side, 'redCards');
      return y === null ? null : y + (red || 0);
    };
    var ch = cards('home'), ca = cards('away');
    if (ch !== null && ca !== null) {
      var tr = el('tr', 'fw-bold');
      var label = el('td', 'text-center', 'Predicted Cards');
      label.setAttribute('data-bs-toggle', 'tooltip');
      label.setAttribute('data-bs-title', 'Cards each team is expected to get in this match (yellow and red cards a game ' +
        'this season): about ' + (ch + ca).toFixed(1) + ' in all.');
      label.style.cursor = 'help';
      tr.appendChild(el('td', 'text-start', ch.toFixed(1)));
      tr.appendChild(label);
      tr.appendChild(el('td', 'text-end', ca.toFixed(1)));
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    box.appendChild(t);
    if (window.bootstrap) box.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(function (n) { bootstrap.Tooltip.getOrCreateInstance(n); });
  }

  function showPrediction(a) {
    var tables = $('stats').querySelectorAll('table'), table = tables[tables.length - 1];
    var body = table && table.querySelector('tbody');
    if (!body) return;
    table.classList.add('profile-table');   // centred labels, like Discipline Profile
    ['predictedRow', 'shotsRow', 'injuredRow'].forEach(function (id) { var old = $(id); if (old) old.remove(); });
    var short = function (n) { return n.split(' ')[0]; };
    var inp = a.model.inputs || {}, hp = (inp.home || {}).profile, ap = (inp.away || {}).profile;
    // The season data the model also uses (squad stats from Refresh Stats): shots per game and who's injured
    function extraRow(id, text, tip, home, away) {
      var tr = el('tr');
      tr.id = id;
      var label = el('td', 'text-center', text);
      label.setAttribute('data-bs-toggle', 'tooltip');
      label.setAttribute('data-bs-title', tip);
      label.style.cursor = 'help';
      tr.appendChild(el('td', 'text-start', home));
      tr.appendChild(label);
      tr.appendChild(el('td', 'text-end', away));
      body.appendChild(tr);
    }
    if ((hp && hp.shotsPerGame) || (ap && ap.shotsPerGame)) {
      extraRow('shotsRow', 'Shots per game', 'Season so far, from the squad\'s player stats. Steadier than goals: the ' +
        'predicted goals use them half and half with recent goals.',
        hp && hp.shotsPerGame ? hp.shotsPerGame.toFixed(1) : '–', ap && ap.shotsPerGame ? ap.shotsPerGame.toFixed(1) : '–');
    }
    var inj = function (p) {
      if (!p || !p.injured.length) return '–';
      return p.injured.length + ' (−' + Math.round(100 * (1 - p.attackFactor)) + '%)';
    };
    if ((hp && hp.injured.length) || (ap && ap.injured.length)) {
      extraRow('injuredRow', 'Injured', 'Players GOAL API lists as injured, and how much that lowers the predicted goals ' +
        '(their share of the team\'s goals and shots). ' + [hp, ap].map(function (p, i) {
          return p && p.injured.length ? short(i ? ev.away_team : ev.home_team) + ': ' + p.injured.join(', ') : null;
        }).filter(Boolean).join('. '), inj(hp), inj(ap));
    }
    var tr = el('tr', 'fw-bold');
    tr.id = 'predictedRow';
    var label = el('td', 'text-center', 'Predicted Goals');
    label.setAttribute('data-bs-toggle', 'tooltip');
    var used = ['goals scored and conceded in recent home games (' + short(ev.home_team) + ') and away games (' + short(ev.away_team) + ')'];
    if ((hp && hp.shotGoals) || (ap && ap.shotGoals)) used.push('season shots per game');
    if ((hp && hp.keeperGoals) || (ap && ap.keeperGoals)) used.push('the keepers\' shots faced');
    if ((hp && hp.injured.length) || (ap && ap.injured.length)) used.push('injured players');
    label.setAttribute('data-bs-title', 'Goals each team is expected to score in this match: our view, the bookmaker\'s ' +
      'odds nudged towards our stats (' + used.join(', ').replace(/, ([^,]*)$/, ' and $1') + '), the same as the ' +
      'Prediction, Markets and Recommended Bets. Stats alone say ' + a.model.lh.toFixed(1) + ' – ' + a.model.la.toFixed(1) +
      '. Confidence: ' + a.level.name + ' (' + a.games + ' games each).');
    label.style.cursor = 'help';
    // home | label | away, like the other rows
    tr.appendChild(el('td', 'text-start', (a.view || a.model).lh.toFixed(1)));
    tr.appendChild(label);
    tr.appendChild(el('td', 'text-end', (a.view || a.model).la.toFixed(1)));
    body.appendChild(tr);
    // the better side of each row in bold (like Discipline Profile): more is better, except conceding / failing to score
    var BETTER = { 'Avg scored': 1, 'Avg conceded': -1, 'Over 2.5 goals': 1, 'Both teams scored': 1, 'Clean sheets': 1,
                   'Failed to score': -1, 'Leading at half-time': 1, 'Shots per game': 1 };
    body.querySelectorAll('tr').forEach(function (tr) {
      var c = tr.children;
      if (c.length !== 3) return;
      var dir = BETTER[c[1].textContent.trim()];
      c[0].classList.remove('fw-bold'); c[2].classList.remove('fw-bold');
      if (!dir) return;
      var h = parseFloat(c[0].textContent), w = parseFloat(c[2].textContent);
      if (isNaN(h) || isNaN(w) || h === w) return;
      c[(h - w) * dir > 0 ? 0 : 2].classList.add('fw-bold');
    });
  }

  document.getElementById('navStake').appendChild(F.stakeInput(function () { draw(); }));

  var liveTimer = null;

  /**
   * A thin bar along the bottom of the top banner while a followed game is live: it fills from the server's last
   * refresh to the next one (Refresh Data Live), then starts again when the new data arrives.
   */
  var refreshBar = null, refreshTick = null, lastAt = 0, everyMs = 60000;
  function showRefreshBar(state, on) {
    if (!on || !state.lastRefresh || state.refreshMinutes === 0) {   // 0: Refresh Data Live is Off
      if (refreshBar) refreshBar.classList.add('d-none');
      return;
    }
    if (!refreshBar) {
      refreshBar = el('div', 'live-refresh-bar');
      refreshBar.appendChild(el('div'));
      document.querySelector('.site-bar').appendChild(refreshBar);
    }
    refreshBar.classList.remove('d-none');
    lastAt = new Date(state.lastRefresh).getTime();
    everyMs = (state.refreshMinutes || 1) * 60000;
    if (!refreshTick) {
      var waitingSince = 0;
      refreshTick = setInterval(function () {
        var left = Math.max(0, lastAt + everyMs - Date.now());
        refreshBar.firstChild.style.width = (100 * (1 - left / everyMs)) + '%';
        refreshBar.title = left > 0 ? 'Next refresh in ' + Math.ceil(left / 1000) + 's' : 'Refreshing…';
        // full: the server is due to refresh, so look for the new data every 5 seconds instead of waiting for the
        // next 30-second re-read (it starts again from empty once it's here)
        if (left > 0) { waitingSince = 0; return; }
        if (!waitingSince) waitingSince = Date.now();
        if (Date.now() - waitingSince >= 5000) { waitingSince = Date.now(); showLive(); }
      }, 500);
    }
  }
  var highlightsAsked = false;
  /** Full time: the game's highlights video if GOAL API has one (asked once per page; the server checks at most every 6h). */
  function showHighlights(gameId) {
    if (highlightsAsked || !gameId) return;
    highlightsAsked = true;
    F.getJson('/api/football/live/match/' + encodeURIComponent(gameId) + '/highlights').then(function (v) {
      // only YouTube's embed player (what GOAL API sends)
      if (!v || !/^https:\/\/www\.youtube(-nocookie)?\.com\/embed\/[\w-]+/.test(v.url || '')) return;
      var frame = document.createElement('iframe');
      frame.src = v.url;
      frame.title = (v.title || 'Highlights') + ': ' + ev.home_team + ' v ' + ev.away_team;
      frame.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture';
      frame.allowFullscreen = true;
      frame.loading = 'lazy';
      frame.referrerPolicy = 'strict-origin-when-cross-origin';
      $('highlights').replaceChildren(frame);
      $('highlightsCard').classList.remove('d-none');
    }).catch(function () { /* no video: the card stays hidden */ });
  }
  /** The saved in-play odds (no API call): redraws Recommended Bets and Markets when a new point's odds have arrived. */
  var oddsRetry = null;
  /**
   * wanted: the points the timeline has (snapshots saved). A window's odds are fetched just after its snapshot, so
   * while one is missing, look again every 4 seconds (up to a minute) rather than waiting for the next 30-second read.
   */
  function rereadLiveOdds(wanted, tries) {
    F.getJson('/api/odds/cache/events/' + encodeURIComponent(ev.id) + '/live?bookmaker=' + F.bookmaker()).then(function (o) {
      var points = o.points || {};
      var missing = (wanted || []).some(function (k) { return !points[k]; });
      clearTimeout(oddsRetry);
      if (missing && (tries || 0) < 15) oddsRetry = setTimeout(function () { rereadLiveOdds(wanted, (tries || 0) + 1); }, 4000);
      var mine = (liveOdds || {}).LIVE;   // the hand-fetched odds stay (they aren't saved on the server)
      if (Object.keys(points).join() !== Object.keys(liveOdds || {}).filter(function (k) { return k !== 'LIVE'; }).join()) {
        liveOdds = points; if (mine) liveOdds.LIVE = mine; draw();
      }
      // a bet window's odds just came in: a chime and a note (not for the ones there when the page opened)
      if (window.GoalAlert) GoalAlert.checkPoints(Object.keys(points)).forEach(function (k) {
        var name = /^\d+$/.test(k) ? k + '\'' : k;
        GoalAlert.windowOpen('🔔 ' + name + ' bet window: new odds are in, Recommended Bets updated');
      });
    }).catch(function () { /* keep what we have */ });
  }
  function showLive() {
    Live.setTimelineHost($('timeline'));
    Live.setHosts({ checklist: $('checklist'), players: $('players') });   // their own cards   // the match points sit under the score, as the page's navigation
    Live.getState().then(function (state) {
      return Live.renderCard($('liveStats'), $('liveNote'), state, ev).then(function (m) {
        // Re-read often (no API calls: the server refreshes followed games at the Refresh Data Live rate) while a followed
        // game isn't finished, or a game waiting to be listed kicks off within 3 hours
        var soon = new Date(ev.commence_time) - Date.now() < 3 * 3600000;
        var on = m ? m.fixture && !Live.finished(m.fixture) : !!Live.followingFor(state, ev) && soon;
        // every 30 seconds (half the fastest Refresh Data Live), so the page is never far behind the server's last refresh
        if (on && !liveTimer) liveTimer = setInterval(showLive, Math.min(30000, (state.refreshMinutes || 1) * 30000));
        if (!on && liveTimer) { clearInterval(liveTimer); liveTimer = null; }
        // the in-play odds saved at each point (60', 80'...) without reloading; a window's odds a few seconds after it
        var saved = ((m && m.snapshots) || []).map(function (x) { return String(x.period); })
          .filter(function (k) { return ['30', 'HT', '60', '80'].indexOf(k) >= 0; });
        if (on) rereadLiveOdds(saved.slice(-1));
        liveMatch = m || null;   // the score, events and stats: which predictions came true
        liveSnaps = ((m && m.snapshots) || []).map(function (x) {   // for the prediction log's bet windows
          return { period: String(x.period), rows: x.rows, xg: x.xg, homeScore: x.homeScore, awayScore: x.awayScore };
        });
        showRefreshBar(state, on && m && m.fixture && Live.isLive(m.fixture));
        if (m && m.fixture && Live.finished(m.fixture)) showHighlights(m.fixture.id);
      });
    }).catch(function (e) {
      ['liveStats', 'checklist', 'players'].forEach(function (id) { $(id).textContent = 'Live Stats unavailable: ' + e.message; });
    });
  }

  F.loadAll().then(function (data) {
    ev = data.matches.find(function (m) { return m.id === matchId; });
    if (!ev) { $('notFound').classList.remove('d-none'); $('header').classList.add('d-none'); return; }
    document.title = ev.home_team + ' v ' + ev.away_team + ' · Sports Betting Assistant';
    $('meta').textContent = ev.comp;
    $('teams').textContent = ev.home_team + ' v ' + ev.away_team;
    showScore('', countdown(ev.commence_time));   // FT / HT / minute as the coloured label
    if (window.GoalAlert && new Date(ev.commence_time) > new Date()) GoalAlert.checkStatus(null);   // open before kick-off: KICK-OFF will show
    F.loadTeams(ev.compKey).then(function (teams) {
      teamsCache = teams;
      $('homeLogo').replaceChildren(F.logo(teams, ev.home_team, 56, ev.home_team_id));
      $('awayLogo').replaceChildren(F.logo(teams, ev.away_team, 56, ev.away_team_id));
      var ht = F.findTeam(teams, ev.home_team, ev.home_team_id), at = F.findTeam(teams, ev.away_team, ev.away_team_id);
      teamIds = [ht && ht.id, at && at.id];   // for the prediction log
      noStats = !ht || !at || ht.id == null || at.id == null;
      MatchStats.render($('stats'), ev, ht, at);
      if (noStats) {
        ['topScorers', 'topFoulers', 'topDefenders', 'discipline'].forEach(function (id) {
          $(id).replaceChildren(el('span', 'text-muted', 'No data available for this game.'));
        });
        draw();
      }
      // the live cards name the teams like Form and Goals Profile do (e.g. "Tottenham")
      shortNames = [ht && (ht.shortName || ht.name), at && (at.shortName || at.name)].filter(Boolean);
      Live.setTeamNames([ht && (ht.shortName || ht.name), at && (at.shortName || at.name)]);
      topScorers();
      showLive();
    });
    draw();
    showLive();
    // in-play prices fetched earlier (no API call)
    F.getJson('/api/odds/cache/events/' + encodeURIComponent(ev.id) + '/live?bookmaker=' + F.bookmaker())
      .then(function (o) { liveOdds = o.points || {}; draw(); }).catch(function () {});
  }).catch(function (e) { $('meta').textContent = 'Could not load: ' + e.message; });
})();
