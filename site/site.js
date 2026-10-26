(function () {
  function select(tab, focus) {
    const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
    for (const other of tabs) {
      const on = other === tab;
      other.setAttribute('aria-selected', String(on));
      other.tabIndex = on ? 0 : -1;
      document.getElementById(other.getAttribute('aria-controls')).hidden = !on;
    }
    if (focus) tab.focus();
  }

  document.addEventListener('click', async (event) => {
    const motion = event.target.closest('[data-motion]');
    if (motion) {
      const paused = document.getElementById(motion.getAttribute('aria-controls')).classList.toggle('is-paused');
      motion.setAttribute('aria-pressed', String(paused));
      motion.textContent = paused ? 'Play demo' : 'Pause demo';
      return;
    }
    const tab = event.target.closest('[role="tab"]');
    if (tab) return select(tab, false);
    const button = event.target.closest('[data-copy]');
    if (!button) return;
    const label = button.querySelector('span');
    try {
      await navigator.clipboard.writeText(document.getElementById(button.dataset.copy).textContent);
      label.textContent = 'Copied';
    } catch {
      label.textContent = 'Select and copy';
    }
    setTimeout(() => { label.textContent = 'Copy'; }, 2000);
  });

  if ('IntersectionObserver' in window) {
    const watcher = new IntersectionObserver((entries) => {
      for (const entry of entries) entry.target.classList.toggle('is-away', !entry.isIntersecting || entry.intersectionRatio < 0.25);
    }, { threshold: 0.25 });
    for (const demo of document.querySelectorAll('.hero, .demo')) {
      demo.classList.add('is-away');
      watcher.observe(demo);
    }
  }

  document.addEventListener('keydown', (event) => {
    const tab = event.target.closest && event.target.closest('[role="tab"]');
    if (!tab || (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight')) return;
    const tabs = Array.from(document.querySelectorAll('[role="tab"]'));
    const step = event.key === 'ArrowRight' ? 1 : -1;
    select(tabs[(tabs.indexOf(tab) + step + tabs.length) % tabs.length], true);
    event.preventDefault();
  });
})();
