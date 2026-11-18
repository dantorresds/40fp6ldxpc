package com.atendenteia.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Resultado de ler um prompt: persona pronta (fotos por link já baixadas), avisos e arquivos novos (para apagar se o dono cancelar). */
data class Prepared(val persona: Persona, val warnings: List<String>, val newFiles: List<String> = emptyList())

/**
 * Importa o prompt de configuração (PDF, TXT ou texto colado, cabeçalho
 * "ATENDENTE-IA-CONFIG v1 · PERSONA N" ou "· ORQUESTRADOR"). A leitura é local
 * ([ConfigDoc]); o Gemini só entra para transcrever PDF ou, de reserva, para
 * ler um documento fora do padrão. O app mostra um resumo antes de aplicar.
 */
object ConfigImport {
    const val MAX_BYTES = 8 * 1024 * 1024

    /** Texto do prompt padrão para colar em qualquer IA (persona 1-3 ou orquestrador com n = 0). */
    fun promptText(ctx: Context, n: Int): String {
        val file = if (n == 0) "prompts/orquestrador.md" else "prompts/persona.md"
        return ctx.assets.open(file).bufferedReader().use { it.readText() }.replace("{{N}}", n.toString())
    }

    private fun str() = JSONObject().put("type", "STRING")

    private val personaSchema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
        .put("personaNumero", JSONObject().put("type", "INTEGER"))
        .put("nome", str()).put("loja", str())
        .put("papel", JSONObject().put("type", "STRING").put("enum", JSONArray(Personas.ROLES)))
        .put("modoFechamento", JSONObject().put("type", "STRING").put("enum", JSONArray(Personas.CLOSE_MODES)))
        .put("linkVenda", str()).put("quandoUsar", str()).put("palavrasChave", str())
        .put("instrucoes", str()).put("preferencias", str()).put("ofertas", str()).put("mensagemHumano", str())
        .put("links", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("titulo", str()).put("url", str()))))
        .put("followUp", JSONObject().put("type", "BOOLEAN"))
        .put("followMinutos", JSONObject().put("type", "INTEGER"))
        .put("followInicio", JSONObject().put("type", "INTEGER"))
        .put("followFim", JSONObject().put("type", "INTEGER"))
        .put("followDomingo", JSONObject().put("type", "BOOLEAN")))
        .put("required", JSONArray().put("nome").put("papel").put("instrucoes"))

    private val orchSchema = JSONObject().put("type", "OBJECT").put("properties", JSONObject()
        .put("promptMestre", str()))
        .put("required", JSONArray().put("promptMestre"))

    private fun readBytes(ctx: Context, uri: Uri): ByteArray {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Não consegui abrir o arquivo.")
        if (bytes.size > MAX_BYTES) error("Arquivo grande demais (máx. 8 MB).")
        return bytes
    }

    private fun isPdf(bytes: ByteArray) = bytes.size > 4 && String(bytes, 0, 4) == "%PDF"

    private fun filePart(bytes: ByteArray): JSONObject =
        if (isPdf(bytes)) JSONObject().put("inlineData", JSONObject().put("mimeType", "application/pdf")
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        else JSONObject().put("text", "CONTEÚDO DO ARQUIVO:\n" + String(bytes, Charsets.UTF_8).take(60_000))

    private suspend fun ask(base: Config, keySlot: Int, bytes: ByteArray, instruction: String, schema: JSONObject?, system: String): String {
        val key = Personas.keyFor(base, keySlot)
        if (key.isBlank()) error("Cadastre a chave do Gemini primeiro.")
        val parts = JSONArray().put(filePart(bytes)).put(JSONObject().put("text", instruction))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        return AiQueue.run(AiQueue.REPLY, key, base.rpmLimit) {
            Gemini.generate(key, base.model, system, contents, schema = schema, maxModels = 3)
        }
    }

    private suspend fun askJson(base: Config, keySlot: Int, bytes: ByteArray, instruction: String, schema: JSONObject): JSONObject {
        val raw = ask(base, keySlot, bytes, instruction, schema, "Você extrai configurações de documentos. Copie o conteúdo fielmente, " +
            "sem inventar nada. Campo sem informação ou com \"-\": deixe vazio.")
        return JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
    }

    /** O Gemini só TRANSCREVE o PDF (texto integral); quem entende o documento é o [ConfigDoc]. */
    private suspend fun transcribe(base: Config, keySlot: Int, bytes: ByteArray): String =
        ask(base, keySlot, bytes,
            "Transcreva o texto INTEGRAL deste documento, fielmente, na mesma ordem, sem resumir, sem comentar e sem acrescentar nada. " +
                "Cada título de seção (ex.: NOME DA PERSONA, PAPEL, INSTRUÇÕES, LINKS) deve ficar sozinho em uma linha. " +
                "Mantenha as quebras de linha, listas e linhas em branco como estão. Não use blocos de código.",
            null, "Você é um transcritor de documentos: devolve só o texto do documento, sem nada além dele.").trim()
            .removePrefix("```markdown").removePrefix("```text").removePrefix("```").removeSuffix("```").trim()

    // ---------------- Persona ----------------

    /** Lê o texto do prompt (local), baixa as fotos por link que faltam e devolve a persona pronta para conferir. */
    suspend fun preparePersona(ctx: Context, current: Persona, text: String): Prepared {
        val parsed = ConfigDoc.parsePersona(text, current).getOrThrow()
        val (photos, warn, files) = PhotoSync.resolve(ctx, parsed.persona.photos)
        return Prepared(parsed.persona.copy(photos = photos), parsed.warnings + warn, files)
    }

    /** Importa um arquivo (TXT/MD direto; PDF transcrito pelo Gemini). Se o formato não bater, o Gemini lê com esquema (reserva). */
    suspend fun importPersona(ctx: Context, base: Config, current: Persona, uri: Uri): Prepared {
        val bytes = readBytes(ctx, uri)
        val local = runCatching {
            val text = if (isPdf(bytes)) transcribe(base, current.keySlot, bytes) else String(bytes, Charsets.UTF_8)
            preparePersona(ctx, current, text)
        }
        local.getOrNull()?.let { return it }
        val (np, warn) = runCatching { readPersona(base, current, bytes) }.getOrElse {
            throw local.exceptionOrNull() ?: it
        }
        return Prepared(np, listOfNotNull(warn, "Documento fora do padrão: foi lido pela IA e pode ter perdido detalhes."))
    }

    /** Lê o arquivo com o Gemini e esquema (reserva) e devolve a persona preenchida (sem salvar) + aviso se o número não bate. */
    private suspend fun readPersona(base: Config, p: Persona, bytes: ByteArray): Pair<Persona, String?> {
        val o = askJson(base, p.keySlot, bytes,
            "Este documento segue o formato ATENDENTE-IA-CONFIG v1 · PERSONA N. Extraia cada seção para o campo " +
                "correspondente do JSON. Em 'instrucoes', copie a seção INSTRUÇÕES inteira (pode resumir só repetições). " +
                "Horas de follow-up: números de 0 a 24.", personaSchema)
        val num = o.optInt("personaNumero", 0)
        val warn = if (num in 1..3 && num != p.id) "O arquivo é da Persona $num, mas você está importando na Persona ${p.id}." else null
        val links = o.optJSONArray("links")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optString("url").startsWith("http") }
            .map { LinkItem(it.optString("titulo").ifBlank { it.optString("url") }, it.optString("url")) } } ?: emptyList()
        fun s(k: String, old: String) = o.optString(k).trim().takeIf { it.isNotBlank() && it != "-" } ?: old
        val out = p.copy(
            label = s("nome", p.label), storeName = s("loja", p.storeName),
            role = o.optString("papel").takeIf { it in Personas.ROLES } ?: p.role,
            closeMode = o.optString("modoFechamento").takeIf { it in Personas.CLOSE_MODES } ?: p.closeMode,
            salesPageUrl = s("linkVenda", p.salesPageUrl).takeIf { it.startsWith("http") || it.isBlank() } ?: p.salesPageUrl,
            whenToUse = s("quandoUsar", p.whenToUse), keywords = s("palavrasChave", p.keywords),
            instructions = s("instrucoes", p.instructions), preferences = s("preferencias", p.preferences),
            offers = s("ofertas", p.offers), handoffMessage = s("mensagemHumano", p.handoffMessage),
            links = if (links.isNotEmpty()) links else p.links,
            salesMode = (o.optString("papel").takeIf { it in Personas.ROLES } ?: p.role) == "vendedor" || p.salesMode,
            followUp = if (o.has("followUp")) o.optBoolean("followUp") else p.followUp,
            followFirstMinutes = o.optInt("followMinutos", p.followFirstMinutes).takeIf { it in 5..1440 } ?: p.followFirstMinutes,
            followStartHour = o.optInt("followInicio", p.followStartHour).takeIf { it in 0..23 } ?: p.followStartHour,
            followEndHour = o.optInt("followFim", p.followEndHour).takeIf { it in 1..24 } ?: p.followEndHour,
            followSunday = if (o.has("followDomingo")) o.optBoolean("followDomingo") else p.followSunday,
        )
        return out to warn
    }

    // ---------------- Orquestrador ----------------

    /** Texto do documento do orquestrador → só as regras (sem cabeçalho e sem a seção PERSONAS). */
    fun prepareRules(text: String): String = ConfigDoc.parseOrchestrator(text).getOrThrow()

    /** Importa um arquivo do orquestrador (TXT direto; PDF transcrito). Reserva: leitura da IA com esquema. */
    suspend fun importRules(ctx: Context, base: Config, keySlot: Int, uri: Uri): String {
        val bytes = readBytes(ctx, uri)
        val local = runCatching {
            prepareRules(if (isPdf(bytes)) transcribe(base, keySlot, bytes) else String(bytes, Charsets.UTF_8))
        }
        local.getOrNull()?.let { return it }
        val o = runCatching {
            askJson(base, keySlot, bytes,
                "Este documento segue o formato ATENDENTE-IA-CONFIG v1 · ORQUESTRADOR. Devolva em 'promptMestre' o texto " +
                    "das seções COMO ESCOLHER NO INÍCIO DA CONVERSA, PERSONA PADRÃO e TROCA NO MEIO DA CONVERSA " +
                    "(e outras de regras, se houver), com esses títulos, fiel ao documento. Não inclua a seção PERSONAS.", orchSchema)
        }.getOrElse { throw local.exceptionOrNull() ?: it }
        return ConfigDoc.orchestratorRules(o.optString("promptMestre")).ifBlank { error("Não encontrei o prompt mestre no arquivo.") }
    }

    /** Resumo curto para o dono confirmar antes de aplicar. */
    fun summary(p: Persona) = buildString {
        appendLine("Nome: ${p.label}")
        if (p.storeName.isNotBlank()) appendLine("Loja/produto: ${p.storeName}")
        appendLine("Papel: ${Personas.roleLabel(p.role)}")
        if (p.role == "vendedor") appendLine("Fechamento: ${Personas.closeLabel(p.closeMode)}")
        if (p.salesPageUrl.isNotBlank()) appendLine("Link de venda: ${p.salesPageUrl}")
        if (p.whenToUse.isNotBlank()) appendLine("Quando usar: ${p.whenToUse.take(120)}")
        if (p.keywords.isNotBlank()) appendLine("Códigos: ${p.keywords}")
        appendLine("Instruções: ${p.instructions.length} caracteres")
        if (p.offers.isNotBlank()) appendLine("Ofertas: ${p.offers.take(120)}")
        appendLine("Links: ${p.links.size} · Sites para ler: ${p.siteSources.size} · Fotos: ${p.photos.size}")
        append("Retomada: ${if (p.followUp) "sim, ${p.followFirstMinutes} min, ${p.followStartHour}h-${p.followEndHour}h" else "não"}")
    }
}

