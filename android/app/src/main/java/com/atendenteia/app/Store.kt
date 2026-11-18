package com.atendenteia.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Foto que a IA pode mandar ao cliente. O arquivo fica em filesDir/photos. */
data class PhotoItem(val id: String, val description: String, val file: String, val url: String = "")

/** Link que a IA pode mandar ao cliente (catálogo, Instagram, localização…). */
data class LinkItem(val title: String, val url: String)

/** Instruções e preferências da loja, salvas no próprio celular. */
/** Voz que será usada nas respostas em áudio, ou null se desligado. */
fun Config.activeVoice(): String? = when (voiceMode) {
    "prebuilt" -> prebuiltVoice
    "mine" -> myVoiceId.takeIf { it.isNotBlank() }
    else -> null
}

data class Config(
    val enabled: Boolean = false,
    val apiKey: String = "",
    /** Modelo estável: menos disputado que o "latest". */
    val model: String = "gemini-3.6-flash",
    /** Chaves 2 e 3 do Gemini (cada persona escolhe a sua; vazio = usa a chave 1). */
    val apiKey2: String = "",
    val apiKey3: String = "",
    /** Chamadas por minuto em cada chave (plano grátis: ~10). */
    val rpmLimit: Int = 10,
    /** Quantos números de WhatsApp este app conecta (1 a 3). */
    val numbers: Int = 1,
    /** Segundos entre o texto e o link/foto (parece a pessoa procurando o arquivo). */
    val mediaGapSeconds: Int = 3,
    /** Horas que a conversa fica presa à persona escolhida. */
    val personaLockHours: Int = 24,
    /** Máximo de mensagens de follow-up por hora em cada número (proteção contra bloqueio). */
    val followMaxPerHour: Int = 20,
    val storeName: String = "",
    val instructions: String = "",
    val preferences: String = "",
    val handoffMessage: String = "Certo! Vou chamar alguém da equipe para continuar com você. Pode seguir mandando mensagens por aqui. 🙂",
    val callNotice: String = "📵 Nosso atendimento não recebe chamadas de voz ou vídeo pelo WhatsApp. Por favor, envie uma mensagem de texto ou áudio por aqui.",
    val pauseHours: Int = 1,
    val delaySeconds: Int = 8,
    val ignoreGroups: Boolean = true,
    val onlyNumbers: String = "",
    val blockedNumbers: String = "",
    val understandAudio: Boolean = true,
    val understandImages: Boolean = true,
    /** Ligações: "off" = não recusa; "all" = recusa todas; "unknown" = só de quem não está na agenda. */
    val callPolicy: String = "all",
    val markRead: Boolean = true,
    val pauseOnOwnerReply: Boolean = true,
    /** Minutos sem você responder até o robô voltar na conversa. */
    val ownerPauseMinutes: Int = 5,
    /** Só responde quem escrever a palavra-chave (uso no WhatsApp pessoal). */
    val triggerEnabled: Boolean = false,
    val triggerWords: String = "",
    /** Minutos que a conversa fica ativa depois da palavra-chave (renova a cada mensagem). */
    val triggerMinutes: Int = 30,
    /** Humanização: respostas fracionadas, poucos emojis, sem frases de robô. */
    val humanize: Boolean = true,
    /** Máximo de emojis por resposta (0 = nenhum). */
    val emojiLimit: Int = 1,
    /** Segundos entre uma mensagem e a próxima quando a resposta é fracionada. */
    val partGapSeconds: Int = 3,
    /** Responder com áudio: "off", "prebuilt" (voz pronta do Gemini) ou "mine" (voz clonada). */
    val voiceMode: String = "off",
    val prebuiltVoice: String = "Kore",
    val myVoiceId: String = "",
    val ttsModel: String = "gemini-3.8-flash-tts",
    /** Jeito de falar (ex.: "simpática e calma, sotaque paulista"). */
    val voiceStyle: String = "",
    /** false = só responde em áudio quando o cliente manda áudio. */
    val voiceAlways: Boolean = false,
    val photos: List<PhotoItem> = emptyList(),
    val links: List<LinkItem> = emptyList(),
    /** Sites que a IA consulta antes de responder (cardápio, pronta entrega, encomendas). */
    val siteSources: List<LinkItem> = emptyList(),
)

