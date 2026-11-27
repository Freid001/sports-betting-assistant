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

  // ---- Team profile from the squad's season stats (GOAL API, saved by Refresh Stats) ----

  var TEAM_SHOT_VALUE = 0.105;   // goals per shot, a typical league conversion (penalties included)
  var SOT_VALUE = 0.30;          // goals per shot on target faced, roughly
  var HOME_EDGE = 1.1, AWAY_EDGE = 0.9;   // season stats aren't split home / away: home sides shoot more, concede less
  var SHOT_WEIGHT = 0.5;         // expected scoring: half recent goals, half season shots
  var KEEPER_WEIGHT = 0.25;      // expected conceding: a quarter from the keepers' shots faced (one position's data)
  var INJURY_WEIGHT = 0.6, INJURY_FLOOR = 0.8;   // missing players take most of their share of the attack, at most 20%
  // Recent games at any venue (too few home / away games early in the season) hide home advantage: the 25 Nov
  // backtest (465 games) had home 38% v away 39% predicted, 48% v 32% actual. Tuned on its first half: 1.15.
  var ANY_VENUE_HOME = 1.15;
  var COVERAGE = 0.8;            // season data is only used when it covers 80%+ of the team's goals (shots) / games (keepers)
  var SHOT_RECORDS = 0.85;       // ...and shots only when 85%+ of the regular outfield players have them recorded
                                 // (GOAL API leaves many blank: e.g. Cole Palmer had none, Morgan Rogers 1 in 7 games)

  /**
   * A team's season so far from its squad list (each player's stats): games (the most any player has played), shots
   * per game, goals its shots suggest a game (players GOAL API has no shots for count their actual goals), goals the
   * keepers' shots faced suggest, how much of the attack (goals + shots) injured players account for, and the squad's
   * goals (to check the squad covers the team). GOAL API leaves many fields blank: missing pieces are null.
   */
  function teamProfile(squad) {
    var num = function (st, k) { var v = parseFloat((st || {})[k]); return isNaN(v) ? 0 : v; };
    var has = function (st, k) { return st && st[k] !== undefined && st[k] !== null && st[k] !== '' && !isNaN(parseFloat(st[k])); };
    var games = 0, shots = 0, shotGoals = 0, goals = 0, saves = 0, conceded = 0, keeperGames = 0, attack = 0, missing = 0, injured = [];
    var regulars = 0, recorded = 0;   // outfield players with 3+ games, and how many of them have shots recorded
    (squad || []).forEach(function (p) {
      var st = p.stats;
      if (!st) return;
      var g = num(st, 'matchPlayed');
      games = Math.max(games, g);
      goals += num(st, 'goals');
      shots += num(st, 'shotsTotal');
      shotGoals += has(st, 'shotsTotal') ? num(st, 'shotsTotal') * TEAM_SHOT_VALUE : num(st, 'goals');
      var share = num(st, 'goals') + TEAM_SHOT_VALUE * num(st, 'shotsTotal');
      attack += share;
      if (st.injured && !/^no$/i.test(st.injured)) { missing += share; injured.push(p.name); }
      if (p.position === 'Goalkeeper' || num(st, 'saves') > 0) {
        saves += num(st, 'saves'); conceded += num(st, 'goalsConceded'); keeperGames += g;
      } else if (g >= 3) {
        regulars++;
        if (has(st, 'shotsTotal')) recorded++;
      }
    });
    if (games < 3) return null;   // too early (or no stats saved yet)
    var shotsOk = shots > 0 && regulars > 0 && recorded >= SHOT_RECORDS * regulars;
    return {
      games: games,
      goals: goals,
      shotsPerGame: shotsOk ? shots / games : null,   // null: too many players without shots recorded
      shotGoals: shotsOk ? shotGoals / games : null,
      // the keepers must have played most of the games, or their numbers say little about the defence
      keeperGoals: keeperGames >= COVERAGE * games && saves + conceded > 0 ? (saves + conceded) / keeperGames * SOT_VALUE : null,
      injured: injured,
      attackFactor: attack > 0 ? Math.max(INJURY_FLOOR, 1 - INJURY_WEIGHT * missing / attack) : 1
    };
  }

  /**
   * Goals model (Poisson). Expected goals:
   *   home = average of (home team's scoring, away team's conceding), and the reverse for away, where
   *   - scoring = recent goals scored at the venue, half and half with season shots per game (steadier), if known;
   *   - conceding = recent goals conceded at the venue, a quarter from the keepers' shots faced, if known;
   *   - then each side's attack is lowered for injured players (their share of the team's goals and shots).
   * homeViews / awayViews: the recent games, optionally with .profile (teamProfile of the squad).
   */
  function model(homeViews, awayViews) {
    var hs = mean(homeViews, function (v) { return v.gf; }), hc = mean(homeViews, function (v) { return v.ga; });
    var as = mean(awayViews, function (v) { return v.gf; }), ac = mean(awayViews, function (v) { return v.ga; });
    if (hs === null || as === null) return null;
    var hp = (homeViews && homeViews.profile) || null, ap = (awayViews && awayViews.profile) || null;
    var blend = function (recent, season, weight) { return season === null || season === undefined ? recent : (1 - weight) * recent + weight * season; };
    // shots only when the squad accounts for most of the team's goals (some players are filed under other teams)
    var covers = function (p, recent) { return p && p.shotGoals !== null && p.goals >= COVERAGE * recent * p.games; };
    var hsx = blend(hs, covers(hp, hs) ? hp.shotGoals * HOME_EDGE : null, SHOT_WEIGHT);
    var asx = blend(as, covers(ap, as) ? ap.shotGoals * AWAY_EDGE : null, SHOT_WEIGHT);
    var hcx = blend(hc, hp && hp.keeperGoals !== null ? hp.keeperGoals * AWAY_EDGE : null, KEEPER_WEIGHT);
    var acx = blend(ac, ap && ap.keeperGoals !== null ? ap.keeperGoals * HOME_EDGE : null, KEEPER_WEIGHT);
    var k = homeViews && homeViews.anyVenue ? ANY_VENUE_HOME : 1;
    var lh = Math.max(0.2, (hsx + acx) / 2 * (hp ? hp.attackFactor : 1) * k);
    var la = Math.max(0.2, (asx + hcx) / 2 * (ap ? ap.attackFactor : 1) / k);
    var home = 0, draw = 0, away = 0, under25 = 0, h0 = pois(0, lh), a0 = pois(0, la);
    for (var i = 0; i <= 10; i++) {
      for (var j = 0; j <= 10; j++) {
        var p = pois(i, lh) * pois(j, la);
        if (i > j) home += p; else if (i === j) draw += p; else away += p;
        if (i + j <= 2) under25 += p;
      }
    }
    var tot = home + draw + away;
    // hs / as: each side's usual scoring (for scaling players' goal rates to this match)
    return { lh: lh, la: la, hs: hsx, as: asx, home: home / tot, draw: draw / tot, away: away / tot,
             over25: 1 - under25, btts: (1 - h0) * (1 - a0),
             inputs: { home: { recent: hs, conceded: hc, profile: hp, shotsUsed: covers(hp, hs) },
                       away: { recent: as, conceded: ac, profile: ap, shotsUsed: covers(ap, as) } } };
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
    if (chance >= 0.35) return 'Coin Flip';    // 35-49%
    if (chance >= 0.2) return 'Outsider';    // 20-34%
    if (chance >= 0.1) return 'Long Shot';   // 10-19%
    return 'Moonshot';                       // under 10%
  }

  var TAG_RANGES = { 'Very Likely': '75%+', 'Likely': '50-74%', 'Coin Flip': '35-49%', 'Outsider': '20-34%', 'Long Shot': '10-19%', 'Moonshot': 'under 10%' };

  /** Chance tag badge, e.g. "Likely", with our calculated chance on hover, e.g. "58.3%" (the range if no chance given). */
  function tagBadge(tag, chance) {
    var b = el('span', 'badge rounded-pill chance-tag ' + tagClass(tag), tag);
    b.setAttribute('data-bs-toggle', 'tooltip');
    b.setAttribute('data-bs-title', chance !== undefined ? (chance * 100).toFixed(1) + '%' : TAG_RANGES[tag]);
    b.style.cursor = 'help';
    return b;
  }

  // ---- Recommended bets: one rule for a match page's Best Bets and the Recommended Bets page ----
  // Value only (Possible / Good / Great Value: +5% or better). Fair Price has no edge: Betting Framework §2 lists it as
  // optional (£2-3) and "not listed in Bets to place"; Golden Rule 6: value beats likelihood.
  var RECOMMEND_MIN = 0.05;
  var FAIR_MIN = -0.05;
  var AVOID = ['correct_score', 'player_first_goal_scorer'];   // Betting Framework §8: always avoid
  var PER_MATCH = 5;
  var MIN_ODDS = 4 / 3;   // 1/3 or bigger: shorter prices win too little to be worth a bet (e.g. 1/20 pays 5p per £1)
  var VERDICT_ORDER = ['Great Value', 'Good Value', 'Possible Value', 'Fair Price', 'Small Cost', 'Poor Price'];
  /** Best verdict first, then the more likely (our chance), then the bigger odds. */
  function byRecommendation(x, y) {
    return VERDICT_ORDER.indexOf(x.verdict.text) - VERDICT_ORDER.indexOf(y.verdict.text) ||
           (y.chance || 0) - (x.chance || 0) || (y.price || 0) - (x.price || 0);
  }
  /**
   * A match's recommended bets from its checked outcomes ({key, ret, chance, price, verdict, ...}): Fair Price or
   * better, odds of 1/3 or bigger, not in a market the framework avoids, in recommendation order, at most 5.
   */
  function recommend(list) {
    return list.filter(function (b) { return b.ret >= RECOMMEND_MIN && b.price >= MIN_ODDS && AVOID.indexOf(b.key) < 0; })
      .sort(byRecommendation).slice(0, PER_MATCH);
  }

  /**
   * Recommended stake, within the Betting Framework's §2 ranges and always one of the top bar's amounts
   * (F.STAKE_BANDS: £1, £2, £5, £10...): more on bets that pay, less on long shots and on thin data. Returns {amount, why}.
   *   Great Value at High or Very High confidence: £10; other Great / Good Value: £5
   *   Possible Value (value on thin data): £2
   *   Fair Price: £2 if it's at least a Coin Flip (35%+), else £1
   *   Then: a Long Shot or Moonshot (under 20%) £1; player markets £2 at most (§7a)
   * b: {verdict, confidence, chance, player}. Used by the match page's and the Bets page's Recommended Bets.
   */
  function stakeFor(b) {
    var v = b.verdict.text, high = /High/.test(b.confidence || ''), tossUp = b.chance >= 0.35;
    var s;
    if (v === 'Great Value' || v === 'Good Value') {
      s = { amount: v === 'Great Value' && high ? 10 : 5, why: v + ', ' + b.confidence + ' confidence' };
    } else if (v === 'Possible Value') {
      s = { amount: 2, why: 'Value on thin data' };
    } else {
      s = tossUp ? { amount: 2, why: 'Fair Price, and a Coin Flip or better' } : { amount: 1, why: 'Fair Price, but less likely to win' };
    }
    if (b.chance < 0.2 && s.amount > 1) s = { amount: 1, why: s.why + ', but a long shot (under 20%)' };
    if (b.player && s.amount > 2) s = { amount: 2, why: 'Player market: £2 at most' };
    // always one of the top bar's amounts (the biggest one not over it)
    s.amount = F.STAKE_BANDS.filter(function (x) { return x <= s.amount; }).pop() || F.STAKE_BANDS[0];
    return s;
  }

  /** Fair Price bets (no edge either way: optional, £2-3, not recommendations), most likely first, at most 3. */
  function fairBets(list) {
    return list.filter(function (b) { return b.ret > FAIR_MIN && b.ret < RECOMMEND_MIN && b.price >= MIN_ODDS && AVOID.indexOf(b.key) < 0; })
      .sort(function (x, y) { return (y.chance || 0) - (x.chance || 0); }).slice(0, 3);
  }

  /** A price verdict badge, the same everywhere: just the label (Great Value, Fair Price...), no tooltip. */
  function verdictBadge(verdict) {
    return el('span', 'badge rounded-pill ' + verdict.cls, verdict.text);
  }

  /** Tooltip on one element (shown on hover, above it). */
  function tip(node, text) {
    node.setAttribute('data-bs-toggle', 'tooltip');
    node.setAttribute('data-bs-title', text);
    node.style.cursor = 'help';
  }

  function tagClass(tag) {
    return { 'Very Likely': 'chance-verylikely', 'Likely': 'chance-likely', 'Coin Flip': 'chance-tossup', 'Outsider': 'chance-outsider',
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
    var out = { model: m, view: m, games: games, level: level, rows: [] };
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
    // our view: the expected goals that best fit "our chance" (the bookmaker's, nudged towards the model: what the
    // verdicts use) for the result and over / under 2.5, so the Prediction card, scores and in-play predictions
    // tell the same story as the Markets and Recommended Bets
    var o25 = null;
    (out.markets || []).forEach(function (mk) {
      if (mk.key !== 'totals' && mk.key !== 'alternate_totals') return;
      var over = mk.rows.find(function (r) { return r.name === 'Over 2.5'; });
      if (over && o25 === null) o25 = over.ours;
    });
    out.view = fitView(m, { home: out.rows[0].ours, draw: out.rows[1].ours, away: out.rows[2].ours, over25: o25 });
    return out;
  }

  /**
   * Expected goals (home, away) whose Poisson chances best match these (home / draw / away win, and over 2.5 goals
   * when known), with the same fields as model() (hs / as kept from the stats model: each side's usual scoring).
   */
  function fitView(m, target) {
    var best = null;
    for (var lh = 0.2; lh <= 4.0001; lh += 0.05) {
      for (var la = 0.2; la <= 4.0001; la += 0.05) {
        var c = chances(lh, la);
        var err = Math.pow(c.home - target.home, 2) + Math.pow(c.away - target.away, 2) + Math.pow(c.draw - target.draw, 2) +
                  (target.over25 !== null && target.over25 !== undefined ? Math.pow(c.over25 - target.over25, 2) : 0);
        if (!best || err < best.err) best = { err: err, lh: lh, la: la, c: c };
      }
    }
    return Object.assign({}, best.c, { lh: best.lh, la: best.la, hs: m.hs, as: m.as, inputs: m.inputs, fitted: true });
  }

  /** Result, over 2.5 and both-teams-to-score chances from expected goals (Poisson). */
  function chances(lh, la) {
    var home = 0, draw = 0, away = 0, under25 = 0;
    for (var i = 0; i <= 10; i++) for (var j = 0; j <= 10; j++) {
      var p = pois(i, lh) * pois(j, la);
      if (i > j) home += p; else if (i === j) draw += p; else away += p;
      if (i + j <= 2) under25 += p;
    }
    var tot = home + draw + away;
    return { home: home / tot, draw: draw / tot, away: away / tot, over25: 1 - under25,
             btts: (1 - pois(0, lh)) * (1 - pois(0, la)) };
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
      var list = (ts[side] || {}).scorers || [];
      // the team's penalty taker: whoever has scored the most of them (none scored: nobody gets the bonus)
      var taker = list.reduce(function (best, p) { return (p.pens || 0) > ((best && best.pens) || 0) ? p : best; }, null);
      list.forEach(function (p) {
        out.push({ name: p.name, side: side, goals: p.goals, games: p.games, position: p.position,
                   shots: p.shots, shotGames: p.shotGames, pens: p.pens, taker: p === taker });
      });
    });
    return out;
  }

  /**
   * A player's goals per game, allowing for his position and how few games the season has had so far: his own
   * record blended with a typical rate for his position, worth PRIOR_GAMES games. Early in the season the typical
   * rate counts for most (2 goals in 3 games is mostly luck); after 20+ games his own record does. So a defender
   * with an early 2-goal burst isn't treated like a forward, and a striker's slow start isn't held against him.
   * With his season stats it also uses:
   * - shots: a steadier guide than goals (each shot worth SHOT_VALUE goals, a typical conversion), half and half
   *   with his goals;
   * - penalties: taken out of his goals (they don't come at a steady rate), and his team's penalty taker
   *   (p.taker) gets PENALTY_RATE goals a game instead.
   */
  var POSITION_RATE = { Forward: 0.35, Midfielder: 0.12, Defender: 0.04, Goalkeeper: 0.005 };
  var UNKNOWN_RATE = 0.12;   // position not known
  var PRIOR_GAMES = 10;
  var SHOT_VALUE = 0.10;     // goals per shot, roughly (a typical non-penalty conversion)
  var PENALTY_RATE = 0.08;   // a team's penalties a game (~0.1) x scored (~0.8), for whoever takes them
  function goalRate(p) {
    var typical = POSITION_RATE[p.position] !== undefined ? POSITION_RATE[p.position] : UNKNOWN_RATE;
    var pens = p.pens > 0 ? Math.min(p.pens, p.goals) : 0;
    var rate = (p.goals - pens + PRIOR_GAMES * typical) / (p.games + PRIOR_GAMES);
    if (p.shots > 0 && p.shotGames > 0) {
      rate = (rate + (p.shots * SHOT_VALUE + PRIOR_GAMES * typical) / (p.shotGames + PRIOR_GAMES)) / 2;
    }
    return rate + (p.taker ? PENALTY_RATE : 0);
  }

  // ---- The prediction log (scored against results later: the accuracy report) ----

  var MODEL_VERSION = '2026-11-27';   // change when the model changes, so the report can compare versions

  /**
   * A pre-match prediction to log: our chances, the bookmaker's (margin removed) and prices, and the Recommended
   * Bets. ids: the teams' saved ids (to find the result later). Null without a model.
   */
  function record(ev, a, homeTeamId, awayTeamId) {
    if (!a || !a.model || homeTeamId == null || awayTeamId == null) return null;
    var m = a.model, h2h = a.rows || [];
    var rec = { matchId: ev.id, point: 'PRE', kickoff: ev.commence_time, competition: ev.compKey, home: ev.home_team,
                away: ev.away_team, homeTeamId: homeTeamId, awayTeamId: awayTeamId, version: MODEL_VERSION,
                confidence: a.level ? a.level.name : null,
                chances: { home: m.home, draw: m.draw, away: m.away, over25: m.over25, btts: m.btts, lh: m.lh, la: m.la } };
    if (a.view && a.view.fitted) {   // what the Prediction card showed (the stats model is chances)
      rec.view = { home: a.view.home, draw: a.view.draw, away: a.view.away, over25: a.view.over25, btts: a.view.btts,
                   lh: a.view.lh, la: a.view.la };
    }
    if (h2h.length === 3) {
      rec.odds = { home: h2h[0].price, draw: h2h[1].price, away: h2h[2].price };
      rec.bookmaker = { home: h2h[0].fair, draw: h2h[1].fair, away: h2h[2].fair };
      rec.ours = { home: h2h[0].ours, draw: h2h[1].ours, away: h2h[2].ours };   // after the nudge (what the verdicts use)
    }
    var all = [];
    h2h.forEach(function (x) { all.push({ key: 'h2h', x: x }); });
    (a.markets || []).forEach(function (mk) { (mk.rows || []).forEach(function (x) { all.push({ key: mk.key, x: x }); }); });
    rec.recommended = recommend(all.map(function (b) {
      return { key: b.key, name: b.x.name, ret: b.x.ret, chance: b.x.ours, price: b.x.price, verdict: b.x.verdict };
    })).map(function (b) { return { key: b.key, name: b.name, price: b.price, chance: b.chance }; });
    return rec;
  }

  var sent = {};
  /** Sends a prediction to the log (once per page load per match and point; the server keeps the last pre-match one). */
  function log(rec) {
    if (!rec || !window.fetch) return;
    var k = rec.matchId + '|' + rec.point, body = JSON.stringify(rec);
    // a bet window once; pre-match again whenever it changes (e.g. once the squads are in), until kick-off
    if (sent[k] && (rec.point !== 'PRE' || sent[k] === body)) return;
    sent[k] = body;
    fetch('/api/predictions', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body })
      .catch(function () { delete sent[k]; });
  }

  /**
   * Settles a logged bet against the final score: true (won), false (lost) or null (can't tell from the score:
   * goalscorers, half-time markets...). For the report's Recommended Bets profit.
   */
  function settle(b, home, away, result) {
    var h = result.home, a = result.away, n = String(b.name);
    if (b.key === 'h2h') return n === home ? h > a : n === away ? a > h : n === 'Draw' ? h === a : null;
    if (b.key === 'btts') return n === 'Yes' ? h > 0 && a > 0 : n === 'No' ? !(h > 0 && a > 0) : null;
    if (b.key === 'totals' || b.key === 'alternate_totals') {
      var mt = /^(Over|Under)\s+([\d.]+)$/.exec(n);
      if (!mt) return null;
      var line = parseFloat(mt[2]);
      return mt[1] === 'Over' ? h + a > line : h + a < line;
    }
    if (b.key === 'draw_no_bet') return h === a ? null : n === home ? h > a : a > h;
    return null;
  }

  /**
   * The accuracy report from logged predictions with results: for each point (PRE, HT, 60...), how many games, how
   * often the most likely result happened, and log loss (lower is better) for ours and the bookmaker's chances; a
   * calibration table (when we said ~X%, how often it happened); and Recommended Bets' profit at £1 each.
   */
  function report(rows) {
    var byPoint = {}, buckets = [], bets = { n: 0, won: 0, staked: 0, returned: 0, unsettled: 0 };
    for (var i = 0; i < 10; i++) buckets.push({ from: i / 10, said: 0, n: 0, happened: 0 });
    (rows || []).forEach(function (p) {
      if (!p.result || !p.chances) return;
      var r = p.result, out = r.home > r.away ? 'home' : r.home === r.away ? 'draw' : 'away';
      var pt = byPoint[p.point] = byPoint[p.point] || { point: p.point, n: 0, right: 0, ours: 0, book: 0, nBook: 0, bookRight: 0 };
      var ch = p.ours || p.chances, top = ['home', 'draw', 'away'].reduce(function (x, y) { return ch[y] > ch[x] ? y : x; });
      pt.n++;
      if (top === out) pt.right++;
      pt.ours += -Math.log(Math.max(1e-6, ch[out]));
      if (p.bookmaker) {
        var bk = p.bookmaker, btop = ['home', 'draw', 'away'].reduce(function (x, y) { return bk[y] > bk[x] ? y : x; });
        pt.nBook++;
        pt.book += -Math.log(Math.max(1e-6, bk[out]));
        if (btop === out) pt.bookRight++;
      }
      if (p.point === 'PRE') {
        ['home', 'draw', 'away'].forEach(function (o) {   // calibration: pre-match chances only
          var c = ch[o], b = buckets[Math.min(9, Math.floor(c * 10))];
          b.n++; b.said += c; if (o === out) b.happened++;
        });
        (p.recommended || []).forEach(function (b) {
          var won = settle(b, p.home, p.away, r);
          if (won === null) { bets.unsettled++; return; }
          bets.n++; bets.staked += 1;
          if (won) { bets.won++; bets.returned += b.price; }
        });
      }
    });
    var pending = (rows || []).filter(function (p) { return !p.result; }).length;
    var points = Object.keys(byPoint).map(function (k) {
      var x = byPoint[k];
      return { point: k, games: x.n, right: x.right / x.n, logLoss: x.ours / x.n,
               bookGames: x.nBook, bookRight: x.nBook ? x.bookRight / x.nBook : null, bookLogLoss: x.nBook ? x.book / x.nBook : null };
    });
    var ORDER = ['PRE', '10', '25', '30', 'HT', '60', '80'];   // 10, 25: older logs
    points.sort(function (a, b) { return ORDER.indexOf(a.point) - ORDER.indexOf(b.point); });
    return { points: points, calibration: buckets.filter(function (b) { return b.n; }).map(function (b) {
               return { from: b.from, to: b.from + 0.1, n: b.n, said: b.said / b.n, happened: b.happened / b.n };
             }),
             bets: { n: bets.n, won: bets.won, profit: bets.returned - bets.staked, unsettled: bets.unsettled },
             pending: pending };
  }

  /**
   * Every player's chance to score in this match (anytime), most likely first: the same sum as the Goalscorer
   * check (goalRate scaled to his team's expected goals here), with no odds needed. For the Prediction card.
   */
  function scorerChances(m) {
    return scorers().map(function (p) {
      if (!p.games) return null;
      var lam = p.side === 'home' ? m.lh : m.la, avg = p.side === 'home' ? m.hs : m.as;
      var rate = goalRate(p), x = rate * (avg > 0 ? lam / avg : 1);
      // the working, for the "how we worked this out" pop-up
      return { name: p.name, side: p.side, position: p.position, chance: 1 - Math.exp(-x),
               goals: p.goals, games: p.games, shots: p.shots, shotGames: p.shotGames, pens: p.pens, taker: p.taker,
               rate: rate, lam: lam, avg: avg, x: x };
    }).filter(Boolean).sort(function (a, b) { return b.chance - a.chance; });
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
        verdict.appendChild(verdictBadge(r.verdict, a.level.name));
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
              stack.append(tagBadge(r.tag, r.ours), verdictBadge(r.verdict, a.level.name));
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
   * "Team  [Coin Flip] [Fair Price]" for one outcome row from analyse(), with the same tooltips as the match page.
   * withName: include the team name in front.
   */
  function pair(r, withName) {
    var wrap = el('span', 'd-inline-flex align-items-center gap-1 text-nowrap');
    if (withName) wrap.appendChild(el('span', 'fw-semibold me-1', r.name));
    if (r.tag) wrap.appendChild(tagBadge(r.tag, r.ours));   // the Football page's pick column
    var vb = verdictBadge(r.verdict, r.confidence);
    wrap.appendChild(vb);
    return wrap;
  }

  /** Redraw with the current Stake box value (called when the stake changes). */
  function rerender() { if (last) render.apply(null, last); }

  return { render: render, rerender: rerender, model: model, teamProfile: teamProfile, scorerChances: scorerChances, pois: pois,
           record: record, log: log, fairBets: fairBets, stakeFor: stakeFor,
           CONSTANTS: { PRIOR_GAMES: PRIOR_GAMES, POSITION_RATE: POSITION_RATE, UNKNOWN_RATE: UNKNOWN_RATE, SHOT_VALUE: SHOT_VALUE,
                        PENALTY_RATE: PENALTY_RATE, MODEL_WEIGHT: MODEL_WEIGHT, MAX_RELATIVE: MAX_RELATIVE, MAX_NUDGE: MAX_NUDGE,
                        ANY_VENUE_HOME: ANY_VENUE_HOME, SHOT_WEIGHT: SHOT_WEIGHT, KEEPER_WEIGHT: KEEPER_WEIGHT }, fitView: fitView, chances: chances, settle: settle, report: report, MODEL_VERSION: MODEL_VERSION, analyse: analyse, confidenceBadge: confidenceBadge, pair: pair, goalRate: goalRate,
           tagBadge: tagBadge, verdictBadge: verdictBadge, recommend: recommend, byRecommendation: byRecommendation,
           label: label, money: money,
           DEFAULT_STAKE: DEFAULT_STAKE };
})();
