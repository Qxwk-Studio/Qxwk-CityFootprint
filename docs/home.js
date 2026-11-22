(function () {
  'use strict';
  var sections = Array.prototype.slice.call(document.querySelectorAll('main > .hero, main > .section'));
  if (sections.length < 2) return;
  var header = document.querySelector('.site-header');
  var footer = document.querySelector('.site-footer');
  var media = window.matchMedia('(min-width: 0px)');
  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  var current = 0;
  var accumulated = 0;
  var gestureLocked = false;
  var gestureTimer = 0;
  var navigationTarget = -1;
  var navigationTimer = 0;
  var animationLockUntil = 0;
  function headerHeight() { return header ? header.getBoundingClientRect().height : 0; }
  function enabled() { return media.matches; }
  function setSnap(on) {
    document.documentElement.classList.toggle('snap-home', on);
    if (!on) { navigationTarget = -1; window.clearTimeout(navigationTimer); }
  }
  function sectionTop(index) { return Math.max(0, sections[index].offsetTop - headerHeight()); }
  function nearestSection() {
    var y = window.scrollY;
    var best = 0;
    var distance = Infinity;
    sections.forEach(function (section, index) {
      var d = Math.abs(y - sectionTop(index));
      if (d < distance) { distance = d; best = index; }
    });
    return best;
  }
  function finishNavigation() {
    if (navigationTarget < 0) return;
    navigationTarget = -1;
    window.clearTimeout(navigationTimer);
    current = nearestSection();
  }
  function goTo(index) {
    var target = Math.max(0, Math.min(sections.length - 1, index));
    if (target === current && Math.abs(window.scrollY - sectionTop(target)) < 2) return;
    current = target;
    navigationTarget = target;
    animationLockUntil = performance.now() + 750;
    window.clearTimeout(navigationTimer);
    window.scrollTo({ top: sectionTop(target), behavior: reduced.matches ? 'auto' : 'smooth' });
    navigationTimer = window.setTimeout(finishNavigation, 1100);
  }
  function atFooter() {
    var max = document.documentElement.scrollHeight - window.innerHeight;
    return max > 0 && window.scrollY >= max - 2;
  }

  function goFooter() {
    if (!footer) return;
    navigationTarget = sections.length - 1;
    animationLockUntil = performance.now() + 750;
    window.clearTimeout(navigationTimer);
    window.scrollTo({
      top: Math.max(0, document.documentElement.scrollHeight - window.innerHeight),
      behavior: reduced.matches ? 'auto' : 'smooth'
    });
    navigationTimer = window.setTimeout(finishNavigation, 1100);
  }
  function normalizeDelta(event) {
    if (event.deltaMode === 1) return event.deltaY * 16;
    if (event.deltaMode === 2) return event.deltaY * window.innerHeight;
    return event.deltaY;
  }
  function canScrollWithin(direction) {
    var el = sections[current] || sections[0];
    if (!el) return false;
    var over = el.scrollHeight - el.clientHeight;
    if (over <= 4) return false;
    var top = Math.max(0, el.offsetTop - headerHeight());
    return direction > 0
      ? window.scrollY < top + over - 2
      : window.scrollY > top + 2;
  }
  function onWheel(event) {
    if (!enabled()) return;
    if (event.ctrlKey) return;
    if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;
    var wheelDirection = event.deltaY > 0 ? 1 : -1;
    // 当前节内容超出视口：放行原生滚动，滚到本节边界再切页
    if (canScrollWithin(wheelDirection)) return;
    event.preventDefault();
    window.clearTimeout(gestureTimer);
    gestureTimer = window.setTimeout(function () {
      accumulated = 0;
      gestureLocked = false;
    }, 220);
    if (performance.now() < animationLockUntil) return;
    accumulated += normalizeDelta(event);
    if (gestureLocked || Math.abs(accumulated) < 24) return;
    var direction = accumulated > 0 ? 1 : -1;
    accumulated = 0;
    gestureLocked = true;
    if (direction < 0 && atFooter()) { goTo(sections.length - 1); return; }
    if (direction < 0 && current === 0) return;
    if (direction > 0 && current === sections.length - 1) { goFooter(); return; }
    goTo(current + direction);
  }
  function onKeyDown(event) {
    if (!enabled()) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    if (['ArrowDown', 'PageDown', ' '].indexOf(event.key) >= 0) {
      if (canScrollWithin(1)) return;
      event.preventDefault(); goTo(current + 1);
    } else if (['ArrowUp', 'PageUp'].indexOf(event.key) >= 0) {
      if (canScrollWithin(-1)) return;
      event.preventDefault(); goTo(current - 1);
    } else if (event.key === 'Home') {
      event.preventDefault(); goTo(0);
    } else if (event.key === 'End') {
      goFooter();
    }
  }
  function onScroll() {
    if (navigationTarget >= 0) return;
    current = nearestSection();
  }
  window.addEventListener('wheel', onWheel, { passive: false });
  window.addEventListener('keydown', onKeyDown);
  window.addEventListener('scroll', onScroll, { passive: true });
  window.addEventListener('scrollend', finishNavigation, { passive: true });
  media.addEventListener('change', function () { setSnap(enabled()); });
  reduced.addEventListener('change', function () { setSnap(enabled()); });
  var signature = document.querySelector('.home-hero object');
  var signatureTimer = 0;
  var signatureReloads = 0;

  function replaySignature() {
    if (!signature || reduced.matches || document.hidden) return;
    var doc = signature.contentDocument;
    if (!doc) {
      if (signatureReloads >= 3) return;
      signatureReloads++;
      try {
        var url = new URL(signature.data, window.location.href);
        url.searchParams.set('replay', String(Date.now()));
        signature.data = url.href;
      } catch (e) {}
      return;
    }
    var paths = Array.prototype.slice.call(doc.querySelectorAll('path'));
    if (!paths.length) return;
    var saved = paths.map(function (path) { return path.style.animation; });
    paths.forEach(function (path) { path.style.animation = 'none'; });
    void signature.getBoundingClientRect();
    paths.forEach(function (path, index) { path.style.animation = saved[index]; });
  }

  function scheduleSignature() {
    window.clearInterval(signatureTimer);
    if (!signature || reduced.matches) return;
    signatureTimer = window.setInterval(replaySignature, 12000);
  }

  if (signature) {
    signature.addEventListener('load', scheduleSignature);
    scheduleSignature();
  }
  reduced.addEventListener('change', scheduleSignature);

  // 图片进入视口时轻微上浮 + 淡入；卡片堆叠时逐张滑入。
  var phoneFrames = Array.prototype.slice.call(document.querySelectorAll('.phone-frame'));
  var recordStacks = Array.prototype.slice.call(document.querySelectorAll('.record-stack'));
  if ((phoneFrames.length || recordStacks.length) && 'IntersectionObserver' in window) {
    var revealObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add('is-visible');
        revealObserver.unobserve(entry.target);
      });
    }, { threshold: 0.22 });
    phoneFrames.forEach(function (frame) {
      frame.classList.add('reveal-pending');
      revealObserver.observe(frame);
    });
    recordStacks.forEach(function (stack) {
      stack.classList.add('reveal-ready');
      revealObserver.observe(stack);
    });
  }

  // 子页面内容跟随滚动从上方落下，滚动位置决定进度，不做一次性播放。
  var motionSections = sections.filter(function (section) {
    return section.classList.contains('feature-page') || section.classList.contains('download-final');
  });
  var entranceFrame = 0;

  function updateEntrance() {
    entranceFrame = 0;
    var vh = window.innerHeight;
    var startShift = window.innerWidth <= 620 ? -38 : -72;
    motionSections.forEach(function (section) {
      var rect = section.getBoundingClientRect();
      var progress = (vh - rect.top) / (vh * 0.62);
      progress = Math.max(0, Math.min(1, progress));
      if (reduced.matches) progress = 1;
      section.style.setProperty('--enter-shift', ((1 - progress) * startShift).toFixed(1) + 'px');
      section.style.setProperty('--enter-opacity', progress.toFixed(3));
    });
  }

  function queueEntrance() {
    if (entranceFrame) return;
    entranceFrame = window.requestAnimationFrame(updateEntrance);
  }

  motionSections.forEach(function (section) {
    section.classList.add('scroll-ready');
  });
  window.addEventListener('scroll', queueEntrance, { passive: true });
  window.addEventListener('resize', queueEntrance);
  updateEntrance();

  setSnap(enabled());
  current = nearestSection();
})();
