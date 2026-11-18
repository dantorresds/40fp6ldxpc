package com.atendenteia.app

import java.text.Normalizer

/**
 * Documento de configuração (formato "ATENDENTE-IA-CONFIG v1"): o "prompt" da persona
 * é gerado a partir dos campos e lido de volta sem IA. Fica de fora só o que é do
 * aparelho: número/conexão do WhatsApp, números permitidos/bloqueados e chave do Gemini.
 * Sem dependência de Android, para testar no PC.
 */
object ConfigDoc {
    const val HEADER = "ATENDENTE-IA-CONFIG v1"

    data class PersonaParse(val persona: Persona, val warnings: List<String>)

    // ---------------- Títulos ----------------

    private enum class Sec(val title: String) {
        NAME("NOME DA PERSONA"), STORE("LOJA / MARCA / PRODUTO"), ROLE("PAPEL"), CLOSE("MODO DE FECHAMENTO"),
        SALES_URL("LINK DE VENDA"), WHEN("QUANDO USAR"), KEYWORDS("CÓDIGOS / PALAVRAS-CHAVE"), INSTRUCTIONS("INSTRUÇÕES"),
        PREFS("TOM DE VOZ E PREFERÊNCIAS"), OFFERS("OFERTAS PERMITIDAS"), HANDOFF("MENSAGEM AO CHAMAR HUMANO"),
        LINKS("LINKS"), SITES("SITES PARA LER"), PHOTOS("FOTOS"), FOLLOW("RETOMADA (FOLLOW-UP)"),
    }

    private fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()

    private val aliases: Map<String, Sec> = buildMap {
        Sec.values().forEach { put(norm(it.title), it) }
        listOf("NOME", "NOME PERSONA").forEach { put(it, Sec.NAME) }
        listOf("LOJA", "MARCA", "PRODUTO", "LOJA MARCA", "LOJA PRODUTO", "MARCA PRODUTO").forEach { put(it, Sec.STORE) }
        listOf("FECHAMENTO").forEach { put(it, Sec.CLOSE) }
        listOf("LINK DE VENDAS", "PAGINA DE VENDAS", "LINK DA PAGINA DE VENDAS").forEach { put(it, Sec.SALES_URL) }
        listOf("CODIGOS", "CODIGO", "PALAVRAS CHAVE", "PALAVRAS CHAVES", "PALAVRA CHAVE").forEach { put(it, Sec.KEYWORDS) }
        listOf("INSTRUCAO").forEach { put(it, Sec.INSTRUCTIONS) }
        listOf("TOM DE VOZ", "PREFERENCIAS", "TOM").forEach { put(it, Sec.PREFS) }
        listOf("OFERTAS").forEach { put(it, Sec.OFFERS) }
        listOf("MENSAGEM HUMANO", "MENSAGEM AO HUMANO", "CHAMAR HUMANO").forEach { put(it, Sec.HANDOFF) }
        listOf("LINK").forEach { put(it, Sec.LINKS) }
        listOf("SITES", "SITE", "SITES PARA O ROBO LER").forEach { put(it, Sec.SITES) }
        listOf("FOTO").forEach { put(it, Sec.PHOTOS) }
        listOf("RETOMADA", "FOLLOW UP", "FOLLOWUP").forEach { put(it, Sec.FOLLOW) }
    }

    /** Títulos exatos (sem apelidos): aceitos sem "#" quando o texto vem de um PDF. */
    private val canonical: Map<String, Sec> = Sec.values().associateBy { norm(it.title) }

    // ---------------- Geração ----------------

