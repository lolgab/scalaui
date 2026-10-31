#!/usr/bin/env python3
"""Dependency-free static site generator for the scalaui docs.

    python3 site/build.py            # writes site/_site
    python3 -m http.server -d site/_site 8000

Pages are Markdown files in site/content (front matter: title, section, order, description).
The architecture diagram and dependency table are generated from ../build.sbt, so they cannot drift.
"""
import html, json, os, re, shutil, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONTENT = HERE / "content"
ASSETS = HERE / "assets"
OUT = HERE / "_site"
ROOT_SBT = HERE.parent / "build.sbt"
SECTIONS = ["Guide", "Reference", "Modules"]

# ---------------------------------------------------------------- markdown

def esc(s):
    return html.escape(s, quote=False)

def slugify(s):
    s = re.sub(r"<[^>]+>", "", s)
    s = re.sub(r"[^\w\s-]", "", s.lower()).strip()
    return re.sub(r"[\s]+", "-", s)

class Ctx:
    def __init__(self, root):
        self.root = root  # relative prefix to site root ("" or "../")

def link_target(url, ctx):
    if re.match(r"^[a-z]+:|^#|^//", url):
        return url
    path, _, frag = url.partition("#")
    if path.endswith(".md"):
        path = path[:-3] + ".html"
    # links in Markdown are relative to the page they are written in
    return path + ("#" + frag if frag else "")

def inline(text, ctx):
    codes = []
    def stash(m):
        codes.append("<code>" + esc(m.group(1)) + "</code>")
        return "\x00%d\x00" % (len(codes) - 1)
    text = re.sub(r"`([^`]+)`", stash, text)
    text = esc(text)
    text = re.sub(r"!\[([^\]]*)\]\(([^)]+)\)", lambda m: '<img src="%s" alt="%s">' % (ctx.root + m.group(2), m.group(1)), text)
    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", lambda m: '<a href="%s">%s</a>' % (link_target(html.unescape(m.group(2)), ctx), m.group(1)), text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*([^*\s][^*]*?)\*(?![\w*])", r"<em>\1</em>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)

def render_md(src, ctx, headings):
    lines = src.split("\n")
    out = []
    i = 0
    def para_end(l):
        return (not l.strip()) or re.match(r"^(#{1,4} |```|[-*] |\d+\. |\||> |:::|<)", l)
    while i < len(lines):
        l = lines[i]
        if not l.strip():
            i += 1; continue
        m = re.match(r"^```\s*(\w*)\s*$", l)
        if m:
            lang = m.group(1) or "text"
            buf = []; i += 1
            while i < len(lines) and not lines[i].startswith("```"):
                buf.append(lines[i]); i += 1
            i += 1
            out.append('<pre><code class="language-%s">%s</code></pre>' % (lang, esc("\n".join(buf))))
            continue
        m = re.match(r"^(#{1,4}) (.*)$", l)
        if m:
            level = len(m.group(1)); raw = m.group(2).strip()
            h = inline(raw, ctx); sid = slugify(raw)
            if level in (2, 3):
                headings.append((level, sid, re.sub(r"<[^>]+>", "", h)))
            out.append('<h%d id="%s">%s<a class="anchor" href="#%s" aria-label="Link to section">#</a></h%d>' % (level, sid, h, sid, level))
            i += 1; continue
        m = re.match(r"^::: ?(\w+)\s*$", l)
        if m:
            buf = []; i += 1
            while i < len(lines) and not lines[i].startswith(":::"):
                buf.append(lines[i]); i += 1
            i += 1
            out.append('<div class="callout %s">%s</div>' % (m.group(1), render_md("\n".join(buf), ctx, [])))
            continue
        if l.startswith("<"):
            buf = []
            while i < len(lines) and lines[i].strip():
                buf.append(lines[i]); i += 1
            out.append("\n".join(buf))
            continue
        if l.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append([c.strip() for c in re.split(r"(?<!\\)\|", lines[i].strip().strip("|"))]); i += 1
            head, body = rows[0], rows[2:]
            t = "<div class=\"table-wrap\"><table><thead><tr>" + "".join("<th>%s</th>" % inline(c.replace("\\|", "|"), ctx) for c in head) + "</tr></thead><tbody>"
            for r in body:
                t += "<tr>" + "".join("<td>%s</td>" % inline(c.replace("\\|", "|"), ctx) for c in r) + "</tr>"
            out.append(t + "</tbody></table></div>")
            continue
        if l.startswith("> "):
            buf = []
            while i < len(lines) and lines[i].startswith(">"):
                buf.append(lines[i][2:]); i += 1
            out.append("<blockquote>%s</blockquote>" % render_md("\n".join(buf), ctx, []))
            continue
        m = re.match(r"^([-*]|\d+\.) (.*)$", l)
        if m:
            ordered = m.group(1)[0].isdigit()
            items = []
            while i < len(lines):
                mm = re.match(r"^([-*]|\d+\.) (.*)$", lines[i])
                if mm:
                    items.append(mm.group(2)); i += 1
                elif lines[i].startswith("  ") and lines[i].strip() and items:
                    items[-1] += " " + lines[i].strip(); i += 1
                else:
                    break
            tag = "ol" if ordered else "ul"
            out.append("<%s>%s</%s>" % (tag, "".join("<li>%s</li>" % inline(it, ctx) for it in items), tag))
            continue
        buf = []
        while i < len(lines) and not (para_end(lines[i]) and buf):
            buf.append(lines[i].strip()); i += 1
        out.append("<p>%s</p>" % inline(" ".join(buf), ctx))
    return "\n".join(out)

