package com.atendenteia.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Aba Conversas: tudo o que o app guardou (memória SQLite), com etiquetas do
 * funil, filtros e envio manual (enviar daqui = assumir a conversa).
 */
@Composable
fun ConversationsScreen(resumeTick: Int) {
    var open by remember { mutableStateOf<String?>(null) }
    val key = open
    if (key == null) ConversationList(resumeTick) { open = it }
    else ConversationDetail(key) { open = null }
}

private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private val fmtShort = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

@Composable
private fun ConversationList(resumeTick: Int, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(4000); tick++ } }
    val all = remember(tick, resumeTick) { runCatching { Memory.list(ctx) }.getOrDefault(emptyList()) }
    val paused = remember(tick, resumeTick) { Store.pausedMap(ctx) }
    val personas = remember(resumeTick) { Personas.load(ctx) }
    val numbers = remember(resumeTick) { Store.load(ctx).numbers }

    var query by remember { mutableStateOf("") }
    var temp by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf("") }
    var objection by remember { mutableStateOf("") }
    var special by remember { mutableStateOf("") }
    var conn by remember { mutableStateOf(0) }

    val now = System.currentTimeMillis()
    val shown = all.filter { c ->
        (query.isBlank() || norm(c.name + " " + c.phone + " " + c.lastText).contains(norm(query))) &&
            (temp.isBlank() || c.temperature == temp) &&
            (stage.isBlank() || c.stage == stage || (stage == "naoperturbe" && c.dnd)) &&
            (objection.isBlank() || c.objection == objection) &&
            (conn == 0 || c.conn == conn) &&
            when (special) {
                "voce" -> c.lastRole == "user" && (paused[c.key] ?: 0L) > now
                "agendado" -> c.followupAt > 0 && !c.dnd
                "pausadas" -> (paused[c.key] ?: 0L) > now
                else -> true
            }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
            placeholder = { Text("Buscar nome, número ou mensagem") })
        ChipRow(listOf("" to "Todas", "voce" to "Aguardando você", "agendado" to "Retomada agendada", "pausadas" to "Pausadas"), special) { special = it }
        ChipRow(listOf("" to "Temperatura") + Funnel.TEMPS.toList(), temp) { temp = it }
        ChipRow(listOf("" to "Etapa") + Funnel.STAGES.toList(), stage) { stage = it }
        ChipRow(listOf("" to "Objeção") + Funnel.OBJECTIONS.toList(), objection) { objection = it }
        if (numbers > 1) ChipRow(listOf("0" to "Todos os números") + (1..numbers).map { "$it" to "Número $it" }, conn.toString()) { conn = it.toInt() }
        Text("${shown.size} de ${all.size} conversas", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
        if (all.isEmpty()) EmptyState(Icons.Default.Email, "Ainda não há conversas guardadas. Elas aparecem aqui assim que chegam mensagens.")
        LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { it.key }) { c ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(c.key) }.padding(vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name.ifBlank { c.phone.ifBlank { "Sem nome" } }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtShort.format(Date(c.updated)), style = MaterialTheme.typography.labelSmall)
                    }
                    Text((if (c.lastRole == "user") "" else "↪ ") + c.lastText, style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val tags = buildList {
                        Funnel.TEMPS[c.temperature]?.let { add(it) }
                        Funnel.STAGES[c.stage]?.let { add(it) }
                        Funnel.OBJECTIONS[c.objection]?.let { add("objeção: $it") }
                        if (c.dnd) add("🚫 não perturbar")
                        if ((paused[c.key] ?: 0L) > now) add("⏸️ pausada")
                        if (c.followupAt > 0 && !c.dnd) add("⏰ " + fmtShort.format(Date(c.followupAt)))
                        personas.firstOrNull { it.id == c.persona }?.let { if (personas.count { p -> p.active } > 1) add(it.label) }
                        if (numbers > 1) add("nº ${c.conn}")
                    }
                    if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Divider()
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ChipRow(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = selected == v, onClick = { onPick(if (selected == v && v != "" && v != "0") "" else v) },
                label = { Text(label, maxLines = 1) })
        }
    }
}

