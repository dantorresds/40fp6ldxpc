package com.atendenteia.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orquestrador (à parte das 3 personas): quando 2 ou 3 personas dividem o mesmo número,
 * ele decide qual delas atende cada conversa, seguindo o prompt mestre do dono.
 *
 * Regra do dono: a persona fica presa à conversa. Só troca no meio se o prompt
 * mestre mandar trocar de forma direta naquela situação (ex.: "depois que o
 * cliente pagar, passe para a Persona 3 (pós-venda)").
 */
data class OrchestratorConfig(
    val enabled: Boolean = false,
    val masterPrompt: String = "",
    val keySlot: Int = 1,
)

data class Pick(val personaId: Int, val reason: String, val switched: Boolean)

object Orchestrator {
    private const val PREFS = "atendente"

    fun load(ctx: Context): OrchestratorConfig {
        val o = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("orchestrator", null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return OrchestratorConfig()
        return OrchestratorConfig(
            enabled = o.optBoolean("enabled", false),
            // A seção PERSONAS agora é montada sozinha: tira a que vinha dentro de prompts antigos.
            masterPrompt = ConfigDoc.orchestratorRules(o.optString("masterPrompt")),
            keySlot = o.optInt("keySlot", 1).coerceIn(1, 3),
        )
    }

    fun save(ctx: Context, c: OrchestratorConfig) {
        val o = JSONObject().put("enabled", c.enabled).put("masterPrompt", ConfigDoc.orchestratorRules(c.masterPrompt)).put("keySlot", c.keySlot)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("orchestrator", o.toString()).apply()
    }

    /** Ficha curta de cada persona para o orquestrador ler. */
    fun menu(cands: List<Persona>) = cands.joinToString("\n") { p ->
        buildString {
            append("Persona ${p.id}: ${p.label}")
            append(" | papel: ${Personas.roleLabel(p.role)}")
            if (p.whenToUse.isNotBlank()) append(" | quando usar: ${p.whenToUse.trim()}")
            if (p.keywords.isNotBlank()) append(" | códigos/palavras-chave: ${p.keywords.trim()}")
            if (p.storeName.isNotBlank()) append(" | loja/produto: ${p.storeName.trim()}")
        }
    }

    /** Regras padrão do prompt mestre (sem a seção PERSONAS, que é montada a partir das personas). */
    fun defaultRules(personas: List<Persona>): String = buildString {
        appendLine("Você é o orquestrador do atendimento. Não fala com o cliente: só decide qual persona atende.")
        appendLine()
        appendLine("## COMO ESCOLHER NO INÍCIO DA CONVERSA")
        appendLine("1. Se a mensagem tiver o código/palavra-chave de uma persona, é ela.")
        appendLine("2. Senão, escolha pelo assunto da mensagem.")
        appendLine("3. Na dúvida, Persona ${personas.firstOrNull()?.id ?: 1}.")
        appendLine()
        appendLine("## TROCA NO MEIO DA CONVERSA")
        appendLine("Não troque de persona no meio da conversa, a não ser nas situações abaixo:")
        appendLine("- (exemplo) Depois que o cliente pagar, passe para a persona de pós-venda.")
        append("- (exemplo) Se o cliente relatar defeito ou problema técnico, passe para a persona de suporte.")
    }

    /** Rascunho de prompt mestre (as regras); a lista de personas entra sozinha. */
    fun draftMasterPrompt(personas: List<Persona>): String = defaultRules(personas)

    private val schema = JSONObject()
        .put("type", "OBJECT")
        .put("properties", JSONObject()
            .put("persona", JSONObject().put("type", "INTEGER"))
            .put("motivo", JSONObject().put("type", "STRING")))
        .put("required", JSONArray().put("persona").put("motivo"))

    /**
     * Decide a persona. [current] é a persona já presa à conversa (0 se nova).
     * Devolve null se a IA não respondeu (quem chamou usa a regra simples).
     */
    suspend fun decide(
        ctx: Context, base: Config, cfg: OrchestratorConfig, cands: List<Persona>,
        conv: Conversation, current: Int, text: String,
    ): Pick? {
        val key = Personas.keyFor(base, cfg.keySlot)
        if (key.isBlank()) return null
        val history = Memory.messages(ctx, conv.key, limit = 12).joinToString("\n") { m ->
            val who = when (m.role) { "user" -> "Cliente"; "owner" -> "Dono"; else -> "Atendente" }
            "$who: ${m.text.take(300)}"
        }
        val currentLine = if (current > 0)
            "A conversa JÁ está com a Persona $current. Mantenha a Persona $current, a não ser que o PROMPT MESTRE mande trocar " +
                "de forma direta nesta situação. Na dúvida, mantenha."
        else "Conversa nova: escolha a persona que deve atender."
        val prompt = buildString {
            appendLine("PROMPT MESTRE DO DONO:")
            appendLine(ConfigDoc.orchestratorPrompt(cfg, cands).take(12000))
            appendLine()
            appendLine("PERSONAS DISPONÍVEIS NESTE NÚMERO:")
            appendLine(menu(cands))
            appendLine()
            if (conv.summary.isNotBlank()) appendLine("Resumo da conversa: ${conv.summary.take(800)}")
            if (history.isNotBlank()) { appendLine("Últimas mensagens:"); appendLine(history) }
            appendLine()
            appendLine("Nova mensagem do cliente: \"${text.take(800)}\"")
            appendLine()
            appendLine(currentLine)
            append("Responda em JSON: persona (número) e motivo (curto, em português).")
        }
        val raw = runCatching {
            AiQueue.run(AiQueue.REPLY, key, base.rpmLimit) {
                val contents = JSONArray().put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                Gemini.generate(key, base.model, "Você é o orquestrador de um atendimento por WhatsApp. " +
                    "Você não conversa com o cliente; só escolhe qual persona atende, seguindo o prompt mestre.",
                    contents, schema = schema, maxModels = 2)
            }
        }.getOrNull() ?: return null
        val o = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull() ?: return null
        val id = o.optInt("persona", 0)
        val reason = o.optString("motivo").take(160)
        val picked = cands.firstOrNull { it.id == id } ?: return null
        return Pick(picked.id, reason, switched = current > 0 && picked.id != current)
    }
}
