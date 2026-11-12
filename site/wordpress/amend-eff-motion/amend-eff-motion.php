<?php
/*
Plugin Name: amend efficiency motion
Description: Animates the Efficiency mode switch on getamend.app. Turning it on folds the light page into the switch with a green ring and a burst of leaves; turning it off opens the light page out of the switch with a sun flare. Uses View Transitions where the browser has them, falls back to the overlay alone, and stays still for people who ask for reduced motion.
Version: 1.0.0
Author: amend
*/

if (!defined('ABSPATH')) {
    exit;
}

add_action('wp_head', function () {
    if (is_admin()) {
        return;
    }
    ?>
<style id="amend-eff-motion-css">
/* ---- the switch itself: dynamic, no overshoot (ease-out settle) ---- */
#amend .eff-knob{transition:transform .56s cubic-bezier(.22,1,.36,1),background-color .4s,scale .18s cubic-bezier(.2,.8,.2,1),box-shadow .4s}
#amend .eff-knob svg{transition:opacity .28s cubic-bezier(.2,.8,.2,1),transform .56s cubic-bezier(.22,1,.36,1)}
#amend .eff .i-leaf{transform:rotate(-120deg) scale(.4)}
#amend .eff[aria-checked="true"] .i-sun{transform:rotate(120deg) scale(.4)}
#amend .eff[aria-checked="true"] .i-leaf{transform:none}
#amend .eff:active .eff-knob{scale:1.22 .9}
#amend .eff:active .eff-track{scale:.96}
#amend .eff-track{transition:background-color .45s,box-shadow .3s,scale .18s cubic-bezier(.2,.8,.2,1)}
@media (hover:hover) and (pointer:fine){
  #amend .eff[data-eff]:hover .eff-track{box-shadow:0 0 0 4px rgba(91,227,155,.18)}
  #amend .eff[data-eff][aria-checked="false"]:hover .eff-track{box-shadow:0 0 0 4px rgba(255,193,69,.28)}
  #amend .eff[data-eff]:hover .eff-knob{translate:2px 0}
  #amend .eff[data-eff][aria-checked="true"]:hover .eff-knob{translate:-2px 0}
}
#amend .eff:focus-visible{outline:2px solid var(--leaf);outline-offset:2px}
/* a light sweep through the track right after a switch */
#amend .eff.eff-pop .eff-track{background-image:linear-gradient(100deg,transparent 35%,rgba(255,255,255,.6) 50%,transparent 65%);background-size:300% 100%;background-repeat:no-repeat;animation:effSweep .9s cubic-bezier(.65,0,.35,1) .25s both}
#amend .eff.eff-pop .eff-knob{animation:effKnob .7s cubic-bezier(.22,1,.36,1) both}
@keyframes effSweep{from{background-position:100% 0}to{background-position:0 0}}
@keyframes effKnob{0%{box-shadow:0 2px 6px rgba(0,0,0,.25),0 0 0 0 rgba(91,227,155,.7)}100%{box-shadow:0 2px 6px rgba(0,0,0,.25),0 0 0 12px rgba(91,227,155,0)}}
html[data-theme=light] #amend .eff.eff-pop .eff-knob{animation-name:effKnobSun}
@keyframes effKnobSun{0%{box-shadow:0 2px 6px rgba(0,0,0,.25),0 0 0 0 rgba(255,193,69,.8)}100%{box-shadow:0 2px 6px rgba(0,0,0,.25),0 0 0 12px rgba(255,193,69,0)}}

/* ---- the whole-page change ---- */
#amend-eff-fx{position:fixed;inset:0;pointer-events:none;z-index:2147483000;overflow:hidden;contain:strict}
#amend-eff-fx > *{position:absolute;left:0;top:0;will-change:transform,opacity}
#amend-eff-fx .ring{border-radius:50%;box-sizing:border-box}
#amend-eff-fx .glow{border-radius:50%}
#amend-eff-fx svg{overflow:visible}
/* while the view transition runs nothing else animates colours, so both pictures are clean */
html.eff-vt *:not(.eff):not(.eff *){transition:none!important}
::view-transition-old(root),::view-transition-new(root){animation:none;mix-blend-mode:normal}
html.eff-to-dark::view-transition-old(root){z-index:2}
html.eff-to-dark::view-transition-new(root){z-index:1}
html.eff-to-light::view-transition-old(root){z-index:1}
html.eff-to-light::view-transition-new(root){z-index:2}
::view-transition-group(eff-fx),::view-transition-group(eff-btn){animation:none;z-index:5}
::view-transition-group(eff-fx){z-index:6}
::view-transition-old(eff-fx),::view-transition-old(eff-btn){display:none}
::view-transition-new(eff-fx),::view-transition-new(eff-btn){animation:none}
</style>
    <?php
}, 99);

