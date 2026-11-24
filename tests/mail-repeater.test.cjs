'use strict';
// Mail and Wi-Fi repeater pages: the wizard's checks, the PEM checks, the QR code encoder, and the mock endpoints they use.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const a = html.indexOf('// ---- Mail and repeater helpers'), b = html.indexOf('// ---- End mail and repeater helpers');
const ctx = vm.createContext({});
vm.runInContext(html.slice(a, b) + `\nthis.api = {${[...html.slice(a, b).matchAll(/^(?:async )?(?:function|const) (\w+)/gm)].map(m => m[1]).join(',')}};`, ctx);
const H = ctx.api, plain = x => JSON.parse(JSON.stringify(x));
test('markers are present and the section sits before the IPCam marker', () => { assert.ok(a > 0 && b > a); assert.ok(b < html.indexOf('// ---- IPCam and settings')); });

// ---- the setup wizard ----
test('domain and hostname checks', () => {
  assert.equal(H.mailDnsName(' Example.COM. '), 'example.com'); assert.equal(H.mailDnsName('localhost'), ''); assert.equal(H.mailDnsName('a b.com'), '');
  assert.equal(H.mailDnsName('-a.com'), ''); assert.equal(H.mailDnsName('1.2.3.4'), ''); assert.equal(H.mailDnsName('x.' + 'a'.repeat(64) + '.com'), '');
  const f = H.mailBlankForm();
  assert.match(H.mailStepErrors(0, f).domain, /Enter your domain/);
  Object.assign(f, {domain: 'example.com', hostname: ''}); assert.deepEqual(plain(H.mailStepErrors(0, f)), {});
  f.hostname = 'not a host'; assert.match(H.mailStepErrors(0, f).hostname, /mail\.example\.com/);
});
test('mode step needs one of the three modes', () => {
  const f = H.mailBlankForm(); assert.ok(H.mailStepErrors(1, f).mode);
  for (const m of ['direct', 'relay', 'receive_only']) { f.mode = m; assert.deepEqual(plain(H.mailStepErrors(1, f)), {}); }
});
test('detail checks: address, SPF, DMARC and report address', () => {
  const f = {...H.mailBlankForm(), domain: 'example.com', mode: 'direct'};
  assert.deepEqual(plain(H.mailStepErrors(2, f)), {});
  for (const ok of ['203.0.113.7', '2001:db8::1', 'myhome.duckdns.org', 'myhome.duckdns.org.']) { f.address = ok; assert.equal(H.mailStepErrors(2, f).address, undefined, ok); }
  for (const bad of ['999.1.1.1', 'nodots', 'a b']) { f.address = bad; assert.ok(H.mailStepErrors(2, f).address, bad); }
  f.address = ''; f.spf = 'include:spf.mailgun.org, spf.other.net'; assert.equal(H.mailStepErrors(2, f).spf, undefined);
  assert.deepEqual(plain(H.mailSplitSpf('include:Spf.Mailgun.org, spf.other.net\nthird.example.org')), ['spf.mailgun.org', 'spf.other.net', 'third.example.org']);
  f.spf = 'nodot'; assert.ok(H.mailStepErrors(2, f).spf); f.spf = 'a.com b.com c.com d.com e.com f.com'; assert.ok(H.mailStepErrors(2, f).spf);
  f.spf = ''; f.dmarc = 'maybe'; assert.ok(H.mailStepErrors(2, f).dmarc); f.dmarc = 'quarantine';
  f.report = 'postmaster@example.com'; assert.equal(H.mailStepErrors(2, f).report, undefined); f.report = 'nope'; assert.match(H.mailStepErrors(2, f).report, /postmaster@example\.com/);
});
test('relay mode needs a server, a port, a user and a password; receive-only treats the relay as optional', () => {
  const f = {...H.mailBlankForm(), domain: 'example.com', mode: 'relay'};
  assert.ok(H.mailStepErrors(2, f).relayHost);
  f.relay.host = 'smtp.example.net'; let e = H.mailStepErrors(2, f); assert.ok(e.relayUser); assert.ok(e.relayPass); assert.equal(e.relayPort, undefined);
  Object.assign(f.relay, {username: 'me', password: 'pw'}); assert.deepEqual(plain(H.mailStepErrors(2, f)), {});
  f.relay.port = '70000'; assert.ok(H.mailStepErrors(2, f).relayPort); f.relay.port = 'abc'; assert.ok(H.mailStepErrors(2, f).relayPort); f.relay.port = '465'; assert.equal(H.mailStepErrors(2, f).relayPort, undefined);
  f.relay.password = ''; f.relay.saved = true; assert.equal(H.mailStepErrors(2, f).relayPass, undefined, 'a saved password counts');
  f.relay.host = 'bad host'; assert.ok(H.mailStepErrors(2, f).relayHost);
  const r = {...H.mailBlankForm(), domain: 'example.com', mode: 'receive_only'}; assert.deepEqual(plain(H.mailStepErrors(2, r)), {});
  assert.equal(H.mailAllErrors({...r, mode: ''}).first, 1); assert.equal(H.mailAllErrors({...r, domain: ''}).first, 0); assert.equal(H.mailAllErrors(r).valid, true);
});
test('the setup body: defaults, lists, relay and the saved password', () => {
  const f = {...H.mailBlankForm(), domain: ' Example.com ', mode: 'relay', address: ' 203.0.113.7 ', spf: 'include:spf.x.net, spf.y.net', dmarc: 'quarantine', report: ' me@example.com '};
  Object.assign(f.relay, {host: 'SMTP.x.net', port: '465', username: ' me ', password: '', saved: true, tls: 'tls'});
  const body = plain(H.mailSetupBody(f));
  assert.deepEqual(body, {domain: 'example.com', hostname: 'mail.example.com', mode: 'relay', address: '203.0.113.7', spfInclude: ['spf.x.net', 'spf.y.net'], dmarcPolicy: 'quarantine', reportEmail: 'me@example.com',
    relay: {host: 'smtp.x.net', port: 465, username: 'me', password: '***', implicitTls: true}});
  assert.equal(H.mailSetupBody({...f, mode: 'direct'}).relay, null); f.relay.host = ''; assert.equal(H.mailSetupBody(f).relay, null);
  const back = H.mailFormFrom({domain: 'a.org', hostname: 'mx.a.org', mode: 'relay', address: '', spfInclude: ['s.x.net'], dmarcPolicy: 'reject', reportEmail: '', relay: {host: 'h.x.net', port: 587, username: 'u', password: '***', implicitTls: false}});
  assert.equal(back.hostnameEdited, true); assert.equal(back.spf, 's.x.net'); assert.equal(back.relay.saved, true); assert.equal(back.relay.tls, 'starttls'); assert.equal(back.relay.password, '');
  assert.equal(H.mailFormFrom({domain: 'a.org', hostname: 'mail.a.org', mode: 'direct', relay: null}).hostnameEdited, false);
});
test('certificate text checks', () => {
  const cert = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----', key = '-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----';
  assert.equal(H.mailPemProblem(cert, key), ''); assert.equal(H.mailPemProblem(cert, key.replace(/PRIVATE KEY/g, 'EC PRIVATE KEY')), '');
  assert.match(H.mailPemProblem('', key), /certificate/); assert.match(H.mailPemProblem(cert, cert), /private key/); assert.match(H.mailPemProblem(cert + 'x'.repeat(70000), key), /too long/);
});
test('grouping records and checks, and splitting long TXT values', () => {
  const g = H.mailGroupRecords({groups: [{id: 'essential', title: 'E'}, {id: 'optional', title: 'O'}, {id: 'empty', title: 'X'}], records: [{id: 1, group: 'optional'}, {id: 2, group: 'essential'}, {id: 3, group: 'weird'}]});
  assert.deepEqual(plain(g.map(x => [x.id, x.items.map(i => i.id)])), [['essential', [2]], ['optional', [1]], ['other', [3]]]);
  const c = H.mailGroupChecks([{group: 'dns', id: 'a'}, {group: 'ports', id: 'b'}, {group: 'server', id: 'c'}, {group: 'mystery', id: 'd'}]);
  assert.deepEqual(plain(c.map(x => x.id)), ['server', 'ports', 'dns', 'other']);
  assert.deepEqual(plain(H.mailChunks('a'.repeat(600))).map(s => s.length), [255, 255, 90]); assert.deepEqual(plain(H.mailChunks('short')), ['short']); assert.deepEqual(plain(H.mailChunks('')), ['']);
});

