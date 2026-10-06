package dev.khanlabs.lanshare.net

import java.net.URLEncoder
import java.util.Locale

/**
 * The web pages of Share to anyone. Plain HTML and CSS, no scripts: the page lists what was shared and links to
 * the downloads. Matches `share_page.html` in the Windows app. Every value that came from a file name or a link is
 * escaped.
 */
internal object SharePage {
    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'; base-uri 'none'; " +
        "frame-ancestors 'none'"

    private const val ICON_SVG =
        """<svg viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="klbg" x1="0" y1="0" x2="256" y2="256" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#171b24"/><stop offset="1" stop-color="#0a0c11"/></linearGradient></defs><rect width="256" height="256" rx="54" fill="url(#klbg)"/><rect x="1.5" y="1.5" width="253" height="253" rx="52.5" fill="none" stroke="#2a3038" stroke-width="2"/><text x="128" y="163" font-family="Georgia, 'Times New Roman', serif" font-size="132" font-weight="700" fill="#eef1f5" text-anchor="middle">K</text><path d="M58 190 Q 128 174 198 190" fill="none" stroke="#2dd4bf" stroke-width="11" stroke-linecap="round"/></svg>"""

    private val FAVICON = "data:image/svg+xml," + URLEncoder.encode(ICON_SVG, "UTF-8").replace("+", "%20")

    private fun line(path: String, size: Int) =
        """<svg class="i" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">$path</svg>"""

    private val FOLDER = line("""<path d="M3 7.5A2.5 2.5 0 0 1 5.5 5H9l2 2.2h7.5A2.5 2.5 0 0 1 21 9.7v7.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z"/>""", 22)
    private val FILE = line("""<path d="M14 3H7.5A2.5 2.5 0 0 0 5 5.5v13A2.5 2.5 0 0 0 7.5 21h9a2.5 2.5 0 0 0 2.5-2.5V8z"/><path d="M14 3v5h5"/>""", 22)
    private val DOWNLOAD = line("""<path d="M12 4v11"/><path d="m7 10.5 5 5 5-5"/><path d="M5 20h14"/>""", 16)
    private val ARCHIVE = line("""<rect x="3" y="4" width="18" height="4.5" rx="1.2"/><path d="M5 8.5v10A2.5 2.5 0 0 0 7.5 21h9a2.5 2.5 0 0 0 2.5-2.5v-10"/><path d="M10 12.5h4"/>""", 16)
    private val LOCK = line("""<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>""", 22)

    fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#x27;")
            else -> append(c)
        }
    }

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble()
        for (unit in listOf("KB", "MB", "GB", "TB")) {
            v /= 1024
            if (v < 1024 || unit == "TB") return if (v >= 100) String.format(Locale.US, "%.0f %s", v, unit) else String.format(Locale.US, "%.1f %s", v, unit)
        }
        return "$bytes B"
    }

    private fun describe(item: ShareItem) =
        if (!item.folder) size(item.size) else "${item.files.size} file${if (item.files.size != 1) "s" else ""}, ${size(item.size)}"

    private fun minutesLeft(ms: Long?): String {
        if (ms == null) return ""
        val minutes = maxOf(1L, Math.round(ms / 60_000.0))
        return if (minutes < 90) "Available for about $minutes more minute${if (minutes != 1L) "s" else ""}."
        else "Available for about ${Math.round(minutes / 60.0)} more hours."
    }

    fun listing(share: ShareServer, base: String): String {
        val rows = share.items.mapIndexed { i, it ->
            val route = if (it.folder) "z" else "f"
            """<li class="${if (it.folder) "dir" else "file"}"><span class="ic">${if (it.folder) FOLDER else FILE}</span>""" +
                """<span class="nm"><b>${escape(it.name)}</b><span>${escape(describe(it))}</span></span>""" +
                """<a class="btn btn-primary" href="$base$route/$i">$DOWNLOAD${if (it.folder) "Download ZIP" else "Download"}</a></li>"""
        }
        val parts = ArrayList<String>()
        parts += "<h1>Shared with you</h1>"
        parts += """<p class="sub">${share.items.size} item${if (share.items.size != 1) "s" else ""}, ${escape(size(share.totalSize))}. ${escape(minutesLeft(share.remainingMs()))}</p>"""
        if (share.items.size > 1 || share.items[0].folder) {
            parts += """<div class="all"><a class="btn btn-primary" href="${base}all.zip">$ARCHIVE Download everything as one ZIP</a>""" +
                """<span class="note">${escape(size(share.zipAll.length))}</span></div>"""
        }
        parts += """<ul class="items">${rows.joinToString("")}</ul>"""
        parts += getAmanah(share, base)
        return render("Shared with you", share.appVersion, parts.joinToString("\n"))
    }

    private fun getAmanah(share: ShareServer, base: String): String {
        val buttons = ArrayList<String>()
        share.downloads.forEachIndexed { i, d ->
            buttons += """<a class="btn" href="${base}get/$i">$DOWNLOAD${escape(d.label)} (${escape(size(d.size))})</a>"""
        }
        if (share.connectUrl.isNotEmpty()) buttons += """<a class="btn" href="${escape(share.connectUrl)}">Open in Amanah</a>"""
        if (buttons.isEmpty()) return ""
        return """<div class="card"><h2>Share files like this yourself</h2>""" +
            """<p>Amanah is a free app that sends files between phones and computers on the same Wi-Fi, with no account and no internet needed.</p>""" +
            """<div class="row">${buttons.joinToString("")}</div></div>"""
    }

    fun login(version: String, error: String = ""): String {
        val err = if (error.isEmpty()) "" else """<p class="err">${escape(error)}</p>"""
        return render("Enter the PIN", version,
            """<div class="card login"><h1>Enter the PIN</h1>""" +
                """<p>This share is protected. Ask the person sharing it for the 6-digit PIN shown on their screen.</p>""" +
                """<form method="post" action=""><input name="pin" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" required autofocus """ +
                """autocomplete="one-time-code" aria-label="6-digit PIN" placeholder="------">""" +
                """<button class="btn btn-primary" type="submit">Open share</button></form>$err</div>""")
    }

    fun ended(version: String): String = render("This share has ended", version,
        """<div class="ended"><span class="ic">$LOCK</span><h1>This share has ended</h1>""" +
            """<p class="sub">Ask the person who sent you the code to share again.</p></div>""")

    fun notFound(version: String): String = render("Not found", version, """<div class="ended"><h1>Not found</h1></div>""")

    private fun render(title: String, version: String, content: String): String = PAGE
        .replace("{{TITLE}}", escape(title))
        .replace("{{FAVICON}}", FAVICON)
        .replace("{{ICON}}", ICON_SVG)
        .replace("{{VERSION}}", escape(version))
        .replace("{{CONTENT}}", content)

    // The slots with text from outside (CONTENT) are filled last, so a file named "{{ICON}}" is never expanded.
    private const val PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>{{TITLE}} — Amanah</title>
