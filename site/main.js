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
