// Goal alert on the match page: when a poll shows the score went up, the crowd cheering and clapping (made in the browser with
// Web Audio, no sound files) and a "GOAL!" banner with the scoring team's badge, in the theme's accent colour.
// Cards too: a yellow or red card count going up shows the card with the team, a referee's whistle.
// The first score / counts seen are only remembered (opening the page mid-game doesn't celebrate old goals); a count
// that goes down (a goal ruled out by VAR) is just remembered too. The 🔊 button by the score mutes it (per browser).
// Several at once (a goal and a card between two polls) show one after another.
// Also: KICK-OFF / HALF TIME / SECOND HALF / FULL TIME with the referee's whistle, PENALTY! (when GOAL API sends a
// penalty count), and a chime + small note when a bet window's odds (30', HT, 60', 80') come in.
window.GoalAlert = (function () {
  var last = null, lastCards = null, ctx = null, queue = [], showing = false, phase = null, points = null;

  /** "2–1" / "2-1" → [2, 1]; anything else → null. */
  function parse(score) {
    var m = /^\s*(\d+)\s*[–-]\s*(\d+)\s*$/.exec(score || '');
    return m ? [parseInt(m[1], 10), parseInt(m[2], 10)] : null;
  }
  /** Who scored since the last score seen: ['home'], ['away'], both, or [] (and remembers this score). */
  function check(score) {
    var now = parse(score);
    if (!now) return [];
    var before = last;
    last = now;
    if (!before) return [];
    var out = [];
    if (now[0] > before[0]) out.push('home');
    if (now[1] > before[1]) out.push('away');
    return out;
  }
  function reset() { last = null; lastCards = null; phase = null; points = null; }

  /** Where the game is from its status: 'pre' (none yet), 'play' (a minute), 'ht' or 'ft'. */
  function phaseOf(status) {
    var t = String(status || '').trim();
    if (!t) return 'pre';
    if (/^HT$/i.test(t)) return 'ht';
    if (/^FT$/i.test(t)) return 'ft';
    return 'play';
  }
  /**
   * The match moving on: 'kickoff', 'ht', 'second' (second half under way) or 'ft', else null (and remembers it).
   * The first status seen is only remembered, except 'pre' (the page was open before kick-off). A blank status once
   * the game has started (no refresh for a while) is ignored, so it doesn't look like a second kick-off.
   */
  function checkStatus(status) {
    var now = phaseOf(status), before = phase;
    if (now === 'pre' && before && before !== 'pre') return null;
    phase = now;
    if (!before || before === now) return null;
    if (now === 'ft') return 'ft';
    if (before === 'pre' && now === 'play') return 'kickoff';
    if (before === 'play' && now === 'ht') return 'ht';
    if (before === 'ht' && now === 'play') return 'second';
    return null;
  }
  /** Bet windows whose odds weren't there before (e.g. ['60']); the first list seen is only remembered. */
  function checkPoints(keys) {
    keys = (keys || []).map(String);
    var before = points;
    points = keys;
    if (!before) return [];
    return keys.filter(function (k) { return before.indexOf(k) < 0; });
  }

  /**
   * New cards since the last counts seen: [{ kind: 'yellow'|'red', side: 'home'|'away' }] (one per card).
   * cards = { yellow: [home, away], red: [home, away], penalty: [home, away] } (numbers or strings); missing is
   * ignored. Kinds come back penalty first, then red, then yellow.
   */
  function checkCards(cards) {
    if (!cards) return [];
    var now = {}, ok = false;
    ['yellow', 'red', 'penalty'].forEach(function (k) {
      var v = cards[k];
      if (v && v.length === 2) {
        var h = parseInt(v[0], 10), a = parseInt(v[1], 10);
        if (!isNaN(h) && !isNaN(a)) { now[k] = [h, a]; ok = true; }
      }
    });
    if (!ok) return [];
    var before = lastCards;
    lastCards = now;
    if (!before) return [];
    var out = [];
    ['penalty', 'red', 'yellow'].forEach(function (k) {   // a red before a yellow (a second yellow comes with its red)
      if (!now[k] || !before[k]) return;
      ['home', 'away'].forEach(function (side, i) {
        for (var n = before[k][i]; n < now[k][i]; n++) out.push({ kind: k, side: side });
      });
    });
    return out;
  }

  // Animations and Sounds come from Settings (the page head sets window.MATCH_ALERTS); both on by default
  function opts() { return window.MATCH_ALERTS || (window.MATCH_ALERTS = { animations: true, sounds: true }); }
  function soundOn() { return opts().sounds !== false; }
  function animationsOn() { return opts().animations !== false; }
  /** Sounds on or off, saved in Settings (the match page's 🔊 button and the Settings page). */
  function setSound(on) {
    opts().sounds = !!on;
    if (window.fetch) fetch('/api/settings/sounds?on=' + !!on, { method: 'POST' }).catch(function () { /* stays for this page */ });
  }
  function setAnimations(on) { opts().animations = !!on; }
  /** Puts a banner on the page, unless Animations is off (the sound still plays). */
  function put(node) { if (animationsOn()) document.body.appendChild(node); return node; }

  // Browsers only allow sound after the user has clicked or typed on the page: get the audio ready then.
  function unlock() {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    if (!ctx) ctx = new AC();
    if (ctx.state === 'suspended') ctx.resume();
  }
  if (typeof document !== 'undefined' && document.addEventListener) {
    ['pointerdown', 'keydown'].forEach(function (e) { document.addEventListener(e, unlock, { passive: true }); });
  }

  /**
   * A stadium crowd shouting "aaah": many voices (sawtooth at random pitches, each wobbling a little) through the
   * formants of an open "ah", over breathy noise. glide: how far the pitch moves over the shout (+0.15 rising cheer,
   * -0.3 a falling groan). Times in seconds from now.
   */
  function crowd(dest, t, opts) {
    var dur = opts.dur, voices = opts.voices || 40, peak = opts.peak || 0.6;
    var mix = ctx.createGain();
    mix.gain.setValueAtTime(0.0001, t);
    mix.gain.exponentialRampToValueAtTime(peak, t + opts.attack);
    mix.gain.setValueAtTime(peak, t + dur * 0.55);
    mix.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    mix.connect(dest);
    // the "ah" vowel: two formant bands, plus a little top end for the shouty edge
    var voiceBus = ctx.createGain();
    voiceBus.gain.value = 1.2 / Math.sqrt(voices);
    // send the voices through all three formant bands
    [[730, 4, 1], [1150, 5, 0.6], [2500, 3, 0.15]].forEach(function (f) {
      var band = ctx.createBiquadFilter(), g = ctx.createGain();
      band.type = 'bandpass'; band.frequency.value = f[0]; band.Q.value = f[1];
      g.gain.value = f[2] * 3;
      voiceBus.connect(band); band.connect(g); g.connect(mix);
    });
    for (var v = 0; v < voices; v++) {
      var o = ctx.createOscillator(), wob = ctx.createOscillator(), wobAmt = ctx.createGain(), vg = ctx.createGain();
      var f0 = v % 2 ? 110 + Math.random() * 90 : 190 + Math.random() * 140;   // lower and higher voices
      var start = t + Math.random() * opts.attack * 0.8, end = t + dur * (0.75 + Math.random() * 0.25);
      o.type = 'sawtooth';
      o.frequency.setValueAtTime(f0, start);
      o.frequency.linearRampToValueAtTime(f0 * (1 + opts.glide), end);
      wob.frequency.value = 4 + Math.random() * 3; wobAmt.gain.value = f0 * 0.03;
      wob.connect(wobAmt); wobAmt.connect(o.frequency);
      vg.gain.setValueAtTime(0.0001, start);
      vg.gain.exponentialRampToValueAtTime(0.5 + Math.random() * 0.5, start + 0.25);
      vg.gain.setValueAtTime(0.6, end - 0.3);
      vg.gain.exponentialRampToValueAtTime(0.0001, end);
      o.connect(vg); vg.connect(voiceBus);
      o.start(start); wob.start(start); o.stop(end + 0.05); wob.stop(end + 0.05);
    }
    // breath and clapping hiss under the voices
    var len = Math.ceil(ctx.sampleRate * dur), buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
    for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
    var n = ctx.createBufferSource(), hp = ctx.createBiquadFilter(), ng = ctx.createGain();
    n.buffer = buf; hp.type = 'bandpass'; hp.frequency.value = 1800; hp.Q.value = 0.5;
    ng.gain.value = opts.hiss || 0.25;
    n.connect(hp); hp.connect(ng); ng.connect(mix);
    n.start(t); n.stop(t + dur);
  }

  /** Lots of people clapping: short noise bursts at random moments, each one a quick snap. */
  function clapping(dest, t, dur, perSecond) {
    var len = Math.ceil(ctx.sampleRate * 0.03), buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
    for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 3);
    var band = ctx.createBiquadFilter();
    band.type = 'bandpass'; band.frequency.value = 1500; band.Q.value = 0.8;
    band.connect(dest);
    var count = Math.round(dur * perSecond);
    for (var c = 0; c < count; c++) {
      var at = t + Math.random() * dur, fade = 1 - (at - t) / dur;   // thinning out towards the end
      var src = ctx.createBufferSource(), g = ctx.createGain();
      src.buffer = buf;
      src.playbackRate.value = 0.8 + Math.random() * 0.5;
      g.gain.value = (0.15 + Math.random() * 0.25) * fade;
      src.connect(g); g.connect(band);
      src.start(at);
    }
  }

  /** A goal: the crowd erupts (a rising "aaah", a second wave of cheering) and claps. */
  function play() {
    if (!soundOn()) return;
    unlock();
    if (!ctx || ctx.state !== 'running') return;   // no click on the page yet: the banner still shows
    var master = ctx.createGain();
    master.gain.value = 0.7;
    master.connect(ctx.destination); playing.push(master);
    var t = ctx.currentTime;
    crowd(master, t, { dur: 5, attack: 0.4, glide: 0.15, voices: 50, peak: 0.7, hiss: 0.35 });
    crowd(master, t + 1.2, { dur: 4, attack: 0.6, glide: 0.08, voices: 30, peak: 0.45, hiss: 0.2 });   // the second wave
    clapping(master, t + 0.6, 4.4, 140);
  }

  /** A referee's whistle: one blast for a yellow, a long blast with a crowd groan for a red. */
  function whistle(kind) {
    if (!soundOn()) return;
    unlock();
    if (!ctx || ctx.state !== 'running') return;
    var t = ctx.currentTime, master = ctx.createGain();
    master.gain.value = 0.35;
    master.connect(ctx.destination); playing.push(master);
    var blasts = kind === 'red' ? [[0, 0.25], [0.35, 0.9]] : [[0, 0.45]];
    blasts.forEach(function (b) {
      var o = ctx.createOscillator(), lfo = ctx.createOscillator(), depth = ctx.createGain(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.value = 2900;
      lfo.frequency.value = 38; depth.gain.value = 140;   // the pea's trill
      lfo.connect(depth); depth.connect(o.frequency);
      g.gain.setValueAtTime(0.0001, t + b[0]);
      g.gain.exponentialRampToValueAtTime(0.5, t + b[0] + 0.02);
      g.gain.setValueAtTime(0.5, t + b[0] + b[1] - 0.05);
      g.gain.exponentialRampToValueAtTime(0.0001, t + b[0] + b[1]);
      o.connect(g); g.connect(master);
      o.start(t + b[0]); lfo.start(t + b[0]); o.stop(t + b[0] + b[1] + 0.02); lfo.stop(t + b[0] + b[1] + 0.02);
    });
    if (kind === 'red') {   // the crowd's groan: low noise swelling after the whistle
      var len = ctx.sampleRate * 3, buf = ctx.createBuffer(1, len, ctx.sampleRate), d = buf.getChannelData(0);
      for (var i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
      var n = ctx.createBufferSource(), lp = ctx.createBiquadFilter(), g = ctx.createGain();
      n.buffer = buf; lp.type = 'lowpass'; lp.frequency.value = 450;
      g.gain.setValueAtTime(0.0001, t + 0.9);
      g.gain.exponentialRampToValueAtTime(0.8, t + 1.6);
      g.gain.exponentialRampToValueAtTime(0.0001, t + 3.8);
      n.connect(lp); lp.connect(g); g.connect(master);
      n.start(t + 0.9); n.stop(t + 3.9);
    }
  }

  /** Whistle blasts [[start, length], ...] in seconds from now. */
  function blasts(list, vol) {
    if (!soundOn()) return;
    unlock();
    if (!ctx || ctx.state !== 'running') return;
    var t = ctx.currentTime, master = ctx.createGain();
    master.gain.value = vol || 0.35;
    master.connect(ctx.destination); playing.push(master);
    list.forEach(function (b) {
      var o = ctx.createOscillator(), lfo = ctx.createOscillator(), depth = ctx.createGain(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.value = 2900;
      lfo.frequency.value = 38; depth.gain.value = 140;
      lfo.connect(depth); depth.connect(o.frequency);
      g.gain.setValueAtTime(0.0001, t + b[0]);
      g.gain.exponentialRampToValueAtTime(0.5, t + b[0] + 0.02);
      g.gain.setValueAtTime(0.5, t + b[0] + b[1] - 0.05);
      g.gain.exponentialRampToValueAtTime(0.0001, t + b[0] + b[1]);
      o.connect(g); g.connect(master);
      o.start(t + b[0]); lfo.start(t + b[0]); o.stop(t + b[0] + b[1] + 0.02); lfo.stop(t + b[0] + b[1] + 0.02);
    });
    return { master: master, t: t };
  }
  var WHISTLES = {
    kickoff: [[0, 0.9]],
    second: [[0, 0.9]],
    ht: [[0, 0.3], [0.45, 0.9]],
    ft: [[0, 0.3], [0.45, 0.3], [0.9, 1.3]]   // the classic three
  };
  /** A penalty: the whistle, then the crowd rising in anticipation. */
  function penaltySound() {
    var w = blasts([[0, 0.2], [0.3, 0.7]]);
    if (w) crowd(w.master, w.t + 0.8, { dur: 3, attack: 1.2, glide: 0.2, voices: 40, peak: 0.6, hiss: 0.15 });
  }
  /** A soft two-note bell for a new bet window. */
  function chime() {
    if (!soundOn()) return;
    unlock();
    if (!ctx || ctx.state !== 'running') return;
    var t = ctx.currentTime;
    [[880, 0], [1318.5, 0.16]].forEach(function (n) {
      var o = ctx.createOscillator(), g = ctx.createGain();
      o.type = 'sine'; o.frequency.value = n[0];
      g.gain.setValueAtTime(0.0001, t + n[1]);
      g.gain.exponentialRampToValueAtTime(0.25, t + n[1] + 0.01);
      g.gain.exponentialRampToValueAtTime(0.0001, t + n[1] + 1.2);
      o.connect(g); g.connect(ctx.destination); playing.push(g);
      o.start(t + n[1]); o.stop(t + n[1] + 1.25);
    });
  }

  var styled = false;
  function style() {
    if (styled) return;
    styled = true;
    var s = document.createElement('style');
    s.textContent =
      '.goal-overlay{position:fixed;inset:0;z-index:2000;display:flex;flex-direction:column;align-items:center;' +
      'justify-content:center;pointer-events:none;background:radial-gradient(circle,rgba(0,0,0,.55),rgba(0,0,0,0) 70%);' +
      'animation:goal-fade 4.5s ease forwards}' +
      '.goal-word{font-size:clamp(4rem,14vw,10rem);font-weight:900;letter-spacing:.05em;color:var(--theme-accent,#ffc107);' +
      'text-shadow:0 0 30px rgba(0,0,0,.6);animation:goal-pop .7s cubic-bezier(.2,1.6,.4,1) both}' +
      '.goal-team{display:flex;align-items:center;gap:.75rem;font-size:1.75rem;font-weight:700;color:#fff;' +
      'text-shadow:0 2px 8px rgba(0,0,0,.7);animation:goal-rise .6s .35s ease both}' +
      '.goal-bit{position:fixed;top:-12px;width:9px;height:14px;z-index:2001;pointer-events:none;' +
      'animation:goal-fall linear forwards}' +
      '.goal-pulse{animation:goal-pulse 1s ease 3}' +
      '.card-shape{width:7rem;height:10rem;border-radius:.6rem;box-shadow:0 10px 30px rgba(0,0,0,.6);margin-bottom:1rem;' +
      'animation:card-show .6s cubic-bezier(.2,1.5,.4,1) both}' +
      '.card-shape.yellow{background:#ffd400}.card-shape.red{background:#e10600}' +
      '.phase-word{font-size:clamp(3rem,10vw,7rem);font-weight:900;letter-spacing:.04em;color:#fff;' +
      'text-shadow:0 0 25px rgba(0,0,0,.7);animation:goal-pop .6s cubic-bezier(.2,1.4,.4,1) both}' +
      '.phase-sub{font-size:2rem;font-weight:800;color:var(--theme-accent,#ffc107);text-shadow:0 2px 8px rgba(0,0,0,.7);' +
      'animation:goal-rise .5s .3s ease both}' +
      '.pen-word{font-size:clamp(3.5rem,12vw,8rem);font-weight:900;color:#fff;letter-spacing:.05em;' +
      'text-shadow:0 0 30px rgba(0,0,0,.7);animation:pen-shake .5s .1s ease both, goal-pop .5s ease both}' +
      '@keyframes pen-shake{0%,100%{translate:0}20%,60%{translate:-10px}40%,80%{translate:10px}}' +
      '.window-note{position:fixed;top:4.5rem;left:50%;z-index:2002;transform:translateX(-50%);padding:.6rem 1.1rem;' +
      'border-radius:.5rem;font-weight:600;background:var(--theme-accent,#ffc107);color:var(--theme-ink,#212529);' +
      'box-shadow:0 6px 20px rgba(0,0,0,.35);animation:note-in .4s ease both, goal-fade 6s ease forwards}' +
      '@keyframes note-in{from{transform:translate(-50%,-150%)}to{transform:translate(-50%,0)}}' +
      '.card-word{font-size:clamp(2.5rem,8vw,5rem);font-weight:900;letter-spacing:.05em;text-shadow:0 0 20px rgba(0,0,0,.6);' +
      'animation:goal-rise .5s .25s ease both}' +
      '.card-word.yellow{color:#ffd400}.card-word.red{color:#ff3b30}' +
      '@keyframes card-show{0%{transform:translateY(60vh) rotate(25deg);opacity:0}100%{transform:rotate(-8deg);opacity:1}}' +
      '@keyframes goal-pop{0%{transform:scale(.2) rotate(-8deg);opacity:0}100%{transform:scale(1) rotate(0);opacity:1}}' +
      '@keyframes goal-rise{0%{transform:translateY(20px);opacity:0}100%{transform:none;opacity:1}}' +
      '@keyframes goal-fade{0%,80%{opacity:1}100%{opacity:0}}' +
      '@keyframes goal-fall{to{transform:translateY(105vh) rotate(720deg)}}' +
      '@keyframes goal-pulse{50%{transform:scale(1.35);color:var(--theme-accent,#ffc107)}}' +
      '@media (prefers-reduced-motion:reduce){.goal-word,.goal-team,.goal-pulse,.card-shape,.card-word,.phase-word,.phase-sub,.pen-word{animation:none}.goal-bit{display:none}.window-note{animation:goal-fade 6s ease forwards}}';
    document.head.appendChild(s);
  }

  /** The banner: GOAL! with the scoring team (badge node optional), confetti, and the score element pulsing. */
  function celebrate(team, badge, scoreEl) { enqueue(function (done) { goal(team, badge, scoreEl, done); }); }
  /** A yellow or red card, or a penalty, for this team (badge node optional). */
  function card(kind, team, badge) {
    enqueue(function (done) { kind === 'penalty' ? penaltyBanner(team, badge, done) : cardBanner(kind, team, badge, done); });
  }
  var PHASE_WORDS = { kickoff: 'KICK-OFF', ht: 'HALF TIME', second: 'SECOND HALF', ft: 'FULL TIME' };
  /** KICK-OFF / HALF TIME / SECOND HALF / FULL TIME, with the score under it (none at kick-off) and the whistle. */
  function phaseBanner(kind, score) {
    enqueue(function (done) {
      style();
      var box = overlay();
      var word = document.createElement('div');
      word.className = 'phase-word';
      word.textContent = PHASE_WORDS[kind] || kind;
      box.appendChild(word);
      if (score && kind !== 'kickoff') {
        var sub = document.createElement('div');
        sub.className = 'phase-sub';
        sub.textContent = score;
        box.appendChild(sub);
      }
      blasts(WHISTLES[kind] || [[0, 0.6]]);
      setTimeout(function () { box.remove(); done(); }, 4200);
    });
  }
  function overlay() {
    var box = document.createElement('div');
    box.className = 'goal-overlay';
    return put(box);
  }
  function penaltyBanner(team, badge, done) {
    style();
    var box = overlay();
    var word = document.createElement('div');
    word.className = 'pen-word';
    word.textContent = 'PENALTY!';
    box.appendChild(word);
    box.appendChild(teamLine(team, badge));
    penaltySound();
    setTimeout(function () { box.remove(); done(); }, 4600);
  }
  /** A small note at the top (not a full-screen banner) with a chime: a bet window's odds are in. */
  function windowOpen(text) {
    style();
    var n = document.createElement('div');
    n.className = 'window-note';
    n.textContent = text;
    put(n);
    chime();
    setTimeout(function () { n.remove(); }, 6200);
  }

  function enqueue(fn) {
    queue.push(fn);
    if (!showing) next();
  }
  var round = 0;   // bumped by stop(): a banner from before it finishing doesn't start the next one
  function next() {
    var fn = queue.shift(), mine = round;
    showing = !!fn;
    if (fn) fn(function () { if (mine === round) next(); });
  }
  var playing = [];   // sounds started (their output), so stop() can cut them off
  /**
   * Clears everything showing or waiting: banners, the bet window note and their sounds. The Settings "Try them"
   * buttons call it first, so clicking again restarts the animation instead of queueing or stacking another.
   */
  function stop() {
    round++;
    queue = [];
    showing = false;
    if (typeof document !== 'undefined' && document.querySelectorAll) {
      [].forEach.call(document.querySelectorAll('.goal-overlay, .window-note'), function (n) { n.remove(); });
    }
    playing.forEach(function (g) { try { g.disconnect(); } catch (e) { /* already done */ } });
    playing = [];
  }
  function teamLine(team, badge) {
    var who = document.createElement('div');
    who.className = 'goal-team';
    if (badge) who.appendChild(badge);
    who.appendChild(document.createTextNode(team || ''));
    return who;
  }
  function cardBanner(kind, team, badge, done) {
    style();
    var box = document.createElement('div');
    box.className = 'goal-overlay';
    var shape = document.createElement('div');
    shape.className = 'card-shape ' + kind;
    var word = document.createElement('div');
    word.className = 'card-word ' + kind;
    word.textContent = kind === 'red' ? 'RED CARD!' : 'YELLOW CARD';
    box.appendChild(shape);
    box.appendChild(word);
    box.appendChild(teamLine(team, badge));
    put(box);
    whistle(kind);
    setTimeout(function () { box.remove(); done(); }, 4600);
  }
  function goal(team, badge, scoreEl, done) {
    style();
    var box = document.createElement('div');
    box.className = 'goal-overlay';
    var word = document.createElement('div');
    word.className = 'goal-word';
    word.textContent = 'GOAL!';
    box.appendChild(word);
    box.appendChild(teamLine(team, badge));
    put(box);
    var accent = getComputedStyle(document.documentElement).getPropertyValue('--theme-accent').trim() || '#ffc107';
    var bits = [];
    for (var i = 0; animationsOn() && i < 60; i++) {
      var b = document.createElement('span');
      b.className = 'goal-bit';
      b.style.left = (Math.random() * 100) + 'vw';
      b.style.background = i % 3 ? accent : '#ffffff';
      b.style.animationDuration = (2 + Math.random() * 2) + 's';
      b.style.animationDelay = (Math.random() * 0.8) + 's';
      document.body.appendChild(b);
      bits.push(b);
    }
    if (scoreEl && animationsOn()) {
      scoreEl.classList.remove('goal-pulse');
      void scoreEl.offsetWidth;   // restart the animation
      scoreEl.classList.add('goal-pulse');
    }
    play();
    setTimeout(function () { box.remove(); bits.forEach(function (x) { x.remove(); }); done(); }, 5000);
  }

  /** The 🔊 / 🔇 button by the score: Sounds on or off (the same switch as in Settings). */
  function muteButton() {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'btn btn-sm btn-link text-decoration-none p-0 ms-2 align-baseline';
    var draw = function () {
      b.textContent = soundOn() ? '🔊' : '🔇';
      b.title = soundOn() ? 'Match sounds on (click to mute)' : 'Match sounds muted (click to turn on)';
    };
    b.addEventListener('click', function () { setSound(!soundOn()); draw(); if (soundOn()) unlock(); });
    draw();
    return b;
  }

  return { parse: parse, check: check, checkCards: checkCards, checkStatus: checkStatus, checkPoints: checkPoints,
           phaseBanner: phaseBanner, windowOpen: windowOpen, chime: chime,
           reset: reset, stop: stop, celebrate: celebrate, card: card,
           play: play, whistle: whistle, muteButton: muteButton,
           soundOn: soundOn, setSound: setSound, animationsOn: animationsOn, setAnimations: setAnimations };
})();
