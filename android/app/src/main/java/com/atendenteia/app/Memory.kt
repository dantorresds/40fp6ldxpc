package com.atendenteia.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Etapas do funil (valor guardado → nome mostrado). */
object Funnel {
    val STAGES = linkedMapOf(
        "novo" to "Novo contato", "conversa" to "Em conversa", "interessado" to "Interessado",
        "pagamento" to "Pagamento pendente", "comprou" to "Comprou", "posvenda" to "Pós-venda",
        "perdido" to "Perdido", "naoperturbe" to "Não perturbar",
    )
    val TEMPS = linkedMapOf("frio" to "🔵 Frio", "morno" to "🟡 Morno", "quente" to "🔴 Quente")
    val OBJECTIONS = linkedMapOf(
        "preco" to "Preço", "confianca" to "Confiança", "momento" to "Momento", "comparando" to "Comparando",
        "consultar" to "Consultar alguém", "duvida" to "Dúvida sobre o produto", "outra" to "Outra",
    )
    /** Fim do funil: cadência longa de recuperação. */
    fun isLate(stage: String) = stage == "interessado" || stage == "pagamento"
    fun isClosed(stage: String) = stage in setOf("comprou", "posvenda", "perdido", "naoperturbe")
}

data class Conversation(
    val key: String, val conn: Int, val chat: String, val name: String, val phone: String,
    val persona: Int, val personaUntil: Long,
    val temperature: String, val stage: String, val objection: String, val tagsManual: Boolean,
    val summary: String, val summaryUpto: Long,
    val lastIn: Long, val lastOut: Long, val lastText: String, val lastRole: String,
    val followupAt: Long, val followupStep: Int, val followupKind: String, val dnd: Boolean,
    val firstFromClient: Boolean, val updated: Long,
)

data class StoredMessage(val id: Long, val ts: Long, val role: String, val text: String, val kind: String)

/**
 * Memória das conversas no próprio celular (SQLite): mensagens por número,
 * resumo ("marco"), persona presa, etiquetas do funil e follow-up agendado.
 */