# ---------------------------------------------------------------- build.sbt graph

def parse_build():
    src = ROOT_SBT.read_text()
    chunks = re.split(r"(?m)^(?=lazy val )", src)
    deps, descr = {}, {}
    for c in chunks:
        m = re.match(r"lazy val (\w+)\s*=", c)
        if not m or m.group(1) == "root":
            continue
        name = m.group(1)
        d = re.search(r"\.dependsOn\(([^)]*)\)", c)
        lst = []
        if d:
            for part in d.group(1).split(","):
                part = part.strip()
                if part and "% Test" not in part:
                    lst.append(part)
        deps[name] = lst
    return deps

def diagram(deps, pages):
    names = list(deps)
    depth = {}
    def d(n):
        if n not in depth:
            depth[n] = 0 if not deps[n] else 1 + max(d(x) for x in deps[n] if x in deps)
        return depth[n]
    for n in names: d(n)
    cols = {}
    for n in names:
        if n == "testkit": continue
        cols.setdefault(depth[n], []).append(n)
    BW, BH, GX, GY = 124, 38, 44, 14
    ncol = max(cols) + 1
    nrow = max(len(v) for v in cols.values())
    width = ncol * (BW + GX) - GX + 20
    height = nrow * (BH + GY) - GY + 20
    # order each column by the mean row of its dependencies (a few barycenter passes) to reduce edge crossings
    row = {n: i for c in cols.values() for i, n in enumerate(sorted(c))}
    for _ in range(4):
        for c in sorted(cols):
            def bary(n):
                ds = [row[x] for x in deps[n] if x in row] + [row[u] for u in cols_users.get(n, []) if u in row]
                return sum(ds) / len(ds) if ds else row[n]
            cols_users = {}
            for u in names:
                for x in deps[u]:
                    cols_users.setdefault(x, []).append(u)
            cols[c].sort(key=bary)
            for i, n in enumerate(cols[c]): row[n] = i
    pos = {}
    for c, ns in cols.items():
        off = (nrow - len(ns)) * (BH + GY) / 2
        for r, n in enumerate(ns):
            pos[n] = (10 + c * (BW + GX), 10 + off + r * (BH + GY))
    s = ['<svg class="arch" viewBox="0 0 %d %d" role="img" aria-label="Module dependency graph">' % (width, height)]
    s.append('<defs><marker id="arr" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="6" markerHeight="6" orient="auto"><path d="M0 0L8 4L0 8z" class="arrowhead"/></marker></defs>')
    for n in names:
        if n not in pos: continue
        for dep in deps[n]:
            if dep not in pos: continue
            x1, y1 = pos[dep][0] + BW, pos[dep][1] + BH / 2
            x2, y2 = pos[n][0], pos[n][1] + BH / 2
            mx = (x1 + x2) / 2
            s.append('<path class="edge" data-from="%s" data-to="%s" d="M%.1f %.1f C%.1f %.1f %.1f %.1f %.1f %.1f" marker-end="url(#arr)"/>' % (dep, n, x1, y1, mx, y1, mx, y2, x2 - 1, y2))
    for n in names:
        if n not in pos: continue
        x, y = pos[n]
        href = ("modules/%s.html" % n) if ("modules/%s" % n) in pages else None
        cls = "node" + (" app" if n == "examples" else "")
        g = '<g class="%s" data-name="%s" transform="translate(%.1f %.1f)"><rect width="%d" height="%d" rx="7"/><text x="%d" y="%d" text-anchor="middle">%s</text></g>' % (cls, n, x, y, BW, BH, BW // 2, BH // 2 + 4, n)
        s.append('<a href="%s">%s</a>' % (href, g) if href else g)
    s.append("</svg>")
    return '<div class="arch-wrap">' + "\n".join(s) + "</div>"

def deps_table(deps, pages):
    rows = ["| Module | Depends on |", "|---|---|"]
    for n, ds in deps.items():
        link = "[%s](modules/%s.md)" % (n, n) if ("modules/%s" % n) in pages else n
        rows.append("| %s | %s |" % (link, ", ".join(("[%s](modules/%s.md)" % (x, x)) if ("modules/%s" % x) in pages else x for x in ds) or "none"))
    return "\n".join(rows)

# ---------------------------------------------------------------- site

def parse_front(text):
    meta = {}
    if text.startswith("---\n"):
        end = text.index("\n---\n", 4)
        for line in text[4:end].split("\n"):
            k, _, v = line.partition(":")
            meta[k.strip()] = v.strip()
        text = text[end + 5:]
    return meta, text

def main():
    deps = parse_build()
    pages = {}
    for p in sorted(CONTENT.rglob("*.md")):
        rel = p.relative_to(CONTENT).with_suffix("").as_posix()
        meta, body = parse_front(p.read_text())
        pages[rel] = {"meta": meta, "body": body, "rel": rel}
    # modules/index.md is the module overview
    template = (HERE / "template.html").read_text()
    if OUT.exists(): shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    shutil.copytree(ASSETS, OUT / "assets")
    (OUT / ".nojekyll").write_text("")
    gallery = HERE.parent / "docs" / "gallery.png"
    if gallery.exists():
        shutil.copyfile(gallery, OUT / "assets" / "gallery.png")  # always the current screenshot

    def sortkey(r):
        m = pages[r]["meta"]
        return (SECTIONS.index(m.get("section", "Guide")), int(m.get("order", 100)), m.get("title", r))
    ordered = sorted(pages, key=sortkey)

    index = []
    rendered = {}
    for rel in ordered:
        pg = pages[rel]
        ctx = Ctx("../" * rel.count("/"))
        body = pg["body"].replace("{{diagram}}", "\n" + diagram(deps, pages) + "\n").replace("{{deps-table}}", deps_table(deps, pages))
        heads = []
        content = render_md(body, ctx, heads)
        rendered[rel] = (content, heads, ctx)
        text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html.unescape(content)))
        title = pg["meta"].get("title", rel)
        # one search entry per page plus one per section for precise hits
        sections = re.split(r'(?=<h[23] id=)', re.sub(r'<a class="anchor"[^>]*>#</a>', "", content))
        for sec in sections:
            m = re.match(r'<h[23] id="([^"]+)">(.*?)</h[23]>', sec, re.S)
            t = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html.unescape(sec))).strip()
            index.append({"u": rel + ".html" + ("#" + m.group(1) if m else ""), "t": title, "h": re.sub(r"<[^>]+>", "", m.group(2)) if m else "", "x": t[:1500]})

    nav_groups = {}
    for rel in ordered:
        nav_groups.setdefault(pages[rel]["meta"].get("section", "Guide"), []).append(rel)

    for k, rel in enumerate(ordered):
        pg = pages[rel]; content, heads, ctx = rendered[rel]
        root = ctx.root
        nav = []
        for sec in SECTIONS:
            if sec not in nav_groups: continue
            nav.append('<div class="nav-section"><div class="nav-title">%s</div>' % sec)
            for r in nav_groups[sec]:
                t = pages[r]["meta"].get("nav", pages[r]["meta"].get("title", r))
                nav.append('<a href="%s%s.html"%s>%s</a>' % (root, r, ' class="current" aria-current="page"' if r == rel else "", esc(t)))
            nav.append("</div>")
        toc = "".join('<a class="l%d" href="#%s">%s</a>' % (lv, sid, t) for lv, sid, t in heads)
        prev_l = '<a class="prev" href="%s%s.html"><span>Previous</span>%s</a>' % (root, ordered[k - 1], esc(pages[ordered[k - 1]]["meta"].get("title", ""))) if k > 0 else "<span></span>"
        next_l = '<a class="next" href="%s%s.html"><span>Next</span>%s</a>' % (root, ordered[k + 1], esc(pages[ordered[k + 1]]["meta"].get("title", ""))) if k + 1 < len(ordered) else "<span></span>"
        title = pg["meta"].get("title", rel)
        wide = pg["meta"].get("layout") == "home"
        page = (template
                .replace("{{root}}", root).replace("{{title}}", esc(title) + ("" if wide else " · scalaui"))
                .replace("{{description}}", html.escape(pg["meta"].get("description", "Cross-platform desktop application toolkit for Scala Native"), quote=True))
                .replace("{{nav}}", "\n".join(nav)).replace("{{toc}}", toc if not wide else "")
                .replace("{{content}}", content).replace("{{prevnext}}", "" if wide else '<nav class="prevnext">%s%s</nav>' % (prev_l, next_l))
                .replace("{{bodyclass}}", "home" if wide else "doc"))
        dest = OUT / (rel + ".html")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(page)
    (OUT / "assets" / "search-index.js").write_text("window.SEARCH_INDEX=" + json.dumps(index, separators=(",", ":")) + ";")
    print("built %d pages -> %s" % (len(pages), OUT))

if __name__ == "__main__":
    main()
