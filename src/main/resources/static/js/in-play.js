/**
 * In-play Best Bets: our chances from a point in the match (a Live Stats snapshot or the live view), with the live
 * price and verdict when live odds have been fetched, in the same Odds · Chance · Verdict table as the markets.
 *
 * Goals still to come for each team (Poisson):
 *   pre-match: the goals model's expected goals x the share of a match's goals still to come
 *   live:      the team's xG (estimated from shots) so far, projected over the time left at the same rate
 *   blend:     weight on pre-match = 1 - minute/120 (at least 40%): early stats are noisy, later ones count more
 *   game state: the team behind pushes (x1.15), the team ahead sits back (x0.9)
 * Share still to come: 45% of goals come before half-time and 55% after, plus stoppage time (2 and 4 minutes),
 * so late minutes count a little more than early ones.
 * Verdicts use the same rules as the pre-match tables (ValueCheck.label); without a price, the lowest price worth
 * taking is (1 / our chance) x 1.05, the framework's +5% value bar.
 */
window.InPlay = (function () {
  var F = window.Football, el = F.el;
  var MINUTE = { WATCH: 15, '10': 10, '25': 25, '30': 30, HT: 45, '45': 50, '60': 60, '70': 70, '80': 80, FT: 90 };
  var W1 = 0.45 / 45, W2 = 0.55 / 45;              // goal weight per minute, first and second half
  var TOTAL = 47 * W1 + 49 * W2;                    // 45 + 2 and 45 + 4 minutes of stoppage
  var VALUE_BAR = 1.05;
  var CHASE = 1.15, PROTECT = 0.9;

  function pois(k, l) { var p = Math.exp(-l); for (var i = 1; i <= k; i++) p *= l / i; return p; }

  /** Share of a match's goals still to come after minute t (0..1). */
  function shareLeft(t) {
    t = Math.max(0, Math.min(90, t));
    var first = t < 45 ? (45 - t + 2) * W1 : 0;
    var second = (90 - Math.max(t, 45) + 4) * W2;
    return (first + second) / TOTAL;
  }

  /** Expected goals still to come for each team, and how they were worked out. */
  function rates(model, snap) {
    var t = snap.minute !== undefined && snap.minute !== null ? snap.minute : MINUTE[snap.period];
    var left = shareLeft(t), played = 1 - left;
    var w = Math.max(0.4, 1 - t / 120);
    var pre = [model.lh * left, model.la * left];
    var xg = snap.xg && snap.xg.length === 2 ? snap.xg.map(Number) : null;
    var live = xg && played > 0.05 ? xg.map(function (x) { return x / played * left; }) : null;
    var r = live ? [w * pre[0] + (1 - w) * live[0], w * pre[1] + (1 - w) * live[1]] : pre.slice();
    var h = parseInt(snap.homeScore, 10) || 0, a = parseInt(snap.awayScore, 10) || 0;
    if (h < a) { r[0] *= CHASE; r[1] *= PROTECT; }
    if (a < h) { r[1] *= CHASE; r[0] *= PROTECT; }
    return { minute: t, home: r[0], away: r[1], pre: pre, live: live, weight: w, score: [h, a] };
  }

  function grid(lh, la, f) {
    var p = 0;
    for (var i = 0; i <= 10; i++) for (var j = 0; j <= 10; j++) if (f(i, j)) p += pois(i, lh) * pois(j, la);
    return p;
  }
  function atLeast(n, l) { var p = 0; for (var k = 0; k < n; k++) p += pois(k, l); return 1 - p; }

  /**
   * The bets that fit the window (Betting Framework §7), with our chance and the minimum price.
   * Each: { market, name, side ('home' / 'away' / null), chance, minPrice }.
   */
  function bets(r, names, period) {
    var h0 = r.score[0], a0 = r.score[1], lh = r.home, la = r.away, total = h0 + a0;
    var out = [];
    function add(market, name, side, chance, find) {
      if (chance > 0.01 && chance < 0.99) out.push({ market: market, name: name, side: side, chance: chance, minPrice: VALUE_BAR / chance, find: find || null });
    }
    var H = grid(lh, la, function (i, j) { return h0 + i > a0 + j; });
    var D = grid(lh, la, function (i, j) { return h0 + i === a0 + j; });
    var A = 1 - H - D;
    var none = Math.exp(-(lh + la));
    var want = {
      WATCH: [], '10': [], FT: [],
      '25': ['next', 'team', 'over'], '30': ['next', 'team', 'over'],
      HT: ['dnb', 'next', 'team', 'over'],
      '45': ['next', 'over'],
      '60': ['win', 'dnb', 'next', 'team', 'over'],
      '70': ['next', 'over'],
      '80': ['next']
    }[period] || [];
    if (want.indexOf('win') >= 0) {
      add('Match Result', names[0], 'home', H, { keys: ['h2h'], name: names[0] });
      add('Match Result', 'Draw', null, D, { keys: ['h2h'], name: 'Draw' });
      add('Match Result', names[1], 'away', A, { keys: ['h2h'], name: names[1] });
    }
    if (want.indexOf('dnb') >= 0 && H + A > 0) {
      add('Draw No Bet', names[0], 'home', H / (H + A), { keys: ['draw_no_bet'], name: names[0] });
      add('Draw No Bet', names[1], 'away', A / (H + A), { keys: ['draw_no_bet'], name: names[1] });
    }
    if (want.indexOf('next') >= 0) {
      add('Next Goal', names[0], 'home', (1 - none) * lh / (lh + la));
      add('Next Goal', names[1], 'away', (1 - none) * la / (lh + la));
      add('Next Goal', 'No more goals', null, none);
    }
    if (want.indexOf('team') >= 0) {
      add('Team To Score Again', names[0], 'home', 1 - Math.exp(-lh));
      add('Team To Score Again', names[1], 'away', 1 - Math.exp(-la));
    }
    if (want.indexOf('over') >= 0) {
      [0.5, 1.5, 2.5].forEach(function (more) {
        var line = total + more;
        // second-half goals over 0.5 at 1-0 = the match over 1.5, so the price comes from the match's goals line
        add(period === 'HT' ? 'Second-Half Goals' : 'Total Goals', 'Over ' + (period === 'HT' ? more : line), null,
            atLeast(Math.floor(more) + 1, lh + la), { keys: ['alternate_totals', 'totals'], name: 'Over', point: line });
      });
    }
    return out;
  }

  /**
   * Our chance for one of the bookmaker's in-play outcomes at this point (the score so far plus the goals still to
   * come): match result, Draw No Bet, over/under goals and both teams to score. Null for anything else.
   */
  function chanceFor(r, names, key, o) {
    var h0 = r.score[0], a0 = r.score[1], lh = r.home, la = r.away;
    var n = F.norm(o.name), home = F.norm(names[0]), away = F.norm(names[1]);
    if (key === 'h2h' || key === 'draw_no_bet') {
      var H = grid(lh, la, function (i, j) { return h0 + i > a0 + j; });
      var A = grid(lh, la, function (i, j) { return h0 + i < a0 + j; });
      var D = 1 - H - A;
      if (key === 'draw_no_bet') return H + A > 0 ? (n === home ? H / (H + A) : n === away ? A / (H + A) : null) : null;
      return n === home ? H : n === away ? A : n === 'draw' ? D : null;
    }
    if (key === 'totals' || key === 'alternate_totals') {
      var line = Number(o.point), need = Math.floor(line - h0 - a0) + 1;   // goals still needed to go over
      var over = need <= 0 ? 1 : atLeast(need, lh + la);
      return n === 'over' ? over : n === 'under' ? 1 - over : null;
    }
    if (key === 'btts') {
      var yes = (h0 > 0 ? 1 : 1 - Math.exp(-lh)) * (a0 > 0 ? 1 : 1 - Math.exp(-la));
      return n === 'yes' ? yes : n === 'no' ? 1 - yes : null;
    }
    return null;
  }

  var TAGS = [[0.75, 'Very Likely', 'chance-verylikely'], [0.5, 'Likely', 'chance-likely'], [0.35, 'Coin Flip', 'chance-tossup'],
              [0.2, 'Outsider', 'chance-outsider'], [0.1, 'Long Shot', 'chance-longshot'], [0, 'Moonshot', 'chance-moonshot']];
  function tagBadge(chance) {
    var t = TAGS.find(function (x) { return chance >= x[0]; });
    var b = el('span', 'badge rounded-pill chance-tag ' + t[2], t[1]);
    b.setAttribute('data-bs-toggle', 'tooltip');
    b.setAttribute('data-bs-title', (chance * 100).toFixed(1) + '%');
    b.style.cursor = 'help';
    return b;
  }

  /** "9/4", "Evens", "2.5" -> decimal odds (null if it isn't a price). */
  function parsePrice(text) {
    var t = String(text || '').trim().toLowerCase();
    if (!t) return null;
    if (t === 'evens' || t === 'evs' || t === 'even') return 2;
    var f = t.match(/^(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)$/);
    if (f) return +f[2] > 0 ? 1 + f[1] / f[2] : null;
    var d = parseFloat(t);
    return !isNaN(d) && /^\d+(\.\d+)?$/.test(t) && d > 1 ? d : null;
  }

  /** The live price for a bet from fetched markets ({key, outcomes:[{name, price, point}]}), or null. */
  function livePrice(markets, find) {
    if (!markets || !find) return null;
    for (var i = 0; i < find.keys.length; i++) {
      var mk = markets.find(function (m) { return m.key === find.keys[i]; });
      var o = mk && mk.outcomes.find(function (x) {
        return F.norm(x.name) === F.norm(find.name) && (find.point === undefined || Number(x.point) === find.point);
      });
      if (o) return Number(o.price);
    }
    return null;
  }

  /**
   * Draws the in-play Best Bets into `box` for a snapshot: the verdict line, where the prices come from, then the bets
   * in the page's own market table (opts.table(rows, title): Odds · Chance · Verdict, like the Sky Bet markets).
   * check = Live.checklist(snap.rows), setup = Live.setup(...).
   * opts: { table, markets (the prices saved for this point), fetchedAt, confidence, onRefresh (shown for a live game) }.
   * Rows: { label, o: {price}, r: {verdict, ours, fair, ret} or null, chance, fits, hint }.
   * Without a price for this point: "–" for odds and verdict (pre-match prices are out of date once it's started).
   * Bets on the checklist leader come first when they're not already winning (the framework's ideal setup).
   */
  function render(box, ev, model, snap, check, setup, opts) {
    opts = opts || {};
    box.replaceChildren();
    var names = [ev.home_team, ev.away_team];
    var r = rates(model, snap);
    var leader = check && check.leader;
    var team = leader === 'home' ? names[0] : leader === 'away' ? names[1] : null;
    // Only the windows with nothing to bet on get a line of text: the table says the rest
    if (snap.period === 'FT' || snap.period === 'WATCH' || snap.period === '10') box.appendChild(el('div', 'text-muted', 'No Bet'));

    if (opts.onRefresh) {
      var src = el('div', 'small text-muted mb-2 d-flex align-items-center gap-2 flex-wrap');
      var btn = el('button', 'btn btn-sm btn-outline-theme', 'Refresh live odds');
      btn.type = 'button';
      btn.title = 'Fetches the bookmaker\'s prices now: about 3 Odds API credits';
      btn.onclick = function () { btn.disabled = true; opts.onRefresh(); };
      src.appendChild(btn);
      src.appendChild(el('span', null, opts.fetchedAt ? 'Live odds from ' + F.ago(opts.fetchedAt) + '.' : 'No live odds fetched yet.'));
      box.appendChild(src);
    }

    var list = bets(r, names, snap.period);
    if (!list.length) return r;
    var rows = list.map(function (b) {
      var price = livePrice(opts.markets, b.find);
      var fits = !!team && setup !== 'winning' && b.side === leader;
      var worth = 'Our chance ' + (b.chance * 100).toFixed(1) + '%: value at ' + F.fractionAtLeast(b.minPrice) + ' or bigger.';
      var row = { label: (fits ? '★ ' : '') + b.name + ' · ' + b.market, o: { price: price }, chance: b.chance, fits: fits,
                  r: null, hint: 'No odds for this point in the match. ' + worth };
      if (price) {
        var ret = b.chance * price - 1;
        row.r = { ours: b.chance, fair: 1 / price, ret: ret, verdict: window.ValueCheck.label(ret, b.chance, opts.confidence) };
      }
      return row;
    });
    var rank = function (x) { return x.r ? x.r.ret : -9; };
    rows.sort(function (p, q) { return (q.fits - p.fits) || (rank(q) - rank(p)) || (q.chance - p.chance); });
    box.appendChild(opts.table(rows, 'In-play bet'));
    return r;
  }

  /** Footer line: goals still to come and how they were worked out. */
  function summary(r, names) {
    var short = function (n) { return n.split(' ')[0]; };
    return 'Goals still to come: ' + short(names[0]) + ' ' + r.home.toFixed(2) + ', ' + short(names[1]) + ' ' + r.away.toFixed(2) +
      ' (' + Math.round(r.weight * 100) + '% pre-match model' + (r.live ? ', ' + Math.round((1 - r.weight) * 100) + '% live xG' : '') +
      ') · Verdicts judge the price, not the result · Not financial advice.';
  }

  return { shareLeft: shareLeft, rates: rates, bets: bets, chanceFor: chanceFor, render: render, summary: summary, parsePrice: parsePrice,
           livePrice: livePrice, MINUTE: MINUTE };
})();
