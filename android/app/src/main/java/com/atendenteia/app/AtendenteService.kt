package com.atendenteia.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.atendenteia.wa.waengine.Engine
import com.atendenteia.wa.waengine.Listener
import com.atendenteia.wa.waengine.Waengine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Serviço em primeiro plano (notificação fixa): mantém até 3 números de
 * WhatsApp conectados e responde os clientes com o Gemini, cada conversa com
 * a sua persona, memória, etiquetas do funil e follow-up.
 */
class AtendenteService : Service() {

    companion object {
        const val ACTION_START = "com.atendenteia.START"
        const val ACTION_STOP = "com.atendenteia.STOP"
        const val ACTION_PAIR = "com.atendenteia.PAIR"
        const val ACTION_LOGOUT = "com.atendenteia.LOGOUT"

        private const val CHANNEL = "atendente_status"
        private const val CHANNEL_ALERT = "atendente_alertas"
        private const val NOTIFICATION_ID = 4501

        private val engines = ConcurrentHashMap<Int, Engine>()
        private val lastStatus = ConcurrentHashMap<Int, String>()
        @Volatile private var instance: AtendenteService? = null

        fun engineFor(conn: Int): Engine? = engines[conn]

        fun statusJson(conn: Int = 1): String = try {
            engines[conn]?.statusJSON() ?: lastStatus[conn] ?: "{\"state\":\"stopped\"}"
        } catch (e: Exception) {
            lastStatus[conn] ?: "{\"state\":\"stopped\"}"
        }

        fun start(ctx: Context, action: String = ACTION_START, phone: String? = null, conn: Int = 1) {
            val i = Intent(ctx, AtendenteService::class.java).setAction(action).putExtra("conn", conn)
            if (phone != null) i.putExtra("phone", phone)
            if (action == ACTION_STOP) {
                ctx.startService(i)
            } else if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        /** Liga de novo depois de reiniciar o celular, se estava ligado. */
        fun startIfEnabled(ctx: Context) {
            if (ctx.getSharedPreferences("atendente_service", MODE_PRIVATE).getBoolean("running", false)) start(ctx)
        }

        /**
         * Você responde pelo app (aba Conversas): envia pelo número da conversa,
         * guarda na memória e pausa o robô como se tivesse respondido pelo WhatsApp.
         */
        /** Descarta mensagens ainda não respondidas (você pausou a conversa no app). */
        fun dropPendingFor(key: String) { instance?.dropPending(key) }

        /** Orientação do dono: a IA responde agora este cliente seguindo o que ele pediu. */
        suspend fun guidedReply(key: String, guidance: String): String =
            (instance ?: throw IllegalStateException("Ligue o motor do WhatsApp na aba Início")).guided(key, guidance)

        fun sendManual(ctx: Context, key: String, text: String) {
            val e = engines[Memory.connOf(key)] ?: throw IllegalStateException("WhatsApp deste número está desligado")
            e.sendText(Memory.chatOf(key), text)
            Memory.add(ctx, key, "owner", text)
            Memory.update(ctx, key) { put("followup_at", 0L); put("followup_step", 0) }
            val c = Store.load(ctx)
            Store.pauseUntil(ctx, key, maxOf(Store.pausedMap(ctx)[key] ?: 0L, System.currentTimeMillis() + c.ownerPauseMinutes.coerceIn(1, 1440) * 60_000L))
            instance?.dropPending(key)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, MutableList<JSONObject>>()
    private val timers = ConcurrentHashMap<String, Job>()
    private val chatLocks = ConcurrentHashMap<String, Mutex>()
    private val retries = ConcurrentHashMap<String, Int>()
    private val summarizing = ConcurrentHashMap.newKeySet<String>()
    /** Última mensagem do cliente por conversa (para reconhecer saudação/ausência automática do WhatsApp Business). */
    private val lastIncoming = ConcurrentHashMap<String, Long>()
    /** Textos que o dono mandou recentemente: texto normalizado -> conversas em que apareceu. */
    private val ownerTexts = ConcurrentHashMap<String, MutableSet<String>>()
    /** Envios de follow-up por número (para o limite por hora). */
    private val followSent = ConcurrentHashMap<Int, MutableList<Long>>()
    private var poller: Job? = null
    private var followLoop: Job? = null
    private val RETRY_DELAYS_MIN = listOf(2, 5)

    /** Responde agora o cliente seguindo a orientação do dono. Devolve o texto enviado. */
    suspend fun guided(key: String, guidance: String): String {
        val conn = Memory.connOf(key)
        val e = engines[conn] ?: throw IllegalStateException("WhatsApp deste número está desligado")
        val base = Store.load(this)
        val conv = Memory.get(this, key) ?: throw IllegalStateException("Conversa não encontrada")
        val all = Personas.load(this)
        val persona = all.firstOrNull { it.id == conv.persona && it.active }
            ?: all.firstOrNull { it.active && it.connection == conn } ?: all[0]
        val c = Personas.forReply(base, persona)
        val name = Store.nameOf(this, key)
        val chat = Memory.chatOf(key)
        dropPending(key)
        runCatching { e.setPresence(chat, "typing") }
        val reply = try {
            AiQueue.run(AiQueue.REPLY, c.apiKey, c.rpmLimit) {
                Brain.answer(c, name, Memory.contextTurns(this, key), emptyList(), Sales.promptSection(this), persona,
                    Brain.guidedInstruction(guidance))
            }
        } catch (ex: Exception) {
            runCatching { e.setPresence(chat, "paused") }
            throw ex
        }
        if (reply.reply.isBlank()) { runCatching { e.setPresence(chat, "paused") }; throw IllegalStateException("A IA não escreveu nada") }
        sendReply(c, e, chat, name, reply, willSpeak = false) ?: throw IllegalStateException("Falha ao enviar pelo WhatsApp")
        Memory.add(this, key, "model", reply.reply)
        applyLead(key, reply, persona)
        scheduleFollowUp(key, reply, persona)
        Store.log(this, "🧭 Resposta orientada para $name (${persona.label}): ${reply.reply.take(200)}")
        return reply.reply
    }

    fun dropPending(key: String) {
        timers.remove(key)?.cancel()
        pending.remove(key)
    }

    /** Confere os Pix em aberto a cada 20 s (no lugar do webhook) enquanto houver pedido aberto. */
    @Synchronized
    private fun ensurePoller() {
        if (poller?.isActive == true || !Sales.hasOpenOrders(this)) return
        poller = scope.launch {
            while (Sales.hasOpenOrders(this@AtendenteService)) {
                runCatching { Sales.pollOnce(this@AtendenteService, { engines[it] }, ::alert) }
                delay(20_000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        Brain.verifiedHook = { Store.setAiVerified(applicationContext) }
        Brain.lessonsFor = { p -> Memory.lessons(applicationContext, p).map { it.text } }
        val action = intent?.action ?: ACTION_START
        val conn = intent?.getIntExtra("conn", 1)?.coerceIn(1, 3) ?: 1
        goForeground("Ligando o WhatsApp…")
        val sp = getSharedPreferences("atendente_service", MODE_PRIVATE)
        when (action) {
            ACTION_STOP -> {
                sp.edit().putBoolean("running", false).apply()
                stopEngines()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAIR -> {
                ensureEngines()
                val phone = intent?.getStringExtra("phone").orEmpty()
                scope.launch {
                    try {
                        engines[conn]?.startPairing(phone)
                    } catch (e: Exception) {
                        updateNotification("Não foi possível gerar o código: ${e.message}")
                        Store.log(this@AtendenteService, "⚠️ Conectar número $conn: ${e.message}")
                    }
                }
            }
            ACTION_LOGOUT -> scope.launch { runCatching { engines[conn]?.logout() } }
            else -> {
                sp.edit().putBoolean("running", true).apply()
                ensureEngines()
            }
        }
        return START_STICKY
    }

    @Synchronized
    private fun ensureEngines() {
        val n = Store.load(this).numbers.coerceIn(1, 3)
        for (conn in 1..3) {
            if (conn > n) { engines.remove(conn)?.let { stopOne(conn, it) }; continue }
            if (engines.containsKey(conn)) continue
            val dir = File(filesDir, if (conn == 1) "whatsapp" else "whatsapp$conn").absolutePath
            val e = Waengine.newEngine(dir, "${Build.MANUFACTURER} ${Build.MODEL}")
            e.setListener(object : Listener {
                override fun onEvent(kind: String, payload: String) = this@AtendenteService.onEvent(conn, kind, payload)
            })
            try {
                e.start()
                engines[conn] = e
            } catch (ex: Exception) {
                updateNotification("Falha ao ligar o número $conn: ${ex.message}")
            }
        }
        ensurePoller() // retoma pedidos em aberto depois de reiniciar
        ensureFollowLoop()
    }

    private fun stopOne(conn: Int, e: Engine) {
        runCatching { lastStatus[conn] = e.statusJSON() }
        runCatching { e.stop() }
    }

    private fun stopEngines() {
        engines.keys.toList().forEach { conn -> engines.remove(conn)?.let { stopOne(conn, it) } }
    }

    override fun onDestroy() {
        stopEngines()
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------- Eventos do motor ----------------

    private fun onEvent(conn: Int, kind: String, payload: String) {
        val suffix = if (Store.load(this).numbers > 1) " (número $conn)" else ""
        when (kind) {
            "state" -> {
                val state = runCatching { JSONObject(payload).optString("state") }.getOrDefault(payload)
                updateNotification(textFor(state) + suffix)
                if (state == "banned" || state == "logged_out") alert("WhatsApp$suffix", textFor(state))
            }
            "pair_code" -> updateNotification("Código para conectar$suffix: $payload")
            "message" -> runCatching { onMessage(conn, JSONObject(payload)) }
            "message_old" -> runCatching { onOldMessage(conn, JSONObject(payload)) }
            "from_me" -> runCatching { onFromMe(conn, JSONObject(payload)) }
            "call" -> runCatching { onCall(conn, JSONObject(payload)) }
        }
    }

    /** Mensagem que chegou com o app desligado: entra na memória, sem resposta automática. */
    private fun onOldMessage(conn: Int, o: JSONObject) {
        val chat = o.optString("chat").ifBlank { return }
        val c = Store.load(this)
        val key = Memory.key(conn, chat)
        if (c.triggerEnabled && !Store.isActive(this, key)) return
        val name = o.optString("pushName").ifBlank { o.optString("phone") }
        Memory.ensure(this, conn, chat, name, o.optString("phone"), fromClient = true)
        Memory.add(this, key, "user", o.optString("text"), o.optString("kind"), o.optLong("timestamp", System.currentTimeMillis()))
    }

    private fun onFromMe(conn: Int, o: JSONObject) {
        val chat = o.optString("chat").ifBlank { return }
        val key = Memory.key(conn, chat)
        val c = Store.load(this)
        // Modo palavra-chave: suas conversas normais não são da conta do robô.
        if (c.triggerEnabled && !Store.isActive(this, key)) return
        val kind = o.optString("kind").ifBlank { "text" }
        val text = o.optString("text")
        val snippet = (if (kind == "text") "\"" + text.take(60) + (if (text.length > 60) "…\"" else "\"") else "[$kind]")
        // Mensagem automática do WhatsApp Business (saudação/ausência/resposta rápida
        // automática): sai do celular segundos depois do cliente escrever, ou é o mesmo
        // texto mandado em várias conversas. Não é você assumindo.
        val norm = text.lowercase().replace(Regex("\\s+"), " ").trim()
        val automatic = automaticOwnerText(key, kind, norm)
        Memory.ensure(this, conn, chat, "", "", fromClient = false)
        Memory.add(this, key, "owner", text, kind)
        if (automatic != null) {
            Store.log(this, "🤖 Mensagem automática do seu WhatsApp para ${Store.nameOf(this, key)} ($automatic): $snippet — o robô continua.")
            return
        }
        Memory.update(this, key) { put("followup_at", 0L); put("followup_step", 0) }
        if (c.pauseOnOwnerReply) {
            // Só ENVIAR mensagem conta como assumir; abrir ou ler a conversa não.
            val wasPaused = Store.isPaused(this, key)
            val minutes = c.ownerPauseMinutes.coerceIn(1, 1440)
            Store.pauseUntil(this, key, maxOf(Store.pausedMap(this)[key] ?: 0L, System.currentTimeMillis() + minutes * 60_000L))
            dropPending(key)
            if (!wasPaused) Store.log(this, "⏸️ Você mandou $snippet para ${Store.nameOf(this, key)} pelo WhatsApp (aparelho ${o.optString("device", "?")}): " +
                "o robô volta depois de $minutes min sem você responder.")
        }
    }

    /** Devolve o motivo se a mensagem "sua" parece automática; null se foi você mesmo. */
    private fun automaticOwnerText(key: String, kind: String, norm: String): String? {
        val now = System.currentTimeMillis()
        val since = now - (lastIncoming[key] ?: 0L)
        if (kind == "text" && since in 0..4_000) return "saiu ${since / 1000.0}s depois da mensagem do cliente"
        if (kind != "text" || norm.length < 25) return null
        val chats = ownerTexts.getOrPut(norm) { ConcurrentHashMap.newKeySet() }
        chats.add(key)
        if (ownerTexts.size > 300) ownerTexts.keys.take(100).forEach { ownerTexts.remove(it) }
        return if (chats.size >= 2) "mesmo texto já enviado em outra conversa" else null
    }

    private fun onCall(conn: Int, o: JSONObject) {
        val c = Store.load(this)
        if (!c.enabled || c.callPolicy == "off") return
        val from = o.optString("from")
        val phone = o.optString("phone")
        if (c.callPolicy == "unknown") {
            // Número oculto que não deu para descobrir: não recusa (pode ser um contato seu).
            if (phone.isBlank() || Contacts.isSaved(this, phone)) return
        }
        scope.launch {
            val e = engines[conn] ?: return@launch
            runCatching { e.rejectCall(from, o.optString("callId")) }
            val chat = o.optString("chat").ifBlank { from }
            if (c.callNotice.isNotBlank()) runCatching { e.sendText(chat, c.callNotice) }
            Store.log(this@AtendenteService, "📵 Chamada recusada de " + (if (phone.isNotBlank()) "+$phone" else Store.nameOf(this@AtendenteService, chat)) +
                (if (c.callPolicy == "unknown") " (fora da agenda)" else "") + (if (c.callNotice.isNotBlank()) "; aviso enviado." else "."))
        }
    }

    private fun allowedNumber(c: Config, phone: String): Boolean {
        fun list(s: String) = s.split(',', ';', '\n', ' ').map { it.filter(Char::isDigit) }.filter { it.length >= 8 }
        val only = list(c.onlyNumbers)
        val blocked = list(c.blockedNumbers)
        if (blocked.any { phone.isNotEmpty() && (phone.endsWith(it) || it.endsWith(phone)) }) return false
        if (only.isNotEmpty()) return only.any { phone.isNotEmpty() && (phone.endsWith(it) || it.endsWith(phone)) }
        return true
    }

    private fun onMessage(conn: Int, o: JSONObject) {
        val c = Store.load(this)
        val chat = o.optString("chat")
        if (chat.isBlank()) return
        val key = Memory.key(conn, chat)
        val phone = o.optString("phone")
        // Modo palavra-chave (WhatsApp pessoal): conversa que não ativou o atendente é
        // ignorada em silêncio — nada de nome, conteúdo ou registro.
        var justTriggered = false
        if (c.triggerEnabled && !Store.isActive(this, key)) {
            if (o.optBoolean("isGroup") || !Trigger.matches(o.optString("text"), c.triggerWords)) return
            justTriggered = true
        }
        if (o.optBoolean("isGroup") && c.ignoreGroups) return
        val name = o.optString("pushName").ifBlank { phone }
        lastIncoming[key] = System.currentTimeMillis()
        Store.rememberName(this, key, name)
        Store.count(this, "received")
        val conv = Memory.ensure(this, conn, chat, name, phone, fromClient = true)
        // Cliente respondeu: a sequência de follow-up para na hora.
        Memory.add(this, key, "user", o.optString("text"), o.optString("kind"))
        Memory.update(this, key) {
            put("followup_at", 0L); put("followup_step", 0)
            if (conv.stage == "perdido" && !conv.tagsManual) put("stage", "conversa")
        }
        if (Selling.isOptOut(o.optString("text"))) {
            Memory.update(this, key) { put("dnd", 1); put("stage", "naoperturbe") }
        }
        val who = Store.nameOf(this, key) + (if (phone.isNotBlank()) " (+$phone)" else " (número oculto)") +
            if (c.numbers > 1) " [número $conn]" else ""
        fun skip(reason: String) {
            if (!o.optBoolean("isGroup")) Store.log(this, "📩 $who: ignorada — $reason")
        }
        when {
            !c.enabled -> return skip("o atendimento automático está desligado (aba Início)")
            c.apiKey.isBlank() -> return skip("falta a chave do Gemini (aba Início)")
            Personas.active(this).none { it.connection == conn } -> return skip("nenhuma persona ativa usa o número $conn (aba Loja)")
            !allowedNumber(c, phone) -> return skip(
                if (phone.isBlank()) "o WhatsApp não informou o número e a lista \"Responder só estes números\" está preenchida"
                else "número fora de \"Responder só estes números\" ou dentro de \"Nunca responder\" (aba Loja)")
            Store.isPaused(this, key) -> return skip("conversa pausada (atendimento humano) até " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(Store.pausedMap(this)[key] ?: 0L)))
        }
        if (justTriggered) {
            Store.activate(this, key, c.triggerMinutes.coerceIn(1, 1440))
            Store.log(this, "🔑 $who escreveu a palavra-chave: atendente ativado por ${c.triggerMinutes} min nessa conversa.")
        }
        Store.log(this, "📩 $who: ${o.optString("text").ifBlank { "[" + o.optString("kind") + "]" }.take(100)} — respondendo em ${c.delaySeconds} s")

        o.put("_conn", conn)
        pending.getOrPut(key) { mutableListOf() }.add(o)
        // Espera alguns segundos: o cliente costuma mandar várias mensagens seguidas.
        timers.remove(key)?.cancel()
        timers[key] = scope.launch {
            delay(c.delaySeconds.coerceIn(2, 60) * 1000L)
            timers.remove(key)
            chatLocks.getOrPut(key) { Mutex() }.withLock { process(conn, key) }
        }
    }

    private suspend fun process(conn: Int, key: String) {
        val msgs = pending.remove(key)?.toList() ?: return
        if (msgs.isEmpty()) return
        val base = Store.load(this)
        val e = engines[conn] ?: return
        val chat = Memory.chatOf(key)
        val name = Store.nameOf(this, key)
        if (Store.isPaused(this, key)) return
        val conv = Memory.get(this, key) ?: return

        if (base.markRead) {
            msgs.forEach { m -> runCatching { e.markRead(chat, m.optString("sender"), m.optString("id")) } }
        }

        val texts = msgs.map { it.optString("text") }.filter { it.isNotBlank() }
        val pick = Personas.choose(this, base, conn, conv, texts.joinToString("\n")) ?: return
        val persona = Personas.load(this).first { it.id == pick.personaId }
        if (persona.id != conv.persona) {
            // Sem prazo: a persona fica com a conversa até o orquestrador mandar trocar.
            Memory.update(this, key) { put("persona", persona.id); put("persona_until", Long.MAX_VALUE) }
            if (Personas.active(this).count { it.connection == conn } > 1) {
                val verb = if (pick.switched) "passou para" else "→"
                Store.log(this, "🎭 $name $verb ${persona.label} (${pick.reason}).")
            }
        }
        val c = Personas.forReply(base, persona)

        // Pedido explícito de atendente: não precisa da IA (regra fixa, sem gastar a IA).
        if (texts.any { Brain.humanRequested(it) }) {
            handoff(c, e, key, name, c.handoffMessage, texts)
            return
        }

        val incoming = msgs.map { m -> toIncoming(c, e, m) }
        // As mensagens novas já estão na memória: o contexto vai até antes delas.
        val history = Memory.contextTurns(this, key).dropLast(msgs.size)
        val clientSentAudio = msgs.any { it.optString("kind") == "audio" }
        val willSpeak = c.activeVoice() != null && (c.voiceAlways || clientSentAudio)
        // Enquanto a IA pensa (e a voz é gerada), o cliente vê "digitando…" ou "gravando áudio…".
        runCatching { e.setPresence(chat, if (willSpeak) "recording" else "typing") }
        val reply = try {
            AiQueue.run(AiQueue.REPLY, c.apiKey, c.rpmLimit) {
                Brain.answer(c, name, history, incoming, Sales.promptSection(this), persona)
            }
        } catch (ex: Exception) {
            runCatching { e.setPresence(chat, "paused") }
            Store.setLastError(this, "Gemini: ${ex.message}")
            val attempt = retries.getOrDefault(key, 0)
            if (attempt < RETRY_DELAYS_MIN.size) {
                // Gemini sobrecarregado costuma voltar em minutos: guarda e tenta de novo.
                retries[key] = attempt + 1
                val wait = RETRY_DELAYS_MIN[attempt]
                pending.getOrPut(key) { mutableListOf() }.addAll(0, msgs)
                Store.log(this, "⏳ Gemini indisponível para $name (${ex.message}). Nova tentativa em $wait min.")
                timers[key]?.cancel()
                timers[key] = scope.launch {
                    delay(wait * 60_000L)
                    timers.remove(key)
                    chatLocks.getOrPut(key) { Mutex() }.withLock { process(conn, key) }
                }
            } else {
                retries.remove(key)
                Store.log(this, "⚠️ Não respondi $name depois de ${RETRY_DELAYS_MIN.size + 1} tentativas: ${ex.message}")
                alert("Atendente não respondeu $name", "Responda pelo WhatsApp. ${ex.message ?: ""}".take(200))
            }
            return
        }
        retries.remove(key)

        // Áudio/foto: guarda o que a IA entendeu no lugar do "[áudio]".
        if (reply.understood.isNotBlank() && msgs.any { it.optString("kind") in setOf("audio", "image") }) {
            Memory.add(this, key, "user", "[o que o cliente mandou em ${if (clientSentAudio) "áudio" else "foto"}] ${reply.understood}", "note")
        }
        applyLead(key, reply, persona)

        if (reply.handoff) {
            handoff(c, e, key, name, reply.reply.ifBlank { c.handoffMessage }, texts)
            return
        }

        var asVoice = false
        if (reply.reply.isBlank()) runCatching { e.setPresence(chat, "paused") }
        val sent = sendReply(c, e, chat, name, reply, willSpeak) ?: return
        asVoice = sent.first
        val sentPhotos = sent.second
        // Venda digital: quem cobra e entrega é o app, nunca a IA.
        if (Sales.charging(this)) {
            if (reply.resendDelivery) {
                Sales.resend(this, e, key, reply.checkoutEmail.ifBlank { null }, ::alert)?.let { Store.log(this, it) }
            } else if (reply.checkoutProductId.isNotBlank() && reply.checkoutEmail.isNotBlank()) {
                Store.log(this, Sales.checkout(this, e, key, reply.checkoutProductId, reply.checkoutEmail, ::alert))
                Memory.update(this, key) { put("stage", "pagamento"); put("temperature", "quente") }
            }
            ensurePoller()
        }
        if (c.triggerEnabled) Store.activate(this, key, c.triggerMinutes.coerceIn(1, 1440)) // renova a janela
        if (reply.reply.isNotBlank() || sentPhotos.isNotEmpty()) Store.count(this, "answered")
        val modelText = reply.reply + if (sentPhotos.isNotEmpty()) "\n[fotos enviadas: ${sentPhotos.joinToString()}]" else ""
        Memory.add(this, key, "model", modelText)
        scheduleFollowUp(key, reply, persona)
        maybeSummarize(c, key)
        val heard = texts.joinToString(" / ").ifBlank { reply.understood.ifBlank { msgs.first().optString("kind") } }
        Store.log(this, "💬 $name: ${heard.take(120)}\n${if (asVoice) "🔊" else "🤖"} ${reply.reply.take(200)}\n(${persona.label} · ${Brain.lastProvider})" +
            if (sentPhotos.isNotEmpty()) "\n📷 ${sentPhotos.joinToString()}" else "")
    }

    /**
     * Envia a resposta como uma pessoa: texto primeiro (fracionado), depois de
     * alguns segundos os links e as fotos. Devolve (foi em áudio, fotos enviadas)
     * ou null se o envio falhou.
     */
    private suspend fun sendReply(c: Config, e: Engine, chat: String, name: String, reply: BotReply, willSpeak: Boolean): Pair<Boolean, List<String>>? {
        val gap = c.mediaGapSeconds.coerceIn(1, 30) * 1000L
        var asVoice = false
        if (reply.reply.isNotBlank()) {
            if (willSpeak) asVoice = sendVoiceReply(c, e, chat, name, reply.reply)
            if (!asVoice) {
                try {
                    if (c.humanize) {
                        val (texts, links) = Humanizer.splitLinks(reply.parts.ifEmpty { listOf(reply.reply) })
                        // 1ª mensagem logo (o "digitando…" já apareceu enquanto a IA pensava);
                        // as seguintes no intervalo configurado, cada uma com seu "digitando…".
                        texts.forEachIndexed { i, part ->
                            e.sendTextTyping(chat, part, if (i == 0) 1500L else c.partGapSeconds.coerceIn(1, 30) * 1000L)
                        }
                        links.forEach { link -> e.sendTextTyping(chat, link, gap) }
                    } else {
                        e.sendText(chat, reply.reply)
                    }
                } catch (ex: Exception) {
                    Store.log(this, "⚠️ Falha ao enviar para $name: ${ex.message}")
                    Store.setLastError(this, "WhatsApp: ${ex.message}")
                    return null
                }
            }
        }
        val sentPhotos = mutableListOf<String>()
        for (p in reply.photos) {
            delay(if (c.humanize) gap else 1500)
            val f = File(Store.photosDir(this), p.file)
            try {
                e.sendImage(chat, f.absolutePath, "")
                sentPhotos += p.id
            } catch (ex: Exception) {
                Store.log(this, "⚠️ Foto ${p.id} não enviada para $name: ${ex.message}")
            }
        }
        for (p in reply.sitePhotos) {
            delay(if (c.humanize) gap else 1500)
            val f = File(cacheDir, "site-${p.code}.img")
            try {
                downloadImage(p.url, f)
                e.sendImage(chat, f.absolutePath, p.caption)
                sentPhotos += p.code
            } catch (ex: Exception) {
                // Sem foto, manda o link da imagem (fallback).
                runCatching { e.sendText(chat, "📷 ${p.caption}: ${p.url}") }
                Store.log(this, "⚠️ Foto do site ${p.code} não enviada para $name: ${ex.message}")
            } finally {
                f.delete()
            }
        }
        return asVoice to sentPhotos
    }

    // ---------------- Funil, follow-up e resumo ----------------

    private fun applyLead(key: String, r: BotReply, p: Persona) {
        val conv = Memory.get(this, key) ?: return
        Memory.update(this, key) {
            if (!conv.tagsManual) {
                if (r.temperature.isNotBlank()) put("temperature", r.temperature)
                // Venda concluída não volta para trás pela IA.
                if (r.stage.isNotBlank() && !(conv.stage == "comprou" && r.stage in setOf("novo", "conversa", "interessado", "pagamento"))) put("stage", r.stage)
                if (r.objection.isNotBlank() || r.stage == "comprou") put("objection", r.objection)
            }
            if (r.optOut) { put("dnd", 1); put("stage", "naoperturbe") }
        }
    }

    private fun scheduleFollowUp(key: String, r: BotReply, p: Persona) {
        val conv = Memory.get(this, key) ?: return
        val combined = Selling.parseWhen(r.followUpAt)
        val at: Long
        val kind: String
        when {
            conv.dnd || !p.followUp || !conv.firstFromClient -> { Memory.update(this, key) { put("followup_at", 0L) }; return }
            combined != null && combined > System.currentTimeMillis() -> { at = combined; kind = "combinado" }
            r.awaitingReply && !Funnel.isClosed(conv.stage) ->
                { at = Selling.fitWindow(System.currentTimeMillis() + p.followFirstMinutes * 60_000L, p); kind = "" }
            else -> { Memory.update(this, key) { put("followup_at", 0L) }; return }
        }
        Memory.update(this, key) { put("followup_at", at); put("followup_step", 0); put("followup_kind", kind) }
    }

    @Synchronized
    private fun ensureFollowLoop() {
        if (followLoop?.isActive == true) return
        followLoop = scope.launch {
            while (true) {
                delay(60_000)
                runCatching { runFollowUps() }.onFailure { Store.log(this@AtendenteService, "⚠️ Follow-up: ${it.message}") }
            }
        }
    }

    private suspend fun runFollowUps() {
        val base = Store.load(this)
        if (!base.enabled || base.apiKey.isBlank()) return
        val now = System.currentTimeMillis()
        val personas = Personas.active(this)
        for (conv in Memory.dueFollowUps(this, now)) {
            val key = conv.key
            val p = personas.firstOrNull { it.id == conv.persona && it.followUp && it.connection == conv.conn }
            if (p == null || conv.dnd || Funnel.isClosed(conv.stage) || conv.lastRole == "user") {
                Memory.update(this, key) { put("followup_at", 0L) }; continue
            }
            val paused = Store.pausedMap(this)[key]
            if (paused != null) { Memory.update(this, key) { put("followup_at", Selling.fitWindow(paused + 60_000, p)) }; continue }
            val inWindow = Selling.fitWindow(now, p)
            if (inWindow - now > 7 * 60_000) { Memory.update(this, key) { put("followup_at", inWindow) }; continue }
            // Proteção contra bloqueio: limite por hora e intervalo entre os envios.
            val sent = followSent.getOrPut(conv.conn) { mutableListOf() }
            synchronized(sent) { sent.removeAll { now - it > 3_600_000 } }
            if (sent.size >= base.followMaxPerHour || (sent.maxOrNull() ?: 0L) > now - 20_000) continue
            val e = engines[conv.conn] ?: continue
            chatLocks.getOrPut(key) { Mutex() }.withLock { sendFollowUp(base, p, e, conv) }
            synchronized(sent) { sent += System.currentTimeMillis() }
        }
    }

    private suspend fun sendFollowUp(base: Config, p: Persona, e: Engine, conv: Conversation) {
        val key = conv.key
        val fresh = Memory.get(this, key) ?: return
        if (fresh.followupAt == 0L || fresh.lastRole == "user") return
        val c = Personas.forReply(base, p)
        val steps = Selling.cadence(fresh.stage, p.followFirstMinutes)
        val step = fresh.followupStep.coerceAtMost(steps.lastIndex)
        val silent = Selling.humanDuration(System.currentTimeMillis() - maxOf(fresh.lastOut, fresh.lastIn))
        val internal = Selling.instruction(step, steps.size, fresh.stage, fresh.objection, fresh.followupKind, silent)
        val name = Store.nameOf(this, key)
        val chat = Memory.chatOf(key)
        runCatching { e.setPresence(chat, "typing") }
        val reply = try {
            AiQueue.run(AiQueue.FOLLOWUP, c.apiKey, c.rpmLimit) {
                Brain.answer(c, name, Memory.contextTurns(this, key), emptyList(), Sales.promptSection(this), p, internal)
            }
        } catch (ex: Exception) {
            runCatching { e.setPresence(chat, "paused") }
            Memory.update(this, key) { put("followup_at", System.currentTimeMillis() + 15 * 60_000L) }
            Store.log(this, "⏳ Follow-up de $name adiado 15 min: ${ex.message}")
            return
        }
        if (reply.reply.isBlank() || reply.handoff) {
            runCatching { e.setPresence(chat, "paused") }
            Memory.update(this, key) { put("followup_at", 0L) }
            return
        }
        sendReply(c, e, chat, name, reply, willSpeak = false) ?: return
        Memory.add(this, key, "model", reply.reply)
        applyLead(key, reply, p)
        val next = step + 1
        Memory.update(this, key) {
            put("followup_kind", "")
            if (next < steps.size) {
                put("followup_step", next)
                put("followup_at", Selling.fitWindow(System.currentTimeMillis() + steps[next] * 60_000L, p))
            } else {
                // Acabou a sequência sem resposta.
                put("followup_at", 0L); put("followup_step", 0)
                if (!fresh.tagsManual) { put("temperature", "frio"); if (Funnel.isLate(fresh.stage)) put("stage", "perdido") }
            }
        }
        Store.log(this, "🔁 Follow-up ${step + 1}/${steps.size} para $name (${p.label}): ${reply.reply.take(160)}")
    }

    /** Atualiza o resumo da conversa em segundo plano quando há mensagens antigas fora dele. */
    private fun maybeSummarize(c: Config, key: String) {
        val old = Memory.pendingForSummary(this, key)
        if (old.size < 10 || !summarizing.add(key)) return
        scope.launch {
            try {
                val conv = Memory.get(this@AtendenteService, key) ?: return@launch
                val text = AiQueue.run(AiQueue.BACKGROUND, c.apiKey, c.rpmLimit) { Brain.summarize(c, conv.summary, old) }
                if (text.isNotBlank()) Memory.setSummary(this@AtendenteService, key, text, old.last().id)
            } catch (_: Exception) {
            } finally {
                summarizing.remove(key)
            }
        }
    }

    /**
     * Fala a resposta com a voz escolhida e envia como mensagem de voz.
     * Links vão em seguida por texto (não dá para clicar em áudio).
     * Devolve false se não deu certo, para o texto ser enviado no lugar.
     */
    private suspend fun sendVoiceReply(c: Config, e: Engine, chat: String, name: String, text: String): Boolean {
        val voice = c.activeVoice() ?: return false
        val spoken = Voice.cleanForSpeech(text)
        if (spoken.isBlank() || spoken.length > 900 || !Voice.canEncodeVoiceNote) return false
        val ogg = File(cacheDir, "voz-${System.currentTimeMillis()}.ogg")
        return try {
            val pcm = Voice.synthesize(c.apiKey, c.ttsModel, voice, spoken, c.voiceStyle)
            Voice.pcmToOggOpus(pcm, ogg)
            e.sendVoice(chat, ogg.absolutePath, Voice.seconds(pcm).toLong())
            val links = Voice.links(text)
            if (links.isNotEmpty()) {
                delay(c.mediaGapSeconds.coerceIn(1, 30) * 1000L)
                runCatching { e.sendText(chat, links.joinToString("\n")) }
            }
            true
        } catch (ex: Exception) {
            Store.log(this, "⚠️ Áudio para $name não saiu (${ex.message}); mandei em texto.")
            false
        } finally {
            ogg.delete()
        }
    }

    /** Baixa uma foto pública do site (até 8 MB). */
    private fun downloadImage(url: String, out: File) {
        WebPageReader.assertPublicUrl(url)
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "AtendenteIA/0.1 (Android)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > 8 * 1024 * 1024) throw IllegalStateException("foto grande demais")
            out.writeBytes(bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun toIncoming(c: Config, e: Engine, m: JSONObject): Incoming {
        val kind = m.optString("kind")
        val text = m.optString("text")
        val wantsMedia = (kind == "audio" && c.understandAudio) || (kind == "image" && c.understandImages)
        if (wantsMedia) {
            try {
                val path = e.downloadMedia(m.optString("id"))
                val f = File(path)
                val bytes = f.readBytes()
                f.delete()
                val mime = m.optString("mime").ifBlank { if (kind == "audio") "audio/ogg" else "image/jpeg" }
                return Incoming(text, bytes, mime, kind)
            } catch (ex: Exception) {
                Store.log(this, "⚠️ Não consegui baixar o $kind: ${ex.message}")
            }
        }
        val placeholder = when (kind) {
            "audio" -> "[o cliente mandou um áudio que não pôde ser ouvido; peça gentilmente para escrever]"
            "image" -> "[o cliente mandou uma foto] $text"
            "video" -> "[o cliente mandou um vídeo] $text"
            "document" -> "[o cliente mandou um documento: $text]"
            "sticker" -> "[o cliente mandou uma figurinha]"
            "location" -> "[o cliente mandou a localização: $text]"
            "contact" -> "[o cliente mandou um contato: $text]"
            else -> text
        }
        return Incoming(placeholder.trim(), kind = kind)
    }

    private fun handoff(c: Config, e: Engine, key: String, name: String, message: String, texts: List<String>) {
        if (message.isNotBlank()) {
            runCatching { e.sendText(Memory.chatOf(key), message) }
            Memory.add(this, key, "model", message)
        }
        Store.pause(this, key, c.pauseHours)
        Memory.update(this, key) { put("followup_at", 0L) }
        Store.log(this, "🙋 $name pediu atendimento humano. Robô pausado por ${c.pauseHours} h nessa conversa.")
        alert("$name precisa de atendimento", texts.lastOrNull()?.take(120) ?: "Abra o WhatsApp para continuar.")
    }

    // ---------------- Notificações ----------------

    private fun textFor(state: String) = when (state) {
        "connected" -> "Atendente ligado e respondendo"
        "needs_pairing" -> "Toque para conectar o número"
        "pairing" -> "Digite o código no WhatsApp"
        "disconnected" -> "Sem conexão com o WhatsApp, tentando de novo…"
        "logged_out" -> "Número desconectado. Toque para conectar de novo"
        "banned" -> "O WhatsApp bloqueou o número temporariamente"
        else -> "Ligando o WhatsApp…"
    }

    private fun channels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atendente ligado", NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Avisos do atendente", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        channels(nm)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun alert(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        channels(nm)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ALERT) else Notification.Builder(this)
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 5000, b.setContentTitle(title).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat).setAutoCancel(true).setContentIntent(openAppIntent()).build())
    }
}
