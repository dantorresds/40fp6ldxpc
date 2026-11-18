package com.atendenteia.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persona: um "vendedor" com instruções, fotos, links e sites próprios.
 * Até 3. Cada uma escolhe o número de WhatsApp (conexão 1-3) e a chave do
 * Gemini (1-3): podem ser compartilhados ou um para cada.
 */
data class Persona(
    val id: Int,
    val active: Boolean = id == 1,
    val label: String = "Persona $id",
    val storeName: String = "",
    val instructions: String = "",
    val preferences: String = "",
    /** Quando usar esta persona (a IA lê isto para escolher). */
    val whenToUse: String = "",
    /** Palavras-chave que escolhem esta persona na hora. */
    val keywords: String = "",
    val handoffMessage: String = "",
    val photos: List<PhotoItem> = emptyList(),
    val links: List<LinkItem> = emptyList(),
    val siteSources: List<LinkItem> = emptyList(),
    val connection: Int = 1,
    val keySlot: Int = 1,
    /** Descontos, bônus e prazos que a IA pode oferecer (nada além disto). */
    val offers: String = "",
    /** Modo vendedor: técnicas de venda e recuperação. */
    val salesMode: Boolean = true,
    val followUp: Boolean = true,
    val followFirstMinutes: Int = 30,
    val followStartHour: Int = 8,
    val followEndHour: Int = 20,
    val followSunday: Boolean = false,
    /** Papel do robô: atendente, vendedor, suporte, agendamento, posvenda. */
    val role: String = "atendente",
    /** Como fecha a venda: "app" (Pix/cartão pelo app), "pagina" (link da página de vendas) ou "afiliado". */
    val closeMode: String = "app",
    /** Página de vendas própria ou link de afiliado (Hotmart, Kiwify, Eduzz…). */
    val salesPageUrl: String = "",
)

object Personas {
    private const val PREFS = "atendente"

