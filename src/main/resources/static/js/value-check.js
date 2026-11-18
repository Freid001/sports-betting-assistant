// Value check: compares Sky Bet's odds with our own chances, following the Betting Framework Spec.
//   Our estimate = Sky Bet's fair (margin-free) chance, nudged towards a goals model by at most MAX_NUDGE points.
//   Average result = stake x (chance x decimal odds - 1), using the Stake box (or £5 if empty).
//   Labels use the framework's thresholds as a share of the stake (per £5: +25p = +5%, -£1 = -20%):
//   ✅ Value >= +5% · ➖ Fair between -5% and +5% · 🟡 Small cost: chance >= 35%, confidence Medium/High, >= -20% · ❌ otherwise.
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
    return { lh: lh, la: la, home: home / tot, draw: draw / tot, away: away / tot,
             over25: 1 - under25, btts: (1 - h0) * (1 - a0) };
  }

  /**
   * Verdict on the PRICE. ret = average return as a share of the stake (e.g. +0.05 = +5%).
   * Great value >= +15% · Good value +5..15% (only "Possible value" when confidence is Low/Very Low) · Fair price within ±5% ·
   * Small cost: overpriced but likely (>= 35%), confidence not Low, no worse than -20% · Poor price otherwise.
   */
  function label(ret, chance, confidence) {
    var thin = confidence === 'Low' || confidence === 'Very Low';
    // With thin data the goals model leans towards underdogs, so don't claim value outright.
    // cls = badge colours: greens for value, yellow for fair, orange for small cost, red for poor
    if (thin && ret >= 0.05) return { text: 'Possible value', cls: 'bg-success-subtle text-success-emphasis border border-success-subtle' };
    if (ret >= 0.15) return { text: 'Great value', cls: 'odds-very-good' };
    if (ret >= 0.05) return { text: 'Good value', cls: 'odds-good' };
    if (ret > -0.05) return { text: 'Fair price', cls: 'odds-ok' };
    if (chance >= 0.35 && confidence !== 'Low' && confidence !== 'Very Low' && ret >= -0.20) return { text: 'Small cost', cls: 'verdict-small-cost' };
    return { text: 'Poor price', cls: 'odds-poor' };
  }

  /** How LIKELY it is, separately from the price. */
  function chanceTag(chance) {
    if (chance >= 0.5) return 'Likely';      // 50%+
    if (chance >= 0.35) return 'Toss-up';    // 35-49%
    if (chance >= 0.2) return 'Outsider';    // 20-34%
    if (chance >= 0.1) return 'Long shot';   // 10-19%
    return 'Moonshot';                       // under 10%
  }

  var TAG_RANGES = { 'Likely': '50% or more', 'Toss-up': '35-49%', 'Outsider': '20-34%', 'Long shot': '10-19%', 'Moonshot': 'under 10%' };

  /** Tooltip on one element (shown on hover, above it). */
  function tip(node, text) {
    node.setAttribute('data-bs-toggle', 'tooltip');
    node.setAttribute('data-bs-title', text);
    node.style.cursor = 'help';
  }

  function tagClass(tag) {
    return { 'Likely': 'chance-likely', 'Toss-up': 'chance-tossup', 'Outsider': 'chance-outsider',
             'Long shot': 'chance-longshot', 'Moonshot': 'chance-moonshot' }[tag] || '';
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
    var sky = ev.bookmakers.find(function (b) { return b.key === 'skybet'; }) || ev.bookmakers[0];
    var h2h = sky && sky.markets.find(function (x) { return x.key === 'h2h'; });
    if (!h2h) return out;
    var names = [ev.home_team, 'Draw', ev.away_team];
    var prices = names.map(function (n) { var o = h2h.outcomes.find(function (x) { return x.name === n; }); return o ? o.price : null; });
    if (!prices.every(Boolean)) return out;
    var fair = F.fairChances(prices).chances;
    var mod = [m.home, m.draw, m.away];
    var ours = fair.map(function (f, i) {
      // Never move a chance by more than a fifth of itself: with little data the model drifts towards
      // "anything can happen", and on long shots even a small nudge looks like a big edge.
      var c = Math.min(cap, f * MAX_RELATIVE);
      return f + Math.max(-c, Math.min(c, MODEL_WEIGHT * (mod[i] - f)));
    });
    var sum = ours.reduce(function (x, y) { return x + y; }, 0);
    ours = ours.map(function (p) { return p / sum; });
    names.forEach(function (n, i) {
      var ret = ours[i] * prices[i] - 1;
      out.rows.push({ name: n, price: prices[i], fair: fair[i], stats: mod[i], ours: ours[i], ret: ret,
                      avg: stake * ret, verdict: label(ret, ours[i], level.name), tag: chanceTag(ours[i]) });
    });
    return out;
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
    var foot = document.getElementById('valueConfidence');   // one confidence for the whole match, in the header
    if (foot) { foot.replaceChildren(confidenceBadge(a)); }

    if (a.rows.length) {
      box.appendChild(el('div', 'section-label', 'Match Result'));
      var t = el('table', 'table table-sm align-middle mb-0 stats-table');
      var tb = el('tbody');
      a.rows.forEach(function (r) {
        var tr = el('tr');
        tr.appendChild(el('td', null, r.name));
        var verdict = el('td', 'text-nowrap text-end');
        // Each badge has its own tooltip: the chance tag explains how likely, the verdict explains the price.
        if (r.tag) {
          var tag = el('span', 'badge rounded-pill chance-tag me-1 ' + tagClass(r.tag), r.tag);
          tip(tag, pct(r.ours));
          verdict.appendChild(tag);
        }
        var vb = el('span', 'badge rounded-pill ' + r.verdict.cls, r.verdict.text);
        // Smallest odds that would be worth taking for our chance (fair odds), as a fraction
        tip(vb, 'Needs ' + F.nearestFraction(1 / r.ours) + ' or bigger');
        verdict.appendChild(vb);
        tr.appendChild(verdict);
        tb.appendChild(tr);
      });
      t.appendChild(tb);
      box.appendChild(t);
    } else {
      box.appendChild(el('p', 'text-muted', 'No Sky Bet match odds saved for this game.'));
    }

    // ---- Goals markets: our chance and fair odds (no Sky Bet price saved yet) ----
    var gl = el('div', 'section-label');
    var glText = el('span', null, 'Goals Markets');
    tip(glText, 'Our goals model only: Sky Bet\u2019s prices for these aren\u2019t saved yet.');
    gl.appendChild(glText);
    box.appendChild(gl);
    var g = el('table', 'table table-sm align-middle mb-0 stats-table'), gb = el('tbody');
    [['Over 2.5 goals', m.over25], ['Under 2.5 goals', 1 - m.over25], ['Both teams to score: Yes', m.btts], ['Both teams to score: No', 1 - m.btts]]
      .forEach(function (r) {
        var tr = el('tr');
        tr.appendChild(el('td', null, r[0]));
        tr.appendChild(el('td', 'text-end', pct(r[1])));
        gb.appendChild(tr);
      });
    g.appendChild(gb); box.appendChild(g);

    box.appendChild(el('div', 'text-muted mt-3',
      'Expected goals ' + m.lh.toFixed(1) + '–' + m.la.toFixed(1) +
      '. Verdicts judge the price, not the result: a value bet still loses most of the time if it\'s a long shot. Not financial advice.'));
  }

  /** Redraw with the current Stake box value (called when the stake changes). */
  function rerender() { if (last) render.apply(null, last); }

  return { render: render, rerender: rerender, model: model, analyse: analyse, confidenceBadge: confidenceBadge,
           DEFAULT_STAKE: DEFAULT_STAKE };
})();
