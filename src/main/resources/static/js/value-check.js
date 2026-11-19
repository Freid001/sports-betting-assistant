// Value check: compares the bookmaker's odds (the one picked in Refresh Odds) with our own chances, following the
// Betting Framework Spec. Our estimate = the bookmaker's fair (margin-free) chance, nudged towards a goals model
// by at most MAX_NUDGE points.
//   Average result = stake x (chance x decimal odds - 1), using the Stake box (or £5 if empty).
//   Labels use the framework's thresholds as a share of the stake (per £5: +25p = +5%, -£1 = -20%):
//   ✅ Value >= +5% · ➖ Fair between -5% and +5% · 🟡 Small Cost: chance >= 35%, confidence Medium/High, >= -20% · ❌ otherwise.
window.ValueCheck = (function () {
  var F = window.Football, el = F.el;

  // How far the stats may move Sky Bet's chance (framework: 1-5 points); less when there's little data.
  var MAX_NUDGE = { 'Very Low': 0.02, 'Low': 0.03, 'Medium': 0.05, 'High': 0.05, 'Very High': 0.05 };
  // Confidence = how much data the estimate rests on (home games for the home team, away games for the away team).
  var CONFIDENCE = [
    { min: 20, name: 'Very High', cls: 'odds-very-good' },
    { min: 12, name: 'High',      cls: 'odds-good' },
    { min: 8,  name: 'Medium',    cls: 'odds-ok' },
    { min: 4,  name: 'Low',       cls: 'odds-poor' },
    { min: 0,  name: 'Very Low',  cls: 'odds-very-poor' }
  ];
  var MODEL_WEIGHT = 0.5;   // move half-way towards the model, then cap at MAX_NUDGE
  var MAX_RELATIVE = 0.2;   // ...and at 20% of Sky Bet's own chance (e.g. 6% can move to 4.8-7.2% at most)
  var DEFAULT_STAKE = 5;    // used when the Stake box is empty
  var last = null;          // last render arguments, so a stake change can redraw

  function mean(arr, f) { return arr.length ? arr.reduce(function (t, v) { return t + f(v); }, 0) / arr.length : null; }
  function pois(k, l) { var p = Math.exp(-l); for (var i = 1; i <= k; i++) p *= l / i; return p; }

  /**
   * Goals model (Poisson). Expected goals:
   *   home = average of (home team's home scoring, away team's away conceding), and the reverse for away.
   */
  function model(homeViews, awayViews) {
    var hs = mean(homeViews, function (v) { return v.gf; }), hc = mean(homeViews, function (v) { return v.ga; });
    var as = mean(awayViews, function (v) { return v.gf; }), ac = mean(awayViews, function (v) { return v.ga; });
    if (hs === null || as === null) return null;
    var lh = Math.max(0.2, (hs + ac) / 2), la = Math.max(0.2, (as + hc) / 2);
    var home = 0, draw = 0, away = 0, under25 = 0, h0 = pois(0, lh), a0 = pois(0, la);
    for (var i = 0; i <= 10; i++) {
      for (var j = 0; j <= 10; j++) {
        var p = pois(i, lh) * pois(j, la);
        if (i > j) home += p; else if (i === j) draw += p; else away += p;
        if (i + j <= 2) under25 += p;
      }
    }
    var tot = home + draw + away;
    return { lh: lh, la: la, hs: hs, as: as, home: home / tot, draw: draw / tot, away: away / tot,
             over25: 1 - under25, btts: (1 - h0) * (1 - a0) };
  }

  /**
   * Verdict on the PRICE. ret = average return as a share of the stake (e.g. +0.05 = +5%).
   * Great Value >= +15% · Good Value +5..15% (only "Possible Value" when confidence is Low/Very Low) · Fair Price within ±5% ·
   * Small Cost: overpriced but likely (>= 35%), confidence not Low, no worse than -20% · Poor Price otherwise.
   */
  function label(ret, chance, confidence) {
    var thin = confidence === 'Low' || confidence === 'Very Low';
    // With thin data the goals model leans towards underdogs, so don't claim value outright.
    // cls = badge colours: greens for value, yellow for fair, orange for small cost, red for poor
    if (thin && ret >= 0.05) return { text: 'Possible Value', cls: 'verdict-possible' };
    if (ret >= 0.15) return { text: 'Great Value', cls: 'odds-very-good' };
    if (ret >= 0.05) return { text: 'Good Value', cls: 'odds-good' };
    if (ret > -0.05) return { text: 'Fair Price', cls: 'odds-ok' };
    if (chance >= 0.35 && confidence !== 'Low' && confidence !== 'Very Low' && ret >= -0.20) return { text: 'Small Cost', cls: 'verdict-small-cost' };
    return { text: 'Poor Price', cls: 'odds-poor' };
  }

  /** How LIKELY it is, separately from the price. */
  function chanceTag(chance) {
    if (chance >= 0.75) return 'Very Likely';   // 75%+
    if (chance >= 0.5) return 'Likely';      // 50-74%
    if (chance >= 0.35) return 'Toss Up';    // 35-49%
    if (chance >= 0.2) return 'Outsider';    // 20-34%
    if (chance >= 0.1) return 'Long Shot';   // 10-19%
    return 'Moonshot';                       // under 10%
  }

  var TAG_RANGES = { 'Very Likely': '75%+', 'Likely': '50-74%', 'Toss Up': '35-49%', 'Outsider': '20-34%', 'Long Shot': '10-19%', 'Moonshot': 'under 10%' };

  /** Chance tag badge, e.g. "Likely", with our calculated chance on hover, e.g. "58.3%" (the range if no chance given). */
  function tagBadge(tag, chance) {
    var b = el('span', 'badge rounded-pill chance-tag ' + tagClass(tag), tag);
    b.setAttribute('data-bs-toggle', 'tooltip');
    b.setAttribute('data-bs-title', chance !== undefined ? (chance * 100).toFixed(1) + '%' : TAG_RANGES[tag]);
    b.style.cursor = 'help';
    return b;
  }

  /** Tooltip on one element (shown on hover, above it). */
  function tip(node, text) {
    node.setAttribute('data-bs-toggle', 'tooltip');
    node.setAttribute('data-bs-title', text);
    node.style.cursor = 'help';
  }

  function tagClass(tag) {
    return { 'Very Likely': 'chance-verylikely', 'Likely': 'chance-likely', 'Toss Up': 'chance-tossup', 'Outsider': 'chance-outsider',
             'Long Shot': 'chance-longshot', 'Moonshot': 'chance-moonshot' }[tag] || '';
  }

  function pct(p) { return Math.round(p * 100) + '%'; }
  function money(v) { return (v >= 0 ? '+' : '−') + (Math.abs(v) < 1 ? Math.round(Math.abs(v) * 100) + 'p' : '£' + Math.abs(v).toFixed(2)); }

  /**
   * The numbers behind the Value check, without drawing anything (used by the match page and the list).
   * Returns null if there aren't enough results.
   */
  function analyse(ev, homeViews, awayViews, stake) {
    var m = model(homeViews, awayViews);
    if (!m) return null;
    var games = Math.min(homeViews.length, awayViews.length);
    var level = CONFIDENCE.find(function (c) { return games >= c.min; });
    var cap = MAX_NUDGE[level.name];
    var out = { model: m, games: games, level: level, rows: [] };
    var sky = F.pickBook(ev);
    var extra = assessMarkets(ev, sky, m, cap, level.name, stake);
    out.markets = extra.markets;    // BTTS, goals lines, first-half result... (each outcome reviewed)
    out.skipped = extra.skipped;    // priced but not checked (corners, cards, correct score, players)
    var h2h = sky && sky.markets.find(function (x) { return x.key === 'h2h'; });
    if (!h2h) return out;
    var names = [ev.home_team, 'Draw', ev.away_team];
    var prices = names.map(function (n) { var o = h2h.outcomes.find(function (x) { return x.name === n; }); return o ? o.price : null; });
    if (!prices.every(Boolean)) return out;
    out.rows = assess(names, prices, [m.home, m.draw, m.away], cap, level.name, stake);
    return out;
  }

  /**
   * One group of outcomes that add up to 100% (a match result, one over/under line...): the bookmaker's fair
   * chance nudged towards the model, then the price verdict and chance tag for each.
   */
  function assess(names, prices, mod, cap, levelName, stake) {
    var fair = F.fairChances(prices).chances;
    var ours = nudge(fair, mod, cap);
    return names.map(function (n, i) {
      var ret = ours[i] * prices[i] - 1;
      return { name: n, price: prices[i], fair: fair[i], stats: mod[i], ours: ours[i], ret: ret,
               avg: stake * ret, verdict: label(ret, ours[i], levelName), tag: chanceTag(ours[i]) };
    });
  }

  /**
   * Move the fair chances towards the model. Never by more than a fifth of a chance (or MAX_NUDGE): with little
   * data the model drifts towards "anything can happen", and on long shots even a small nudge looks like a big
   * edge. Then make them add up to 100% again without breaking those limits: re-scale, re-cap, repeat.
   */
  function nudge(fair, mod, cap) {
    var limit = fair.map(function (f) { return Math.min(cap, f * MAX_RELATIVE); });
    var clamp = function (p, i) { return Math.max(fair[i] - limit[i], Math.min(fair[i] + limit[i], p)); };
    var ours = fair.map(function (f, i) { return clamp(f + MODEL_WEIGHT * (mod[i] - f), i); });
    for (var k = 0; k < 50; k++) {
      var sum = ours.reduce(function (x, y) { return x + y; }, 0);
      if (Math.abs(sum - 1) < 1e-12) break;
      ours = ours.map(function (p, i) { return clamp(p / sum, i); });
    }
    return ours;
  }

  // ---- The other markets: the model's chance for each outcome ----

  var FIRST_HALF = 0.45;   // share of goals scored before half-time (about 45% in top leagues)

  /** Chance of a scoreline condition, goals for each side ~ Poisson. */
  function grid(lh, la, f) {
    var p = 0;
    for (var i = 0; i <= 10; i++) for (var j = 0; j <= 10; j++) if (f(i, j)) p += pois(i, lh) * pois(j, la);
    return p;
  }
  function result(lh, la) {
    return { H: grid(lh, la, function (i, j) { return i > j; }), D: grid(lh, la, function (i, j) { return i === j; }),
             A: grid(lh, la, function (i, j) { return i < j; }) };
  }
  /** Half-time and full-time result together (first and second half goals are independent). */
  function htft(lh, la, ht, ft) {
    var h1 = lh * FIRST_HALF, a1 = la * FIRST_HALF, h2 = lh - h1, a2 = la - a1, p = 0;
    var side = function (x, y) { return x > y ? 'H' : x === y ? 'D' : 'A'; };
    for (var i = 0; i <= 6; i++) for (var j = 0; j <= 6; j++) {
      if (side(i, j) !== ht) continue;
      var first = pois(i, h1) * pois(j, a1);
      for (var k = 0; k <= 6; k++) for (var l = 0; l <= 6; l++) {
        if (side(i + k, j + l) === ft) p += first * pois(k, h2) * pois(l, a2);
      }
    }
    return p;
  }

  /** Markets the Value check doesn't price, and why (framework §8, or no model yet). */
  var NOT_CHECKED = {
    player_shots_on_target: 'model', player_shots_on_target_alternate: 'model', player_to_score_or_assist: 'model',
    alternate_totals_corners: 'model', alternate_totals_cards: 'model', double_chance: 'model'
  };

  /** Every player of either team in the scorer tables (from the Top Scorers card): [{name, side, goals, games, position}]. */
  function scorers() {
    var ts = window.TopScorers || {}, out = [];
    ['home', 'away'].forEach(function (side) {
      ((ts[side] || {}).scorers || []).forEach(function (p) {
        out.push({ name: p.name, side: side, goals: p.goals, games: p.games, position: p.position });
      });
    });
    return out;
  }

  /**
   * A player's goals per game, allowing for his position and how few games the season has had so far: his own
   * record blended with a typical rate for his position, worth PRIOR_GAMES games. Early in the season the typical
   * rate counts for most (2 goals in 3 games is mostly luck); after 20+ games his own record does. So a defender
   * with an early 2-goal burst isn't treated like a forward, and a striker's slow start isn't held against him.
   */
  var POSITION_RATE = { Forward: 0.35, Midfielder: 0.12, Defender: 0.04, Goalkeeper: 0.005 };
  var UNKNOWN_RATE = 0.12;   // position not known
  var PRIOR_GAMES = 10;
  function goalRate(p) {
    var typical = POSITION_RATE[p.position] !== undefined ? POSITION_RATE[p.position] : UNKNOWN_RATE;
    return (p.goals + PRIOR_GAMES * typical) / (p.games + PRIOR_GAMES);
  }

  /**
   * Every other market the bookmaker priced: [{key, rows, show}] with every outcome reviewed (rows as in assess()).
   * show = how many rows the card shows before "Show all": over/under the most balanced line (both sides),
   * half-time/full-time the 3 most likely; the rest go in the pop-up.
   */
  function assessMarkets(ev, book, m, cap, levelName, stake) {
    var out = [], skipped = [];
    var side = function (name) { return name === ev.home_team ? 'H' : name === ev.away_team ? 'A' : name === 'Draw' ? 'D' : null; };
    (book ? book.markets : []).forEach(function (mk) {
      if (mk.key === 'h2h' || mk.key === 'h2h_3_way') return;   // the Match Result section
      var rows = [], show = null;
      var outs = mk.outcomes;
      if (mk.key === 'btts') {
        var yes = outs.find(function (o) { return o.name === 'Yes'; }), no = outs.find(function (o) { return o.name === 'No'; });
        if (yes && no) rows = assess(['Yes', 'No'], [yes.price, no.price], [m.btts, 1 - m.btts], cap, levelName, stake);
      } else if (mk.key === 'draw_no_bet' || mk.key === 'h2h_3_way_h1') {
        var half = mk.key === 'h2h_3_way_h1';
        var r = half ? result(m.lh * FIRST_HALF, m.la * FIRST_HALF) : null;
        var sides = half ? ['H', 'D', 'A'] : ['H', 'A'];
        var picked = sides.map(function (s) { return outs.find(function (o) { return side(o.name) === s; }); });
        if (picked.every(Boolean)) {
          var mod = half ? sides.map(function (s) { return r[s]; })
            : [m.home / (m.home + m.away), m.away / (m.home + m.away)];
          rows = assess(picked.map(function (o) { return o.name; }), picked.map(function (o) { return o.price; }), mod, cap, levelName, stake);
        }
      } else if (mk.key === 'halftime_fulltime') {
        var parts = outs.map(function (o) { var p = o.name.split('/'); return [side(p[0]), side(p[1])]; });
        if (outs.length === 9 && parts.every(function (p) { return p[0] && p[1]; })) {
          rows = assess(outs.map(function (o) { return o.name.replace('/', ' / '); }), outs.map(function (o) { return o.price; }),
            parts.map(function (p) { return htft(m.lh, m.la, p[0], p[1]); }), cap, levelName, stake);
          rows.sort(function (a, b) { return a.price - b.price; });   // most likely first
          show = 3;
        }
      } else if (mk.key === 'player_goal_scorer_anytime' || mk.key === 'player_first_goal_scorer') {
        // Each player who has scored this season: goals per game (blended with his position's typical rate, see
        // goalRate), scaled to his team's expected goals in this match. Anytime: chance he scores at least once. First: his share of the goals x chance of any goal.
        // The bookmaker's margin: its chances should add up to the expected goals (anytime) or to the chance of
        // any goal (first), so they're scaled down to that.
        var first = mk.key === 'player_first_goal_scorer', total = m.lh + m.la, anyGoal = 1 - Math.exp(-total);
        var priced = outs.filter(function (o) { return o.description && o.price > 1; });
        var implied = priced.reduce(function (t, o) { return t + 1 / o.price; }, 0);
        var scale = implied ? Math.min(1, (first ? anyGoal : total) / implied) : 1;
        scorers().forEach(function (p) {
          var o = priced.find(function (x) { return F.samePlayer(p.name, x.description); });
          if (!o || !p.games) return;
          var lam = p.side === 'home' ? m.lh : m.la, avg = p.side === 'home' ? m.hs : m.as;
          var x = goalRate(p) * (avg > 0 ? lam / avg : 1);   // allows for position and early-season luck
          var mod = first ? (x / total) * anyGoal : 1 - Math.exp(-x);
          var fair = scale / o.price, limit = Math.min(cap, fair * MAX_RELATIVE);
          var ours = Math.max(fair - limit, Math.min(fair + limit, fair + MODEL_WEIGHT * (mod - fair)));
          var ret = ours * o.price - 1;
          rows.push({ name: p.name, price: o.price, fair: fair, stats: mod, ours: ours, ret: ret, avg: stake * ret,
                      verdict: label(ret, ours, levelName), tag: chanceTag(ours) });
        });
        rows.sort(function (a, b) { return b.ours - a.ours; });   // most likely first
        show = 5;
        if (!rows.length) { skipped.push(mk.key); return; }   // the scorer tables haven't loaded (or nobody matched)
      } else if (mk.key === 'correct_score') {
        // Scorelines straight from the goals model; "Away 2-1" style names are turned round to home-away
        var cs = outs.map(function (o) {
          var g = /(\d+)\s*[-:]\s*(\d+)/.exec(o.name);
          if (!g) return null;
          var a = +g[1], b = +g[2], away = o.name.indexOf(ev.away_team) >= 0 && o.name.indexOf(ev.home_team) < 0;
          return { o: o, h: away ? b : a, a: away ? a : b };
        }).filter(Boolean);
        if (cs.length) {
          var probs = cs.map(function (c) { return pois(c.h, m.lh) * pois(c.a, m.la); });
          var sum = probs.reduce(function (t, p) { return t + p; }, 0);
          rows = assess(cs.map(function (c) { return c.o.name; }), cs.map(function (c) { return c.o.price; }),
            probs.map(function (p) { return p / sum; }), cap, levelName, stake);
          rows.sort(function (a, b) { return a.price - b.price; });
          show = 3;
        }
      } else if (['alternate_totals', 'totals', 'alternate_team_totals', 'team_totals'].indexOf(mk.key) >= 0) {
        var team = mk.key.indexOf('team') >= 0, lines = {};
        outs.forEach(function (o) { var g = (o.description || '') + '|' + o.point; (lines[g] = lines[g] || {})[o.name] = o; });
        var keys = Object.keys(lines).filter(function (g) { return lines[g].Over && lines[g].Under; });
        var balance = function (g) { return Math.abs(Math.log(lines[g].Over.price / lines[g].Under.price)); };
        keys.sort(function (a, b) { return balance(a) - balance(b); });   // the main line (closest to 50/50) first
        show = 2;   // its Over and Under
        keys.forEach(function (g) {
          var o = lines[g].Over, u = lines[g].Under, pOver;
          if (team) {
            var lam = side(o.description) === 'H' ? m.lh : side(o.description) === 'A' ? m.la : null;
            if (lam === null) return;
            pOver = grid(lam, 0, function (i) { return i > o.point; });
          } else {
            pOver = grid(m.lh, m.la, function (i, j) { return i + j > o.point; });
          }
          var who = team ? o.description + ' ' : '';
          rows = rows.concat(assess([who + 'Over ' + o.point, who + 'Under ' + o.point], [o.price, u.price], [pOver, 1 - pOver], cap, levelName, stake));
        });
      } else {
        skipped.push(mk.key);
        return;
      }
      if (rows.length) out.push({ key: mk.key, rows: rows, show: show || rows.length, lines: mk.key.indexOf('totals') >= 0 });
    });
    return { markets: out, skipped: skipped };
  }

  /** Coloured confidence badge (same colours as the odds badges), with an explanation on hover. */
  function confidenceBadge(a) {
    var cb = el('span', 'badge rounded-pill ' + a.level.cls, a.level.name);
    cb.style.cursor = 'help';
    cb.setAttribute('data-bs-toggle', 'tooltip');
    cb.setAttribute('data-bs-title', 'Confidence: how much to trust these verdicts.\nBased on ' + a.games +
      ' home/away games per team (Very Low under 4, Low 4-7, Medium 8-11, High 12-19, Very High 20+).');
    return cb;
  }

  function render(box, ev, homeViews, awayViews) {
    last = [box, ev, homeViews, awayViews];
    var stake = F.stake() || DEFAULT_STAKE;
    box.replaceChildren();
    var a = analyse(ev, homeViews, awayViews, stake);
    if (!a) { box.appendChild(el('p', 'text-muted mb-0', 'Not enough results yet to estimate chances.')); return; }
    var m = a.model;
    // Confidence isn't shown: it limits how far the stats move Sky Bet's chances and turns
    // "Good Value" into "Possible Value" when data is thin, so the verdict already reflects it.

    /** Each outcome with its chance tag and price verdict. */
    function table(rows) {
      var t = el('table', 'table table-sm align-middle mb-0 stats-table');
      var tb = el('tbody');
      rows.forEach(function (r) {
        var tr = el('tr');
        tr.appendChild(el('td', null, r.name));
        var verdict = el('td', 'text-nowrap text-end');
        var tag = tagBadge(r.tag, r.ours);
        tag.classList.add('me-1');
        verdict.appendChild(tag);
        verdict.appendChild(el('span', 'badge rounded-pill ' + r.verdict.cls, r.verdict.text));
        tr.appendChild(verdict);
        tb.appendChild(tr);
      });
      t.appendChild(tb);
      return t;
    }

    /** One market: the first `show` rows on the card, everything in the "Show all" pop-up (like the odds card). */
    function section(title, rows, show, lines) {
      box.appendChild(el('div', 'section-label', title));
      box.appendChild(table(rows.slice(0, show)));
      var modal = document.getElementById('marketModal');
      if (rows.length > show && modal) {
        var more = el('a', 'small d-inline-block mt-1 link-secondary', 'Show all ' + (lines ? rows.length / 2 + ' lines' : rows.length));
        more.href = '#';
        more.addEventListener('click', function (e) {
          e.preventDefault();
          document.getElementById('marketModalTitle').textContent = title + ' · Value Check';
          var body = document.getElementById('marketModalBody');
          // lines in order (0.5, 1.5...) in the pop-up; other markets most likely first
          var all = lines ? rows.slice().sort(function (x, y) { return parseFloat(x.name.split(' ').pop()) - parseFloat(y.name.split(' ').pop()); }) : rows;
          body.replaceChildren(table(all));
          bootstrap.Modal.getOrCreateInstance(modal).show();
        });
        box.appendChild(more);
      }
    }

    /**
     * Goalscorer: one row per player, First and Anytime columns, each with its chance tag and price verdict side by side.
     * The 5 most likely (anytime) on the card, everyone checked in the "Show all" pop-up.
     */
    function scorerSection(first, any) {
      var cols = [first, any].filter(Boolean), heads = [first ? 'First' : null, any ? 'Anytime' : null].filter(Boolean);
      var names = [];
      [any, first].filter(Boolean).forEach(function (mk) {
        mk.rows.forEach(function (r) { if (names.indexOf(r.name) < 0) names.push(r.name); });
      });
      var find = function (mk, n) { return mk.rows.find(function (r) { return r.name === n; }); };
      function scorerTable(list) {
        var t = el('table', 'table table-sm align-middle mb-0 stats-table');
        var head = el('tr');   // bold, dark headers
        head.appendChild(el('th', 'fw-semibold', 'Player'));
        heads.forEach(function (h) { head.appendChild(el('th', 'fw-semibold', h)); });   // First / Anytime, left-aligned
        var thead = el('thead'); thead.appendChild(head); t.appendChild(thead);
        var tb = el('tbody');
        list.forEach(function (n) {
          var tr = el('tr');
          tr.appendChild(el('td', null, n));
          cols.forEach(function (mk) {
            var r = find(mk, n), td = el('td');
            if (r) {
              var stack = el('div', 'd-inline-flex align-items-center gap-1 text-nowrap');   // chance tag, then price verdict
              stack.append(tagBadge(r.tag, r.ours), el('span', 'badge rounded-pill ' + r.verdict.cls, r.verdict.text));
              td.appendChild(stack);
            } else {
              td.appendChild(el('span', 'text-muted', '–'));
            }
            tr.appendChild(td);
          });
          tb.appendChild(tr);
        });
        t.appendChild(tb);
        return t;
      }
      box.appendChild(el('div', 'section-label', 'Goalscorer'));
      box.appendChild(scorerTable(names.slice(0, 5)));
      var modal = document.getElementById('marketModal');
      if (names.length > 5 && modal) {
        var more = el('a', 'small d-inline-block mt-1 link-secondary', 'Show all ' + names.length + ' players');
        more.href = '#';
        more.addEventListener('click', function (e) {
          e.preventDefault();
          document.getElementById('marketModalTitle').textContent = 'Goalscorer · Value Check';
          document.getElementById('marketModalBody').replaceChildren(scorerTable(names));
          bootstrap.Modal.getOrCreateInstance(modal).show();
        });
        box.appendChild(more);
      }
    }

    if (a.rows.length) section('Match Result', a.rows, 3);
    else box.appendChild(el('p', 'text-muted', 'No ' + F.bookmakerName() + ' match odds saved for this game.'));
    var firstGs = a.markets.find(function (mk) { return mk.key === 'player_first_goal_scorer'; });
    var anyGs = a.markets.find(function (mk) { return mk.key === 'player_goal_scorer_anytime'; });
    var scorersDone = false;
    a.markets.forEach(function (mk) {
      if (mk === firstGs || mk === anyGs) {   // one Goalscorer table, where the first of them would be
        if (!scorersDone) scorerSection(firstGs, anyGs);
        scorersDone = true;
        return;
      }
      section(F.MARKET_NAMES[mk.key] || mk.key, mk.rows, mk.show, mk.lines);
    });
    if (!a.markets.length && a.rows.length) {
      box.appendChild(el('p', 'small text-muted mt-2 mb-0', 'Tick more markets in Refresh Odds to check them too.'));
    }
    if (a.skipped.length) {
      var names = a.skipped.map(function (k) { return F.MARKET_NAMES[k] || k; })
        .filter(function (n, i, all) { return all.indexOf(n) === i; }).join(', ');
      box.appendChild(el('p', 'small text-muted mt-2 mb-0', 'Not checked yet: ' + names + '.'));
    }

    box.appendChild(el('div', 'small text-muted mt-2',
      'Expected goals ' + m.lh.toFixed(1) + '–' + m.la.toFixed(1) + '. Verdicts judge the price, not the result. Not financial advice.'));
  }

  /**
   * "Team  [Toss Up] [Fair Price]" for one outcome row from analyse(), with the same tooltips as the match page.
   * withName: include the team name in front.
   */
  function pair(r, withName) {
    var wrap = el('span', 'd-inline-flex align-items-center gap-1 text-nowrap');
    if (withName) wrap.appendChild(el('span', 'fw-semibold me-1', r.name));
    if (r.tag) wrap.appendChild(tagBadge(r.tag, r.ours));   // the Football page's pick column
    var vb = el('span', 'badge rounded-pill ' + r.verdict.cls, r.verdict.text);
    wrap.appendChild(vb);
    return wrap;
  }

  /** Redraw with the current Stake box value (called when the stake changes). */
  function rerender() { if (last) render.apply(null, last); }

  return { render: render, rerender: rerender, model: model, analyse: analyse, confidenceBadge: confidenceBadge, pair: pair, goalRate: goalRate,
           DEFAULT_STAKE: DEFAULT_STAKE };
})();