// ---- the repeater ----
test('repeater names, passphrases and traffic rates', () => {
  assert.equal(H.repeaterName(''), 'DIRECT-hd-Homedroid'); assert.equal(H.repeaterName('Guest net'), 'DIRECT-hd-Guest net'); assert.equal(H.repeaterName('DIRECT-ab-Mine'), 'DIRECT-ab-Mine');
  assert.equal(H.repeaterName('DIRECT-Mine'), 'DIRECT-hd-Mine'); assert.equal(H.repeaterName('x'.repeat(40)), 'DIRECT-hd-' + 'x'.repeat(22)); assert.equal(H.repeaterName('Café'), 'DIRECT-hd-Caf');
  assert.equal(H.repeaterPassOk('abcd-efgh-jkmn'), true); assert.equal(H.repeaterPassOk('short'), false); assert.equal(H.repeaterPassOk('x'.repeat(64)), false); assert.equal(H.repeaterPassOk('pässword12'), false);
  assert.deepEqual(plain(H.repeaterRates({bytesUp: 1000, bytesDown: 5000}, {bytesUp: 3000, bytesDown: 9000}, 2000)), {up: 1000, down: 2000});
  assert.deepEqual(plain(H.repeaterRates({bytesUp: 9000, bytesDown: 9000}, {bytesUp: 10, bytesDown: 10}, 2000)), {up: 0, down: 0}); assert.deepEqual(plain(H.repeaterRates(null, {bytesUp: 1, bytesDown: 1}, 1000)), {up: 0, down: 0});
  for (const s of ['off', 'waiting', 'starting', 'running', 'needs_permission', 'unsupported', 'error']) assert.ok(H.REPEATER_STATES[s].label, s);
});

