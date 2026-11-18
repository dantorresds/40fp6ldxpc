package com.atendenteia.app

/**
 * "Skill" de humanização: regras de escrita para a IA (prompt) e uma limpeza
 * feita no próprio app, para não depender só da IA obedecer:
 * respostas fracionadas em mensagens curtas, poucos emojis, sem cara de robô.
 * Kotlin puro (sem Android), testado em HumanizerTest.
 */
object Humanizer {

    /** Máximo de mensagens por resposta; o excesso é juntado na última. */
    const val MAX_PARTS = 4

    /** Tamanho a partir do qual uma resposta sem "parts" é fracionada pelo app. */
    private const val SPLIT_FROM = 220
    private const val TARGET = 200

    fun emojiRule(limit: Int): String = when {
        limit <= 0 -> "Não use emojis."
        limit == 1 -> "No máximo 1 emoji na resposta inteira, e só quando combinar; a maioria das respostas fica sem emoji."
        else -> "No máximo $limit emojis na resposta inteira; muitas respostas ficam sem emoji."
    }

    fun promptSection(c: Config): String = """
## Jeito de escrever (parecer gente, não robô)
- Escreva como alguém da loja digitando no celular. Nada de texto de e-mail ou de central de atendimento.
- Divida a resposta em mensagens curtas, na ordem, no campo "parts": de 1 a 3 mensagens, cada uma com 1 ou 2 frases (até uns 180 caracteres). Uma ideia por mensagem. Pergunta simples = 1 mensagem só.
- Lista de produtos ou preços pode ficar numa mensagem só, um item por linha.
- ${emojiRule(c.emojiLimit)}
- Varie o jeito de falar: não comece duas respostas seguidas do mesmo modo, não repita a saudação depois da primeira mensagem e não use o nome do cliente toda hora (no máximo de vez em quando).
- Evite frases prontas de robô: "Como posso ajudar?", "Fico à disposição", "Estou à disposição", "Espero ter ajudado", "Posso ajudar em algo mais?", "Agradecemos o contato", "Prezado(a)".
- Acompanhe o tom do cliente: se ele escreve curto e informal, responda curto e informal ("tá", "pra", "beleza"); se ele for formal, seja mais formal. Sem gíria forçada e sem erro de português de propósito.
- Responda primeiro o que foi perguntado, direto ao ponto. Não repita o que já foi dito na conversa.
- No máximo uma pergunta por vez, e só se precisar.
- Sem títulos, sem markdown (**, ##) e sem listas enfeitadas. *Negrito* do WhatsApp só em preço ou algo muito importante, raramente.
- Reações curtas e naturais podem abrir a resposta quando couber ("Ah, entendi", "Opa", "Boa!"), sem repetir a mesma.
""".trim()

    /**
     * Aplica a humanização no que a IA devolveu: usa "parts" se vierem, senão
     * fraciona o texto; limita emojis; tira markdown e despedidas de robô.
     */
    fun finish(parts: List<String>, reply: String, emojiLimit: Int): List<String> {
        var list = parts.map { it.trim() }.filter { it.isNotBlank() }
        if (list.isEmpty()) list = split(reply)
        list = list.map(::cleanMarkdown)
        list = limitEmojis(list, emojiLimit)
        list = dropRoboticClosing(list)
        list = list.map { tidy(it) }.filter { it.isNotBlank() }
        if (list.size > MAX_PARTS) list = list.take(MAX_PARTS - 1) + list.drop(MAX_PARTS - 1).joinToString("\n")
        return list
    }

