package com.atendenteia.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URL

/**
 * Leitura de uma página pública qualquer, em duas etapas:
 *  1. direta (HTML, sem navegador), com dados estruturados JSON-LD;
 *  2. Jina Reader (r.jina.ai, grátis), que abre a página num navegador e
 *     devolve o texto — para sites montados por JavaScript.
 *
 * Leitor independente: não depende de nenhum servidor próprio.
 */
object WebPageReader {

    data class Photo(val name: String, val url: String)
    data class Page(val text: String, val photos: List<Photo>, val via: String)

    private const val MAX_HTML = 1_800_000
    private const val MIN_TEXT = 400

    /** Página de verificação antirrobô (Cloudflare etc.): não há o que ler. */
    private val BLOCKED_PAGE = Regex("just a moment|security verification|verificação de segurança|requiring captcha", RegexOption.IGNORE_CASE)

    /** Imagem em Markdown, como o Jina devolve: ![nome](https://…) */
    private val MD_IMAGE = Regex("!\\[([^\\]]*)\\]\\((https?://[^)\\s]+)[^)]*\\)")

    fun read(url: String): Page {
        assertPublicUrl(url)
        val direct = runCatching { readDirect(url) }.getOrNull()
        if (direct != null) return direct
        return readWithJina(url)
    }

    // ---------------- 1. Leitura direta ----------------

    private fun readDirect(url: String): Page? {
        val html = httpGet(url, "text/html,application/xhtml+xml").take(MAX_HTML)
        if (requiresJavascript(html)) return null
        val text = cleanText(html)
        if (BLOCKED_PAGE.containsMatchIn(text.take(1500))) return null
        val jsonLd = structuredData(html)
        val full = buildString {
            append(text)
            if (jsonLd.isNotBlank()) append("\n\nDados estruturados da página (JSON-LD):\n").append(jsonLd)
        }
        if (full.length < MIN_TEXT) return null
        return Page(full, productPhotosFromJsonLd(html, url), "direta")
    }