object Store {
    private const val PREFS = "atendente"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Config {
        val raw = prefs(ctx).getString("config", null) ?: return Config()
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Config()
        }
    }

    fun save(ctx: Context, c: Config) {
        prefs(ctx).edit().putString("config", toJson(c).toString()).apply()
    }

    fun photosDir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }

    private fun toJson(c: Config) = JSONObject().apply {
        put("cfgVersion", 2)
        put("enabled", c.enabled); put("apiKey", c.apiKey); put("model", c.model)
        put("apiKey2", c.apiKey2); put("apiKey3", c.apiKey3); put("rpmLimit", c.rpmLimit); put("numbers", c.numbers)
        put("mediaGapSeconds", c.mediaGapSeconds); put("personaLockHours", c.personaLockHours); put("followMaxPerHour", c.followMaxPerHour)
        put("storeName", c.storeName); put("instructions", c.instructions); put("preferences", c.preferences)
        put("handoffMessage", c.handoffMessage); put("callNotice", c.callNotice)
        put("pauseHours", c.pauseHours); put("delaySeconds", c.delaySeconds)
        put("ignoreGroups", c.ignoreGroups); put("onlyNumbers", c.onlyNumbers); put("blockedNumbers", c.blockedNumbers)
        put("understandAudio", c.understandAudio); put("understandImages", c.understandImages)
        put("callPolicy", c.callPolicy); put("markRead", c.markRead); put("pauseOnOwnerReply", c.pauseOnOwnerReply)
        put("triggerEnabled", c.triggerEnabled); put("triggerWords", c.triggerWords); put("triggerMinutes", c.triggerMinutes); put("ownerPauseMinutes", c.ownerPauseMinutes)
        put("humanize", c.humanize); put("emojiLimit", c.emojiLimit); put("partGapSeconds", c.partGapSeconds)
        put("voiceMode", c.voiceMode); put("prebuiltVoice", c.prebuiltVoice); put("myVoiceId", c.myVoiceId)
        put("ttsModel", c.ttsModel); put("voiceStyle", c.voiceStyle); put("voiceAlways", c.voiceAlways)
        put("photos", JSONArray().apply {
            c.photos.forEach { put(JSONObject().put("id", it.id).put("description", it.description).put("file", it.file).put("url", it.url)) }
        })
        put("links", JSONArray().apply {
            c.links.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
        })
        put("siteSources", JSONArray().apply {
            c.siteSources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
        })
    }

    private fun fromJson(o: JSONObject): Config {
        val d = Config()
        val photos = o.optJSONArray("photos")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { PhotoItem(it.optString("id"), it.optString("description"), it.optString("file"), it.optString("url")) }
        } ?: emptyList()
        fun linkList(key: String) = o.optJSONArray(key)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { LinkItem(it.optString("title"), it.optString("url")) }
        } ?: emptyList()
        val links = linkList("links")
        val cfgVersion = o.optInt("cfgVersion", 1)
        return Config(
            enabled = o.optBoolean("enabled", d.enabled),
            apiKey = o.optString("apiKey", d.apiKey),
            // "gemini-flash-latest" era o padrão antigo e cai no modelo mais disputado.
            model = o.optString("model", d.model).ifBlank { d.model }.let { if (it == "gemini-flash-latest") d.model else it },
            apiKey2 = o.optString("apiKey2"), apiKey3 = o.optString("apiKey3"),
            rpmLimit = o.optInt("rpmLimit", d.rpmLimit).coerceIn(1, 1000),
            numbers = o.optInt("numbers", d.numbers).coerceIn(1, 3),
            mediaGapSeconds = o.optInt("mediaGapSeconds", d.mediaGapSeconds).coerceIn(1, 30),
            personaLockHours = o.optInt("personaLockHours", d.personaLockHours).coerceIn(1, 720),
            followMaxPerHour = o.optInt("followMaxPerHour", d.followMaxPerHour).coerceIn(1, 120),
            storeName = o.optString("storeName", d.storeName),
            instructions = o.optString("instructions", d.instructions),
            preferences = o.optString("preferences", d.preferences),
            handoffMessage = o.optString("handoffMessage", d.handoffMessage),
            callNotice = o.optString("callNotice", d.callNotice),
            // Padrões antigos (12 h e 10 min) viram os novos (1 h e 5 min) uma vez só.
            pauseHours = o.optInt("pauseHours", d.pauseHours).let { if (cfgVersion < 2 && it == 12) d.pauseHours else it },
            delaySeconds = o.optInt("delaySeconds", d.delaySeconds),
            ignoreGroups = o.optBoolean("ignoreGroups", d.ignoreGroups),
            onlyNumbers = o.optString("onlyNumbers", d.onlyNumbers),
            blockedNumbers = o.optString("blockedNumbers", d.blockedNumbers),
            understandAudio = o.optBoolean("understandAudio", d.understandAudio),
            understandImages = o.optBoolean("understandImages", d.understandImages),
            // Versões antigas tinham só liga/desliga ("answerCalls").
            callPolicy = o.optString("callPolicy", "").ifBlank { if (o.optBoolean("answerCalls", true)) "all" else "off" },
            markRead = o.optBoolean("markRead", d.markRead),
            pauseOnOwnerReply = o.optBoolean("pauseOnOwnerReply", d.pauseOnOwnerReply),
            triggerEnabled = o.optBoolean("triggerEnabled", d.triggerEnabled),
            triggerWords = o.optString("triggerWords", d.triggerWords),
            triggerMinutes = o.optInt("triggerMinutes", d.triggerMinutes),
            humanize = o.optBoolean("humanize", d.humanize),
            emojiLimit = o.optInt("emojiLimit", d.emojiLimit).coerceIn(0, 5),
            partGapSeconds = o.optInt("partGapSeconds", d.partGapSeconds).coerceIn(1, 30),
            ownerPauseMinutes = o.optInt("ownerPauseMinutes", d.ownerPauseMinutes).let { if (cfgVersion < 2 && it == 10) d.ownerPauseMinutes else it },
            voiceMode = o.optString("voiceMode", d.voiceMode),
            prebuiltVoice = o.optString("prebuiltVoice", d.prebuiltVoice),
            myVoiceId = o.optString("myVoiceId", d.myVoiceId),
            ttsModel = o.optString("ttsModel", d.ttsModel).ifBlank { d.ttsModel },
            voiceStyle = o.optString("voiceStyle", d.voiceStyle),
            voiceAlways = o.optBoolean("voiceAlways", d.voiceAlways),
            photos = photos,
            links = links,
            siteSources = linkList("siteSources"),
        )
    }

    // ---- Conversas pausadas (atendimento humano) ----

    /** Chave = chat JID, valor = pausado até (ms). */
    fun pausedMap(ctx: Context): Map<String, Long> {
        val o = JSONObject(prefs(ctx).getString("paused", "{}") ?: "{}")
        val now = System.currentTimeMillis()
        return o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { it > now }
    }

    fun isPaused(ctx: Context, chat: String) = pausedMap(ctx).containsKey(chat)

    fun pause(ctx: Context, chat: String, hours: Int) = pauseUntil(ctx, chat, System.currentTimeMillis() + hours * 3_600_000L)

    /** Pausa a conversa até o horário indicado (substitui a pausa anterior). */
    @Synchronized
    fun pauseUntil(ctx: Context, chat: String, until: Long) {
        val m = pausedMap(ctx).toMutableMap()
        m[chat] = until
        prefs(ctx).edit().putString("paused", JSONObject(m as Map<*, *>).toString()).apply()
    }

    @Synchronized
    fun resume(ctx: Context, chat: String) {
        val m = pausedMap(ctx).toMutableMap()
        m.remove(chat)
        prefs(ctx).edit().putString("paused", JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ---- Conversas ativadas pela palavra-chave ----

    @Synchronized
    fun activate(ctx: Context, chat: String, minutes: Int) {
        val o = JSONObject(prefs(ctx).getString("active", "{}") ?: "{}")
        val now = System.currentTimeMillis()
        val keep = JSONObject()
        o.keys().forEach { k -> if (o.getLong(k) > now) keep.put(k, o.getLong(k)) }
        keep.put(chat, now + minutes * 60_000L)
        prefs(ctx).edit().putString("active", keep.toString()).apply()
    }

    fun isActive(ctx: Context, chat: String): Boolean {
        val o = JSONObject(prefs(ctx).getString("active", "{}") ?: "{}")
        return o.optLong(chat, 0L) > System.currentTimeMillis()
    }

    /** Nome do contato por chat (para mostrar no app). */
    @Synchronized
    fun rememberName(ctx: Context, chat: String, name: String) {
        if (name.isBlank()) return
        val o = JSONObject(prefs(ctx).getString("names", "{}") ?: "{}")
        if (o.optString(chat) == name) return
        o.put(chat, name)
        prefs(ctx).edit().putString("names", o.toString()).apply()
    }

    fun nameOf(ctx: Context, chat: String): String {
        val o = JSONObject(prefs(ctx).getString("names", "{}") ?: "{}")
        return o.optString(chat).ifBlank { chat.substringBefore("@") }
    }

    // ---- Contadores de diagnóstico ----

    @Synchronized
    fun count(ctx: Context, key: String) {
        val p = prefs(ctx)
        p.edit().putInt("count_$key", p.getInt("count_$key", 0) + 1).apply()
    }

    fun counter(ctx: Context, key: String) = prefs(ctx).getInt("count_$key", 0)

    fun setLastError(ctx: Context, text: String) =
        prefs(ctx).edit().putString("lastError", text.take(300)).putLong("lastErrorAt", System.currentTimeMillis()).apply()

    fun lastError(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val t = p.getString("lastError", null) ?: return null
        return t to p.getLong("lastErrorAt", 0)
    }

    /** Alguma chave de IA já respondeu no "Testar conexão" (libera o assistente). */
    fun aiVerified(ctx: Context) = prefs(ctx).getBoolean("aiVerified", false)
    fun setAiVerified(ctx: Context) = prefs(ctx).edit().putBoolean("aiVerified", true).apply()

    /** Assistente de configuração concluído (data em ms; 0 = não). */
    fun wizardDoneAt(ctx: Context) = prefs(ctx).getLong("wizardDoneAt", 0L)
    fun setWizardDoneAt(ctx: Context, v: Long) = prefs(ctx).edit().putLong("wizardDoneAt", v).apply()

    fun resetDiagnostics(ctx: Context) = prefs(ctx).edit()
        .remove("count_received").remove("count_answered").remove("lastError").remove("lastErrorAt").apply()

    // ---- Registro de atividade ----

    data class LogLine(val time: Long, val text: String)

    @Synchronized
    fun log(ctx: Context, text: String) {
        val a = JSONArray(prefs(ctx).getString("log", "[]") ?: "[]")
        a.put(JSONObject().put("t", System.currentTimeMillis()).put("s", text.take(500)))
        val start = maxOf(0, a.length() - 300)
        val trimmed = JSONArray()
        for (i in start until a.length()) trimmed.put(a.get(i))
        prefs(ctx).edit().putString("log", trimmed.toString()).apply()
    }

    fun logLines(ctx: Context): List<LogLine> {
        val a = JSONArray(prefs(ctx).getString("log", "[]") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { LogLine(it.optLong("t"), it.optString("s")) }.reversed()
    }

    fun clearLog(ctx: Context) = prefs(ctx).edit().putString("log", "[]").apply()
}

/** Turno de conversa guardado para dar contexto à IA. */
data class Turn(val role: String, val text: String)

/** Histórico por conversa (últimos 30 turnos), em arquivos no celular. */
object History {
    private const val MAX = 30

    private fun file(ctx: Context, chat: String): File {
        val dir = File(ctx.filesDir, "history").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest(chat.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    @Synchronized
    fun get(ctx: Context, chat: String): List<Turn> {
        val f = file(ctx, chat)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Turn(it.optString("r"), it.optString("t")) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun append(ctx: Context, chat: String, turns: List<Turn>) {
        val all = (get(ctx, chat) + turns).takeLast(MAX)
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("r", it.role).put("t", it.text)) }
        file(ctx, chat).writeText(a.toString())
    }

    fun clearAll(ctx: Context) {
        File(ctx.filesDir, "history").listFiles()?.forEach { it.delete() }
    }
}
