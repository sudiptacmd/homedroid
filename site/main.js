// Progressive enhancement: content and downloads work without JavaScript.
for (const gallery of document.querySelectorAll('[data-gallery]')) {
  const tabs = [...gallery.querySelectorAll('[data-tab]')];
  const panels = [...gallery.querySelectorAll('[data-panel]')];
  const list = gallery.querySelector('.tablist');
  list.hidden = false;
  list.setAttribute('role', 'tablist');
  function select(index, focus = false) {
    tabs.forEach((tab, i) => {
      tab.setAttribute('aria-selected', String(i === index));
      tab.tabIndex = i === index ? 0 : -1;
      panels[i].hidden = i !== index;
    });
    if (focus) tabs[index].focus();
  }
  tabs.forEach((tab, i) => {
    tab.id = `tab-${tab.dataset.tab}`;
    tab.setAttribute('role', 'tab');
    panels[i].setAttribute('role', 'tabpanel');
    panels[i].setAttribute('aria-labelledby', tab.id);
    panels[i].tabIndex = 0;
    tab.addEventListener('click', () => select(i));
    tab.addEventListener('keydown', e => {
      const next = e.key === 'ArrowRight' ? (i + 1) % tabs.length : e.key === 'ArrowLeft' ? (i + tabs.length - 1) % tabs.length : e.key === 'Home' ? 0 : e.key === 'End' ? tabs.length - 1 : -1;
      if (next >= 0) { e.preventDefault(); select(next, true); }
    });
  });
  select(0);
}
// OS is a hint, never an automatic download or an architecture guess.
const platform = navigator.userAgentData?.platform || navigator.platform || '';
const ua = navigator.userAgent;
const os = /Android|iPhone|iPad|iPod/i.test(ua) || (/Mac/i.test(platform) && navigator.maxTouchPoints > 1) ? '' : /Win/i.test(platform) ? 'windows' : /Mac/i.test(platform) ? 'macos' : /Linux/i.test(platform) ? 'linux' : '';
if (os) {
  document.querySelectorAll(`[data-os="${os}"]`).forEach(a => {
    a.classList.add('detected');
    a.querySelector('.os-hint').textContent = 'Your OS';
  });
  const note = document.getElementById('os-note');
  if (note) note.textContent = `Highlighted for ${ {linux:'Linux',windows:'Windows',macos:'macOS'}[os] }. Check your CPU architecture before downloading.`;
}
// Media starts only on request, including for reduced-motion users.
for (const video of document.querySelectorAll('video')) video.addEventListener('play', () => {
  for (const other of document.querySelectorAll('video')) if (other !== video) other.pause();
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden) document.querySelectorAll('video').forEach(v => v.pause());
});
// Live GitHub star count; the links work without it.
(async () => {
  const slots = document.querySelectorAll('[data-stars]');
  if (!slots.length) return;
  const key = 'hd-stars';
  let n;
  try {
    const c = JSON.parse(sessionStorage.getItem(key) || 'null');
    if (c && Date.now() - c.t < 36e5) n = c.n;
  } catch {}
  if (n == null) {
    try {
      const r = await fetch('https://api.github.com/repos/sudiptacmd/homedroid', {headers: {Accept: 'application/vnd.github+json'}});
      if (!r.ok) return;
      n = (await r.json()).stargazers_count;
      if (typeof n !== 'number') return;
      try { sessionStorage.setItem(key, JSON.stringify({n, t: Date.now()})); } catch {}
    } catch { return; }
  }
  const text = n >= 1000 ? (n / 1000).toFixed(n >= 1e4 ? 0 : 1).replace(/\.0$/, '') + 'k' : String(n);
  slots.forEach(s => { s.textContent = text; s.hidden = false; s.title = `${n.toLocaleString()} stars`; });
})();
