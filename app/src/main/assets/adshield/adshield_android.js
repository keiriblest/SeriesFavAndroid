(function() {
    // 1. Limpieza de Overlays (Nuke)
    function nuke(el) { el.remove(); }
    
    function scan() {
        document.querySelectorAll('[style*="2147483647"], [style*="z-index:99999"]').forEach(el => {
            if (el.querySelector('iframe') || el.classList.contains('ad-overlay')) nuke(el);
        });
    }

    // 2. Bloqueo de clics en iframes/popups (Evita toques fantasma)
    document.addEventListener('click', (e) => {
        if (e.target.closest('iframe') || e.target.closest('.ad-overlay')) {
            e.preventDefault();
            e.stopPropagation();
            return false;
        }
    }, true);

    const observer = new MutationObserver(scan);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    setInterval(scan, 1000);
})();
