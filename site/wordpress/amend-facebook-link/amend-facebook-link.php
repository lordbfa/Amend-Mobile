<?php
/*
Plugin Name: amend Facebook link
Description: Adds amend's Facebook page (facebook.com/getamend) to the social links in the footer (.amend-social), drawn exactly like the other links there, on every page.
Version: 1.0.0
Author: amend
*/

if (!defined('ABSPATH')) {
    exit;
}

add_action('wp_footer', function () {
    ?>
<script id="amend-fb-link">
(function () {
  var URL = 'https://www.facebook.com/getamend';
  var PATH = 'M13.5 21v-7.5h2.5l.4-3h-2.9V8.6c0-.9.3-1.5 1.5-1.5h1.5V4.4c-.3 0-1.2-.1-2.2-.1-2.2 0-3.7 1.3-3.7 3.8v2.4H8v3h2.6V21h2.9Z';
  var NS = 'http://www.w3.org/2000/svg';

  function icon(like) {
    var s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('fill', 'currentColor');
    s.setAttribute('aria-hidden', 'true');
    if (like) {
      ['class', 'width', 'height', 'style'].forEach(function (k) {
        var v = like.getAttribute(k);
        if (v) s.setAttribute(k, v);
      });
      if (!like.getAttribute('width') && like.width && like.tagName === 'IMG') {
        s.setAttribute('width', like.width);
        s.setAttribute('height', like.height);
      }
    } else {
      s.setAttribute('width', '20');
      s.setAttribute('height', '20');
    }
    var p = document.createElementNS(NS, 'path');
    p.setAttribute('d', PATH);
    s.appendChild(p);
    return s;
  }

  function add() {
    var rows = document.querySelectorAll('.amend-social');
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      if (row.querySelector('a[href*="facebook.com"]')) continue;
      var model = row.querySelector('a');
      // a copy of another social link, so it looks exactly like the rest
      var a = model ? model.cloneNode(true) : document.createElement('a');
      a.removeAttribute('id');
      // drop the copied network's own class (its colour, say) and mark this one as Facebook
      a.className = (a.className || '').replace(/(^|[\s_-])(tiktok|snapchat|youtube|yt|x|twitter|telegram|threads|instagram|ig)(?=$|[\s_-])/gi, '$1').replace(/\s+/g, ' ').trim();
      a.classList.add('facebook');
      a.href = URL;
      a.target = '_blank';
      a.rel = 'noopener me';
      a.setAttribute('aria-label', 'amend on Facebook');
      a.setAttribute('title', 'Facebook');
      var pic = a.querySelector('svg, img');
      if (pic) pic.parentNode.replaceChild(icon(pic), pic);
      else a.insertBefore(icon(null), a.firstChild);
      // the copied link's own name (e.g. "TikTok") becomes "Facebook"
      var walker = document.createTreeWalker(a, NodeFilter.SHOW_TEXT, null);
      var named = false, n, texts = [];
      while ((n = walker.nextNode())) if (/\S/.test(n.nodeValue)) texts.push(n);
      texts.forEach(function (t) { t.nodeValue = named ? '' : t.nodeValue.replace(/\S.*\S|\S/, 'Facebook'); named = true; });
      // Facebook first: where most people look for us
      var first = model;
      while (first && first.parentNode !== row) first = first.parentNode;
      row.insertBefore(a, first || row.firstChild);
    }
    return rows.length;
  }

  function run() {
    if (add()) return;
    // the row may be added a moment later: watch for it briefly
    if (!window.MutationObserver) return;
    var mo = new MutationObserver(function () { if (add()) mo.disconnect(); });
    mo.observe(document.body, { childList: true, subtree: true });
    setTimeout(function () { mo.disconnect(); }, 8000);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run);
  else run();
})();
</script>
    <?php
}, 100);