add_action('wp_footer', function () {
    if (is_admin()) {
        return;
    }
    ?>
<script id="amend-eff-motion-js">
(function () {
  var d = document.documentElement;
  if (!document.querySelector('[data-eff]')) return;
  var mq = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;
  function reduced() { return !!(mq && mq.matches); }
  var passing = false, busy = false;
  // easing from the motion system: travel in-out, settle out; no overshoot
  var IO = 'cubic-bezier(.65,0,.35,1)', OUT = 'cubic-bezier(.22,1,.36,1)';
  var GREEN = '91,227,155', SUN = '255,193,69';

  var fx = document.createElement('div');
  fx.id = 'amend-eff-fx';
  fx.setAttribute('aria-hidden', 'true');
  fx.setAttribute('data-no-i18n', '');
  document.body.appendChild(fx);

  function isDark() { return d.getAttribute('data-theme') !== 'light'; }
  function centre(b) {
    var t = b.querySelector('.eff-track') || b, r = t.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }
  function radius(p) {
    var w = innerWidth, h = innerHeight;
    return Math.hypot(Math.max(p.x, w - p.x), Math.max(p.y, h - p.y)) + 24;
  }
  function el(cls, css) {
    var e = document.createElement('div');
    e.className = cls;
    for (var k in css) e.style[k] = css[k];
    return e;
  }
  function leaf(size) {
    var NS = 'http://www.w3.org/2000/svg', s = document.createElementNS(NS, 'svg'), u = document.createElementNS(NS, 'use');
    s.setAttribute('width', size); s.setAttribute('height', size);
    u.setAttribute('href', '#amend-leaf'); s.appendChild(u);
    return s;
  }
  function anim(node, frames, opts) {
    if (!node.animate) return;
    var a = node.animate(frames, opts);
    return a;
  }

  /* the overlay: an edge ring travelling with the page edge, a glow, and leaves or sun rays */
  function overlay(p, R, toDark, dur) {
    var col = toDark ? GREEN : SUN, D = R * 2, frag = document.createDocumentFragment(), made = [];
    var ring = el('ring', { width: D + 'px', height: D + 'px', marginLeft: (p.x - R) + 'px', marginTop: (p.y - R) + 'px',
      border: '4px solid rgba(' + col + ',1)',
      boxShadow: '0 0 36px 10px rgba(' + col + ',.6), inset 0 0 36px 10px rgba(' + col + ',.5)' });
    ring.style.transform = 'scale(' + (toDark ? 1 : 0) + ')';
    ring.dataset.life = dur;

    var G = 220, glow = el('glow', { width: G + 'px', height: G + 'px', marginLeft: (p.x - G / 2) + 'px', marginTop: (p.y - G / 2) + 'px',
      background: 'radial-gradient(circle, rgba(' + col + ',.9) 0%, rgba(' + col + ',.35) 35%, rgba(' + col + ',0) 70%)' });
    made.push([glow, toDark
      ? [{ transform: 'scale(.2)', opacity: 0 }, { transform: 'scale(1.3)', opacity: 1, offset: .55 }, { transform: 'scale(2.2)', opacity: 0 }]
      : [{ transform: 'scale(.3)', opacity: 1 }, { transform: 'scale(2.6)', opacity: 0 }],
      { duration: toDark ? 900 : 700, delay: toDark ? dur * .78 : 0, easing: OUT, fill: 'both' }]);

    // a shockwave ring from the switch once the change lands
    var S = 60, shock = el('ring', { width: S + 'px', height: S + 'px', marginLeft: (p.x - S / 2) + 'px', marginTop: (p.y - S / 2) + 'px',
      border: '2px solid rgba(' + col + ',.9)' });
    made.push([shock, [{ transform: 'scale(.4)', opacity: 1 }, { transform: 'scale(7)', opacity: 0 }],
      { duration: 800, delay: toDark ? dur * .82 : 60, easing: OUT, fill: 'both' }]);

    if (toDark) {
      // leaves spray out of the switch: the energy you just saved
      var n = innerWidth < 600 ? 12 : 18;
      for (var i = 0; i < n; i++) {
        var a = (i / n) * Math.PI * 2 + Math.random() * .4, dist = 90 + Math.random() * 170, sz = 14 + Math.random() * 16;
        var lf = el('leaf', { marginLeft: (p.x - sz / 2) + 'px', marginTop: (p.y - sz / 2) + 'px' });
        lf.appendChild(leaf(sz));
        var rot = (Math.random() * 2 - 1) * 260, dx = Math.cos(a) * dist, dy = Math.sin(a) * dist;
        made.push([lf, [
          { transform: 'translate(0,0) rotate(0deg) scale(.2)', opacity: 0 },
          { transform: 'translate(' + dx * .6 + 'px,' + dy * .6 + 'px) rotate(' + rot * .5 + 'deg) scale(1)', opacity: 1, offset: .35 },
          { transform: 'translate(' + dx + 'px,' + (dy + 40) + 'px) rotate(' + rot + 'deg) scale(.7)', opacity: 0 }],
          { duration: 1100 + Math.random() * 400, delay: dur * .8 + Math.random() * 90, easing: OUT, fill: 'both' }]);
      }
    } else {
      // sun rays flare out as the light page opens
      var m = 14;
      for (var j = 0; j < m; j++) {
        var ang = (j / m) * 360, len = 46 + (j % 2) * 26;
        var ray = el('ray', { width: '4px', height: len + 'px', borderRadius: '4px', marginLeft: (p.x - 2) + 'px', marginTop: (p.y - len) + 'px',
          transformOrigin: '50% 100%', background: 'linear-gradient(to top, rgba(' + SUN + ',0), rgba(' + SUN + ',1))' });
        made.push([ray, [
          { transform: 'rotate(' + ang + 'deg) translateY(-10px) scaleY(.1)', opacity: 0 },
          { transform: 'rotate(' + ang + 'deg) translateY(-40px) scaleY(1)', opacity: 1, offset: .4 },
          { transform: 'rotate(' + (ang + 18) + 'deg) translateY(-130px) scaleY(.3)', opacity: 0 }],
          { duration: 900, delay: 40 + (j % 3) * 30, easing: OUT, fill: 'both' }]);
      }
    }
    frag.appendChild(ring);
    made.forEach(function (m) { frag.appendChild(m[0]); });
    fx.appendChild(frag); // one DOM change for the whole burst
    var last = 0;
    made.forEach(function (m) {
      anim(m[0], m[1], m[2]);
      last = Math.max(last, (m[2].delay || 0) + m[2].duration);
    });
    last = Math.max(last, dur);
    setTimeout(function () {
      if (ring.parentNode) ring.parentNode.removeChild(ring);
      made.forEach(function (m) { if (m[0].parentNode) m[0].parentNode.removeChild(m[0]); });
    }, last + 80);
    ring.dataset.last = last;
    return ring;
  }


  /* the page edge and its glowing ring move together, frame by frame, from one clock */
  var dyn = document.createElement('style');
  dyn.id = 'amend-eff-motion-dyn';
  document.head.appendChild(dyn);
  function circ(r, p) { return 'circle(' + Math.max(0, r).toFixed(1) + 'px at ' + p.x.toFixed(1) + 'px ' + p.y.toFixed(1) + 'px)'; }
  function clipRule(sel, r, p) {
    try {
      var sh = dyn.sheet, i = sh.insertRule(sel + '{clip-path:' + circ(r, p) + '}', sh.cssRules.length);
      return sh.cssRules[i];
    } catch (e) { return null; }
  }
  function dropRule() { var sh = dyn.sheet; if (sh) while (sh.cssRules.length) sh.deleteRule(0); }
  // cubic-bezier(.65,0,.35,1), the travel curve
  function io(t) { return t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
  function drive(rule, p, R, toDark, dur, ring) {
    var t0 = null;
    (function f(now) {
      if (t0 === null) t0 = now;
      var k = Math.min(1, (now - t0) / dur), e = io(k), r = toDark ? R * (1 - e) : R * e;
      if (rule) rule.style.clipPath = circ(r, p);
      if (ring) {
        ring.style.transform = 'scale(' + (r / R).toFixed(4) + ')';
        ring.style.opacity = toDark ? (r < 24 ? (r / 24).toFixed(3) : '1') : (k > .85 ? ((1 - k) / .15).toFixed(3) : '1');
      }
      if (k < 1) requestAnimationFrame(f);
    })(performance.now());
  }

  function flip(b) { passing = true; try { b.click(); } finally { passing = false; } }
  function pop(b) {
    b.classList.remove('eff-pop'); void b.offsetWidth; b.classList.add('eff-pop');
    setTimeout(function () { b.classList.remove('eff-pop'); }, 1300);
  }

  function run(b) {
    var p = centre(b), R = radius(p), toDark = !isDark(), dur = innerWidth < 600 ? 820 : 980;
    busy = true;
    var done = function () { busy = false; };
    if (!document.startViewTransition) {
      // no view transitions: the page cross-fades its colours as before, the overlay plays over it
      flip(b); pop(b);
      var rg = overlay(p, R, toDark, dur);
      drive(null, p, R, toDark, dur, rg);
      setTimeout(done, +rg.dataset.last);
      return;
    }
    var clipSel = toDark ? '::view-transition-old(root)' : '::view-transition-new(root)';
    var rule = clipRule(clipSel, toDark ? R : 0, p);
    d.classList.add('eff-vt', toDark ? 'eff-to-dark' : 'eff-to-light');
    fx.style.viewTransitionName = 'eff-fx';
    b.style.viewTransitionName = 'eff-btn';
    var vt;
    try {
      vt = document.startViewTransition(function () { flip(b); pop(b); });
    } catch (e) { dropRule(); d.classList.remove('eff-vt', 'eff-to-dark', 'eff-to-light'); fx.style.viewTransitionName = ''; b.style.viewTransitionName = ''; flip(b); done(); return; }
    function cleanup() {
      dropRule();
      d.classList.remove('eff-vt', 'eff-to-dark', 'eff-to-light');
      fx.style.viewTransitionName = '';
      b.style.viewTransitionName = '';
    }
    vt.ready.then(function () {
      if (toDark) {
        // the light page folds into the switch; the dark page settles underneath
        d.animate({ transform: ['scale(1.06)', 'scale(1)'], filter: ['brightness(.55)', 'brightness(1)'] },
          { duration: dur + 200, easing: OUT, fill: 'both', pseudoElement: '::view-transition-new(root)' });
      } else {
        // the light page opens out of the switch
        d.animate({ transform: ['scale(1)', 'scale(.96)'], filter: ['brightness(1)', 'brightness(.6)'] },
          { duration: dur, easing: IO, fill: 'both', pseudoElement: '::view-transition-old(root)' });
      }
      // keeps the transition open for the whole reveal
      d.animate({ opacity: [1, 1] }, { duration: dur + 60, fill: 'both', pseudoElement: clipSel });
      var ring = overlay(p, R, toDark, dur);
      drive(rule, p, R, toDark, dur, ring);
    }).catch(function () {});
    vt.finished.then(cleanup, cleanup).then(function () { setTimeout(done, 300); });
  }

  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest ? e.target.closest('[data-eff]') : null;
    if (!b || passing) return;
    if (reduced()) return; // the switch just changes, as before
    e.preventDefault();
    e.stopImmediatePropagation();
    if (busy) return;
    run(b);
  }, true);
})();
</script>
    <?php
}, 99);