    private fun body(s: String): String {
        val t = s.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (t.isBlank()) return "-"
        // Linha "## algo" dentro do texto viraria uma seção nova na leitura.
        return t.lines().joinToString("\n") { if (Regex("^\\s*##(?!#)\\s").containsMatchIn(it)) it.replaceFirst("##", "###") else it }
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun linkLine(l: LinkItem): String {
        val t = oneLine(l.title)
        return if (t.isBlank() || t == l.url.trim()) l.url.trim() else "$t - ${l.url.trim()}"
    }

    private fun photoLine(p: PhotoItem) = buildString {
        append(oneLine(p.id)); append(" - "); append(oneLine(p.description).ifBlank { "foto" })
        if (p.url.isNotBlank()) { append(" - "); append(p.url.trim()) }
    }

    fun followLine(p: Persona) =
        if (!p.followUp) "não"
        else "sim; ${p.followFirstMinutes} minutos; das ${p.followStartHour} às ${p.followEndHour}; domingo ${if (p.followSunday) "sim" else "não"}"

    fun renderPersona(p: Persona): String {
        val parts = listOf(
            Sec.NAME to p.label, Sec.STORE to p.storeName, Sec.ROLE to p.role, Sec.CLOSE to p.closeMode,
            Sec.SALES_URL to p.salesPageUrl, Sec.WHEN to p.whenToUse, Sec.KEYWORDS to p.keywords,
            Sec.INSTRUCTIONS to p.instructions, Sec.PREFS to p.preferences, Sec.OFFERS to p.offers,
            Sec.HANDOFF to p.handoffMessage,
            Sec.LINKS to p.links.joinToString("\n") { linkLine(it) },
            Sec.SITES to p.siteSources.joinToString("\n") { linkLine(it) },
            Sec.PHOTOS to p.photos.joinToString("\n") { photoLine(it) },
            Sec.FOLLOW to followLine(p),
        )
        return "$HEADER · PERSONA ${p.id}\n\n" + parts.joinToString("\n\n") { (s, v) -> "## ${s.title}\n${body(v)}" } + "\n"
    }

    /** Nome de arquivo sugerido para exportar. */
    fun fileName(p: Persona, ext: String) = "Persona ${p.id} - ${p.label.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()}.$ext"

    // ---------------- Leitura ----------------

    private val headerRegex = Regex("^\\s*ATENDENTE-IA-CONFIG\\b(.*)$", RegexOption.IGNORE_CASE)
    private val hashHeading = Regex("^\\s*(#{1,6})\\s+(.*?)\\s*#*\\s*$")

    private class Block(val sec: Sec?, val title: String, val lines: MutableList<String> = mutableListOf())


    /** Quebra o texto em blocos. Títulos com "#"; sem nenhum (PDF), vale a linha que for exatamente um título em maiúsculas, na ordem certa. */
    private fun split(text: String, known: (String) -> Sec?, bareTitles: (String) -> Boolean): Pair<List<String>, List<Block>> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val hasHash = lines.any { l -> hashHeading.matchEntire(l)?.let { known(it.groupValues[2]) != null } == true }
        val pre = mutableListOf<String>()
        val blocks = mutableListOf<Block>()
        var lastIdx = -1
        for (line in lines) {
            val m = hashHeading.matchEntire(line)
            if (m != null && m.groupValues[2].isNotBlank()) {
                val level = m.groupValues[1].length
                val title = m.groupValues[2]
                val sec = known(title)
                if (sec != null && level <= 2) { blocks += Block(sec, title); continue }
                if (sec == null && level == 2) { blocks += Block(null, title); continue }
            }
            val t = line.trim()
            if (!hasHash && t.isNotEmpty() && t == t.uppercase() && bareTitles(t)) {
                val sec = known(t)
                val idx = sec?.ordinal ?: Int.MAX_VALUE
                if (sec != null && idx > lastIdx) { lastIdx = idx; blocks += Block(sec, t); continue }
            }
            if (blocks.isEmpty()) pre += line else blocks.last().lines += line
        }
        return pre to blocks
    }

