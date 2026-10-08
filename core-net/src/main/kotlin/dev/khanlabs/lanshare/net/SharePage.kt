package dev.khanlabs.lanshare.net

import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/** One ZIP download on the page: [label] is empty for a single ZIP, "Part 1 of 2" when the files need several. */
internal class ZipLink(val href: String, val label: String, val size: Long, val files: Int)

/** The ZIP buttons for a group of files. [loose] files are too big to zip and download on their own. */
internal class ZipOffer(val links: List<ZipLink>, val loose: List<ShareFile>)

/** Everything the listing page shows, worked out for one visitor. */
internal class ListingModel(
    val base: String,
    val appVersion: String,
    val remainingMs: Long?,
    val totalFiles: Int,
    val totalBytes: Long,
    /** What was added after this visitor first looked and has not been downloaded by them yet. */
    val fresh: List<ShareItem>,
    val earlier: List<ShareItem>,
    /** Ids this visitor already downloaded completely. */
    val got: Set<Int>,
    val everything: ZipOffer?,
    val freshZip: ZipOffer?,
    /** ZIP buttons for folders, by the id of the folder's first file. */
    val folderZips: Map<Int, ZipOffer>,
    /** Changes when the list changes; the page's script compares it with the server's. */
    val version: String,
    val preparing: Int,
    val failed: List<ShareFile>,
    val downloads: List<ShareDownload>,
    val connectUrl: String,
)

/**
 * The web pages of Share to anyone. Plain HTML and CSS: the page lists what was shared and links to the downloads.
 * The list page has one small script, allowed by a hash in the security policy, that checks every few seconds whether
 * the list changed and then shows a link to reload; without scripts the page reloads itself now and then. Every
 * value that came from a file name or a link is escaped.
 */
internal object SharePage {
    /** The only script on any page. It reads nothing but a version number, and can only ask this server for one. */
    private const val SCRIPT = "(function(){var b=document.getElementById('upd'),v=document.body.getAttribute('data-v');" +
        "if(!b||!v||!window.fetch)return;var u=location.pathname.replace(/\\/?$/,'/')+'v';" +
        "function t(){fetch(u,{cache:'no-store'}).then(function(r){return r.ok?r.text():v}).then(function(x){" +
        "if(x!==v)b.hidden=false}).catch(function(){})}setInterval(t,5000)})();"