<link rel="icon" type="image/svg+xml" href="{{FAVICON}}">
<style>
  :root {
    --paper: #080a0e; --card: #12151b; --card-2: #181c24;
    --ink: #e7eaef; --muted: #97a0ad; --faint: #5c6470;
    --border: #222831; --border-soft: #1b1f26;
    --clay: #2dd4bf; --clay-strong: #5eead4; --clay-soft: rgba(45, 212, 191, 0.16); --on-clay: #080a0e;
    --brick: #ff5f57;
    --radius: 10px; --radius-sm: 7px;
    --font-display: Georgia, 'Times New Roman', serif;
    --font-body: 'Segoe UI', system-ui, -apple-system, Roboto, sans-serif;
    --font-mono: 'Cascadia Code', Consolas, ui-monospace, monospace;
    color-scheme: dark;
  }
  @media (prefers-color-scheme: light) {
    :root {
      --paper: #f4efe4; --card: #fbf8f1; --card-2: #f0ead9;
      --ink: #29251f; --muted: #6f675b; --faint: #a89e8c;
      --border: #e4dcc8; --border-soft: #ece4d2;
      --clay: #0d9488; --clay-strong: #0f766e; --clay-soft: rgba(13, 148, 136, 0.14); --on-clay: #fff;
      --brick: #a8442f;
      color-scheme: light;
    }
  }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: flex; flex-direction: column; background: var(--paper); color: var(--ink); font: 15px/1.45 var(--font-body); }
  .i { flex: none; display: block; }
  .brand-bar { display: flex; align-items: center; gap: 12px; padding: 13px 20px; border-bottom: 1px solid var(--border); background: linear-gradient(180deg, var(--card) 0%, var(--card-2) 100%); }
  .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .brand-icon { width: 27px; height: 27px; border-radius: 7px; flex: none; overflow: hidden; box-shadow: 0 0 0 1px var(--border-soft); }
  .brand-icon svg { width: 100%; height: 100%; display: block; }
  .brand-mark { font-family: var(--font-display); font-size: 19px; font-weight: 700; white-space: nowrap; }
  .brand-mark .dev { color: var(--clay-strong); font-style: italic; }
  .brand-divider { width: 1px; height: 16px; background: var(--border); flex: none; }
  .brand-subtitle { font-size: 13px; color: var(--muted); font-weight: 600; }
  main { flex: 1; width: 100%; max-width: 720px; margin: 0 auto; padding: 24px 16px 36px; }
  h1 { font-family: var(--font-display); font-size: 25px; font-weight: 700; margin: 0 0 4px; line-height: 1.2; word-break: break-word; }
  h2 { font-size: 15px; margin: 0 0 4px; }
  .sub { margin: 0 0 18px; color: var(--muted); font-size: 13.5px; }
  .btn { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 40px; padding: 8px 16px; border-radius: var(--radius-sm); text-decoration: none; cursor: pointer; font: 600 14px/1.2 var(--font-body); border: 1px solid var(--border); background: var(--card-2); color: var(--ink); }
  .btn:hover { border-color: var(--clay); }
  .btn:focus-visible { outline: none; border-color: var(--clay); box-shadow: 0 0 0 3px var(--clay-soft); }
  .btn-primary { background: var(--clay); border-color: var(--clay); color: var(--on-clay); }
  .btn-primary:hover { background: var(--clay-strong); border-color: var(--clay-strong); }
  .all { display: flex; flex-wrap: wrap; gap: 10px 14px; align-items: center; margin-bottom: 14px; }
  .all .note { color: var(--muted); font-size: 13px; }
  ul.items { list-style: none; margin: 0 0 18px; padding: 0; background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; }
  ul.items li { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border-top: 1px solid var(--border-soft); }
  ul.items li:first-child { border-top: 0; }
  .ic { color: var(--muted); }
  .dir .ic { color: var(--clay); }
  .nm { flex: 1; min-width: 0; }
  .nm b { display: block; font-weight: 600; word-break: break-word; }
  .nm span { font: 12px var(--font-mono); color: var(--muted); }
  .card { background: var(--card); border: 1px solid var(--border); border-radius: var(--radius); padding: 16px; margin-bottom: 18px; }
  .card p { margin: 0 0 12px; color: var(--muted); font-size: 13.5px; }
  .card .row { display: flex; flex-wrap: wrap; gap: 10px; }
  .login { max-width: 380px; margin: 36px auto 0; text-align: center; }
  .login input { width: 9em; padding: 10px 12px; text-align: center; letter-spacing: 6px; font: 600 20px var(--font-mono); border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--card-2); color: var(--ink); }
  .login input:focus { outline: none; border-color: var(--clay); box-shadow: 0 0 0 3px var(--clay-soft); }
  .login form { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
  .err { margin: 12px 0 0; color: var(--brick); font-size: 13.5px; }
  .ended { text-align: center; margin-top: 36px; }
  .ended .ic { display: inline-flex; padding: 12px; border-radius: 50%; background: var(--clay-soft); color: var(--clay); margin-bottom: 12px; }
  footer { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px 18px; padding: 14px 16px; border-top: 1px solid var(--border); font-size: 12.5px; color: var(--faint); }
  footer .made { color: var(--muted); }
  footer .heart { color: var(--brick); }
  footer a { color: var(--clay); text-decoration: none; }
  footer a:hover { text-decoration: underline; }
  @media (max-width: 480px) { ul.items li { flex-wrap: wrap; } ul.items li .btn { width: 100%; } }
</style>
</head>
<body>
<header class="brand-bar">
  <span class="brand">
    <span class="brand-icon">{{ICON}}</span>
    <span class="brand-mark">KhanLabs<span class="dev">.dev</span></span>
  </span>
  <span class="brand-divider"></span>
  <span class="brand-subtitle">Amanah</span>
</header>

<main>
  {{CONTENT}}
</main>

<footer>
  <span>{{VERSION}}</span>
  <span class="made">Made with <span class="heart" role="img" aria-label="love">&#9829;&#xFE0E;</span> by Khan</span>
  <a href="https://khanlabs.dev" target="_blank" rel="noopener noreferrer">khanlabs.dev</a>
</footer>
</body>
</html>
"""
}