    /** Fraciona um texto longo em mensagens: por parágrafo e, se preciso, por frase. */
    fun split(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= SPLIT_FROM) return listOf(t)
        val out = mutableListOf<String>()
        for (para in t.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            // Listas (vários itens em linhas) ficam inteiras numa mensagem.
            val isList = para.lines().size > 1
            if (para.length <= TARGET + 60 || isList) {
                out += para
                continue
            }
            var cur = StringBuilder()
            for (s in sentences(para)) {
                if (cur.isNotEmpty() && cur.length + 1 + s.length > TARGET) {
                    out += cur.toString(); cur = StringBuilder()
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
            if (cur.isNotEmpty()) out += cur.toString()
        }
        // Junta pedaços muito curtos ao anterior ("Ok!" sozinho fica estranho).
        val merged = mutableListOf<String>()
        for (p in out) {
            if (merged.isNotEmpty() && (p.length < 25 || merged.last().length < 25) && merged.last().length + p.length < TARGET) {
                merged[merged.size - 1] = merged.last() + (if (merged.last().contains('\n') || p.contains('\n')) "\n" else " ") + p
            } else merged += p
        }
        return merged
    }

    /** Frases: corta depois de . ! ? seguidos de espaço (não quebra links nem "R$ 12,50"). */
    private fun sentences(p: String): List<String> =
        p.split(Regex("(?<=[.!?…])\\s+(?=[A-ZÀ-Úa-zà-ú0-9*\"(])")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun cleanMarkdown(s: String): String = s
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "*$1*")
        .replace(Regex("__(.+?)__"), "_$1_")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")

    private val roboticClosing = Regex(
        "(?i)\\s*(?:(?:e\\s+)?(?:fico|estou|estamos|ficamos)\\s+(?:à|a)\\s+(?:sua\\s+|inteira\\s+)?disposi[cç][aã]o[^.!?\\n]*[.!]?" +
            "|espero ter (?:te )?ajudado[^.!?\\n]*[.!]?" +
            "|(?:posso|podemos) (?:te )?ajudar em (?:algo|mais alguma coisa|mais algo)[^?\\n]*\\??" +
            "|(?:há|tem) mais alguma coisa (?:em )?que (?:eu )?possa ajudar[^?\\n]*\\??" +
            "|agradecemos (?:o|pelo) (?:seu )?contato[^.!?\\n]*[.!]?)\\s*$"
    )

    /** Tira a despedida de robô do fim da última mensagem (se sobrar texto). */
    private fun dropRoboticClosing(parts: List<String>): List<String> {
        if (parts.isEmpty()) return parts
        val last = parts.last()
        val cut = last.replace(roboticClosing, "").trim()
        return when {
            cut == last.trim() -> parts
            cut.isNotBlank() && cut.any { it.isLetterOrDigit() } -> parts.dropLast(1) + cut
            parts.size > 1 -> parts.dropLast(1)
            else -> parts
        }
    }

    // ---- Emojis ----

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF || cp in 0x2B00..0x2BFF

    private fun isEmojiTail(cp: Int): Boolean =
        cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    /** Mantém só os primeiros [limit] emojis somando todas as mensagens. */
    /**
     * Tira os links do meio do texto: o texto vai primeiro e cada link depois,
     * como a pessoa que escreve e então procura o link para colar.
     */
    fun splitLinks(parts: List<String>): Pair<List<String>, List<String>> {
        val url = Regex("https?://\\S+")
        val links = mutableListOf<String>()
        val texts = parts.mapNotNull { p ->
            url.findAll(p).forEach { links += it.value.trimEnd('.', ',', ')', '!', ';') }
            val rest = p.replace(url, "").replace(Regex("[ \\t]{2,}"), " ").trim().trimEnd(':').trim()
            rest.takeIf { it.any(Char::isLetterOrDigit) }
        }
        return texts to links.distinct()
    }

    fun limitEmojis(parts: List<String>, limit: Int): List<String> {
        var kept = 0
        return parts.map { s ->
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (!isEmojiBase(cp)) {
                    if (!isEmojiTail(cp) && cp != 0x200D) sb.appendCodePoint(cp)
                    i += Character.charCount(cp)
                    continue
                }
                // Um emoji inteiro: base + modificadores + sequências unidas por ZWJ (ou par de bandeira).
                val start = i
                i += Character.charCount(cp)
                if (cp in 0x1F1E6..0x1F1FF && i < s.length && s.codePointAt(i) in 0x1F1E6..0x1F1FF) i += 2
                while (i < s.length) {
                    val n = s.codePointAt(i)
                    if (isEmojiTail(n)) { i += Character.charCount(n); continue }
                    if (n == 0x200D && i + 1 < s.length) {
                        val next = s.codePointAt(i + 1)
                        if (isEmojiBase(next)) { i += 1 + Character.charCount(next); continue }
                    }
                    break
                }
                if (kept < limit) { sb.append(s, start, i); kept++ }
            }
            sb.toString()
        }
    }

    private fun tidy(s: String): String = s.lines().joinToString("\n") { line ->
        line.replace(Regex("[ \\t]{2,}"), " ").replace(Regex(" +([,.!?;:])"), "$1").trim()
    }.replace(Regex("\\n{3,}"), "\n\n").trim()
}
