// A small fake browser (document, elements, events, selectors, innerHTML) – just enough of the DOM
// for the page scripts to build and click through their pop-ups and cards in tests.

const VOID = new Set(['input', 'img', 'br', 'hr', 'meta', 'link']);

class Node {}

class Text extends Node {
  constructor(text) { super(); this.nodeType = 3; this.data = String(text); this.parentNode = null; }
  get textContent() { return this.data; }
}

class ClassList {
  constructor(el) { this.el = el; }
  _get() { return this.el.className.split(/\s+/).filter(Boolean); }
  _set(list) { this.el.className = list.join(' '); }
  add(...c) { const l = this._get(); c.forEach(x => { if (!l.includes(x)) l.push(x); }); this._set(l); }
  remove(...c) { this._set(this._get().filter(x => !c.includes(x))); }
  contains(c) { return this._get().includes(c); }
  toggle(c, force) {
    const on = force === undefined ? !this.contains(c) : !!force;
    if (on) this.add(c); else this.remove(c);
    return on;
  }
  replace(a, b) { if (!this.contains(a)) return false; this._set(this._get().map(x => (x === a ? b : x))); return true; }
}

class Element extends Node {
  constructor(tag, doc) {
    super();
    this.nodeType = 1;
    this.tagName = String(tag).toUpperCase();
    this.ownerDocument = doc;
    this.childNodes = [];
    this.parentNode = null;
    this.attributes = {};
    this.className = '';
    this.style = {};
    this.classList = new ClassList(this);
    this.listeners = {};
    this.value = '';
    this.checked = false;
    this.disabled = false;
  }
  get children() { return this.childNodes.filter(n => n instanceof Element); }
  get id() { return this.attributes.id || ''; }
  set id(v) { this.attributes.id = String(v); }
  get textContent() { return this.childNodes.map(n => n.textContent).join(''); }
  set textContent(v) { this.replaceChildren(); if (v !== '' && v !== null && v !== undefined) this.appendChild(new Text(v)); }
  get innerText() { return this.textContent; }
  get isConnected() { let n = this; while (n.parentNode) n = n.parentNode; return n === this.ownerDocument.documentElement; }
  set innerHTML(html) { this.replaceChildren(...parseHtml(html, this.ownerDocument)); }

  _adopt(n) {
    if (typeof n === 'string') n = new Text(n);
    if (n.parentNode) n.parentNode._removeChild(n);
    n.parentNode = this;
    return n;
  }
  _removeChild(n) { const i = this.childNodes.indexOf(n); if (i >= 0) this.childNodes.splice(i, 1); n.parentNode = null; }
  appendChild(n) { this.childNodes.push(this._adopt(n)); return n; }
  append(...ns) { ns.forEach(n => this.appendChild(n)); }
  prepend(...ns) { ns.reverse().forEach(n => this.childNodes.unshift(this._adopt(n))); }
  replaceChildren(...ns) { this.childNodes.slice().forEach(c => this._removeChild(c)); this.append(...ns); }
  remove() { if (this.parentNode) this.parentNode._removeChild(this); }
  after(...ns) {
    const p = this.parentNode; if (!p) return;
    let i = p.childNodes.indexOf(this) + 1;
    ns.forEach(n => { n = p._adopt(n); p.childNodes.splice(i++, 0, n); });
  }
  setAttribute(k, v) {
    this.attributes[k] = String(v);
    if (k === 'class') this.className = String(v);
    if (['type', 'value', 'placeholder', 'title', 'href', 'src', 'for'].includes(k)) this[k === 'for' ? 'htmlFor' : k] = String(v);
    if (k === 'checked') this.checked = true;
    if (k === 'disabled') this.disabled = true;
  }
  getAttribute(k) { return k === 'class' ? this.className : (k in this.attributes ? this.attributes[k] : null); }
  hasAttribute(k) { return k in this.attributes || (k === 'class' && !!this.className); }
  removeAttribute(k) { delete this.attributes[k]; }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  removeEventListener(type, fn) { this.listeners[type] = (this.listeners[type] || []).filter(f => f !== fn); }
  dispatchEvent(ev) {
    ev.target = ev.target || this;
    let n = this;
    while (n && !ev._stopped) {
      (n.listeners[ev.type] || []).forEach(f => f.call(n, ev));
      if (typeof n['on' + ev.type] === 'function') n['on' + ev.type].call(n, ev);
      n = ev.bubbles === false ? null : n.parentNode;
    }
    return true;
  }
  click() {
    if (this.disabled) return;
    if (this.tagName === 'INPUT' && this.type === 'checkbox') { this.checked = !this.checked; this.dispatchEvent(new Event('click')); this.dispatchEvent(new Event('change')); return; }
    this.dispatchEvent(new Event('click'));
  }
  matches(sel) { return sel.split(',').some(s => matchCompound(this, s.trim())); }
  closest(sel) { let n = this; while (n instanceof Element) { if (n.matches(sel)) return n; n = n.parentNode; } return null; }
  querySelectorAll(sel) {
    const out = [];
    const parts = sel.trim().split(/\s+/);
    const walk = n => n.children.forEach(c => { if (matchChain(c, parts, this)) out.push(c); walk(c); });
    walk(this);
    return out;
  }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  contains(n) { while (n) { if (n === this) return true; n = n.parentNode; } return false; }
}