/** Fotos por link: baixa para o aparelho as que ainda não existem aqui. */
object PhotoSync {
    private const val MAX_IMAGE = 8 * 1024 * 1024

    /** Baixa a imagem do link e guarda reduzida (JPEG, até 1600 px) em photosDir. Devolve o nome do arquivo. */
    fun download(ctx: Context, url: String): String {
        if (!url.startsWith("http://") && !url.startsWith("https://")) error("O link precisa começar com http:// ou https://.")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 20_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AtendenteIA/1.0")
        }
        try {
            if (conn.responseCode != 200) error("o endereço respondeu ${conn.responseCode}")
            val type = conn.contentType.orEmpty().lowercase()
            if (!type.startsWith("image/")) error("o link não é de uma imagem (${type.ifBlank { "tipo desconhecido" }})")
            if (conn.contentLengthLong > MAX_IMAGE) error("imagem grande demais (máx. 8 MB)")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_IMAGE) error("imagem grande demais (máx. 8 MB)")
                }
            }
            return saveBytes(ctx, out.toByteArray())
        } finally {
            conn.disconnect()
        }
    }

    private fun saveBytes(ctx: Context, bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) error("não consegui abrir como imagem")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("não consegui abrir como imagem")
        val name = UUID.randomUUID().toString() + ".jpg"
        File(Store.photosDir(ctx), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return name
    }

    /**
     * Fotos que vieram do prompt: com arquivo no aparelho ficam como estão; com link e sem arquivo são
     * baixadas; sem link e sem arquivo viram aviso e saem. Devolve (fotos, avisos, arquivos novos).
     */
    fun resolve(ctx: Context, photos: List<PhotoItem>): Triple<List<PhotoItem>, List<String>, List<String>> {
        val warn = mutableListOf<String>()
        val files = mutableListOf<String>()
        val out = photos.mapNotNull { p ->
            when {
                p.file.isNotBlank() && File(Store.photosDir(ctx), p.file).exists() -> p
                p.url.isNotBlank() -> runCatching { download(ctx, p.url) }.fold(
                    { name -> files += name; p.copy(file = name) },
                    { warn += "Foto \"${p.id}\": não consegui baixar (${it.message ?: "erro"}); foi ignorada."; null })
                else -> { warn += "Foto \"${p.id}\": sem link e sem arquivo neste aparelho; foi ignorada."; null }
            }
        }
        return Triple(out, warn, files)
    }

    fun deleteFiles(ctx: Context, names: List<String>) {
        names.forEach { runCatching { File(Store.photosDir(ctx), it).delete() } }
    }
}
