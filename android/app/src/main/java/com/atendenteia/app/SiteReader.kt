package com.atendenteia.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Foto de produto que veio do site (a IA pede pelo código "p<id>"). */
data class SitePhoto(val code: String, val url: String, val caption: String)

/**
 * Lê os sites da loja ANTES de a IA responder, para ela saber se está aberta e
 * o que tem disponível agora. Quem lê é este leitor; o Gemini só interpreta o
 * texto pronto. Quem lê é o [WebPageReader]: HTML direto; se a página depender de
 * JavaScript, o Jina Reader (r.jina.ai, grátis) abre e devolve o texto.
 * O resultado fica guardado 3 minutos para não consultar a cada mensagem.
 */
object SiteReader {
    private const val CACHE_MS = 3 * 60_000L
    private const val MAX_CHARS = 14_000

    private data class Cached(val at: Long, val text: String, val photos: List<SitePhoto>)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Fotos de produtos vistas na última leitura, por código. */
    private val photos = ConcurrentHashMap<String, SitePhoto>()

    fun photo(code: String): SitePhoto? = photos[code.trim().lowercase()]

    /** Texto para o prompt com todos os sites cadastrados (vazio se não houver). */
    fun read(sources: List<LinkItem>, force: Boolean = false): String {
        if (sources.isEmpty()) return ""
        val now = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date())
        val parts = sources.map { src ->
            val body = try {
                readOne(src.url, force)
            } catch (e: Exception) {
                "(não consegui ler agora: ${e.message}. Não afirme disponibilidade; diga que vai confirmar.)"
            }
            "### ${src.title} — ${src.url}\n$body"
        }
        return "Consultado em $now.\n\n" + parts.joinToString("\n\n")
    }

    private fun readOne(url: String, force: Boolean): String {
        val hit = cache[url]
        if (!force && hit != null && System.currentTimeMillis() - hit.at < CACHE_MS) {
            return hit.text
        }
        WebPageReader.assertPublicUrl(url)
        val (text, ph) = readGeneric(url)
        cache[url] = Cached(System.currentTimeMillis(), text, ph)
        ph.forEach { photos[it.code] = it }
        return text
    }

    private fun readGeneric(url: String): Pair<String, List<SitePhoto>> {
        val page = WebPageReader.read(url)
        val prefix = "g" + (url.hashCode().toUInt() % 1000u) + "_"
        val photos = page.photos.take(60).mapIndexed { i, p -> SitePhoto(prefix + (i + 1), p.url, p.name) }
        val list = if (photos.isEmpty()) "" else
            "\n\nFotos encontradas no site (use o código em \"photos\"):\n" + photos.joinToString("\n") { "- [${it.code}] ${it.caption}" }
        return (page.text.take(MAX_CHARS) + list) to photos
    }
}