    /** Lê o texto do prompt e devolve a persona (sem salvar) + avisos. Falha se não achar nenhuma seção conhecida. */
    fun parsePersona(text: String, base: Persona): Result<PersonaParse> {
        val (pre, blocks) = split(text, { canonicalOrAlias(it) }, { norm(it) in canonical })
        if (blocks.none { it.sec != null }) return Result.failure(IllegalArgumentException("Não encontrei nenhuma seção conhecida (## NOME DA PERSONA, ## PAPEL, ## INSTRUÇÕES…)."))
        val warn = mutableListOf<String>()

        // Cabeçalho: "ATENDENTE-IA-CONFIG v1 · PERSONA N"
        val head = pre.firstNotNullOfOrNull { headerRegex.matchEntire(it) }
        if (head != null) {
            val rest = head.groupValues[1]
            if (Regex("ORQUESTRADOR", RegexOption.IGNORE_CASE).containsMatchIn(rest))
                warn += "O documento é do Orquestrador, não de uma persona."
            Regex("PERSONA\\s*(\\d)", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
                if (n != base.id) warn += "O documento é da Persona $n, mas você está aplicando na Persona ${base.id}."
            }
        }
        if (pre.count { it.isNotBlank() && headerRegex.matchEntire(it) == null } > 1)
            warn += "Havia texto antes da primeira seção; foi ignorado."

        val map = LinkedHashMap<Sec, String>()
        val extras = mutableListOf<String>()
        for (b in blocks) {
            val content = b.lines.joinToString("\n").trim()
            if (b.sec == null) {
                if (content.isNotBlank() && content != "-") extras += "${b.title.trim()}\n$content"
                else extras += b.title.trim()
                warn += "Seção desconhecida \"${b.title.trim()}\": foi para o fim das instruções."
            } else map[b.sec] = (map[b.sec]?.let { it + "\n" } ?: "") + content
        }
        fun get(s: Sec) = map[s]?.let { if (it.trim() == "-") "" else it.trim() }

        var p = base
        get(Sec.NAME)?.let { if (it.isNotBlank()) p = p.copy(label = it.lines().first().trim()) }
        get(Sec.STORE)?.let { p = p.copy(storeName = it) }
        get(Sec.ROLE)?.let { v ->
            val r = norm(v).lowercase()
            val found = Personas.ROLES.firstOrNull { it == r } ?: Personas.ROLES.firstOrNull { norm(Personas.roleLabel(it)).lowercase() == r }
            if (found != null) p = p.copy(role = found) else if (v.isNotBlank()) warn += "Papel \"$v\" não existe; mantive ${Personas.roleLabel(base.role)}."
        }
        get(Sec.CLOSE)?.let { v ->
            val m = norm(v).lowercase()
            val found = Personas.CLOSE_MODES.firstOrNull { it == m }
            if (found != null) p = p.copy(closeMode = found) else if (v.isNotBlank()) warn += "Modo de fechamento \"$v\" não existe; mantive ${base.closeMode}."
        }
        get(Sec.SALES_URL)?.let { v ->
            if (v.isBlank() || v.startsWith("http")) p = p.copy(salesPageUrl = v.trim())
            else warn += "Link de venda \"${v.take(60)}\" não começa com http; mantive o anterior."
        }
        get(Sec.WHEN)?.let { p = p.copy(whenToUse = it) }
        get(Sec.KEYWORDS)?.let { p = p.copy(keywords = it) }
        get(Sec.INSTRUCTIONS)?.let { p = p.copy(instructions = it) }
        get(Sec.PREFS)?.let { p = p.copy(preferences = it) }
        get(Sec.OFFERS)?.let { p = p.copy(offers = it) }
        get(Sec.HANDOFF)?.let { p = p.copy(handoffMessage = it) }
        get(Sec.LINKS)?.let { p = p.copy(links = parseLinks(it, warn, "Links")) }
        get(Sec.SITES)?.let { p = p.copy(siteSources = parseLinks(it, warn, "Sites para ler")) }
        get(Sec.PHOTOS)?.let { p = p.copy(photos = parsePhotos(it, base.photos, warn)) }
        get(Sec.FOLLOW)?.let { parseFollow(it, p, warn)?.let { np -> p = np } }
        if (extras.isNotEmpty()) p = p.copy(instructions = (p.instructions + "\n\n" + extras.joinToString("\n\n")).trim())
        p = p.copy(salesMode = p.role == "vendedor" || p.salesMode)
        return Result.success(PersonaParse(p, warn))
    }

    private fun canonicalOrAlias(title: String): Sec? = aliases[norm(title)]

