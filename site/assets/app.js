(function () {
  'use strict';
  var doc = document, root = doc.documentElement, body = doc.body;
  var base = body.getAttribute('data-root') || '';

  /* ---------- theme ---------- */
  doc.querySelector('.theme-btn').addEventListener('click', function () {
    var cur = root.getAttribute('data-theme');
    if (!cur) cur = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    var next = cur === 'dark' ? 'light' : 'dark';
    root.setAttribute('data-theme', next);
    try { localStorage.setItem('theme', next); } catch (e) {}
  });

  /* ---------- mobile nav ---------- */
  var mb = doc.querySelector('.menu-btn');
  mb.addEventListener('click', function () {
    var open = body.classList.toggle('nav-open');
    mb.setAttribute('aria-expanded', open ? 'true' : 'false');
  });
  doc.querySelector('.main').addEventListener('click', function () { body.classList.remove('nav-open'); });
  var cur = doc.querySelector('.sidebar a.current');
  if (cur && cur.scrollIntoView) { var sb = doc.getElementById('sidebar'); sb.scrollTop = Math.max(0, cur.offsetTop - 120); }

  /* ---------- Scala / shell highlighter ---------- */
  var KW = 'abstract case catch class def do else enum export extends false final finally for given if implicit import inline lazy match new null object opaque override package private protected return sealed super then this throw trait true try type using val var while with yield derives end extension transparent open'.split(' ');
  var KWS = {}; KW.forEach(function (k) { KWS[k] = 1; });
  function span(c, s) { return '<span class="tok-' + c + '">' + s + '</span>'; }
  function esc(s) { return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
  function scala(src) {
    var out = '', i = 0, n = src.length, m;
    var re = {
      lc: /^\/\/[^\n]*/, bc: /^\/\*[\s\S]*?(\*\/|$)/,
      ts: /^[sf]?"""[\s\S]*?("""|$)/, str: /^[sf]?"(\\.|[^"\\\n])*"?/, chr: /^'(\\.|[^'\\\n])'/,
      num: /^(0x[0-9a-fA-F_]+|\d[\d_]*(\.\d+)?([eE][+-]?\d+)?[lLfFdD]?)/,
      id: /^[A-Za-z_$][\w$]*/, ann: /^@[A-Za-z_][\w.]*/
    };
    while (i < n) {
      var rest = src.slice(i), c = rest[0];
      if ((m = re.lc.exec(rest)) || (m = re.bc.exec(rest))) { out += span('com', esc(m[0])); i += m[0].length; continue; }
      if ((m = re.ts.exec(rest)) || (m = re.str.exec(rest)) || (m = re.chr.exec(rest))) { out += span('str', esc(m[0])); i += m[0].length; continue; }
      if (/\d/.test(c) && (m = re.num.exec(rest))) { out += span('num', m[0]); i += m[0].length; continue; }
      if (c === '@' && (m = re.ann.exec(rest))) { out += span('ann', m[0]); i += m[0].length; continue; }
      if ((m = re.id.exec(rest))) {
        var w = m[0], after = rest.slice(w.length).match(/^\s*[(\[]/);
        if (KWS[w]) out += span('kw', w);
        else if (/^[A-Z]/.test(w)) out += span('type', w);
        else if (after && src[i - 1] === '.') out += span('fn', w);
        else if (after && /(^|\s)def\s+$/.test(src.slice(Math.max(0, i - 8), i))) out += span('fn', w);
        else out += w;
        i += w.length; continue;
      }
      out += esc(c); i++;
    }
    return out;
  }
  function shell(src) {
    return src.split('\n').map(function (l) {
      var m = /^(\s*)(#.*)$/.exec(l);
      if (m) return m[1] + span('com', esc(m[2]));
      var ci = l.search(/\s#\s/), tail = '';
      if (ci >= 0) { tail = span('com', esc(l.slice(ci))); l = l.slice(0, ci); }
      l = esc(l).replace(/(&quot;[^&]*&quot;|"[^"]*"|'[^']*')/g, function (s) { return span('str', s); })
        .replace(/(^|\s)(--?[A-Za-z][\w-]*)/g, function (_, a, b) { return a + span('kw', b); })
        .replace(/^(\s*)(\$?[A-Z_]+=)/, function (_, a, b) { return a + span('type', b); });
      return l + tail;
    }).join('\n');
  }
  doc.querySelectorAll('pre > code').forEach(function (code) {
    var lang = (code.className.match(/language-(\w+)/) || [])[1];
    var src = code.textContent;
    if (lang === 'scala' || lang === 'sbt') code.innerHTML = scala(src);
    else if (lang === 'sh' || lang === 'bash') code.innerHTML = shell(src);
    var b = doc.createElement('button');
    b.className = 'copy'; b.type = 'button'; b.textContent = 'Copy';
    b.addEventListener('click', function () {
      if (navigator.clipboard) navigator.clipboard.writeText(src).then(function () { b.textContent = 'Copied'; setTimeout(function () { b.textContent = 'Copy'; }, 1200); });
    });
    code.parentNode.appendChild(b);
  });

  /* ---------- architecture diagram hover ---------- */
  var arch = doc.querySelector('svg.arch');
  if (arch) {
    var edges = [].slice.call(arch.querySelectorAll('.edge')), nodes = [].slice.call(arch.querySelectorAll('.node'));
    nodes.forEach(function (nd) {
      var name = nd.getAttribute('data-name');
      nd.addEventListener('mouseenter', function () {
        arch.classList.add('dim'); var related = {}; related[name] = 1;
        edges.forEach(function (e) { if (e.dataset.from === name || e.dataset.to === name) { e.classList.add('hi'); related[e.dataset.from] = related[e.dataset.to] = 1; } });
        nodes.forEach(function (x) { if (related[x.getAttribute('data-name')]) x.classList.add('hi'); });
      });
      nd.addEventListener('mouseleave', function () {
        arch.classList.remove('dim'); edges.concat(nodes).forEach(function (e) { e.classList.remove('hi'); });
      });
    });
  }

  /* ---------- toc scroll spy ---------- */
  var tocLinks = [].slice.call(doc.querySelectorAll('.toc a'));
  if (tocLinks.length && 'IntersectionObserver' in window) {
    var map = {};
    tocLinks.forEach(function (a) { map[a.getAttribute('href').slice(1)] = a; });
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { tocLinks.forEach(function (a) { a.classList.remove('active'); }); var a = map[e.target.id]; if (a) a.classList.add('active'); } });
    }, { rootMargin: '-70px 0px -75% 0px' });
    doc.querySelectorAll('.content h2[id], .content h3[id]').forEach(function (h) { io.observe(h); });
  }

  /* ---------- search ---------- */
  var q = doc.getElementById('q'), box = doc.getElementById('results'), sel = -1, hits = [];
  var idx = window.SEARCH_INDEX || [];
  function tokens(s) { return s.toLowerCase().split(/[^a-z0-9_.]+/).filter(Boolean); }
  function score(e, ts) {
    var t = e.t.toLowerCase(), h = e.h.toLowerCase(), x = e.x.toLowerCase(), s = 0;
    for (var i = 0; i < ts.length; i++) {
      var w = ts[i], hit = 0;
      if (h.indexOf(w) >= 0) { hit += 6; if (h === w) hit += 4; }
      if (t.indexOf(w) >= 0) hit += 4;
      var p = x.indexOf(w);
      if (p >= 0) { hit += 1; var c = 0; while (p >= 0 && c < 4) { c++; p = x.indexOf(w, p + 1); } hit += c * 0.25; }
      if (!hit) return 0;
      s += hit;
    }
    return s;
  }
  function snippet(e, ts) {
    var x = e.x, low = x.toLowerCase(), p = -1;
    for (var i = 0; i < ts.length && p < 0; i++) p = low.indexOf(ts[i]);
    var from = Math.max(0, p - 40), s = x.slice(from, from + 110);
    s = esc(s);
    ts.forEach(function (w) { s = s.replace(new RegExp('(' + w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ')', 'ig'), '<mark>$1</mark>'); });
    return (from > 0 ? '…' : '') + s + '…';
  }
  function run() {
    var ts = tokens(q.value);
    if (!ts.length) { box.hidden = true; return; }
    hits = idx.map(function (e) { return { e: e, s: score(e, ts) }; }).filter(function (r) { return r.s > 0; })
      .sort(function (a, b) { return b.s - a.s; }).slice(0, 10);
    sel = hits.length ? 0 : -1;
    box.innerHTML = hits.length ? hits.map(function (r, i) {
      return '<a href="' + base + r.e.u + '" class="' + (i === 0 ? 'sel' : '') + '"><span class="r-t">' + esc(r.e.t) + (r.e.h ? ' › ' + esc(r.e.h) : '') + '</span><span class="r-s">' + snippet(r.e, ts) + '</span></a>';
    }).join('') : '<div class="none">No results</div>';
    box.hidden = false;
  }
  function move(d) {
    var as = box.querySelectorAll('a'); if (!as.length) return;
    as[sel] && as[sel].classList.remove('sel'); sel = (sel + d + as.length) % as.length;
    as[sel].classList.add('sel'); as[sel].scrollIntoView({ block: 'nearest' });
  }
  q.addEventListener('input', run);
  q.addEventListener('focus', run);
  q.addEventListener('keydown', function (e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
    else if (e.key === 'Enter') { var a = box.querySelectorAll('a')[sel]; if (a) location.href = a.href; }
    else if (e.key === 'Escape') { box.hidden = true; q.blur(); }
  });
  doc.addEventListener('click', function (e) { if (!e.target.closest('.search')) box.hidden = true; });
  doc.addEventListener('keydown', function (e) {
    if (e.key === '/' && !/^(INPUT|TEXTAREA)$/.test((e.target.tagName || ''))) { e.preventDefault(); q.focus(); }
  });
})();