// ---- QR code ----
// A decoder written separately from the encoder: it finds the function patterns itself, undoes the mask, de-interleaves, checks every
// Reed-Solomon block and reads the text back.
const GF = (() => { const e = [], l = []; for (let i = 0, x = 1; i < 255; i++) { e[i] = x; l[x] = i; x <<= 1; if (x > 255) x ^= 0x11d; } return {e, l}; })();
const gmul = (x, y) => x && y ? GF.e[(GF.l[x] + GF.l[y]) % 255] : 0;
const BLOCKS = {1: [10, 1, 16, 0, 0], 2: [16, 1, 28, 0, 0], 3: [26, 1, 44, 0, 0], 4: [18, 2, 32, 0, 0], 5: [24, 2, 43, 0, 0], 6: [16, 4, 27, 0, 0], 7: [18, 4, 31, 0, 0], 8: [22, 2, 38, 2, 39], 9: [22, 3, 36, 2, 37], 10: [26, 4, 43, 1, 44]};
const ALIGN = {1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50]};
function decode(m) {
  const n = m.length, v = (n - 17) / 4, fn = Array.from({length: n}, () => Array(n).fill(false));
  const mark = (x0, y0, w, h) => { for (let y = y0; y < y0 + h; y++) for (let x = x0; x < x0 + w; x++) if (x >= 0 && y >= 0 && x < n && y < n) fn[y][x] = true; };
  mark(0, 0, 9, 9); mark(n - 8, 0, 8, 9); mark(0, n - 8, 9, 8); mark(6, 0, 1, n); mark(0, 6, n, 1);
  const al = ALIGN[v]; al.forEach((cy, i) => al.forEach((cx, j) => { if (!((i === 0 && j === 0) || (i === 0 && j === al.length - 1) || (i === al.length - 1 && j === 0))) mark(cx - 2, cy - 2, 5, 5); }));
  if (v >= 7) { mark(n - 11, 0, 3, 6); mark(0, n - 11, 6, 3); }
  // format information, read from the copy beside the top-left finder and checked against the other copy
  const first = [], second = [];
  for (let i = 0; i <= 5; i++) first.push(m[i][8]); first.push(m[7][8], m[8][8], m[8][7]); for (let i = 9; i < 15; i++) first.push(m[8][14 - i]);
  for (let i = 0; i < 8; i++) second.push(m[8][n - 1 - i]); for (let i = 8; i < 15; i++) second.push(m[n - 15 + i][8]);
  assert.deepEqual(first, second, 'both format copies agree');
  const bits = first.reduce((s, bit, i) => s | (bit ? 1 << i : 0), 0) ^ 0x5412;
  assert.equal(bits >> 13, 0, 'error correction level M'); assert.equal(bits & 0x3ff, bch(bits >> 10, 0x537, 10), 'format BCH');
  const mask = bits >> 10 & 7;
  const maskFn = [(x, y) => (x + y) % 2 === 0, (x, y) => y % 2 === 0, (x, y) => x % 3 === 0, (x, y) => (x + y) % 3 === 0, (x, y) => ((y >> 1) + Math.floor(x / 3)) % 2 === 0, (x, y) => x * y % 2 + x * y % 3 === 0, (x, y) => (x * y % 2 + x * y % 3) % 2 === 0, (x, y) => ((x + y) % 2 + x * y % 3) % 2 === 0][mask];
  if (v >= 7) { // version information
    let bv = 0; for (let i = 0; i < 18; i++) if (m[Math.floor(i / 3)][n - 11 + i % 3]) bv |= 1 << i;
    assert.equal(bv >> 12, v, 'version bits'); assert.equal(bv & 0xfff, bch(v, 0x1f25, 12), 'version BCH');
  }
  const stream = []; let up = true;
  for (let x = n - 1; x > 0; x -= 2) {
    if (x === 6) x--;
    for (let k = 0; k < n; k++) { const y = up ? n - 1 - k : k; for (const xx of [x, x - 1]) if (!fn[y][xx]) stream.push((m[y][xx] !== maskFn(xx, y)) ? 1 : 0); }
    up = !up;
  }
  const [ecc, c1, d1, c2, d2] = BLOCKS[v], sizes = [...Array(c1).fill(d1), ...Array(c2).fill(d2)], total = sizes.reduce((s, x) => s + x, 0) + ecc * sizes.length;
  assert.equal(stream.length, total * 8 + (v === 1 ? 0 : v < 7 ? 7 : 0), 'data module count for version ' + v);
  const words = []; for (let i = 0; i < total; i++) words.push(parseInt(stream.slice(i * 8, i * 8 + 8).join(''), 2));
  const blocks = sizes.map(() => ({d: [], e: []})); let p = 0;
  for (let i = 0; i < Math.max(d1, d2); i++) blocks.forEach((bl, k) => { if (i < sizes[k]) bl.d.push(words[p++]); });
  for (let i = 0; i < ecc; i++) blocks.forEach(bl => bl.e.push(words[p++]));
  for (const bl of blocks) { // every codeword polynomial must vanish at the roots of the generator
    const cw = [...bl.d, ...bl.e];
    for (let r = 0; r < ecc; r++) { let s = 0; for (const c of cw) s = gmul(s, GF.e[r]) ^ c; assert.equal(s, 0, 'Reed-Solomon syndrome'); }
  }
  const data = blocks.flatMap(bl => bl.d), bitstr = data.map(x => x.toString(2).padStart(8, '0')).join('');
  assert.equal(bitstr.slice(0, 4), '0100', 'byte mode'); const cb = v < 10 ? 8 : 16, len = parseInt(bitstr.slice(4, 4 + cb), 2);
  const out = []; for (let i = 0; i < len; i++) out.push(parseInt(bitstr.slice(4 + cb + i * 8, 12 + cb + i * 8), 2));
  return {version: v, mask, text: Buffer.from(out).toString('utf8')};
}
function bch(data, gen, bitsLen) { let r = data << bitsLen; const top = 31 - Math.clz32(gen); for (let i = 31 - Math.clz32(r || 1); i >= top; i--) if (r >> i & 1) r ^= gen << (i - top); return r; }
test('QR: the Reed-Solomon check words match the published HELLO WORLD example (version 1, level M)', () => {
  const data = [32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17];
  assert.deepEqual(plain(H.qrEcc(data, 10)), [196, 35, 39, 119, 235, 215, 231, 226, 93, 23]);
});
test('QR: format and version bit patterns match the standard', () => {
  assert.equal(H.qrFormatBits(0), 0b101010000010010); assert.equal(H.qrFormatBits(1), 0b101000100100101); assert.equal(H.qrFormatBits(5), 0b100000011001110); assert.equal(H.qrFormatBits(7), 0b100101010100000);
  assert.equal(H.qrVersionBits(7), 0x07c94); assert.equal(H.qrVersionBits(8), 0x085bc); assert.equal(H.qrVersionBits(10), 0x0a4d3);
});
test('QR: capacities at level M and the tables add up', () => {
  assert.deepEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map(v => H.qrCapacity(v)), [14, 26, 42, 62, 84, 106, 122, 152, 180, 213]);
  const total = [26, 44, 70, 100, 134, 172, 196, 242, 292, 346];
  for (let v = 1; v <= 10; v++) { const [e, g] = H.QR_M[v]; assert.equal(g.reduce((s, [c, d]) => s + c * (d + e), 0), total[v - 1], 'codewords in version ' + v); }
});
test('QR: finder, timing and dark module are where the standard puts them', () => {
  const q = H.qrEncode('hi'), m = q.modules; assert.equal(q.version, 1); assert.equal(q.size, 21);
  for (const [x, y] of [[0, 0], [6, 0], [0, 6], [6, 6], [2, 2], [4, 4], [14, 0], [20, 6], [0, 14], [6, 20]]) assert.equal(m[y][x], true, `finder ${x},${y}`);
  for (const [x, y] of [[1, 1], [5, 5], [7, 7], [13, 1], [1, 13], [7, 0]]) assert.equal(m[y][x], false, `light ${x},${y}`);
  for (let i = 8; i < 13; i++) { assert.equal(m[6][i], i % 2 === 0); assert.equal(m[i][6], i % 2 === 0); }
  assert.equal(m[13][8], true, 'dark module');
});
test('QR: encode, decode and read back, from version 1 to 10', () => {
  const lengths = [1, 14, 15, 26, 27, 42, 43, 62, 63, 84, 85, 106, 107, 122, 123, 152, 153, 180, 181, 213];
  const seen = new Set();
  for (const n of lengths) {
    const text = Array.from({length: n}, (_, i) => String.fromCharCode(33 + (i * 7 + n) % 90)).join('');
    const q = H.qrEncode(text), d = decode(q.modules.map(r => [...r]));
    assert.equal(d.text, text, 'length ' + n); assert.equal(d.version, q.version); assert.equal(d.mask, q.mask); seen.add(q.version);
  }
  assert.equal(seen.size, 10, 'all ten versions used');
});
test('QR: a real Wi-Fi payload, accents and the size limit', () => {
  const payload = 'WIFI:T:WPA;S:DIRECT-hd-Homedroid;P:abcd-efgh-jkmn;;', q = H.qrEncode(payload), d = decode(q.modules.map(r => [...r]));
  assert.equal(d.text, payload); assert.ok(q.version <= 4);
  assert.equal(decode(H.qrEncode('Café ☕').modules.map(r => [...r])).text, 'Café ☕');
  const longest = 'WIFI:T:WPA;S:' + 'DIRECT-hd-' + 'n'.repeat(22) + ';P:' + 'p'.repeat(63) + ';;'; assert.ok(longest.length < 213); assert.equal(decode(H.qrEncode(longest).modules.map(r => [...r])).text, longest);
  assert.throws(() => H.qrEncode('x'.repeat(214)), /too long/);
});
test('QR: the SVG is a black-on-white square with a four-module quiet zone', () => {
  const q = H.qrEncode('WIFI:T:WPA;S:DIRECT-hd-Homedroid;P:abcd-efgh-jkmn;;'), side = q.size + 8;
  const svg = H.qrSvg(q, 'Wi-Fi "join" code'); assert.ok(svg.startsWith(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${side} ${side}"`)); assert.ok(svg.includes('fill="#fff"') && svg.includes('fill="#000"')); assert.ok(!svg.includes('"join"'));
  const dark = [...svg.matchAll(/h(\d+)v1/g)].reduce((s, x) => s + +x[1], 0); assert.equal(dark, q.modules.flat().filter(Boolean).length);
  assert.ok(Number.isFinite(q.penalty) && q.mask >= 0 && q.mask < 8);
});

// ---- The mock endpoints behind the pages ----
const {createMockServer} = require('./dashboard-mock/server.cjs');
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function mock(t) {
  const server = createMockServer({log() {}, delayMs: 10, jobStepMs: 1});
  await new Promise(r => server.listen(0, '127.0.0.1', r)); t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  return async (route, method = 'GET', body) => {
    const res = await fetch(base + '/api/' + route, {method, headers: {Authorization: 'Bearer demo', ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, body: body === undefined ? undefined : JSON.stringify(body)});
    return {status: res.status, data: await res.json().catch(() => null)};
  };
}
const setup = {domain: 'example.org', hostname: '', mode: 'direct', address: '203.0.113.7', spfInclude: [], dmarcPolicy: 'none', reportEmail: '', relay: null};
test('mock mail: not installed, install through Modules, then setup with the dashboard\'s own checks', async t => {
  const r = await mock(t), s = (await r('mail')).data;
  assert.equal(s.phase, 'not_installed'); assert.equal(s.setup, null); assert.equal(s.admin, null); assert.equal(s.modes.length, 3); assert.ok(s.limits.length >= 5); assert.equal(s.ports.length, 5); assert.ok(s.ports.every(p => !p.listening));
  assert.equal((await r('mail/setup', 'POST', setup)).status, 409); assert.equal((await r('mail/dns')).status, 409); assert.equal((await r('mail/accounts')).status, 409);
  assert.equal((await r('modules/stalwart/install', 'POST')).status, 200); await sleep(300);
  assert.equal((await r('mail')).data.phase, 'needs_setup');
  for (const bad of [{...setup, domain: 'nodot'}, {...setup, mode: 'x'}, {...setup, address: 'a b'}, {...setup, mode: 'relay'}, {...setup, mode: 'relay', relay: {host: 'smtp.x.net', port: 587, username: 'u', password: ''}}, {...setup, dmarcPolicy: 'maybe'}]) {
    const x = await r('mail/setup', 'POST', bad); assert.equal(x.status, 400, JSON.stringify(bad)); assert.ok(x.data.error);
  }
  const ok = await r('mail/setup', 'POST', {...setup, mode: 'relay', spfInclude: ['spf.x.net'], relay: {host: 'smtp.x.net', port: 587, username: 'u', password: 'secret1'}});
  assert.equal(ok.status, 200); assert.equal(ok.data.phase, 'starting'); assert.equal(ok.data.setup.hostname, 'mail.example.org'); assert.equal(ok.data.setup.relay.password, '***');
  assert.equal((await r('mail/accounts')).status, 409, 'the server is still starting');
  const kept = await r('mail/setup', 'POST', {...ok.data.setup, relay: {...ok.data.setup.relay, password: '***'}}); assert.equal(kept.status, 200); assert.equal(kept.data.setup.relay.password, '***');
});
test('mock mail: DNS records, the zone, health checks and mail app settings', async t => {
  const r = await mock(t);
  assert.equal((await r('mail/_mock', 'POST', {phase: 'starting'})).data.phase, 'starting');
  let dns = (await r('mail/dns')).data; assert.equal(dns.dkimReady, false); assert.ok(dns.records.find(x => x.id === 'dkim').pending);
  assert.equal((await r('mail/_mock', 'POST', {phase: 'ready'})).data.phase, 'ready');
  dns = (await r('mail/dns')).data; assert.equal(dns.dkimReady, true);
  const dkim = dns.records.find(x => x.id === 'dkim-rsa'); assert.ok(dkim.value.length > 255); assert.ok(dkim.chunks.length >= 2); assert.equal(dkim.chunks.join(''), dkim.value); assert.match(dkim.zone, /^rsa\._domainkey\.example\.com\. IN TXT \("/);
  const mx = dns.records.find(x => x.id === 'mx'); assert.equal(mx.priority, 10); assert.equal(mx.host, '@'); assert.match(mx.zone, /IN MX 10 mail\.example\.com\.$/);
  assert.ok(dns.records.every(x => dns.groups.some(g => g.id === x.group))); assert.ok(!dns.zone.includes('<')); assert.ok(dns.records.find(x => x.id === 'mta-sts').extra);
  const c = (await r('mail/check', 'POST')).data; assert.ok(c.checks.every(x => ['ok', 'warn', 'fail', 'info'].includes(x.status))); assert.equal(c.summary.ok + c.summary.warn + c.summary.fail, c.checks.filter(x => x.status !== 'info').length);
  assert.ok(c.checks.some(x => x.status === 'fail' && x.fix)); assert.ok(c.checks.every(x => ['server', 'ports', 'tls', 'network', 'dns'].includes(x.group))); assert.ok(c.note);
  const cl = (await r('mail/clients?user=Alice@example.com')).data; assert.equal(cl.email, 'alice@example.com'); assert.equal(cl.away.incoming.port, 993); assert.equal(cl.home.incoming.port, 9930); assert.equal(cl.away.outgoing.alternative.port, 587); assert.equal(cl.steps.length, 4);
  assert.equal((await r('mail/clients?user=%25%25')).data.email, 'you@example.com');
});
test('mock mail: accounts, passwords and the certificate', async t => {
  const r = await mock(t); await r('mail/_mock', 'POST', {phase: 'ready'});
  const list = (await r('mail/accounts')).data.accounts; assert.ok(list.some(a => a.admin)); assert.ok(list.some(a => !a.admin));
  assert.equal((await r('mail/accounts', 'POST', {name: 'Bad Name'})).status, 400); assert.equal((await r('mail/accounts', 'POST', {name: 'carol', password: 'short'})).status, 400);
  const made = (await r('mail/accounts', 'POST', {name: 'carol'})).data; assert.equal(made.email, 'carol@example.com'); assert.equal(made.generated, true); assert.ok(made.password.length >= 12);
  assert.equal((await r('mail/accounts', 'POST', {name: 'carol'})).status, 409);
  assert.equal((await r('mail/accounts', 'POST', {name: 'dave', password: 'my own password'})).data.generated, false);
  assert.equal((await r(`mail/accounts/${made.id}/password`, 'POST', {})).data.generated, true); assert.equal((await r(`mail/accounts/${made.id}/password`, 'POST', {password: 'x'})).status, 400); assert.equal((await r('mail/accounts/nope/password', 'POST', {})).status, 404);
  const admin = list.find(a => a.admin); assert.equal((await r('mail/accounts/' + admin.id, 'DELETE')).status, 409);
  assert.equal((await r('mail/accounts/' + made.id, 'DELETE')).status, 200); assert.equal((await r('mail/accounts/' + made.id, 'DELETE')).status, 404);
  const cert = '-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----', key = '-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----';
  assert.equal((await r('mail/certificate', 'POST', {certificate: 'x', privateKey: key})).status, 400); assert.equal((await r('mail/certificate', 'POST', {certificate: cert, privateKey: cert})).status, 400);
  assert.equal((await r('mail/certificate', 'POST', {certificate: cert, privateKey: key})).status, 200); await sleep(2100);
  assert.equal((await r('mail/check', 'POST')).data.checks.find(x => x.id === 'tls').status, 'ok');
  assert.equal((await r('mail/_mock', 'POST', {phase: 'bogus'})).status, 400);
});
test('mock repeater: switch, permission, settings and traffic', async t => {
  const r = await mock(t), s = (await r('repeater')).data;
  assert.equal(s.state, 'off'); assert.equal(s.enabled, false); assert.equal(s.network.name, 'DIRECT-hd-Homedroid'); assert.equal(s.proxy.allowLan, false); assert.equal(s.instructions.length, 6); assert.ok(s.limits.length >= 4);
  assert.equal(s.wifiQr, `WIFI:T:WPA;S:${s.network.name};P:${s.network.passphrase};;`);
  assert.equal((await r('repeater', 'POST', {passphrase: 'short'})).status, 400); assert.equal((await r('repeater', 'POST', {band: '6'})).status, 400);
  const set = (await r('repeater', 'POST', {name: 'Guest', band: '5', allowLan: true})).data; assert.equal(set.network.name, 'DIRECT-hd-Guest'); assert.equal(set.network.band, '5'); assert.equal(set.proxy.allowLan, true);
  const regen = (await r('repeater', 'POST', {regenerate: true})).data; assert.notEqual(regen.network.passphrase, s.network.passphrase); assert.ok(H.repeaterPassOk(regen.network.passphrase));
  await r('repeater/_mock', 'POST', {state: 'needs_permission'}); const denied = await r('repeater', 'POST', {enabled: true}); assert.equal(denied.status, 409); assert.match(denied.data.error, /Nearby devices/);
  await r('repeater/_mock', 'POST', {state: 'unsupported'}); assert.equal((await r('repeater', 'POST', {enabled: true})).status, 409); assert.equal((await r('repeater')).data.state, 'unsupported');
  const m = await mock(t); assert.equal((await m('repeater', 'POST', {enabled: true})).data.state, 'starting'); await sleep(1700);
  const run = (await m('repeater')).data; assert.equal(run.state, 'running'); assert.equal(run.network.address, '192.168.49.1'); assert.equal(run.clients.count, 2); assert.equal(run.proxy.http, '192.168.49.1:8282'); assert.equal(run.proxy.pac, 'http://192.168.49.1:8283/proxy.pac');
  const later = (await m('repeater')).data; assert.ok(later.traffic.bytesDown > run.traffic.bytesDown && later.traffic.bytesUp > run.traffic.bytesUp);
  assert.equal((await m('repeater', 'POST', {enabled: false})).data.state, 'off'); assert.equal((await m('repeater')).data.clients.count, 0);
});
