// Shared helpers for the football pages. Reads the local odds cache (free, no API credits).
window.Football = (function () {
  var NAMES = { uefa_champs_league: 'Champions League', uefa_nations_league: 'Nations League',
                uefa_european_championship: 'Euros', fifa_world_cup: 'World Cup' };

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  function title(name) { return NAMES[name] || name.replace(/_/g, ' '); }
  function ago(iso) {
    var mins = Math.round((Date.now() - new Date(iso)) / 60000);
    if (mins < 60) return mins + ' min ago';
    if (mins < 48 * 60) return Math.round(mins / 60) + ' h ago';
    return Math.round(mins / 1440) + ' days ago';
  }
  function day(iso) { return new Date(iso).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' }); }
  function time(iso) { return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }); }

  /** Loads every cached tournament. Resolves to { tournaments, matches, notes }. */
  function loadAll() {
    return fetch('/api/football/tournaments').then(function (r) { return r.json(); }).then(function (ts) {
      var cached = ts.filter(function (t) { return t.cached; });
      var notes = ts.filter(function (t) { return !t.cached; }).map(function (t) { return title(t.name) + ': no data yet'; });
      return Promise.all(cached.map(function (t) {
        return fetch('/api/odds/cache/' + t.key).then(function (r) { return r.json(); }).then(function (c) {
          notes.unshift(title(t.name) + ': updated ' + ago(c.fetchedAt));
          return c.events.map(function (ev) { ev.comp = title(t.name); ev.compKey = t.name; ev.fetchedAt = c.fetchedAt; return ev; });
        });
      })).then(function (lists) {
        var matches = [].concat.apply([], lists).sort(function (a, b) { return a.commence_time.localeCompare(b.commence_time); });
        return { tournaments: ts, matches: matches, notes: notes };
      });
    });
  }

  /** Margin-free implied chances for a set of decimal prices. */
  function fairChances(prices) {
    var s = prices.reduce(function (t, p) { return t + 1 / p; }, 0);
    return { chances: prices.map(function (p) { return 1 / p / s; }), margin: s - 1 };
  }

  /** Standard bookmaker fractions, used to show decimal odds the way UK bookies do (1.36 -> 4/11). */
  var LADDER = ['1/20','1/16','1/14','1/12','1/10','1/9','1/8','2/15','1/7','2/13','1/6','2/11','1/5','2/9','1/4',
    '2/7','3/10','1/3','4/11','2/5','4/9','1/2','8/15','4/7','8/13','4/6','8/11','4/5','5/6','10/11','1/1','21/20',
    '11/10','6/5','5/4','11/8','6/4','13/8','7/4','15/8','2/1','85/40','9/4','12/5','5/2','13/5','11/4','3/1','10/3',
    '7/2','4/1','9/2','5/1','11/2','6/1','13/2','7/1','15/2','8/1','17/2','9/1','10/1','11/1','12/1','14/1','16/1',
    '18/1','20/1','22/1','25/1','28/1','33/1','40/1','50/1','66/1','80/1','100/1'].map(function (f) {
      var p = f.split('/'); return { text: f === '1/1' ? 'Evens' : f, value: p[0] / p[1] };
    });

  /** Decimal odds as a UK fraction, e.g. 2.5 -> 6/4, 2.0 -> Evens, 2.4 -> 7/5. */
  function fraction(dec) {
    var x = dec - 1, best = LADDER[0];
    LADDER.forEach(function (f) { if (Math.abs(f.value - x) < Math.abs(best.value - x)) best = f; });
    // Decimal odds are rounded to 2 places, so allow that much difference from a standard price.
    if (Math.abs(best.value - x) <= 0.006) return best.text;
    // Not on the standard ladder: closest simple fraction n/d.
    var pick = null;
    [1, 2, 3, 4, 5, 6, 8, 10, 20].forEach(function (d) {
      var n = Math.max(1, Math.round(x * d)), err = Math.abs(n / d - x);
      if (!pick || err < pick.err - 1e-9) pick = { n: n, d: d, err: err };
    });
    var g = gcd(pick.n, pick.d);
    return (pick.n / g) + '/' + (pick.d / g);
  }
  function gcd(a, b) { return b ? gcd(b, a % b) : a; }

  /** Five colour bands by chance (darkest = most extreme). Colours are defined in the page CSS (fragments.html). */
  var BANDS = [
    { min: 0.70, cls: 'odds-very-good', label: '70%+' },
    { min: 0.50, cls: 'odds-good',      label: '50–69%' },
    { min: 0.25, cls: 'odds-ok',        label: '25–49%' },
    { min: 0.10, cls: 'odds-poor',      label: '10–24%' },
    { min: 0,    cls: 'odds-very-poor', label: 'under 10%' }
  ];

  function band(chance) {
    for (var i = 0; i < BANDS.length; i++) if (chance >= BANDS[i].min) return BANDS[i];
    return BANDS[BANDS.length - 1];
  }

  function chanceClass(chance) {
    if (chance === null || chance === undefined) return 'text-bg-secondary';
    return band(chance).cls;
  }

  // ---- Stake (£), shared by every page and remembered in this browser ----
  var STAKE_KEY = 'football-stake';
  var stakeValue = (function () {
    try { var v = parseFloat(localStorage.getItem(STAKE_KEY)); return v > 0 ? v : 0; } catch (e) { return 0; }
  })();
  function stake() { return stakeValue; }

  /**
   * A "Stake £" input. onChange is called after every edit so the page can redraw its badges.
   * With a stake set, badges show what you'd get back (stake + winnings) instead of the fraction.
   */
  function stakeInput(onChange) {
    var group = el('div', 'input-group input-group-sm');
    group.style.width = '9.5rem';
    group.appendChild(el('span', 'input-group-text', 'Stake £'));
    var input = el('input', 'form-control');
    // Arrows move in £5 steps; any amount (e.g. £2.50) can still be typed.
    input.type = 'number'; input.min = '0'; input.step = '5'; input.placeholder = '0';
    input.setAttribute('aria-label', 'Stake in pounds');
    if (stakeValue) input.value = stakeValue;
    input.addEventListener('input', function () {
      var v = parseFloat(input.value);
      stakeValue = v > 0 ? v : 0;
      try { localStorage.setItem(STAKE_KEY, String(stakeValue)); } catch (e) { /* ignore */ }
      onChange();
    });
    group.appendChild(input);
    return group;
  }

  function money(v) { return '£' + (v >= 100 ? v.toFixed(0) : v.toFixed(2)); }

  /**
   * Odds badge coloured by chance. Shows the fraction, or with a stake set, the total return
   * (stake + winnings, like a bookie's "potential returns"). Hover for the details.
   */
  function oddsBadge(dec, chance) {
    if (!dec) return el('span', 'text-muted', '–');
    var st = stakeValue;
    var b = el('span', 'badge rounded-pill ' + chanceClass(chance), st ? money(st * dec) : fraction(dec));
    b.style.minWidth = st ? '4rem' : '3.2rem';
    // Hover text, e.g. "8/5 (36%)".
    var tip = [fraction(dec) + (chance !== null && chance !== undefined ? ' (' + Math.round(chance * 100) + '%)' : '')];
    // Bootstrap tooltip (shown on hover, see the listener below), one fact per line.
    b.setAttribute('data-bs-toggle', 'tooltip');
    b.setAttribute('data-bs-title', tip.join('\n'));
    b.style.cursor = 'help';
    return b;
  }

  /** Small legend explaining the badge colours. */
  function legend() {
    var wrap = el('span', 'small text-muted d-inline-flex flex-wrap align-items-center gap-2');
    wrap.appendChild(el('span', null, (stakeValue ? 'Badges show total return for ' + money(stakeValue) + '. ' : '') + 'Colour = chance:'));
    BANDS.forEach(function (band) { wrap.appendChild(el('span', 'badge rounded-pill ' + band.cls, band.label)); });
    return wrap;
  }

  // Create badge tooltips the first time they're hovered (works for rows drawn after page load too).
  document.addEventListener('mouseover', function (e) {
    var t = e.target.closest && e.target.closest('[data-bs-toggle="tooltip"]');
    if (!t || !window.bootstrap || bootstrap.Tooltip.getInstance(t)) return;
    bootstrap.Tooltip.getOrCreateInstance(t, { placement: 'top', customClass: 'odds-tip' }).show();
  });

  // ---- Team logos ----
  var teamCache = {};
  var FLAGS = { england: 'gb-eng', scotland: 'gb-sct', wales: 'gb-wls', 'northern ireland': 'gb-nir',
    'republic of ireland': 'ie', ireland: 'ie', france: 'fr', germany: 'de', spain: 'es', italy: 'it', portugal: 'pt',
    netherlands: 'nl', belgium: 'be', croatia: 'hr', denmark: 'dk', switzerland: 'ch', austria: 'at', poland: 'pl',
    sweden: 'se', norway: 'no', serbia: 'rs', turkey: 'tr', ukraine: 'ua', 'czech republic': 'cz', czechia: 'cz',
    hungary: 'hu', greece: 'gr', romania: 'ro', slovakia: 'sk', slovenia: 'si', albania: 'al',
    georgia: 'ge', finland: 'fi', iceland: 'is', israel: 'il', bosnia: 'ba', 'bosnia and herzegovina': 'ba',
    brazil: 'br', argentina: 'ar', uruguay: 'uy', colombia: 'co', usa: 'us', 'united states': 'us', mexico: 'mx',
    canada: 'ca', japan: 'jp', 'south korea': 'kr', australia: 'au', morocco: 'ma', senegal: 'sn', nigeria: 'ng' };

  /** Lower-case, no accents, no club words, so "Paris Saint-Germain FC" matches "Paris Saint Germain". */
  function norm(name) {
    return (name || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
      .replace(/[^a-z0-9 ]/g, ' ')
      .replace(/\b(fc|cf|ac|sc|afc|ssc|fk|sk|as|rc|cd|ud|sv|vfb|vfl|bv|bsc|club|de|of|the|calcio|1|04|05|09)\b/g, ' ')
      .replace(/\s+/g, ' ').trim();
  }

  function loadTeams(tournament) {
    if (!teamCache[tournament]) {
      teamCache[tournament] = fetch('/api/football/teams/' + tournament)
        .then(function (r) { return r.ok ? r.json() : { teams: [] }; })
        .then(function (d) { return d.teams || []; })
        .catch(function () { return []; });
    }
    return teamCache[tournament];
  }

  /**
   * Odds API name -> football-data.org name, for clubs the two sites name differently.
   * Add a line here if a logo is missing (both sides are compared after norm()).
   */
  var ALIASES = {
    'aek athens': 'pae aek',
    'sporting lisbon': 'sporting clube portugal'
  };

  function findCrest(teams, name) {
    var n = norm(name);
    if (ALIASES[n]) n = ALIASES[n];
    if (!n) return null;
    var best = null;
    teams.forEach(function (t) {
      [t.name, t.shortName, t.tla].forEach(function (cand) {
        var c = norm(cand);
        if (!c || best) return;
        if (c === n) best = t;
      });
    });
    if (!best) {
      teams.forEach(function (t) {
        if (best) return;
        var c = norm(t.name), s = norm(t.shortName);
        if ((c && (c.indexOf(n) >= 0 || n.indexOf(c) >= 0)) || (s && s.length > 3 && (s.indexOf(n) >= 0 || n.indexOf(s) >= 0))) best = t;
      });
    }
    return best && best.crest ? best.crest : null;
  }

  /** A logo element: crest if found, else a country flag for national teams, else initials. */
  function logo(teams, name, size) {
    size = size || 48;
    var url = findCrest(teams, name);
    if (!url) {
      var code = FLAGS[name.toLowerCase()];
      if (code) url = 'https://flagcdn.com/w80/' + code + '.png';
    }
    var box = el('span', 'd-inline-flex align-items-center justify-content-center flex-shrink-0');
    box.style.width = box.style.height = size + 'px';
    if (url) {
      var img = el('img');
      img.src = url; img.alt = name; img.loading = 'lazy';
      img.style.maxWidth = img.style.maxHeight = size + 'px';
      img.onerror = function () { box.replaceChildren(initials(name, size)); };
      box.appendChild(img);
    } else {
      box.appendChild(initials(name, size));
    }
    return box;
  }

  function initials(name, size) {
    var words = norm(name).split(' ').filter(Boolean);
    var text = (words.length > 1 ? words[0][0] + words[1][0] : (words[0] || '?').slice(0, 3)).toUpperCase();
    var c = el('span', 'rounded-circle bg-secondary-subtle text-secondary fw-semibold d-inline-flex align-items-center justify-content-center', text);
    c.style.width = c.style.height = size + 'px';
    c.style.fontSize = Math.round(size / 3) + 'px';
    return c;
  }

  return { loadTeams: loadTeams, logo: logo, el: el, title: title, ago: ago, day: day, time: time, loadAll: loadAll, fairChances: fairChances, fraction: fraction,
           oddsBadge: oddsBadge, chanceClass: chanceClass, legend: legend,
           stake: stake, stakeInput: stakeInput, money: money };
})();