    private val mdLink = Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)")
    private val titleUrl = Regex("^(.*?)\\s*(?:[-–—|]\\s+|:\\s*)?(https?://\\S+)\\s*$", RegexOption.IGNORE_CASE)
    private val bullet = Regex("^\\s*(?:[-*•]\\s+)")

    /** Linhas "título - url", "título: url", "[título](url)" ou só a url. */
    fun parseLinks(text: String, warn: MutableList<String>? = null, what: String = "Links"): List<LinkItem> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() && it != "-" }.mapNotNull { raw ->
            val line = raw.replace(bullet, "").trim()
            mdLink.matchEntire(line)?.let { return@mapNotNull LinkItem(it.groupValues[1].trim(), it.groupValues[2]) }
            val m = titleUrl.matchEntire(line)
            if (m == null) { warn?.add("$what: ignorei a linha sem endereço http: \"${raw.take(60)}\"."); null }
            else {
                val url = m.groupValues[2].trimEnd(',', ';', ')', '.')
                LinkItem(m.groupValues[1].trim().trimEnd('-', ':', '–', '—', '|').trim().ifBlank { url }, url)
            }
        }

    /** Linhas "id - descrição - url" ou "id - descrição" (foto só do aparelho). */
    private fun parsePhotos(text: String, old: List<PhotoItem>, warn: MutableList<String>): List<PhotoItem> =
        text.lines().map { it.trim() }.filter { it.isNotBlank() && it != "-" }.mapNotNull { raw ->
            val parts = raw.replace(bullet, "").split(" - ").map { it.trim() }
            val id = parts[0].lowercase().replace(Regex("[^a-z0-9_-]"), "")
            if (id.isBlank()) { warn += "Fotos: linha sem id ignorada: \"${raw.take(60)}\"."; return@mapNotNull null }
            val last = parts.last()
            val url = if (parts.size >= 2 && Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(last)) last else ""
            val descParts = if (url.isNotEmpty()) parts.subList(1, parts.size - 1) else parts.drop(1)
            val desc = descParts.joinToString(" - ").trim()
            val prev = old.firstOrNull { it.id == id }
            // Mantém o arquivo local se for a mesma foto; link novo (diferente do antigo) manda baixar de novo.
            val keep = prev != null && (url.isEmpty() || prev.url.isBlank() || prev.url == url)
            PhotoItem(id, desc.ifBlank { prev?.description.orEmpty() }, if (keep) prev!!.file else "", url)
        }

    private fun parseFollow(text: String, p: Persona, warn: MutableList<String>): Persona? {
        var t = text.trim()
        if (t.isBlank()) return null
        val low = norm(t).lowercase()
        val first = low.split(' ').firstOrNull().orEmpty()
        if (first in setOf("nao", "off", "desligado", "desligada", "false", "desativado")) return p.copy(followUp = false)
        if (first !in setOf("sim", "ligado", "ligada", "on", "true", "ativo", "ativado")) {
            warn += "Retomada: não entendi \"${t.take(40)}\"; mantive como estava."; return null
        }
        var out = p.copy(followUp = true)
        val lowT = norm(t).lowercase()
        val rangeRe = Regex("(?:das?\\s+)?(\\d{1,2})\\s*(?:h|:00)?\\s*(?:as|a|-|ate)\\s*(\\d{1,2})\\s*(?:h|:00)?")
        t = lowT
        rangeRe.find(lowT)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            if (a in 0..23 && b in 1..24 && a < b) out = out.copy(followStartHour = a, followEndHour = b)
            else warn += "Retomada: horário \"${m.value}\" fora do permitido; mantive o anterior."
            t = lowT.removeRange(m.range)
        }
        Regex("(\\d+)\\s*(min\\w*|h\\b|hora\\w*)").find(t)?.let { m ->
            val n = m.groupValues[1].toInt() * (if (m.groupValues[2].startsWith("min")) 1 else 60)
            if (n in 5..1440) out = out.copy(followFirstMinutes = n) else warn += "Retomada: $n minutos fora de 5 a 1440; mantive o anterior."
        }
        if (Regex("domingo").containsMatchIn(t)) {
            val no = Regex("domingo\\s*[:=]?\\s*nao|sem\\s+domingo|exceto\\s+domingo|nao\\s+(?:no\\s+)?domingo").containsMatchIn(t)
            out = out.copy(followSunday = !no)
        } else if (Regex("todos os dias").containsMatchIn(t)) out = out.copy(followSunday = true)
        return out
    }

    // ---------------- Orquestrador ----------------

    private val orchTitles = listOf("PERSONAS", "COMO ESCOLHER NO INICIO DA CONVERSA", "PERSONA PADRAO", "TROCA NO MEIO DA CONVERSA")
    private val orchTitleDisplay = mapOf(
        "PERSONAS" to "PERSONAS", "COMO ESCOLHER NO INICIO DA CONVERSA" to "COMO ESCOLHER NO INÍCIO DA CONVERSA",
        "PERSONA PADRAO" to "PERSONA PADRÃO", "TROCA NO MEIO DA CONVERSA" to "TROCA NO MEIO DA CONVERSA",
    )

    /** Seção PERSONAS: uma linha por persona ativa, sempre montada a partir das personas. */
    fun orchestratorPersonasSection(personas: List<Persona>): String =
        personas.filter { it.active }.joinToString("\n") { p ->
            buildString {
                append("Persona ${p.id} - ${oneLine(p.label)} - ${p.role}")
                if (p.whenToUse.isNotBlank()) append(" - ${oneLine(p.whenToUse)}")
                if (p.keywords.isNotBlank()) append(" - códigos: ${oneLine(p.keywords)}")
            }
        }

    private fun isHeading(line: String): Boolean {
        val m = hashHeading.matchEntire(line)
        if (m != null && m.groupValues[1].length <= 2 && m.groupValues[2].isNotBlank()) return true
        val t = line.trim()
        return t.isNotEmpty() && t == t.uppercase() && norm(t) in orchTitles
    }

    /** Tira o cabeçalho e a seção PERSONAS (antiga ou não); deixa só as regras, com títulos "## ". */
    fun orchestratorRules(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (headerRegex.matchEntire(line) != null) { i++; continue }
            val isPersonas = isHeading(line) && norm(hashHeading.matchEntire(line)?.groupValues?.get(2) ?: line) == "PERSONAS"
            if (isPersonas) {
                i++
                var seen = false
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank()) { if (seen) break else { i++; continue } }
                    if (isHeading(l)) break
                    seen = true; i++
                }
                continue
            }
            val t = line.trim()
            val bare = t.isNotEmpty() && t == t.uppercase() && hashHeading.matchEntire(line) == null && norm(t) in orchTitles
            out += if (bare) "## " + (orchTitleDisplay[norm(t)] ?: t) else line
            i++
        }
        return out.joinToString("\n").trim()
    }

    /** Lê o texto do documento do orquestrador. Devolve só as regras (sem cabeçalho e sem PERSONAS). */
    fun parseOrchestrator(text: String): Result<String> {
        val lines = text.lines()
        val hasHeader = lines.any { headerRegex.matchEntire(it)?.groupValues?.get(1)?.contains("ORQUESTRADOR", ignoreCase = true) == true }
        val hasTitle = lines.any { l -> val t = l.trim().trimStart('#').trim(); t.isNotEmpty() && norm(t) in orchTitles && (l.trim().startsWith("#") || t == t.uppercase()) }
        if (!hasHeader && !hasTitle) return Result.failure(IllegalArgumentException("Não encontrei seções do orquestrador (## COMO ESCOLHER NO INÍCIO DA CONVERSA…)."))
        val rules = orchestratorRules(text)
        if (rules.isBlank()) return Result.failure(IllegalArgumentException("O documento não tem regras além da lista de personas."))
        return Result.success(rules)
    }

    /** Documento completo do orquestrador (para ver, exportar e compartilhar). */
    fun renderOrchestrator(cfg: OrchestratorConfig, personas: List<Persona>): String {
        val sb = StringBuilder("$HEADER · ORQUESTRADOR\n\n## PERSONAS\n")
        sb.append(orchestratorPersonasSection(personas).ifBlank { "-" })
        val rules = orchestratorRules(cfg.masterPrompt)
        if (rules.isNotBlank()) sb.append("\n\n").append(rules)
        return sb.append("\n").toString()
    }

    /** Texto que o orquestrador lê para decidir: PERSONAS gerada + regras (as do dono, ou as padrão se vazio). */
    fun orchestratorPrompt(cfg: OrchestratorConfig, cands: List<Persona>): String {
        val rules = orchestratorRules(cfg.masterPrompt).ifBlank { Orchestrator.defaultRules(cands) }
        return "## PERSONAS\n" + orchestratorPersonasSection(cands).ifBlank { "-" } + "\n\n" + rules
    }
}