@Composable
internal fun ConversationDetail(key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val conv = remember(tick) { Memory.get(ctx, key) }
    val msgs = remember(tick) { Memory.messages(ctx, key, 300) }
    val pausedUntil = remember(tick) { Store.pausedMap(ctx)[key] ?: 0L }
    val personas = remember { Personas.load(ctx) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showTags by remember { mutableStateOf(false) }
    var guideOpen by remember { mutableStateOf(false) }
    var guideBusy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.size - 1) }

    if (conv == null) { onBack(); return }
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar") }
            Column(Modifier.weight(1f)) {
                Text(conv.name.ifBlank { "Sem nome" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(listOfNotNull(conv.phone.takeIf { it.isNotBlank() }?.let { "+$it" },
                    personas.firstOrNull { it.id == conv.persona }?.label).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { showTags = !showTags }) { Text(if (showTags) "Fechar" else "Etiquetas") }
        }
        Text(listOfNotNull(Funnel.TEMPS[conv.temperature], Funnel.STAGES[conv.stage], Funnel.OBJECTIONS[conv.objection]?.let { "objeção: $it" },
            if (conv.tagsManual) "etiquetas suas" else "etiquetas da IA").joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (pausedUntil > now) "⏸️ Robô pausado até ${fmtShort.format(Date(pausedUntil))}" else "🤖 Robô respondendo",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (pausedUntil > now) TextButton(onClick = { Store.resume(ctx, key); tick++ }) { Text("Voltar o robô") }
            else TextButton(onClick = { Store.pause(ctx, key, Store.load(ctx).pauseHours); AtendenteService.dropPendingFor(key); tick++ }) { Text("Assumir conversa") }
        }
        Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (guideBusy) "🧭 Trabalhando na orientação…" else info, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(enabled = !guideBusy, onClick = { guideOpen = true }) { Text("🧭 Orientar a IA") }
        }
        if (conv.followupAt > 0 && !conv.dnd) Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⏰ Retomada nº ${conv.followupStep + 1} em ${fmtShort.format(Date(conv.followupAt))}", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { Memory.update(ctx, key) { put("followup_at", 0L) }; tick++ }) { Text("Cancelar") }
        }

        if (showTags) TagEditor(conv) { tick++ }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            items(msgs, key = { it.id }) { m ->
                val mine = m.role != "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.padding(3.dp).widthIn(max = 300.dp)
                        .background(when (m.role) {
                            "user" -> MaterialTheme.colorScheme.surfaceVariant
                            "owner" -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.primaryContainer
                        }, RoundedCornerShape(12.dp)).padding(8.dp)) {
                        Text(m.text.ifBlank { "[${m.kind}]" })
                        Text((if (m.role == "owner") "você · " else if (m.role == "model") "IA · " else "") + fmtShort.format(Date(m.ts)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("Enviar por aqui pausa o robô nesta conversa (como responder pelo WhatsApp).", style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Mensagem") })
            IconButton(enabled = !sending && input.isNotBlank(), onClick = {
                val text = input.trim()
                sending = true; error = ""
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { AtendenteService.sendManual(ctx, key, text) } }
                    sending = false
                    r.onSuccess { input = ""; tick++ }.onFailure { error = "Não enviou: ${it.message}" }
                }
            }) { Icon(Icons.Default.Send, "Enviar") }
        }
    }

    if (guideOpen) GuideDialog(canReply = true, onDismiss = { guideOpen = false }) { guidance, save, allPersonas, replyNow ->
        guideOpen = false; guideBusy = true; info = ""
        scope.launch {
            val out = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                val all = Personas.load(ctx)
                val p = all.firstOrNull { it.id == conv.persona } ?: all.firstOrNull { it.active && it.connection == conv.conn } ?: all[0]
                val c = Personas.forReply(Store.load(ctx), p)
                if (save) runCatching {
                    val rule = AiQueue.run(AiQueue.REPLY, c.apiKey, c.rpmLimit) { Brain.learn(c, guidance, Memory.contextTurns(ctx, key)) }
                    Memory.addLesson(ctx, if (allPersonas) 0 else p.id, rule, key)
                    out += "📝 Aprendido${if (allPersonas) " (todas as personas)" else " (${p.label})"}: $rule"
                }.onFailure { out += "⚠️ Não salvou o aprendizado: ${it.message}" }
                if (replyNow) runCatching { AtendenteService.guidedReply(key, guidance) }
                    .onSuccess { out += "✅ A IA respondeu o cliente." }
                    .onFailure { out += "⚠️ Não respondeu: ${it.message}" }
            }
            guideBusy = false; info = out.joinToString("\n"); tick++
        }
    }
}

