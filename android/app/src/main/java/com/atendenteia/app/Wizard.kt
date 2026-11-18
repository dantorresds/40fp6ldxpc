package com.atendenteia.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Uma fala da entrevista. [note] = conteúdo lido de link/arquivo (vai para a IA, não aparece como balão do dono). */
data class WizardTurn(val role: String, val text: String, val note: String = "")

/** Resultado de cada rodada do assistente. */
data class WizardStep(val message: String, val progress: Int, val done: Boolean, val config: JSONObject?, val summary: String,
                      val options: List<String> = emptyList(), val heard: String = "")

/**
 * Assistente de configuração: a própria IA da loja entrevista o dono, uma
 * pergunta por vez, lê os links e arquivos que ele mandar e, no fim, escreve
 * todos os campos do app no padrão de texto do Atendente IA.
 */
object Wizard {
    private const val PREFS = "atendente_assistente"
    private const val MAX_NOTE = 9_000

    // ---------------- Conversa salva (retoma se fechar a janela) ----------------

    fun load(ctx: Context): List<WizardTurn> {
        val a = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("turns", "[]") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { WizardTurn(it.optString("r"), it.optString("t"), it.optString("n")) }
    }

    fun save(ctx: Context, turns: List<WizardTurn>) {
        val a = JSONArray()
        turns.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text).put("n", it.note)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("turns", a.toString()).apply()
    }

    fun clear(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()

    // ---------------- Links e arquivos ----------------

    private val URL = Regex("https?://[^\\s<>\"]+")

    /** Lê os links que o dono colou (site, catálogo, página de vendas) para a IA ver o conteúdo. */
    fun readLinks(text: String): String {
        val urls = URL.findAll(text).map { it.value.trimEnd('.', ',', ')', '!') }.distinct().take(3).toList()
        if (urls.isEmpty()) return ""
        return urls.joinToString("\n\n") { url ->
            val body = when {
                Regex("instagram\\.com|facebook\\.com|tiktok\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url) ->
                    "(rede social: o conteúdo não pode ser lido automaticamente; use só como link)"
                else -> runCatching {
                    val viaSite = SiteReader.read(listOf(LinkItem("Página", url)), force = true)
                    viaSite.ifBlank { WebPageReader.read(url).text }
                }.getOrElse { "(não consegui ler: ${it.message})" }
            }
            "[Conteúdo lido do link $url]\n" + body.take(MAX_NOTE)
        }
    }

    data class Attachment(val name: String, val mime: String, val bytes: ByteArray)

    fun readAttachment(ctx: Context, uri: Uri): Attachment {
        var name = "arquivo"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/pdf"
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > 15 * 1024 * 1024) throw IllegalArgumentException("Arquivo maior que 15 MB.")
        return Attachment(name, mime, bytes)
    }

    // ---------------- Prompt ----------------

    private val schema = JSONObject("""
{"type":"OBJECT","properties":{
 "message":{"type":"STRING","description":"próxima pergunta ou mensagem para o dono"},
 "progress":{"type":"INTEGER","description":"0 a 100"},
 "options":{"type":"ARRAY","items":{"type":"STRING"},"description":"2 a 5 respostas curtas sugeridas para a pergunta"},
 "heard":{"type":"STRING","description":"transcrição da resposta em áudio do dono, se houver"},
 "extracted":{"type":"STRING","description":"tudo que foi lido de um arquivo anexado nesta rodada: produtos, preços, regras"},
 "done":{"type":"BOOLEAN"},
 "summary":{"type":"STRING"},
 "config":{"type":"OBJECT","properties":{
   "storeName":{"type":"STRING"},
   "instructions":{"type":"STRING"},
   "preferences":{"type":"STRING"},
   "handoffMessage":{"type":"STRING"},
   "callPolicy":{"type":"STRING"},
   "callNotice":{"type":"STRING"},
   "pauseHours":{"type":"INTEGER"},
   "ownerPauseMinutes":{"type":"INTEGER"},
   "delaySeconds":{"type":"INTEGER"},
   "ignoreGroups":{"type":"BOOLEAN"},
   "triggerEnabled":{"type":"BOOLEAN"},
   "triggerWords":{"type":"STRING"},
   "links":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"title":{"type":"STRING"},"url":{"type":"STRING"}}}},
   "siteSources":{"type":"ARRAY","items":{"type":"OBJECT","properties":{"title":{"type":"STRING"},"url":{"type":"STRING"}}}}
 }}
},"required":["message","progress","done"]}
""")

    /** Exemplo de qualidade (texto de uma loja fictícia), só para a IA copiar o FORMATO. */
    private const val EXAMPLE_INSTRUCTIONS = """ESTE WHATSAPP É SÓ DE ENCOMENDAS (pedidos agendados, presentes e personalizados).
Pedidos para hoje (pronta entrega) são atendidos SOMENTE no WhatsApp da pronta entrega: (11) 99999-0000.
Se o cliente quiser algo para hoje, explique com carinho que quem cuida disso é o WhatsApp da pronta entrega e mande este link:
https://wa.me/5511999990000?text=Ol%C3%A1!%20Quero%20comprar%20para%20agora.

SEU PAPEL: TIRAR DÚVIDAS, NÃO VENDER
- Você só informa: produtos, preços, pedidos mínimos, datas, prazos, entrega, pagamento e cuidados.
- A encomenda é feita e paga SOMENTE pelo site, pelo próprio cliente:
  https://loja.example.com/encomendas
- Nunca anote pedido, nunca pergunte "posso fechar?", nunca monte resumo de pedido, nunca diga que a encomenda está "confirmada".
- Quando o cliente disser que quer encomendar, explique em poucas palavras como fazer no site e mande o link.
- Se o cliente não conseguir usar o site ou tiver um pedido fora do padrão, passe para a equipe.

SOBRE A LOJA
- Doceria Exemplo: doces caramelizados e artesanais (maçãs do amor, brigadeiros, bala baiana).
- Retirada: Rua das Flores, 123 - Centro, Cidade Exemplo - UF (CEP 00000-000).
- Instagram: @doceriaexemplo.

REGRAS DA ENCOMENDA (o site é consultado antes de cada resposta; siga o site em preços, mínimos e disponibilidade)
- Entregas de quarta a sábado.
- Antecedência mínima: 2 dias.
- Entrada de 50% paga no site para confirmar a encomenda.
- Item que o site mostra como ESGOTADO não pode ser encomendado agora.

ENTREGA
- Entregador (app de entregas) na cidade e região, ou retirada na loja.
- O site calcula o frete pelo endereço, a partir de R$ 6,00. Não diga valor exato de frete.

PAGAMENTO
- Somente pelo site: Pix ou cartão (cartão com taxa de 6%). Não aceitamos dinheiro.
- Você não confirma pagamento: se mandarem comprovante, agradeça e diga que a equipe vai conferir.

NÃO FAZER
- Não fechar venda nem registrar pedido por aqui.
- Não dar desconto nem inventar promoção.
- Reclamação ou problema com pedido já feito: passe para a equipe."""

    private const val EXAMPLE_PREFERENCES = """- Tom carinhoso, alegre e próximo. Tratar por "você".
- Mensagens curtas, uma pergunta por vez.
- Objetivo: tirar as dúvidas e levar o cliente ao site para encomendar.
- Quando perguntarem de um produto, mandar a foto com o preço e o pedido mínimo.
- Emojis com moderação: 🍎🍓🎁❤️ (no máximo 2 por mensagem).
- Em vez de "não sei", dizer "vou confirmar com a equipe e já te respondo"."""

    private fun systemPrompt() = """
Você é o assistente de configuração do app "Atendente IA", que responde o WhatsApp de um negócio com inteligência artificial.
Sua tarefa: entrevistar o DONO do negócio, em português do Brasil, e no fim escrever a configuração completa do atendente.

COMO ENTREVISTAR
- Uma pergunta por vez, curta e simpática.
- Em toda pergunta, sugira em "options" de 2 a 5 respostas curtas (até 6 palavras cada) que o dono pode tocar em vez de digitar
  (ex.: ["Delivery de comida", "Loja online", "Ebook ou curso", "Afiliado", "Serviços com agendamento"]; ["Só informar e mandar o link", "Fechar a venda pelo WhatsApp"]).
  Em perguntas abertas (nome, endereço, link), sugira atalhos úteis como ["Não tenho site", "Vou anexar um PDF", "Pular"].
- O dono pode responder por ÁUDIO: entenda o áudio como a resposta dele e escreva a transcrição em "heard".
- Comece perguntando o tipo de negócio (delivery/restaurante, loja online, infoproduto/ebook/curso, afiliado, serviços com agendamento, outro) e o nome.
- Adapte as perguntas ao tipo de negócio. Cubra, nesta ordem e sem pular o que for importante:
  1. Nome e o que vende (uma frase).
  2. Produtos/serviços e preços: peça o link do site/catálogo OU que anexe um PDF ou foto do cardápio/tabela (botão de clipe). Se já tiver site com preços, não peça para digitar a lista.
  3. Como o cliente compra: pelo site/link (a IA só informa e manda o link) ou pelo WhatsApp com a equipe. Pergunte se a IA deve fechar venda ou só informar e encaminhar.
  4. Se existe outro WhatsApp do negócio para outro assunto (ex.: pronta entrega x encomendas) e quando encaminhar para ele (monte o link wa.me com texto).
  5. Endereço/retirada, área atendida e horário de funcionamento.
  6. Entrega/frete/prazo (ou acesso, no caso de produto digital).
  7. Formas de pagamento e taxas.
  8. Regras: pedido mínimo, antecedência, sinal, trocas, garantia, reembolso, cuidados com o produto.
  9. Perguntas que os clientes mais fazem.
  10. O que a IA NÃO pode fazer e quando chamar uma pessoa da equipe.
  11. Tom de voz e emojis.
  12. Instagram e outros links (localização, avaliação no Google, página de vendas).
  13. Se é WhatsApp pessoal: ativar só com palavra-chave? Quais palavras?
  14. Ligações: não recusar, recusar todas, ou recusar só quem não está na agenda.
- Quando o dono mandar um link, o app lê a página e te mostra o conteúdo entre [Conteúdo lido do link …]: aproveite e não pergunte de novo o que já está lá.
- Quando o dono anexar um arquivo (PDF/foto), leia tudo e escreva em "extracted" os produtos, preços e regras encontrados, completos.
- Se a resposta for vaga, peça um detalhe a mais, mas não insista mais de uma vez: anote [confira] e siga.
- Em "progress" diga de 0 a 100 quanto da entrevista já foi coberto.
- Nunca invente dado. O que o dono não informou fica de fora ou como [PREENCHER: …] se for essencial.

QUANDO TERMINAR (o dono respondeu o essencial ou pediu para finalizar)
- Responda com "done": true, "summary" (um resumo curto do que foi configurado) e "config" com:
  storeName; instructions; preferences; handoffMessage (frase curta e simpática ao passar para a equipe);
  callPolicy ("off", "all" ou "unknown"); callNotice (frase após recusar ligação, ou vazio);
  pauseHours (padrão 1); ownerPauseMinutes (padrão 5); delaySeconds (padrão 8); ignoreGroups (padrão true);
  triggerEnabled e triggerWords (só se for WhatsApp pessoal);
  links: [{title, url}] (catálogo, Instagram, mapa, avaliação, outro WhatsApp…);
  siteSources: [{title, url}] (páginas com preço/estoque/horário que o atendente deve ler antes de responder).
- "instructions" deve seguir EXATAMENTE o formato do exemplo abaixo: blocos com TÍTULO EM MAIÚSCULAS e linhas começando com "- ";
  sempre com "SEU PAPEL" logo no começo e "NÃO FAZER" no fim; inclua "PAGAMENTO" com "Você não confirma pagamento…".
  Se houver site consultado, diga "(o site é consultado antes de cada resposta; siga o site em preços e disponibilidade)".
- "preferences" segue o formato do exemplo de preferências.
- Copie o FORMATO e o nível de detalhe do exemplo, nunca o conteúdo (é de outra loja).

EXEMPLO DE "instructions" (outra loja, só o formato):
$EXAMPLE_INSTRUCTIONS

EXEMPLO DE "preferences" (outra loja, só o formato):
$EXAMPLE_PREFERENCES

Responda SEMPRE só com JSON: {"message": "...", "options": ["..."], "progress": 0, "heard": "", "extracted": "", "done": false, "summary": "", "config": {...}}.
""".trim()

    // ---------------- Uma rodada ----------------

    /**
     * Manda a conversa para a IA e devolve a próxima pergunta (ou a configuração final).
     * [attachment] vai só nesta rodada (precisa do Gemini, que lê PDF e foto).
     */
    fun next(c: Config, turns: List<WizardTurn>, attachment: Attachment?, finish: Boolean): WizardStep {
        val system = systemPrompt()
        val ask = if (finish) "\n[O dono pediu para FINALIZAR agora: responda com done=true e a configuração completa com o que já tem.]" else ""
        if (c.apiKey.isBlank()) throw GeminiException("Cadastre a chave do Gemini na aba Início.")
        val raw = run {
            val contents = JSONArray()
            var lastRole = ""
            var parts = JSONArray()
            fun flush() { if (parts.length() > 0) contents.put(JSONObject().put("role", lastRole).put("parts", parts)) }
            fun add(role: String, part: JSONObject) {
                if (role != lastRole) { flush(); lastRole = role; parts = JSONArray() }
                parts.put(part)
            }
            add("user", JSONObject().put("text", "Olá! Quero configurar meu atendente."))
            turns.forEachIndexed { i, t ->
                val role = if (t.role == "ai") "model" else "user"
                val text = listOf(t.text, t.note).filter { it.isNotBlank() }.joinToString("\n\n")
                add(role, JSONObject().put("text", text.ifBlank { "(sem texto)" } + if (i == turns.lastIndex && role == "user") ask else ""))
            }
            if (attachment != null) {
                add("user", JSONObject().put("inlineData", JSONObject().put("mimeType", attachment.mime)
                    .put("data", Base64.encodeToString(attachment.bytes, Base64.NO_WRAP))))
                add("user", JSONObject().put("text",
                    if (attachment.mime.startsWith("audio")) "[Resposta do dono em áudio, acima. Entenda como a resposta à sua última pergunta e escreva a transcrição em \"heard\".]"
                    else "[Arquivo anexado: ${attachment.name}. Leia tudo e preencha \"extracted\".]"))
            }
            if (turns.isEmpty() && finish) add("user", JSONObject().put("text", ask))
            flush()
            Gemini.generate(c.apiKey, c.model, system, contents, schema)
        }
        val o = parseJson(raw)
        return WizardStep(
            message = o.optString("message").trim(),
            progress = o.optInt("progress", 0).coerceIn(0, 100),
            done = o.optBoolean("done"),
            config = o.optJSONObject("config"),
            summary = o.optString("summary").trim(),
            options = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotBlank() }.take(5) } ?: emptyList(),
            heard = o.optString("heard").trim(),
        ).also { step -> lastExtracted = o.optString("extracted").trim() }
    }

    /** O que a IA leu do último arquivo (vira anotação na conversa). */
    @Volatile var lastExtracted: String = ""

    private fun parseJson(raw: String): JSONObject {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { JSONObject(t) }.getOrElse {
            val a = t.indexOf('{'); val b = t.lastIndexOf('}')
            if (a >= 0 && b > a) runCatching { JSONObject(t.substring(a, b + 1)) }.getOrNull() ?: JSONObject().put("message", t)
            else JSONObject().put("message", t)
        }
    }

    // ---------------- Aplicar ----------------

    /** Junta a configuração gerada com a atual (mantém chaves, fotos, voz, vendas). */
    fun apply(current: Config, cfg: JSONObject): Config {
        fun links(key: String, fallback: List<LinkItem>) = cfg.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .map { LinkItem(it.optString("title").trim(), it.optString("url").trim()) }
                .filter { it.title.isNotBlank() && it.url.startsWith("http") }
        } ?: fallback
        val policy = cfg.optString("callPolicy").takeIf { it in setOf("off", "all", "unknown") } ?: current.callPolicy
        return current.copy(
            storeName = cfg.optString("storeName").ifBlank { current.storeName },
            instructions = cfg.optString("instructions").ifBlank { current.instructions },
            preferences = cfg.optString("preferences").ifBlank { current.preferences },
            handoffMessage = cfg.optString("handoffMessage").ifBlank { current.handoffMessage },
            callPolicy = policy,
            callNotice = if (cfg.has("callNotice")) cfg.optString("callNotice") else current.callNotice,
            pauseHours = cfg.optInt("pauseHours", current.pauseHours).coerceIn(1, 720),
            ownerPauseMinutes = cfg.optInt("ownerPauseMinutes", current.ownerPauseMinutes).coerceIn(1, 1440),
            delaySeconds = cfg.optInt("delaySeconds", current.delaySeconds).coerceIn(2, 60),
            ignoreGroups = cfg.optBoolean("ignoreGroups", current.ignoreGroups),
            triggerEnabled = cfg.optBoolean("triggerEnabled", current.triggerEnabled),
            triggerWords = cfg.optString("triggerWords").ifBlank { current.triggerWords },
            links = links("links", current.links),
            siteSources = links("siteSources", current.siteSources),
        )
    }

    /** Restaurar: apaga o que o assistente preencheu (mantém chaves, fotos, voz e vendas) e libera refazer. */
    fun restore(current: Config): Config {
        val d = Config()
        return current.copy(
            storeName = d.storeName, instructions = d.instructions, preferences = d.preferences,
            handoffMessage = d.handoffMessage, callPolicy = d.callPolicy, callNotice = d.callNotice,
            pauseHours = d.pauseHours, ownerPauseMinutes = d.ownerPauseMinutes, delaySeconds = d.delaySeconds,
            ignoreGroups = d.ignoreGroups, triggerEnabled = d.triggerEnabled, triggerWords = d.triggerWords,
            links = d.links, siteSources = d.siteSources,
        )
    }
}