    val CSP: String = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'self'; base-uri 'none'; " +
        "frame-ancestors 'none'; connect-src 'self'; script-src 'sha256-" +
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(SCRIPT.toByteArray(Charsets.UTF_8))) + "'"

    private const val ICON_SVG =
        """<svg viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg"><rect width="256" height="256" rx="58" fill="#0F766E"/><path d="M59.8,191.4 L128,72.6 L196.2,191.4" fill="none" stroke="#FEF7FF" stroke-width="30.8" stroke-linecap="round" stroke-linejoin="round"/><circle cx="128" cy="154" r="16.5" fill="#FEF7FF"/></svg>"""

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

    private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

    private fun button(href: String, text: String, note: String = "", primary: Boolean = true, icon: String = DOWNLOAD): String =
        """<a class="btn${if (primary) " btn-primary" else ""}" href="${escape(href)}">$icon${escape(text)}${if (note.isEmpty()) "" else " <small>(${escape(note)})</small>"}</a>"""

    private fun zipButtons(offer: ZipOffer, whole: String, icon: String = DOWNLOAD): String = offer.links.joinToString("") { l ->
        val text = if (offer.links.size == 1) whole else "ZIP ${l.label.replaceFirstChar { it.lowercase() }}"
        button(l.href, text, size(l.size), icon = icon)
    }

    private fun looseNote(offer: ZipOffer?): String {
        val loose = offer?.loose.orEmpty()
        if (loose.isEmpty()) return ""
        return """<span class="note">${escape(plural(loose.size, "file"))} over 1.9 GB ${if (loose.size == 1) "is" else "are"} not in the ZIP. """ +
            """Download ${if (loose.size == 1) "it" else "them"} from the list below.</span>"""
    }

    private fun row(item: ShareItem, m: ListingModel, isNew: Boolean): String {
        val tags = StringBuilder()
        val acts = StringBuilder()
        if (isNew) tags.append("""<span class="tag new">New</span>""")
        val ready = item.files.filter { it.state == ShareFile.State.READY }
        if (item.failed > 0) {
            tags.append("""<span class="tag bad">${if (item.folder) "${item.failed} couldn't be read" else "Couldn't be read"}</span>""")
        }
        if (item.preparing > 0) {
            tags.append("""<span class="tag">Getting ready${if (item.folder) ", ${item.preparing} left" else ""}</span>""")
        }
        if (ready.isNotEmpty() && ready.size == item.files.size && ready.all { it.id in m.got }) tags.append("""<span class="tag ok">Downloaded</span>""")
        if (!item.folder) {
            ready.firstOrNull()?.let { acts.append(button("${m.base}f/${it.id}", "Download")) }
        } else {
            val offer = m.folderZips[item.firstId]
            if (offer != null) acts.append(zipButtons(offer, "Download ZIP"))
            val single = if (offer == null) ready.singleOrNull() else null
            if (single != null) acts.append(button("${m.base}f/${single.id}", "Download"))
            // files too big for a ZIP each get their own link
            val own = offer?.loose.orEmpty()
            if (own.isNotEmpty()) {
                acts.append("""<span class="note">""" + own.joinToString(" ") { f ->
                    """<a href="${m.base}f/${f.id}">${escape(f.name.substringAfterLast('/'))} (${escape(size(f.size))})</a>"""
                } + "</span>")
            }
        }
        return """<li class="${if (item.folder) "dir" else "file"}"><span class="ic">${if (item.folder) FOLDER else FILE}</span>""" +
            """<span class="nm"><b>${escape(item.name)}</b><span class="meta">${escape(describe(item))}</span></span>""" +
            """<span class="act">$tags$acts</span></li>"""
    }

    fun listing(m: ListingModel): String {
        val parts = ArrayList<String>()
        parts += "<h1>Shared with you</h1>"
        parts += """<p class="sub">${escape(plural(m.totalFiles, "file"))}, ${escape(size(m.totalBytes))}. ${escape(minutesLeft(m.remainingMs))}</p>"""
        parts += """<div id="upd" class="banner" hidden><a href="">The list was updated. Tap to see what changed.</a></div>"""
        if (m.preparing > 0) {
            parts += """<p class="box">Getting ${escape(plural(m.preparing, "file"))} ready. A download button shows up here as soon as each one can be downloaded.</p>"""
        }
        if (m.failed.isNotEmpty()) {
            val names = m.failed.take(5).joinToString(", ") { escape(it.name) } + if (m.failed.size > 5) " and ${m.failed.size - 5} more" else ""
            parts += """<p class="box warn">${escape(plural(m.failed.size, "file"))} could not be read on the sharing device: $names. Ask them to share again.</p>"""
        }
        m.everything?.let { all ->
            parts += """<div class="all">${zipButtons(all, "Download everything as one ZIP", ARCHIVE)}${looseNote(all)}</div>"""
        }
        if (m.fresh.isNotEmpty()) {
            val n = m.fresh.sumOf { it.files.size }
            parts += """<div class="sec"><h2>Just added</h2><span class="tag new">${escape(plural(n, "new file"))}</span></div>"""
            m.freshZip?.let { z -> parts += """<div class="all">${zipButtons(z, "Download the new files as one ZIP", ARCHIVE)}${looseNote(z)}</div>""" }
            parts += """<ul class="items">${m.fresh.joinToString("") { row(it, m, true) }}</ul>"""
            if (m.earlier.isNotEmpty()) parts += """<div class="sec"><h2>Shared before</h2></div>"""
        }
        if (m.earlier.isNotEmpty()) parts += """<ul class="items">${m.earlier.joinToString("") { row(it, m, false) }}</ul>"""
        parts += """<p class="small">To check that a download is complete, compare it with <a href="${m.base}checksums.sha256">this list of SHA-256 checksums</a>.</p>"""
        parts += getAmanah(m)
        return render("Shared with you", m.appVersion, parts.joinToString("\n"),
            head = """<noscript><meta http-equiv="refresh" content="30"></noscript>""",
            bodyAttr = """ data-v="${escape(m.version)}"""")
    }

    private fun getAmanah(m: ListingModel): String {
        val buttons = ArrayList<String>()
        m.downloads.forEachIndexed { i, d ->
            buttons += """<a class="btn" href="${m.base}get/$i">$DOWNLOAD${escape(d.label)} (${escape(size(d.size))})</a>"""
        }
        if (m.connectUrl.isNotEmpty()) buttons += """<a class="btn" href="${escape(m.connectUrl)}">Open in Amanah</a>"""
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

    private fun render(title: String, version: String, content: String, head: String = "", bodyAttr: String = ""): String = PAGE
        .replace("{{TITLE}}", escape(title))
        .replace("{{FAVICON}}", FAVICON)
        .replace("{{ICON}}", ICON_SVG)
        .replace("{{VERSION}}", escape(version))
        .replace("{{HEAD}}", head)
        .replace("{{BODYATTR}}", bodyAttr)
        .replace("{{SCRIPT}}", if (bodyAttr.isEmpty()) "" else "<script>$SCRIPT</script>")
        .replace("{{CONTENT}}", content)

    // The slot with text from outside (CONTENT) is filled last, so a file named "{{ICON}}" is never expanded.
    private const val PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>{{TITLE}} — Amanah by KhanLabs.dev</title>
<link rel="icon" type="image/svg+xml" href="{{FAVICON}}">
{{HEAD}}
<style>
  /* The look of the Amanah app: one accent, three surfaces (paper, card, raised), corners of 12, 20 and 28, one typeface,
     short spring motion, flat surfaces. The numbers are the app's (Theme.kt and Theme.qml).
     The list page has one tiny script that asks this server whether the list changed; without scripts the page reloads. */
  :root {
    --paper: #FEF7FF; --card: #F7F2FA; --raised: #E6E0E9;
    --ink: #1D1B20; --muted: #49454F; --border: #CAC4D0; --line: #E6E0E9;
    --accent: #0F766E; --accent-ink: #FFFFFF; --accent-soft: rgba(15, 118, 110, 0.12);
    --brick: #C62828;
    --r8: 8px; --r12: 12px; --r20: 20px; --r28: 28px;
    --font: 'Segoe UI Variable Text', 'Segoe UI', system-ui, -apple-system, Roboto, sans-serif;
    --quick: 140ms; --spring: cubic-bezier(0.3, 1.5, 0.5, 1);
    color-scheme: light;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --paper: #0B0D0E; --card: #151A1C; --raised: #1D2427;
      --ink: #E8EEF0; --muted: #A9B4B8; --border: #2E3639; --line: #1D2427;
      --accent: #2DD4BF; --accent-ink: #0B0D0E; --accent-soft: rgba(45, 212, 191, 0.14);
      --brick: #FF5F57;
      color-scheme: dark;
    }
  }
  * { box-sizing: border-box; }
  [hidden] { display: none !important; }
  body {
    margin: 0; min-height: 100vh; display: flex; flex-direction: column;
    background: var(--paper); color: var(--ink); font: 15px/1.5 var(--font);
    font-variant-numeric: tabular-nums;
  }
  .i { flex: none; display: block; }

  /* brand bar: "Amanah by KhanLabs.dev" */
  .brand-bar { display: flex; align-items: center; gap: 10px; padding: 14px 20px; border-bottom: 1px solid var(--border); background: var(--paper); }
  .brand { display: flex; align-items: center; gap: 12px; min-width: 0; }
  .brand-icon { width: 30px; height: 30px; border-radius: 23%; flex: none; overflow: hidden; }
  .brand-icon svg { width: 100%; height: 100%; display: block; }
  .brand-mark { font-size: 21px; font-weight: 700; letter-spacing: 0.3px; white-space: nowrap; }
  .brand-by { font-size: 13px; color: var(--muted); text-decoration: none; white-space: nowrap; padding-top: 3px; }
  .brand-by:hover { color: var(--accent); text-decoration: underline; }

  main { flex: 1; width: 100%; max-width: 720px; margin: 0 auto; padding: 24px 16px 36px; }
  h1 { font-size: 26px; font-weight: 600; margin: 0 0 4px; line-height: 1.2; word-break: break-word; }
  h2 { font-size: 15px; margin: 0 0 4px; }
  .sub { margin: 0 0 18px; color: var(--muted); font-size: 14px; }

  /* buttons: pills, like the app */
  .btn {
    display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 44px;
    padding: 8px 20px; border-radius: 999px; text-decoration: none; cursor: pointer;
    font: 600 14px/1.2 var(--font); border: 1px solid transparent; background: var(--raised); color: var(--ink);
    transition: transform var(--quick) var(--spring), border-color var(--quick) ease;
  }
  .btn small { font-weight: 500; opacity: 0.8; }
  .btn:hover { border-color: var(--accent); }
  .btn:active { transform: scale(0.97); }
  .btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .btn-primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
  .btn-primary:hover { border-color: var(--ink); }

  .all { display: flex; flex-wrap: wrap; gap: 10px 14px; align-items: center; margin-bottom: 14px; }
  .note { color: var(--muted); font-size: 13.5px; }
  .note a { color: var(--accent); text-decoration: none; }
  .note a:hover { text-decoration: underline; }
  .banner { margin: 0 0 14px; }
  .banner a { display: block; padding: 12px 16px; border-radius: var(--r12); background: var(--accent-soft); color: var(--accent); text-decoration: none; font-weight: 600; }
  .box { margin: 0 0 14px; padding: 12px 16px; border-radius: var(--r12); background: var(--card); color: var(--muted); font-size: 14px; }
  .box.warn { box-shadow: inset 0 0 0 1.5px var(--brick); }
  .sec { display: flex; align-items: center; gap: 10px; margin: 22px 0 8px; }
  .sec h2 { margin: 0; font-size: 12.5px; font-weight: 600; letter-spacing: 0.8px; text-transform: uppercase; color: var(--muted); }
  .small { color: var(--muted); font-size: 13px; margin: 0 0 18px; }
  .small a { color: var(--accent); text-decoration: none; }
  .tag { display: inline-block; padding: 1px 10px; border-radius: 999px; font: 600 12px/1.7 var(--font); background: var(--raised); color: var(--muted); white-space: nowrap; }
  .tag.new { background: var(--accent); color: var(--accent-ink); }
  .tag.ok { color: var(--accent); background: var(--accent-soft); }
  .tag.bad { color: var(--brick); box-shadow: inset 0 0 0 1px var(--brick); background: transparent; }

  ul.items { list-style: none; margin: 0 0 18px; padding: 0; background: var(--card); border-radius: var(--r20); overflow: hidden; }
  ul.items li { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-top: 1px solid var(--line); }
  ul.items li:first-child { border-top: 0; }
  .ic { color: var(--muted); }
  .dir .ic { color: var(--accent); }
  .nm { flex: 1; min-width: 0; }
  .nm b { display: block; font-weight: 600; word-break: break-word; }
  .nm .meta { font-size: 13px; color: var(--muted); }
  .act { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: flex-end; }

  .card { background: var(--card); border-radius: var(--r20); padding: 18px; margin-bottom: 18px; }
  .card p { margin: 0 0 12px; color: var(--muted); font-size: 14px; }
  .card .row { display: flex; flex-wrap: wrap; gap: 10px; }
  .login { max-width: 380px; margin: 36px auto 0; text-align: center; border-radius: var(--r28); padding: 28px 22px; }
  .login input {
    width: 9em; padding: 10px 12px; text-align: center; letter-spacing: 6px; font: 600 20px var(--font);
    border: 1px solid transparent; border-radius: var(--r12); background: var(--raised); color: var(--ink);
  }
  .login input:focus { outline: none; border-color: var(--accent); }
  .login form { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
  .err { margin: 12px 0 0; color: var(--brick); font-size: 14px; }
  .ended { text-align: center; margin-top: 36px; }
  .ended .ic { display: inline-flex; padding: 14px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); margin-bottom: 12px; }

  footer { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px 18px; padding: 14px 16px; border-top: 1px solid var(--border); font-size: 12.5px; color: var(--muted); }
  footer .made { color: var(--muted); }
  footer .heart { color: var(--brick); }
  footer a { color: var(--accent); text-decoration: none; }
  footer a:hover { text-decoration: underline; }
  @media (max-width: 480px) { ul.items li { flex-wrap: wrap; } .act { width: 100%; justify-content: stretch; } .act .btn { flex: 1 1 100%; } }
  @media (max-width: 360px) { .brand-by { display: none; } }
  @media (prefers-reduced-motion: reduce) { .btn { transition: none; } .btn:active { transform: none; } }
</style>
</head>
<body{{BODYATTR}}>
<header class="brand-bar">
  <span class="brand">
    <span class="brand-icon">{{ICON}}</span>
    <span class="brand-mark">Amanah</span>
  </span>
  <a class="brand-by" href="https://khanlabs.dev" target="_blank" rel="noopener noreferrer">by KhanLabs.dev</a>
</header>

<main>
  {{CONTENT}}
</main>

<footer>
  <span>{{VERSION}}</span>
  <span class="made">Made with <span class="heart" role="img" aria-label="love">&#9829;&#xFE0E;</span> by Khan</span>
  <a href="https://khanlabs.dev" target="_blank" rel="noopener noreferrer">KhanLabs.dev</a>
</footer>
{{SCRIPT}}
</body>
</html>
"""
}