/**
 * Orientação do dono para a IA: vira um aprendizado salvo no banco (vale nas
 * próximas conversas) e, se quiser, a IA já responde o cliente seguindo isso.
 */
@Composable
internal fun GuideDialog(canReply: Boolean, onDismiss: () -> Unit, onConfirm: (String, Boolean, Boolean, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(true) }
    var allPersonas by remember { mutableStateOf(false) }
    var replyNow by remember { mutableStateOf(canReply) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Orientar a IA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Diga como a IA deve responder, do seu jeito. Ex.: \"quando perguntarem de entrega, diga que entregamos " +
                    "só no centro e ofereça retirada\", \"seja mais direta e não mande o catálogo logo de cara\".",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp), placeholder = { Text("Sua orientação") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it })
                    Text("Aprender e usar nas próximas conversas", style = MaterialTheme.typography.bodySmall)
                }
                if (save) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allPersonas, { allPersonas = it })
                    Text("Vale para todas as personas", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(replyNow, { replyNow = it })
                    Text(if (canReply) "A IA responde agora este cliente seguindo isto" else "Refazer a última resposta seguindo isto",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && (save || replyNow), onClick = { onConfirm(text.trim(), save, allPersonas, replyNow) }) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

/** Busca de cliente pelo nome ou parte do número, para assumir a conversa. */
@Composable
internal fun ClientSearch(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val found = remember(q) { runCatching { Memory.search(ctx, q, 8) }.getOrDefault(emptyList()) }
    Column {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Assumir conversa: nome ou parte do número") })
        if (q.isNotBlank() && found.isEmpty()) Text(
            if (q.filter(Char::isDigit).length in 1..2) "Digite pelo menos 3 números." else "Nenhum cliente encontrado.",
            style = MaterialTheme.typography.bodySmall)
        found.forEach { c ->
            Column(Modifier.fillMaxWidth().clickable { q = ""; onOpen(c.key) }.padding(vertical = 6.dp)) {
                Text(c.name.ifBlank { "Sem nome" } + (if (c.phone.isNotBlank()) "  +${c.phone}" else ""), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(fmtShort.format(Date(c.updated)) + " · " + c.lastText, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Divider()
        }
    }
}

@Composable
private fun TagEditor(conv: Conversation, changed: () -> Unit) {
    val ctx = LocalContext.current
    fun set(col: String, v: String) {
        Memory.update(ctx, conv.key) { put(col, v); put("tags_manual", 1) }
        changed()
    }
    Card(Modifier.fillMaxWidth().padding(4.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mudar à mão trava as etiquetas (a IA não muda mais).", style = MaterialTheme.typography.labelSmall)
            ChipRow(Funnel.TEMPS.toList(), conv.temperature) { set("temperature", it) }
            ChipRow(Funnel.STAGES.toList().filter { it.first != "naoperturbe" }, conv.stage) { set("stage", it) }
            ChipRow(Funnel.OBJECTIONS.toList(), conv.objection) { set("objection", it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Não perturbar (sem retomadas)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(checked = conv.dnd, onCheckedChange = { on ->
                    Memory.update(ctx, conv.key) { put("dnd", if (on) 1 else 0); if (on) put("followup_at", 0L) }
                    changed()
                })
            }
            if (conv.tagsManual) TextButton(onClick = {
                Memory.update(ctx, conv.key) { put("tags_manual", 0) }; changed()
            }) { Text("Deixar a IA etiquetar de novo") }
        }
    }
}