    fun load(ctx: Context): List<Persona> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("personas", null)
        val list = raw?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { a ->
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }
        if (list.isNullOrEmpty()) {
            // Primeira vez: a configuração atual vira a persona 1.
            val c = Store.load(ctx)
            return listOf(fromConfig(Persona(1), c), Persona(2, active = false), Persona(3, active = false))
        }
        return (1..3).map { id -> list.firstOrNull { it.id == id } ?: Persona(id, active = false) }
    }

    fun save(ctx: Context, list: List<Persona>) {
        val a = JSONArray(); list.forEach { a.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("personas", a.toString()).apply()
        // A persona 1 continua espelhada nos campos antigos (compatibilidade).
        list.firstOrNull { it.id == 1 }?.let { p -> Store.save(ctx, apply(Store.load(ctx), p).copy(apiKey = Store.load(ctx).apiKey)) }
    }

    fun active(ctx: Context) = load(ctx).filter { it.active }

    val ROLES = listOf("atendente", "vendedor", "suporte", "agendamento", "posvenda")
    fun roleLabel(r: String) = when (r) {
        "vendedor" -> "Vendedor"; "suporte" -> "Suporte técnico"; "agendamento" -> "Agendamento"
        "posvenda" -> "Pós-venda"; else -> "Atendente"
    }
    val CLOSE_MODES = listOf("app", "pagina", "afiliado")
    fun closeLabel(m: String) = when (m) {
        "pagina" -> "Manda o link da página de vendas"; "afiliado" -> "Manda o link de afiliado"
        else -> "Fecha no app (Pix/cartão)"
    }

    /** Trecho do prompt com o papel e o jeito de fechar a venda desta persona. */
    fun rolePrompt(p: Persona): String = buildString {
        append("Seu papel nesta conversa: ${roleLabel(p.role)}.")
        when (p.role) {
            "suporte" -> append(" Ajude a resolver o problema com calma, passo a passo; se não souber resolver, chame um humano.")
            "agendamento" -> append(" Seu foco é marcar, remarcar ou confirmar horários; confirme data, hora e nome antes de fechar.")
            "posvenda" -> append(" O cliente já comprou: tire dúvidas de uso e entrega, peça opinião e cuide da satisfação.")
            "vendedor" -> append(" Entenda a necessidade, apresente o produto pelos benefícios e conduza à compra sem pressionar.")
        }
        if (p.role == "vendedor" || p.salesPageUrl.isNotBlank()) {
            when (p.closeMode) {
                "pagina" -> append(" Para comprar, envie o link da página de vendas: ${p.salesPageUrl.ifBlank { "(link não cadastrado: peça para o cliente aguardar um humano)" }}. Não colete pagamento pelo chat.")
                "afiliado" -> append(" Para comprar, envie o link de compra (afiliado): ${p.salesPageUrl.ifBlank { "(link não cadastrado: peça para o cliente aguardar um humano)" }}. O pagamento e a entrega são feitos pela plataforma do link; não colete pagamento pelo chat.")
                else -> append(" A venda é fechada aqui no chat, com o pagamento pelo app (Pix/cartão).")
            }
        }
    }

    fun keyFor(c: Config, slot: Int) = when (slot) { 2 -> c.apiKey2; 3 -> c.apiKey3; else -> c.apiKey }.ifBlank { c.apiKey }

    /** Config usada para responder com esta persona. */
    fun effective(base: Config, p: Persona): Config = apply(base, p).copy(apiKey = keyFor(base, p.keySlot))

    /** Config para a IA responder: a da persona + papel e jeito de fechar a venda. */
    fun forReply(base: Config, p: Persona): Config = effective(base, p).let { c ->
        c.copy(instructions = (c.instructions.trim() + "\n\n" + rolePrompt(p)).trim())
    }

    private fun apply(base: Config, p: Persona) = base.copy(
        storeName = p.storeName, instructions = p.instructions, preferences = p.preferences,
        handoffMessage = p.handoffMessage.ifBlank { base.handoffMessage },
        photos = p.photos, links = p.links, siteSources = p.siteSources,
    )

    /** Copia os campos de persona de uma Config editada (telas Loja e Fotos). */
    fun fromConfig(p: Persona, c: Config) = p.copy(
        storeName = c.storeName, instructions = c.instructions, preferences = c.preferences,
        handoffMessage = c.handoffMessage, photos = c.photos, links = c.links, siteSources = c.siteSources,
    )

    /** Separa uma Config editada: o que é da persona vai para ela, o resto para a configuração geral. */
    fun split(base: Config, edited: Config, p: Persona): Pair<Config, Persona> {
        val persona = fromConfig(p, edited)
        val global = edited.copy(
            storeName = base.storeName, instructions = base.instructions, preferences = base.preferences,
            handoffMessage = base.handoffMessage, photos = base.photos, links = base.links, siteSources = base.siteSources,
            apiKey = base.apiKey, apiKey2 = base.apiKey2, apiKey3 = base.apiKey3,
        )
        return global to persona
    }

    /** Todas as fotos de todas as personas (para não apagar arquivo em uso). */
    fun allPhotoFiles(ctx: Context) = load(ctx).flatMap { it.photos }.map { it.file }.toSet()

    private fun links(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getJSONObject(i) }
        .map { o -> LinkItem(o.optString("title"), o.optString("url")) } } ?: emptyList()

    private fun linksJson(l: List<LinkItem>) = JSONArray().apply { l.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) } }

    private fun toJson(p: Persona) = JSONObject().apply {
        put("id", p.id); put("active", p.active); put("label", p.label); put("storeName", p.storeName)
        put("instructions", p.instructions); put("preferences", p.preferences); put("whenToUse", p.whenToUse)
        put("keywords", p.keywords); put("handoffMessage", p.handoffMessage)
        put("photos", JSONArray().apply { p.photos.forEach { put(JSONObject().put("id", it.id).put("description", it.description).put("file", it.file).put("url", it.url)) } })
        put("links", linksJson(p.links)); put("siteSources", linksJson(p.siteSources))
        put("connection", p.connection); put("keySlot", p.keySlot); put("offers", p.offers); put("salesMode", p.salesMode)
        put("followUp", p.followUp); put("followFirstMinutes", p.followFirstMinutes); put("followStartHour", p.followStartHour)
        put("followEndHour", p.followEndHour); put("followSunday", p.followSunday)
        put("role", p.role); put("closeMode", p.closeMode); put("salesPageUrl", p.salesPageUrl)
    }

    private fun fromJson(o: JSONObject): Persona {
        val id = o.optInt("id", 1).coerceIn(1, 3)
        val d = Persona(id)
        return Persona(
            id = id, active = o.optBoolean("active", d.active), label = o.optString("label", d.label).ifBlank { d.label },
            storeName = o.optString("storeName"), instructions = o.optString("instructions"), preferences = o.optString("preferences"),
            whenToUse = o.optString("whenToUse"), keywords = o.optString("keywords"), handoffMessage = o.optString("handoffMessage"),
            photos = o.optJSONArray("photos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
                .map { PhotoItem(it.optString("id"), it.optString("description"), it.optString("file"), it.optString("url")) } } ?: emptyList(),
            links = links(o.optJSONArray("links")), siteSources = links(o.optJSONArray("siteSources")),
            connection = o.optInt("connection", 1).coerceIn(1, 3), keySlot = o.optInt("keySlot", 1).coerceIn(1, 3),
            offers = o.optString("offers"), salesMode = o.optBoolean("salesMode", true), followUp = o.optBoolean("followUp", true),
            followFirstMinutes = o.optInt("followFirstMinutes", 30).coerceIn(5, 1440),
            followStartHour = o.optInt("followStartHour", 8).coerceIn(0, 23), followEndHour = o.optInt("followEndHour", 20).coerceIn(1, 24),
            followSunday = o.optBoolean("followSunday", false),
            role = o.optString("role", "atendente").takeIf { it in ROLES } ?: "atendente",
            closeMode = o.optString("closeMode", "app").takeIf { it in CLOSE_MODES } ?: "app",
            salesPageUrl = o.optString("salesPageUrl"),
        )
    }

    /**
     * Escolhe a persona da conversa. A persona fica PRESA à conversa: só troca
     * se o orquestrador (prompt mestre) mandar trocar de forma direta.
     * Conversa nova: código/palavra-chave → orquestrador (ou IA pelo "quando
     * usar") → a primeira do número. Devolve (persona, motivo, trocou) ou null
     * se nenhuma persona atende este número.
     */
    suspend fun choose(ctx: Context, base: Config, conn: Int, conv: Conversation, text: String): Pick? {
        val cands = active(ctx).filter { it.connection == conn }
        if (cands.isEmpty()) return null
        if (cands.size == 1) return Pick(cands[0].id, "única do número", false)
        val orch = Orchestrator.load(ctx)
        val current = cands.firstOrNull { it.id == conv.persona }
        if (current != null) {
            // Já tem persona: só o orquestrador pode trocar, e só se o prompt mestre mandar.
            if (orch.enabled && orch.masterPrompt.isNotBlank() && text.isNotBlank()) {
                Orchestrator.decide(ctx, base, orch, cands, conv, current.id, text)
                    ?.takeIf { it.switched }?.let { return it.copy(reason = "orquestrador: ${it.reason}") }
            }
            return Pick(current.id, "presa à conversa", false)
        }
        cands.firstOrNull { it.keywords.isNotBlank() && Trigger.matches(text, it.keywords) }?.let { return Pick(it.id, "código/palavra-chave", false) }
        if (orch.enabled && text.isNotBlank()) {
            Orchestrator.decide(ctx, base, orch, cands, conv, 0, text)?.let { return it.copy(reason = "orquestrador: ${it.reason}") }
        } else if (text.isNotBlank() && cands.any { it.whenToUse.isNotBlank() }) {
            val menu = cands.joinToString("\n") { "${it.id}: ${it.label} — ${it.whenToUse.ifBlank { it.storeName }}" }
            val key = keyFor(base, cands[0].keySlot)
            val picked = runCatching {
                AiQueue.run(AiQueue.REPLY, key, base.rpmLimit) {
                    val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject()
                        .put("text", "Mensagem do cliente: \"${text.take(600)}\"\n\nOpções:\n$menu\n\nResponda só o número da opção que deve atender, ou 0 se não der para saber."))))
                    Gemini.generate(key, base.model, "Você direciona clientes para o atendente certo. Responda só com um número.", contents, maxModels = 2)
                }
            }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull()
            cands.firstOrNull { it.id == picked }?.let { return Pick(it.id, "escolhida pela IA", false) }
        }
        return Pick(cands[0].id, "padrão do número", false)
    }
}