object Memory {
    /** Chave da conversa: o chat puro na conexão 1 (compatível com as versões antigas). */
    fun key(conn: Int, chat: String) = if (conn <= 1) chat else "$conn:$chat"
    fun connOf(key: String) = if (key.length > 2 && key[1] == ':' && key[0] in '2'..'3') key[0] - '0' else 1
    fun chatOf(key: String) = if (connOf(key) > 1) key.substring(2) else key

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "memoria.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE conversations(key TEXT PRIMARY KEY, conn INTEGER, chat TEXT, name TEXT, phone TEXT,
                persona INTEGER DEFAULT 0, persona_until INTEGER DEFAULT 0, temperature TEXT DEFAULT '', stage TEXT DEFAULT 'novo',
                objection TEXT DEFAULT '', tags_manual INTEGER DEFAULT 0, summary TEXT DEFAULT '', summary_upto INTEGER DEFAULT 0,
                last_in INTEGER DEFAULT 0, last_out INTEGER DEFAULT 0, last_text TEXT DEFAULT '', last_role TEXT DEFAULT '',
                followup_at INTEGER DEFAULT 0, followup_step INTEGER DEFAULT 0, followup_kind TEXT DEFAULT '', dnd INTEGER DEFAULT 0,
                first_from_client INTEGER DEFAULT 0, updated INTEGER DEFAULT 0)""")
            db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT, ts INTEGER, role TEXT, text TEXT, kind TEXT)")
            db.execSQL("CREATE INDEX messages_key ON messages(key, id)")
            db.execSQL("CREATE INDEX conv_updated ON conversations(updated)")
            createLessons(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) createLessons(db)
        }
        private fun createLessons(db: SQLiteDatabase) =
            db.execSQL("CREATE TABLE IF NOT EXISTS lessons(id INTEGER PRIMARY KEY AUTOINCREMENT, persona INTEGER, text TEXT, created INTEGER, source TEXT)")
    }

    @Volatile private var db: Db? = null
    private fun w(ctx: Context): SQLiteDatabase =
        (db ?: synchronized(this) { db ?: Db(ctx.applicationContext).also { db = it } }).writableDatabase

    private fun Cursor.conv() = Conversation(
        getString(0), getInt(1), getString(2), getString(3) ?: "", getString(4) ?: "", getInt(5), getLong(6),
        getString(7) ?: "", getString(8) ?: "novo", getString(9) ?: "", getInt(10) == 1, getString(11) ?: "", getLong(12),
        getLong(13), getLong(14), getString(15) ?: "", getString(16) ?: "", getLong(17), getInt(18), getString(19) ?: "",
        getInt(20) == 1, getInt(21) == 1, getLong(22),
    )
    private const val COLS = "key,conn,chat,name,phone,persona,persona_until,temperature,stage,objection,tags_manual,summary," +
        "summary_upto,last_in,last_out,last_text,last_role,followup_at,followup_step,followup_kind,dnd,first_from_client,updated"

    fun get(ctx: Context, key: String): Conversation? =
        w(ctx).rawQuery("SELECT $COLS FROM conversations WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.conv() else null }

    fun list(ctx: Context, limit: Int = 500): List<Conversation> =
        w(ctx).rawQuery("SELECT $COLS FROM conversations ORDER BY updated DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(c.conv()) }
        }

    fun dueFollowUps(ctx: Context, now: Long): List<Conversation> =
        w(ctx).rawQuery("SELECT $COLS FROM conversations WHERE followup_at>0 AND followup_at<=? AND dnd=0 ORDER BY followup_at LIMIT 50",
            arrayOf(now.toString())).use { c -> buildList { while (c.moveToNext()) add(c.conv()) } }

    /** Cria a conversa se não existir. */
    @Synchronized
    fun ensure(ctx: Context, conn: Int, chat: String, name: String, phone: String, fromClient: Boolean): Conversation {
        val key = key(conn, chat)
        get(ctx, key)?.let { c ->
            if ((name.isNotBlank() && name != c.name) || (phone.isNotBlank() && phone != c.phone)) {
                update(ctx, key) { if (name.isNotBlank()) put("name", name); if (phone.isNotBlank()) put("phone", phone) }
            }
            return get(ctx, key)!!
        }
        w(ctx).insert("conversations", null, ContentValues().apply {
            put("key", key); put("conn", conn); put("chat", chat); put("name", name); put("phone", phone)
            put("first_from_client", if (fromClient) 1 else 0); put("updated", System.currentTimeMillis())
        })
        importOldHistory(ctx, key, chat)
        return get(ctx, key)!!
    }

    fun update(ctx: Context, key: String, fill: ContentValues.() -> Unit) {
        val v = ContentValues().apply(fill)
        if (v.size() > 0) w(ctx).update("conversations", v, "key=?", arrayOf(key))
    }

    /** Guarda uma mensagem (role: user, model ou owner). Devolve o id. */
    fun add(ctx: Context, key: String, role: String, text: String, kind: String = "text", ts: Long = System.currentTimeMillis()): Long {
        if (text.isBlank() && kind == "text") return 0
        val id = w(ctx).insert("messages", null, ContentValues().apply {
            put("key", key); put("ts", ts); put("role", role); put("text", text); put("kind", kind)
        })
        update(ctx, key) {
            put("updated", maxOf(ts, System.currentTimeMillis() - 1000)); put("last_text", text.ifBlank { "[$kind]" }.take(200)); put("last_role", role)
            if (role == "user") put("last_in", ts) else put("last_out", ts)
        }
        return id
    }

    fun messages(ctx: Context, key: String, limit: Int = 200, afterId: Long = 0): List<StoredMessage> =
        w(ctx).rawQuery("SELECT id,ts,role,text,kind FROM messages WHERE key=? AND id>? ORDER BY id DESC LIMIT $limit",
            arrayOf(key, afterId.toString())).use { c ->
            buildList { while (c.moveToNext()) add(StoredMessage(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3) ?: "", c.getString(4) ?: "")) }
        }.reversed()

    fun countAfter(ctx: Context, key: String, afterId: Long): Int =
        w(ctx).rawQuery("SELECT COUNT(*) FROM messages WHERE key=? AND id>?", arrayOf(key, afterId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Contexto para a IA: o resumo (se houver) e as mensagens depois dele
     * (as últimas 20; até 40 enquanto o resumo está atrasado).
     */
    fun contextTurns(ctx: Context, key: String): List<Turn> {
        val conv = get(ctx, key) ?: return emptyList()
        val after = messages(ctx, key, 40, conv.summaryUpto)
        val recent = if (conv.summary.isBlank()) after.takeLast(20) else after
        val out = mutableListOf<Turn>()
        if (conv.summary.isNotBlank()) out += Turn("user", "[Resumo do que já aconteceu nesta conversa (só para você, o cliente não vê)]\n${conv.summary}")
        recent.forEach { m ->
            when (m.role) {
                "owner" -> out += Turn("model", "[resposta da equipe] ${m.text}")
                "model" -> out += Turn("model", m.text)
                else -> out += Turn("user", m.text.ifBlank { "[${m.kind}]" })
            }
        }
        return out
    }

    /** Mensagens antigas que ainda não entraram no resumo (fora das últimas 20). */
    fun pendingForSummary(ctx: Context, key: String): List<StoredMessage> {
        val conv = get(ctx, key) ?: return emptyList()
        val after = messages(ctx, key, 400, conv.summaryUpto)
        return if (after.size <= 20) emptyList() else after.dropLast(20)
    }

    fun setSummary(ctx: Context, key: String, summary: String, uptoId: Long) =
        update(ctx, key) { put("summary", summary.take(4000)); put("summary_upto", uptoId) }

    /**
     * Procura clientes pelo nome ou por parte do número (mín. 3 dígitos),
     * ignorando acentos e maiúsculas. Mais recentes primeiro.
     */
    fun search(ctx: Context, query: String, limit: Int = 20): List<Conversation> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val digits = q.filter(Char::isDigit)
        val words = norm(q).split(Regex("\\s+")).filter { it.isNotBlank() && it.any(Char::isLetter) }
        return list(ctx, 2000).filter { c ->
            (digits.length >= 3 && (c.phone.contains(digits) || c.chat.filter(Char::isDigit).contains(digits))) ||
                (words.isNotEmpty() && words.all { norm(c.name).contains(it) })
        }.take(limit)
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase()

    // ---------------- Aprendizados (orientações do dono) ----------------

    data class Lesson(val id: Long, val persona: Int, val text: String, val created: Long)

    fun addLesson(ctx: Context, persona: Int, text: String, source: String = "") {
        if (text.isBlank()) return
        w(ctx).insert("lessons", null, ContentValues().apply {
            put("persona", persona); put("text", text.trim().take(600)); put("created", System.currentTimeMillis()); put("source", source)
        })
    }

    /** Aprendizados da persona (0 = vale para todas), do mais antigo ao mais novo. */
    fun lessons(ctx: Context, persona: Int): List<Lesson> =
        w(ctx).rawQuery("SELECT id,persona,text,created FROM lessons WHERE persona=? OR persona=0 ORDER BY id",
            arrayOf(persona.toString())).use { c ->
            buildList { while (c.moveToNext()) add(Lesson(c.getLong(0), c.getInt(1), c.getString(2) ?: "", c.getLong(3))) }
        }

    fun deleteLesson(ctx: Context, id: Long) { w(ctx).delete("lessons", "id=?", arrayOf(id.toString())) }

    fun deleteAll(ctx: Context) {
        w(ctx).execSQL("DELETE FROM messages"); w(ctx).execSQL("DELETE FROM conversations")
    }

    /** Traz os 30 turnos guardados pelas versões antigas (pasta history), uma vez. */
    private fun importOldHistory(ctx: Context, key: String, chat: String) {
        runCatching {
            val hash = MessageDigest.getInstance("SHA-1").digest(chat.toByteArray()).joinToString("") { "%02x".format(it) }
            val f = File(File(ctx.filesDir, "history"), "$hash.json")
            if (!f.exists() || key != chat) return
            val a = JSONArray(f.readText())
            val base = System.currentTimeMillis() - a.length() * 1000L
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                add(ctx, key, if (o.optString("r") == "model") "model" else "user", o.optString("t"), ts = base + i * 1000L)
            }
            f.delete()
        }
    }
}
