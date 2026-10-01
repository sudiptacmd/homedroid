// Run with node --test tests/mobile-dashboard.test.cjs (no dependencies).
const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const full = html.split('<script>')[1].split('</script>')[0];
const script = full.slice(full.indexOf('// ---- Mobile sidebar'), full.indexOf('// ---- Routing'));

function setup(mobile = true) {
  const nodes = new Map(), listeners = {};
  const classes = new Set();
  const document = {activeElement: null, body: {classList: {toggle(name, on) { on ? classes.add(name) : classes.delete(name); }}},
    addEventListener(name, handler) { listeners[name] = handler; }};
  function $(id) {
    if (!nodes.has(id)) nodes.set(id, {attributes: {}, inert: false, hidden: false, offsetParent: {},
      setAttribute(k, v) { this.attributes[k] = v; }, removeAttribute(k) { delete this.attributes[k]; },
      addEventListener(k, v) { this[k] = v; }, focus() { document.activeElement = this; },
      querySelectorAll() { return [$('#navClose'), $('#firstLink'), $('#lastLink')]; }});
    return nodes.get(id);
  }
  const media = {matches: mobile, addEventListener(name, handler) { this.change = handler; }};
  const context = vm.createContext({$, document, matchMedia: () => media});
  vm.runInContext(script, context);
  return {$, document, classes, media, listeners, run: code => vm.runInContext(code, context)};
}

test('mobile navigation opens a modal sidebar and makes the background inaccessible', () => {
  const t = setup();
  assert.equal(t.$('#sidebar').inert, true);
  t.$('#navToggle').onclick();
  assert.equal(t.$('#sidebar').inert, false);
  assert.equal(t.$('#sidebar').attributes['aria-modal'], 'true');
  assert.equal(t.$('#navToggle').attributes['aria-expanded'], 'true');
  assert.equal(t.$('main').inert, true);
  assert.equal(t.$('.top').inert, true);
  assert.equal(t.$('#navBackdrop').hidden, false);
  assert.equal(t.document.activeElement, t.$('#navClose'));
});
test('outside tap, Escape and a link to the current page all dismiss the drawer', () => {
  const t = setup();
  for (const close of [() => t.$('#navBackdrop').onclick(), () => t.listeners.keydown({key: 'Escape', preventDefault() {}}),
    () => t.$('#sidebar').click({target: {closest: () => ({})}})]) {
    t.$('#navToggle').onclick(); close();
    assert.equal(t.$('#navToggle').attributes['aria-expanded'], 'false');
    assert.equal(t.$('#sidebar').inert, true);
    assert.equal(t.$('main').inert, false);
    assert.equal(t.document.activeElement, t.$('#navToggle'));
  }
});
test('keyboard focus stays in the open sidebar in both directions', () => {
  const t = setup(); t.$('#navToggle').onclick();
  t.$('#lastLink').focus();
  t.listeners.keydown({key: 'Tab', shiftKey: false, preventDefault() {}});
  assert.equal(t.document.activeElement, t.$('#navClose'));
  t.listeners.keydown({key: 'Tab', shiftKey: true, preventDefault() {}});
  assert.equal(t.document.activeElement, t.$('#lastLink'));
});
test('resizing to desktop restores persistent navigation and unlocks the page', () => {
  const t = setup(); t.$('#navToggle').onclick();
  t.media.matches = false; t.media.change();
  assert.equal(t.$('#sidebar').inert, false);
  assert.equal(t.$('#sidebar').attributes['aria-hidden'], undefined);
  assert.equal(t.$('#sidebar').attributes['role'], undefined);
  assert.equal(t.$('main').inert, false);
  assert.equal(t.classes.has('nav-open'), false);
});
test('each page has one navigation entry, including optional cameras, and no bottom tab bar', () => {
  assert.ok(!html.includes('class="tabbar"'));
  const nav = html.match(/<nav class="nav"[\s\S]*?<\/nav>/)[0];
  for (const page of ['overview', 'modules', 'deploys', 'files', 'ssh', 'ai', 'camera', 'cameras', 'cluster', 'settings']) {
    assert.equal((nav.match(new RegExp(`data-tab="${page}"`, 'g')) || []).length, 1);
  }
});
