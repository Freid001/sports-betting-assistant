// Shared helpers for the football pages. Reads the local odds cache (free, no API credits).
window.Football = (function () {
  var NAMES = { epl: 'Premier League', uefa_champs_league: 'UEFA Champions League', england_league1: 'English League One',
                uefa_nations_league: 'UEFA Nations League', uefa_european_championship: 'UEFA Euro', fifa_world_cup: 'FIFA World Cup' };

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }
  // ---- Poisson goals maths (the value check, in-play and the match page all use these) ----
  /** P(exactly k goals) when l are expected. */
  function pois(k, l) { var p = Math.exp(-l); for (var i = 1; i <= k; i++) p *= l / i; return p; }
  /**
   * P(0..10 goals) for an expected-goals figure, worked out once and kept: the value check tries thousands of
   * pairs, and working each from scratch made one analyse() take over 100 ms. Same numbers as pois().
   */
  var pmfCache = {}, pmfCount = 0;
  function pmf(l) {
    var key = l.toFixed(6), out = pmfCache[key];
    if (out) return out;
    out = [Math.exp(-l)];
    for (var k = 1; k <= 10; k++) out.push(out[k - 1] * (l / k));
    if (++pmfCount > 5000) { pmfCache = {}; pmfCount = 1; }   // bounded (in-play rates vary continuously)
    return (pmfCache[key] = out);
  }
  /** Chance of a scoreline condition f(home goals, away goals), each side's goals Poisson (0-10 each). */
  function grid(lh, la, f) {
    var p = 0, ph = pmf(lh), pa = pmf(la);
    for (var i = 0; i <= 10; i++) for (var j = 0; j <= 10; j++) if (f(i, j)) p += ph[i] * pa[j];
    return p;
  }

  // ---- Chance tags: how LIKELY something is (separately from the price) ----
  var TAG_RANGES = { 'Very Likely': '75%+', 'Likely': '50-74%', 'Coin Flip': '35-49%', 'Outsider': '20-34%', 'Long Shot': '10-19%', 'Moonshot': 'under 10%' };
  function chanceTag(chance) {
    if (chance >= 0.75) return 'Very Likely';
    if (chance >= 0.5) return 'Likely';
    if (chance >= 0.35) return 'Coin Flip';
    if (chance >= 0.2) return 'Outsider';
    if (chance >= 0.1) return 'Long Shot';
    return 'Moonshot';
  }
  function tagClass(tag) {
    return { 'Very Likely': 'chance-verylikely', 'Likely': 'chance-likely', 'Coin Flip': 'chance-tossup', 'Outsider': 'chance-outsider',
             'Long Shot': 'chance-longshot', 'Moonshot': 'chance-moonshot' }[tag] || '';
  }
  /** Chance tag badge, e.g. "Likely", with the chance on hover, e.g. "58.3%" (the tag's range if no chance given). */
  function tagBadge(tag, chance) {
    var b = el('span', 'badge rounded-pill chance-tag ' + tagClass(tag), tag);
    b.setAttribute('data-bs-toggle', 'tooltip');
    b.setAttribute('data-bs-title', chance !== undefined ? (chance * 100).toFixed(1) + '%' : TAG_RANGES[tag]);
    b.style.cursor = 'help';
    return b;
  }
  /** The badge for a chance: tagBadge(chanceTag(chance), chance). */
  function chanceBadge(chance) { return tagBadge(chanceTag(chance), chance); }

  function title(name) { return NAMES[name] || name.replace(/_/g, ' '); }
  function ago(iso) {
    var mins = Math.round((Date.now() - new Date(iso)) / 60000);
    if (mins < 60) return mins + ' min ago';
    if (mins < 48 * 60) return Math.round(mins / 60) + ' h ago';
    return Math.round(mins / 1440) + ' days ago';
  }
  function day(iso) { return new Date(iso).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' }); }
  function time(iso) { return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }); }

  /**
   * GET (or send) JSON from our own server. Rejects with an Error whose message is the server's
   * {"message"} if it gave one, "the app needs a restart…" for a route the running app doesn't have yet
   * (new code not loaded), else "HTTP <status>". The status is on error.status.
   */
  function getJson(url, opts) {
    return fetch(url, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (body) {
        if (!r.ok) {
          var e = new Error(body.message || (r.status === 404 ? 'the app needs a restart to load this' : 'HTTP ' + r.status));
          e.status = r.status;
          throw e;
        }
        return body;
      });
    });
  }
  /** POST (or method) a JSON body, or nothing, and read the JSON answer. */
  function sendJson(url, body, method) {
    var opts = { method: method || 'POST' };
    if (body !== undefined) {
      opts.headers = { 'Content-Type': 'application/json' };
      opts.body = JSON.stringify(body);
    }
    return getJson(url, opts);
  }

  /**
   * The frame shared by the Live Stats and Favourites pop-ups: title, ×, a scrolling body, and a footer with
   * a note on the left, then any extra buttons, Cancel and Save. Shows it, and returns { modal, q } where
   * q('data-save') finds an element by its data- attribute.
   *   opts: { title, body (HTML), note (data- attribute for the footer note), buttons: [[label, data-attr]] }
   */
  function modalFrame(modalEl, opts) {
    var extra = (opts.buttons || []).map(function (b) {
      return '<button type="button" class="btn btn-outline-secondary" ' + b[1] + '>' + b[0] + '</button>';
    }).join('');
    modalEl.innerHTML =
      '<div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable"><div class="modal-content">' +
      '<div class="modal-header"><h2 class="modal-title h5">' + opts.title + '</h2>' +
      '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button></div>' +
      '<div class="modal-body small">' + opts.body + '</div>' +
      '<div class="modal-footer justify-content-between"><span class="small text-muted" ' + opts.note + '></span>' +
      '<span class="d-flex gap-2">' + extra +
      '<button type="button" class="btn btn-outline-secondary" data-bs-dismiss="modal">Cancel</button>' +
      '<button type="button" class="btn btn-theme" data-save>Save</button></span></div></div></div>';
    var modal = bootstrap.Modal.getOrCreateInstance(modalEl);
    modal.show();
    return { modal: modal, q: function (attr) { return modalEl.querySelector('[' + attr + ']'); } };
  }

  /**
   * Loads every cached tournament. Resolves to { tournaments, matches, notes }. First reads the bookmaker picked in
   * Refresh Odds from the server, so every page and the live odds at each point use the same one.
   */
  function loadAll() {
    return syncBookmaker().then(function () { return getJson('/api/football/tournaments'); }).then(function (ts) {
      var cached = ts.filter(function (t) { return t.cached; });
      var notes = ts.filter(function (t) { return !t.cached; }).map(function (t) { return title(t.name) + ': no data yet'; });
      return Promise.all(cached.map(function (t) {
        return getJson('/api/odds/cache/' + t.key).then(function (c) {
          t.fetchedAt = c.fetchedAt;
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

  /** Nearest standard bookie price for any decimal odds (for estimates like "needs 7/2"). */
  function nearestFraction(dec) {
    var x = dec - 1, best = LADDER[0];
    LADDER.forEach(function (f) { if (Math.abs(f.value - x) < Math.abs(best.value - x)) best = f; });
    return best.text;
  }

  /** The shortest standard bookie price at least as big as the decimal odds (for "back at 9/4 or bigger"). */
  /**
   * Where a game should be from the clock alone (no API call): 10' in the first half, HT, 61' in the second half,
   * FT about 2 hours after kick-off. Null before kick-off. Allows for stoppage time and a 15-minute break.
   */
  function clockStatus(iso, now) {
    var m = ((now || Date.now()) - new Date(iso).getTime()) / 60000;
    if (isNaN(m) || m < 0) return null;
    if (m < 47) return Math.max(1, Math.ceil(m)) + '\'';
    if (m < 62) return 'HT';
    if (m < 115) return Math.min(90, Math.ceil(m - 17)) + '\'';
    return 'FT';
  }

  /**
   * A match status as a coloured label, the same on every page: Pre-Match white, a minute (34', 45+2', LIVE 34') red,
   * HT yellow, FT dark green. Null for anything else ("Kicks off in 3 h").
   */
  function statusBadge(text) {
    var t = String(text || '').trim();
    var cls = /^FT$/.test(t) ? 'status-ft' : /^HT$/.test(t) ? 'status-ht' : /^(LIVE )?\d+(\+\d+)?'$/.test(t) ? 'status-live' :
      t === 'Pre-Match' ? 'status-pre' : null;
    return cls ? el('span', 'badge rounded-pill status-badge ' + cls, t) : null;
  }

  function fractionAtLeast(dec) {
    var x = dec - 1 - 1e-9;
    var f = LADDER.find(function (l) { return l.value >= x; });
    return f ? f.text : LADDER[LADDER.length - 1].text;
  }

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
  function stake() { return window.STAKE_BAR === false ? 0 : stakeValue; }   // Stake Bar off in Settings: no stake
  /** The stake amounts used across the app: the top bar's quick buttons and every recommended stake. */
  var STAKE_BANDS = [1, 2, 5, 10, 20];

  /**
   * A "Stake £" input. onChange is called after every edit so the page can redraw its badges.
   * With a stake set, badges show what you'd get back (stake + winnings) instead of the fraction.
   */
  function stakeInput(onChange) {
    var outer = el('div', 'd-flex align-items-center gap-1');
    var group = el('div', 'input-group input-group-sm');
    group.style.width = '10rem';
    group.appendChild(el('span', 'input-group-text', '£'));   // reads: £ [10] Stake
    var input = el('input', 'form-control');
    // Arrows move in £5 steps; any amount (e.g. £2.50) can still be typed.
    input.type = 'number'; input.min = '0'; input.step = '5'; input.placeholder = '0';
    input.setAttribute('aria-label', 'Stake in pounds');
    if (stakeValue) input.value = stakeValue;
    group.appendChild(input);
    group.appendChild(el('span', 'input-group-text', 'Stake'));
    outer.appendChild(group);

    // Quick amounts: click to set, click the highlighted one again (or "No bet") to clear.
    var presets = [0].concat(STAKE_BANDS).map(function (amount) {
      var b = el('button', 'btn btn-sm btn-outline-light', amount ? '£' + amount : 'No bet');   // same height as the input
      b.type = 'button';
      b.title = amount ? 'Show returns for a £' + amount + ' stake' : 'Clear the stake and show odds';
      b.addEventListener('click', function () {
        set(stakeValue === amount ? 0 : amount);
        input.value = stakeValue || '';
      });
      outer.appendChild(b);
      return { amount: amount, button: b };
    });

    function highlight() {
      presets.forEach(function (p) { p.button.classList.toggle('active', p.amount === stakeValue); });
    }
    function set(v) {
      stakeValue = v > 0 ? v : 0;
      try { localStorage.setItem(STAKE_KEY, String(stakeValue)); } catch (e) { /* ignore */ }
      highlight();
      onChange();
    }
    input.addEventListener('input', function () { set(parseFloat(input.value)); });
    highlight();
    return outer;
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
    // Hover text, the same on every page, e.g. "10/3 · 23.1% (21.7% no margin)": the price's own chance, then the
    // bookmaker's chance with its margin taken out (left off when there's no margin to take out)
    var pc = function (p) { return (p * 100).toFixed(1) + '%'; };
    var margin = chance > 0 ? (1 / dec) / chance - 1 : 0;
    var tip = fraction(dec) + ' · ' + pc(1 / dec) + (chance > 0 && margin >= 0.0005 ? ' (' + pc(chance) + ' no margin)' : '');
    // Bootstrap tooltip (shown on hover, see the listener below)
    b.setAttribute('data-bs-toggle', 'tooltip');
    b.setAttribute('data-bs-title', tip);
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

  /**
   * The blue box at the top of the Refresh Odds / Refresh Stats / Live Stats pop-ups, always the same rows:
   * API, Plan, Calls left, Limit, Resets, Last refreshed (plus an optional warning in red).
   * No API says which plan you're on, so the plan is worked out from the limit it reports.
   */
  function infoBox(box, info) {
    box.replaceChildren();
    [['API', info.api], ['Plan', info.plan], ['Calls left', info.left], ['Limit', info.limit], ['Resets', info.reset], ['Last refreshed', info.last]].forEach(function (l) {
      var row = el('div', 'd-flex justify-content-between gap-3');
      row.appendChild(el('span', 'text-muted', l[0]));
      row.appendChild(el('span', 'fw-semibold text-end', l[1] || 'unknown'));
      box.appendChild(row);
    });
    if (info.warning) box.appendChild(el('div', 'text-danger mt-1', info.warning));
  }

  /** Lower-case, no accents, no club words, so "Paris Saint-Germain FC" matches "Paris Saint Germain". */
  function norm(name) {
    return (name || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
      .replace(/[^a-z0-9 ]/g, ' ')
      .replace(/\b(fc|cf|ac|sc|afc|ssc|fk|sk|as|rc|cd|ud|sv|vfb|vfl|bv|bsc|club|de|of|the|and|calcio|1|04|05|09)\b/g, ' ')
      .replace(/\s+/g, ' ').trim();
  }

  function loadTeams(tournament) {
    if (!teamCache[tournament]) {
      teamCache[tournament] = getJson('/api/football/teams/' + tournament)
        .then(function (d) {
          var list = d.teams || [];
          if (d.all) list.strict = true;   // every saved team (competition not on the free plan): match names strictly
          return list;
        })
        .catch(function () { return []; });
    }
    return teamCache[tournament];
  }

  /**
   * Odds API name -> saved team name, for clubs the two sites name differently.
   * Add a line here if a logo is missing (both sides are compared after norm()).
   */
  var ALIASES = {
    'aek athens': 'pae aek',
    'sporting lisbon': 'sporting clube portugal',
    'lyon': 'olympique lyonnais'
  };

  /** The saved team (id, name, crest...) for an Odds API team name, or null. */
  function findTeam(teams, name) {
    var n = norm(name);
    if (ALIASES[n]) n = ALIASES[n];
    if (!n) return null;
    var best = null;
    teams.forEach(function (t) {
      [t.name, t.shortName, t.tla].forEach(function (cand) {
        if (!best && norm(cand) === n) best = t;
      });
    });
    if (!best && teams.strict) {
      // all the words of one full name are in the other ("Benfica" / "Sport Lisboa e Benfica", "AZ Alkmaar" / "AZ"),
      // so "Sparta Prague" isn't taken for Sparta Rotterdam or "Lillestrom" for Lille
      var words = n.split(' ');
      teams.forEach(function (t) {
        var c = norm(t.name).split(' ');
        if (!best && c[0] && (words.every(function (w) { return c.indexOf(w) >= 0; })
            || c.every(function (w) { return words.indexOf(w) >= 0; }))) best = t;
      });
      return best;
    }
    if (!best) {
      teams.forEach(function (t) {
        if (best) return;
        var c = norm(t.name), s = norm(t.shortName);
        if ((c && (c.indexOf(n) >= 0 || n.indexOf(c) >= 0)) || (s && s.length > 3 && (s.indexOf(n) >= 0 || n.indexOf(s) >= 0))) best = t;
      });
    }
    return best;
  }

  function findCrest(teams, name) {
    var t = findTeam(teams, name);
    return t && t.crest ? t.crest : null;
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

  /** Same player? Bookmakers and GOAL API don't always spell names the same way. */
  function samePlayer(a, b) {
    var x = norm(a).split(" "), y = norm(b).split(" ");
    if (!x[0] || !y[0]) return false;
    if (x.join(' ') === y.join(' ')) return true;
    if (x[x.length - 1] === y[y.length - 1] && x[0][0] === y[0][0]) return true;   // same surname and first initial
    // One name inside the other, e.g. "Emersonn" and "Emersonn Correia da Silva"
    var shorter = x.length <= y.length ? x : y, longer = shorter === x ? y : x;
    return shorter.every(function (w) { return longer.indexOf(w) >= 0; });
  }

  /** A position ("Forwards", "Offence", "Centre-Forward"...) as a short word: Forward, Midfielder... */
  function positionName(raw) {
    if (!raw) return null;
    var r = raw.toLowerCase();
    if (r.indexOf('goal') >= 0 || r === 'keeper') return 'Goalkeeper';
    if (r.indexOf('def') >= 0 || r.indexOf('back') >= 0) return 'Defender';
    if (r.indexOf('mid') >= 0) return 'Midfielder';
    if (r.indexOf('off') >= 0 || r.indexOf('forward') >= 0 || r.indexOf('wing') >= 0 || r.indexOf('striker') >= 0 || r.indexOf('attack') >= 0) return 'Forward';
    return raw;
  }

  /** Market names shown on the match page (odds card and Value check). */
  var MARKET_NAMES = { h2h: 'Match Result', h2h_3_way: 'Match Result (3-Way)', totals: 'Over/Under Goals',
    alternate_totals: 'Over/Under Goals', btts: 'Both Teams to Score', double_chance: 'Double Chance',
    draw_no_bet: 'Draw No Bet', h2h_3_way_h1: 'First-Half Result', halftime_fulltime: 'Half-Time / Full-Time',
    player_goal_scorer_anytime: 'Anytime Goalscorer', player_first_goal_scorer: 'First Goalscorer',
    player_shots_on_target: 'Player Shots on Target', player_to_score_or_assist: 'To Score or Assist',
    player_shots_on_target_alternate: 'Player Shots on Target', correct_score: 'Correct Score',
    alternate_totals_corners: 'Over/Under Corners', alternate_totals_cards: 'Over/Under Cards', team_totals: 'Team Goals',
    alternate_team_totals: 'Team Goals' };

  // ---- The bookmaker whose odds the pages show (picked in Refresh Odds, remembered in this browser) ----
  var BOOKMAKER_NAMES = { skybet: 'Sky Bet', betway: 'Betway', paddypower: 'Paddy Power', williamhill: 'William Hill' };
  function bookmaker() {
    try { return localStorage.getItem('bookmaker') || 'skybet'; } catch (e) { return 'skybet'; }
  }
  /** Picks the bookmaker for the whole app: kept on the server (live odds use it too), and in this browser. */
  function setBookmaker(key) {
    try { localStorage.setItem('bookmaker', key); } catch (e) { /* not kept */ }
    return sendJson('/api/odds/cache/bookmaker?key=' + encodeURIComponent(key)).catch(function () { /* restart needed */ });
  }
  /** The server's bookmaker into this browser (resolves either way; an old server keeps the browser's choice). */
  function syncBookmaker() {
    return getJson('/api/odds/cache/bookmaker').then(function (b) {
      if (b && b.bookmaker) { try { localStorage.setItem('bookmaker', b.bookmaker); } catch (e) { /* not kept */ } }
    }).catch(function () { /* keep the browser's */ });
  }
  function bookmakerName(key) { return BOOKMAKER_NAMES[key || bookmaker()] || key; }

  /** Friendly names for Refresh Odds' extra markets, in the order they're listed (unknown keys go last). */
  var EXTRA_MARKET_NAMES = {
    alternate_totals: 'Over/Under Goals (all lines)', btts: 'Both Teams to Score', double_chance: 'Double Chance',
    draw_no_bet: 'Draw No Bet', alternate_team_totals: 'Team Goals', correct_score: 'Correct Score',
    h2h_3_way_h1: 'First-Half Result', halftime_fulltime: 'Half-Time / Full-Time',
    alternate_totals_corners: 'Over/Under Corners', alternate_totals_cards: 'Over/Under Cards',
    player_shots_on_target_alternate: 'Player Shots on Target (all lines)', player_shots_on_target: 'Player Shots on Target',
    player_goal_scorer_anytime: 'Anytime Goalscorer', player_first_goal_scorer: 'First Goalscorer',
    player_to_score_or_assist: 'To Score or Assist'
  };
  /** What each extra market is, in a line (Settings shows it under the tick box). */
  var EXTRA_MARKET_INFO = {
    alternate_totals: 'Total goals over or under a line, from 0.5 to 5.5',
    btts: 'Do both teams score at least once?',
    double_chance: 'Two of the three results in one bet, e.g. home or draw',
    draw_no_bet: 'Home or away; your stake back if it\'s a draw',
    alternate_team_totals: 'Goals by one team, over or under a line',
    correct_score: 'The exact final score',
    h2h_3_way_h1: 'Home, draw or away at half-time',
    halftime_fulltime: 'The result at half-time and at full-time, e.g. draw / home',
    alternate_totals_corners: 'Total corners, over or under a line',
    alternate_totals_cards: 'Total cards, over or under a line',
    player_shots_on_target_alternate: 'A player\'s shots on target, over or under each line',
    player_shots_on_target: 'A player\'s shots on target, over or under a line',
    player_goal_scorer_anytime: 'A player scores at any time',
    player_first_goal_scorer: 'A player scores the first goal',
    player_to_score_or_assist: 'A player scores or sets up a goal'
  };
  /** Market keys in EXTRA_MARKET_NAMES' order. */
  function sortMarkets(keys) {
    var order = Object.keys(EXTRA_MARKET_NAMES);
    return keys.slice().sort(function (a, b) {
      var x = order.indexOf(a), y = order.indexOf(b);
      return (x < 0 ? 99 : x) - (y < 0 ? 99 : y);
    });
  }

  /**
   * What Refresh Odds fetches, from Settings: {matchResult, scope ('off' | 'favs' | 'all'), markets: [...]}.
   * They used to be kept in this browser: the first time, those choices are copied to the server.
   */
  function refreshOptions() {
    return getJson('/api/settings').then(function (s) {
      var r = s.refresh || { matchResult: true, scope: 'off', markets: [] };
      if (r.saved) return r;
      try {
        var old = { matchResult: localStorage.getItem('matchResult') !== 'off',
                    scope: localStorage.getItem('extraMarketsScope') || 'off',
                    markets: JSON.parse(localStorage.getItem('extraMarkets') || '[]') };
        if (['off', 'favs', 'all'].indexOf(old.scope) < 0) old.scope = 'off';
        return sendJson('/api/settings/refresh', old).catch(function () { return old; });
      } catch (e) { return r; }
    });
  }
  function saveRefreshOptions(r) { return sendJson('/api/settings/refresh', r); }
  /** The picked bookmaker's odds for a match (The Odds API event shape), or null if none are saved. */
  function pickBook(ev) {
    var key = bookmaker();
    return (ev && ev.bookmakers || []).find(function (b) { return b.key === key; }) || null;
  }

  return { pois: pois, pmf: pmf, grid: grid, chanceTag: chanceTag, tagBadge: tagBadge, chanceBadge: chanceBadge,
           loadTeams: loadTeams, findTeam: findTeam, logo: logo, el: el, title: title, ago: ago, day: day, time: time, loadAll: loadAll, fairChances: fairChances, fraction: fraction, nearestFraction: nearestFraction, fractionAtLeast: fractionAtLeast, clockStatus: clockStatus, statusBadge: statusBadge,
           oddsBadge: oddsBadge, chanceClass: chanceClass, legend: legend,
           stake: stake, stakeInput: stakeInput, STAKE_BANDS: STAKE_BANDS, money: money, norm: norm, infoBox: infoBox,
           getJson: getJson, sendJson: sendJson, modalFrame: modalFrame,
           bookmaker: bookmaker, setBookmaker: setBookmaker, bookmakerName: bookmakerName, pickBook: pickBook,
           MARKET_NAMES: MARKET_NAMES, samePlayer: samePlayer, positionName: positionName,
           EXTRA_MARKET_NAMES: EXTRA_MARKET_NAMES, EXTRA_MARKET_INFO: EXTRA_MARKET_INFO, sortMarkets: sortMarkets, refreshOptions: refreshOptions,
           saveRefreshOptions: saveRefreshOptions, syncBookmaker: syncBookmaker };
})();
