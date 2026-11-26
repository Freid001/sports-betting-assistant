// Loads the page scripts (src/main/resources/static/js) into a sandbox with a small fake browser,
// so they can be tested with Node alone:  node --test "src/test/js/*.test.js"
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { createDocument, Node, Event } = require('./fake-dom');

const JS = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js');

/** A fake fetch: routes['POST /api/x'] or routes['/api/x'] = body | (url, opts) => body | { status, body }. */
function fakeFetch(routes, calls) {
  return (url, opts) => {
    const method = (opts && opts.method) || 'GET';
    calls.push({ url, method, body: opts && opts.body });
    let r = routes[method + ' ' + url] !== undefined ? routes[method + ' ' + url] : routes[url];
    if (r === undefined) {
      const prefix = Object.keys(routes).find(k => k.endsWith('*') && url.startsWith(k.slice(0, -1)));
      r = prefix ? routes[prefix] : undefined;
    }
    if (typeof r === 'function') r = r(url, opts);
    if (r instanceof Error) return Promise.reject(r);
    if (r === undefined) r = { status: 404, body: { error: 'Not Found', path: url } };   // like Spring's 404 for a route the app doesn't have
    const res = r && r.status !== undefined && r.body !== undefined ? r : { status: 200, body: r };
    return Promise.resolve({
      ok: res.status >= 200 && res.status < 300, status: res.status,
      json: () => Promise.resolve(JSON.parse(JSON.stringify(res.body))),
      text: () => Promise.resolve(JSON.stringify(res.body))
    });
  };
}

function load(files, options) {
  options = options || {};
  const store = Object.assign({}, options.storage || {});
  const document = createDocument();
  const notes = [];
  const calls = [];
  const modals = [];
  const sandbox = {
    console, Math, Date, JSON, Promise, Number, String, Array, Object, isNaN, parseInt, parseFloat, Node, Event,
    setTimeout, clearTimeout, setInterval: () => 0, clearInterval: () => {},
    document,
    localStorage: { getItem: k => (k in store ? store[k] : null), setItem: (k, v) => { store[k] = String(v); }, removeItem: k => { delete store[k]; } },
    notify: (message, type) => notes.push({ message, type }),
    bootstrap: {
      Modal: { getOrCreateInstance: el => { const m = { el, shown: false, show() { this.shown = true; }, hide() { this.shown = false; } }; modals.push(m); return m; } },
      Tooltip: { getInstance: () => null, getOrCreateInstance: () => ({ show() {} }) }
    },
    fetch: fakeFetch(options.routes || {}, calls)
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  for (const f of [].concat(files)) {
    vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), sandbox, { filename: path.join(JS, f) });
  }
  sandbox.notes = notes;
  sandbox.calls = calls;
  sandbox.modals = modals;
  sandbox.store = store;
  sandbox.routes = options.routes || {};
  return sandbox;
}

/** Lets pending promises (fake fetches) finish. */
function settle() { return new Promise(r => setTimeout(r, 0)).then(() => new Promise(r => setTimeout(r, 0))).then(() => new Promise(r => setTimeout(r, 5))); }

/** Copies a value made inside the sandbox into a plain one, so deepEqual can compare it. */
function plain(v) { return JSON.parse(JSON.stringify(v)); }

module.exports = { load, settle, plain };