    /** Página que só mostra conteúdo com JavaScript . */
    fun requiresJavascript(html: String): Boolean {
        if (!Regex("<script\\b[^>]*src=", RegexOption.IGNORE_CASE).containsMatchIn(html)) return false
        val visible = html
            .replace(Regex("(?is)<(script|style|svg|template|noscript)[\\s\\S]*?</\\1\\s*>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " ")
            .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        val message = Regex("(?:habilitar|ativar|enable|requires|necess[aá]rio).*?javascript|javascript.*?(?:habilitar|ativar|enable|required)", RegexOption.IGNORE_CASE)
        val structuredProduct = Regex("\"@type\"\\s*:\\s*\"Product\"|itemtype=[\"'][^\"']*Product", RegexOption.IGNORE_CASE).containsMatchIn(html)
        return !structuredProduct && (message.containsMatchIn(visible) || visible.length < 75)
    }

    private fun decodeHtml(v: String): String {
        val map = mapOf("&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">",
            "&nbsp;" to " ", "&#39;" to "'", "&#x27;" to "'", "&#x2f;" to "/")
        return v.replace(Regex("&(?:amp|quot|apos|lt|gt|nbsp|#39|#x27|#x2F);", RegexOption.IGNORE_CASE)) { map[it.value.lowercase()] ?: it.value }
            .replace(Regex("&#(\\d+);")) { m -> runCatching { String(Character.toChars(m.groupValues[1].toInt().coerceAtMost(0x10ffff))) }.getOrDefault("") }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> runCatching { String(Character.toChars(m.groupValues[1].toInt(16).coerceAtMost(0x10ffff))) }.getOrDefault("") }
    }

    fun cleanText(html: String): String = decodeHtml(html
        .replace(Regex("(?s)<!--.*?-->"), " ")
        .replace(Regex("(?is)<(script|style|svg|noscript|template)[\\s\\S]*?</\\1\\s*>"), " ")
        .replace(Regex("(?is)<(header|footer|nav)[\\s\\S]*?</\\1\\s*>"), " ")
        .replace(Regex("(?i)</(?:p|li|h[1-6]|div|article|section|tr|td)>|<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]*>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n[ \\t]*"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")).trim()

    private val LD_SCRIPT = Regex("(?is)<script\\b[^>]*type=[\"']application/ld\\+json[\"'][^>]*>([\\s\\S]*?)</script>")

    private fun structuredData(html: String): String {
        val chunks = mutableListOf<String>()
        for (m in LD_SCRIPT.findAll(html)) {
            if (chunks.sumOf { it.length } > 12_000) break
            runCatching { chunks += JSONTokener(m.groupValues[1].trim()).nextValue().toString().take(6_000) }
        }
        return chunks.joinToString("\n").take(12_000)
    }

    /** Fotos de produtos declaradas no JSON-LD (nome + imagem), sem adivinhar. */
    private fun productPhotosFromJsonLd(html: String, base: String): List<Photo> {
        val out = mutableListOf<Photo>()
        fun walk(v: Any?, depth: Int) {
            if (depth > 12 || out.size >= 150) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                is JSONObject -> {
                    val type = v.opt("@type").let { if (it is JSONArray) (0 until it.length()).joinToString(" ") { i -> it.optString(i) } else it?.toString().orEmpty() }
                    val name = v.optString("name")
                    if (Regex("(?:^|\\W)(?:Product|MenuItem)(?:$|\\W)", RegexOption.IGNORE_CASE).containsMatchIn(type) && name.isNotBlank()) {
                        val img = v.opt("image")
                        val raw = when (img) {
                            is String -> img
                            is JSONArray -> img.opt(0)?.let { if (it is JSONObject) it.optString("url", it.optString("contentUrl")) else it.toString() }
                            is JSONObject -> img.optString("url", img.optString("contentUrl"))
                            else -> null
                        }
                        absolute(raw, base)?.let { out += Photo(name.trim().take(200), it) }
                    }
                    for (k in v.keys()) {
                        if (k in setOf("@context", "description", "image", "offers", "aggregateRating", "review")) continue
                        walk(v.opt(k), depth + 1)
                    }
                }
            }
        }
        for (m in LD_SCRIPT.findAll(html)) runCatching { walk(JSONTokener(m.groupValues[1]).nextValue(), 0) }
        return out
    }

    // ---------------- 2. Jina Reader ----------------

    private fun readWithJina(url: String): Page {
        val md = httpGet("https://r.jina.ai/$url", "text/plain", mapOf("X-Timeout" to "20")).take(MAX_HTML)
        if (BLOCKED_PAGE.containsMatchIn(md.take(1500))) {
            throw IllegalStateException("o site bloqueou a leitura automática")
        }
        // Tira os endereços de imagem do texto (economiza cota do Gemini) e guarda nome → foto.
        val photos = MD_IMAGE.findAll(md)
            .map { Photo(it.groupValues[1].trim(), it.groupValues[2]) }
            .filter { it.name.length >= 3 && !Regex("\\.svg(?:$|\\?)|logo|icon|avatar|placeholder", RegexOption.IGNORE_CASE).containsMatchIn(it.url) }
            .distinctBy { it.url }.take(150).toList()
        val text = md.replace(MD_IMAGE, " ").replace(Regex("[ \\t]+"), " ").trim()
        if (text.isBlank()) throw IllegalStateException("página sem texto legível")
        return Page(text, photos, "leitor")
    }

    // ---------------- Rede ----------------

    private fun absolute(raw: String?, base: String): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val u = URI(base).resolve(raw.trim())
            if (u.scheme == "https" || u.scheme == "http") u.toString().take(2000) else null
        }.getOrNull()
    }

    /** Só endereços públicos: nada de localhost ou rede interna (regra do importador). */
    fun assertPublicUrl(raw: String) {
        val u = URI(raw)
        require(u.scheme == "http" || u.scheme == "https") { "use um endereço http ou https" }
        val host = u.host?.lowercase() ?: throw IllegalArgumentException("endereço inválido")
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.endsWith(".internal")) {
            "endereço local não permitido"
        }
        for (a in InetAddress.getAllByName(host)) {
            val private = a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
                a.isMulticastAddress || (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) ||
                (a is Inet4Address && a.address[0].toInt() and 0xff == 100 && (a.address[1].toInt() and 0xff) in 64..127)
            require(!private) { "o endereço não é público" }
        }
    }

    fun httpGet(url: String, accept: String, headers: Map<String, String> = emptyMap()): String {
        var current = url
        repeat(4) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "AtendenteIA/0.1 (Android)")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("redirecionamento inválido")
                    current = URI(current).resolve(loc).toString()
                    assertPublicUrl(current)
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                    val buf = CharArray(8192); val sb = StringBuilder()
                    while (sb.length < MAX_HTML) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
                    sb.toString()
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("o site redirecionou muitas vezes")
    }
}