class Event {
  constructor(type, init) { this.type = type; this.bubbles = !init || init.bubbles !== false; this.target = null; }
  stopPropagation() { this._stopped = true; }
  preventDefault() { this.defaultPrevented = true; }
}

// ---- selectors: tag, #id, .class, [attr], [attr=value], descendant (space), comma lists ----
function matchCompound(el, s) {
  const re = /^([a-zA-Z0-9*]+)?((?:#[\w-]+|\.[\w-]+|\[[^\]]+\])*)$/;
  const m = re.exec(s);
  if (!m) throw new Error('fake-dom: unsupported selector ' + s);
  if (m[1] && m[1] !== '*' && el.tagName !== m[1].toUpperCase()) return false;
  const bits = m[2].match(/#[\w-]+|\.[\w-]+|\[[^\]]+\]/g) || [];
  return bits.every(b => {
    if (b[0] === '#') return el.id === b.slice(1);
    if (b[0] === '.') return el.classList.contains(b.slice(1));
    const a = /^\[([\w-]+)(?:=["']?([^"'\]]*)["']?)?\]$/.exec(b);
    const val = a[1] === 'type' ? el.type : el.getAttribute(a[1]);
    return a[2] === undefined ? (a[1] === 'type' ? el.type !== undefined : el.hasAttribute(a[1])) : String(val) === a[2];
  });
}
function matchChain(el, parts, root) {
  if (!matchCompound(el, parts[parts.length - 1])) return false;
  let i = parts.length - 2, n = el.parentNode;
  while (i >= 0 && n && n !== root.parentNode) {
    if (n instanceof Element && matchCompound(n, parts[i])) i--;
    n = n.parentNode;
  }
  return i < 0;
}

// ---- innerHTML: simple, well-formed HTML only ----
function parseHtml(html, doc) {
  const root = new Element('div', doc);
  const stack = [root];
  const re = /<(\/?)([a-zA-Z0-9]+)([^>]*)>|([^<]+)/g;
  let m;
  while ((m = re.exec(html))) {
    const top = stack[stack.length - 1];
    if (m[4] !== undefined) { if (m[4].trim()) top.appendChild(new Text(decode(m[4]))); continue; }
    if (m[1]) { stack.pop(); continue; }
    const el = new Element(m[2], doc);
    const attrRe = /([\w-:]+)(?:="([^"]*)")?/g;
    let a;
    while ((a = attrRe.exec(m[3]))) el.setAttribute(a[1], a[2] === undefined ? '' : decode(a[2]));
    top.appendChild(el);
    if (!VOID.has(m[2].toLowerCase()) && !/\/\s*$/.test(m[3])) stack.push(el);
  }
  return root.childNodes.slice();
}
function decode(s) { return s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'"); }

function createDocument() {
  const doc = { listeners: {} };
  doc.createElement = tag => new Element(tag, doc);
  doc.createElementNS = (ns, tag) => new Element(tag, doc);
  doc.createTextNode = t => new Text(t);
  doc.documentElement = new Element('html', doc);
  doc.body = new Element('body', doc);
  doc.documentElement.appendChild(doc.body);
  doc.getElementById = id => doc.documentElement.querySelector('#' + id);
  doc.querySelector = s => doc.documentElement.querySelector(s);
  doc.querySelectorAll = s => doc.documentElement.querySelectorAll(s);
  doc.addEventListener = (t, f) => { (doc.listeners[t] = doc.listeners[t] || []).push(f); };
  return doc;
}

module.exports = { createDocument, Element, Node, Text, Event };
