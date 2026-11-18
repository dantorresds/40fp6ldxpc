package com.atendenteia.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Janela do assistente: a IA entrevista o dono e preenche a aba Loja. */
@Composable
fun WizardDialog(config: Config, onClose: () -> Unit, onApply: (Config) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<WizardTurn>().apply { addAll(Wizard.load(ctx)) } }
    var options by remember { mutableStateOf(listOf<String>()) }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<JSONObject, String>?>(null) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recSecs by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val audioFile = remember { File(ctx.cacheDir, "assistente-resposta.wav") }

    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1 + if (busy.isNotEmpty()) 1 else 0) }
    LaunchedEffect(recorder) { while (recorder != null) { recSecs = recorder?.seconds ?: 0; delay(250) } }

    /** Uma rodada: guarda a fala do dono, pergunta à IA, mostra a próxima pergunta. */
    fun round(userText: String, attachment: Wizard.Attachment? = null, finish: Boolean = false) {
        error = ""
        busy = when {
            attachment?.mime?.startsWith("audio") == true -> "Ouvindo seu áudio…"
            attachment != null -> "Lendo o arquivo…"
            Regex("https?://").containsMatchIn(userText) -> "Lendo o link…"
            else -> "Pensando…"
        }
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val text = userText
                    val att = attachment
                    val note = if (att == null) Wizard.readLinks(text) else ""
                    val before = turns.toList()
                    val withUser = if (text.isNotBlank() || att != null) before + WizardTurn("me", text, note) else before
                    val step = Wizard.next(config, withUser, att, finish)
                    // Mostra o que foi entendido do áudio / lido do arquivo.
                    val fixedUser = when {
                        att?.mime?.startsWith("audio") == true -> WizardTurn("me", "🎤 " + step.heard.ifBlank { "(áudio)" })
                        att != null -> WizardTurn("me", text, "[Conteúdo do arquivo ${att.name}]\n" + Wizard.lastExtracted.take(9000))
                        else -> null
                    }
                    Triple(step, withUser, fixedUser)
                }
            }
            busy = ""
            res.onSuccess { (step, withUser, fixedUser) ->
                turns.clear(); turns.addAll(withUser)
                if (fixedUser != null && turns.isNotEmpty()) turns[turns.lastIndex] = fixedUser
                if (step.message.isNotBlank()) turns.add(WizardTurn("ai", step.message))
                Wizard.save(ctx, turns)
                options = step.options
                progress = maxOf(progress, step.progress)
                if (step.done && step.config != null) result = step.config to step.summary
            }.onFailure { error = it.message ?: "erro" }
        }
    }

    LaunchedEffect(Unit) { if (turns.isEmpty()) round("") }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runCatching { Wizard.readAttachment(ctx, uri) }
            .onSuccess { round("📎 ${it.name}", it) }
            .onFailure { error = it.message ?: "erro" }
    }
    var micOk by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }

    fun toggleRecord() {
        val r = recorder
        if (r == null) {
            if (!micOk) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return }
            recorder = WavRecorder(audioFile).also { it.start() }
        } else {
            r.stop(); recorder = null
            if (r.seconds < 1) return
            round("", Wizard.Attachment("resposta.wav", "audio/wav", audioFile.readBytes()))
        }
    }

    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Assistente de configuração", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Responda tocando nas opções, escrevendo, por áudio 🎤 ou anexando PDF/foto 📎.", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { recorder?.stop(); onClose() }) { Icon(Icons.Default.Close, "Fechar (a conversa fica salva)") }
                }
                LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    items(turns) { t ->
                        val mine = t.role == "me"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Text(t.text, Modifier.padding(4.dp).widthIn(max = 320.dp)
                                .background(if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(12.dp)).padding(10.dp))
                        }
                    }
                    if (busy.isNotEmpty()) item { Text(busy, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                }
                if (error.isNotEmpty()) {
                    Text("⚠️ $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { round("") }) { Text("Tentar de novo") }
                }
                if (options.isNotEmpty() && busy.isEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        options.forEach { op ->
                            SuggestionChip(onClick = {
                                options = emptyList()
                                if (op.contains("anexar", true) || op.contains("PDF", false)) filePicker.launch(arrayOf("application/pdf", "image/*"))
                                else round(op)
                            }, label = { Text(op) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = busy.isEmpty() && recorder == null, onClick = { filePicker.launch(arrayOf("application/pdf", "image/*")) }) {
                        Icon(Icons.Default.Add, "Anexar PDF ou foto")
                    }
                    if (recorder != null) {
                        Text("Gravando… $recSecs s  (toque em ■ para enviar)", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    } else {
                        OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Sua resposta") }, maxLines = 4)
                    }
                    if (input.isBlank() || recorder != null) {
                        FilledTonalButton(enabled = busy.isEmpty(), onClick = { toggleRecord() }, contentPadding = PaddingValues(8.dp)) {
                            Text(if (recorder == null) "🎤" else "■")
                        }
                    } else {
                        IconButton(enabled = busy.isEmpty(), onClick = { val t = input.trim(); input = ""; options = emptyList(); round(t) }) {
                            Icon(Icons.Default.Send, "Enviar")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = busy.isEmpty() && turns.size >= 4, onClick = { round("Pode finalizar com o que já tem.", finish = true) }) {
                        Text("Finalizar agora")
                    }
                    TextButton(enabled = busy.isEmpty(), onClick = {
                        Wizard.clear(ctx); turns.clear(); options = emptyList(); progress = 0; round("")
                    }) { Text("Recomeçar") }
                }
            }
        }
    }

    result?.let { (cfg, summary) ->
        val preview = remember(cfg) { Wizard.apply(config, cfg) }
        AlertDialog(onDismissRequest = {},
            title = { Text("Pronto! Confira antes de salvar") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium)
                    Text("Nome: ${preview.storeName}", fontWeight = FontWeight.SemiBold)
                    Text("O que a IA precisa saber", fontWeight = FontWeight.SemiBold)
                    Text(preview.instructions, style = MaterialTheme.typography.bodySmall)
                    Text("Preferências de atendimento", fontWeight = FontWeight.SemiBold)
                    Text(preview.preferences, style = MaterialTheme.typography.bodySmall)
                    Text("Ao passar para a equipe: ${preview.handoffMessage}", style = MaterialTheme.typography.bodySmall)
                    if (preview.links.isNotEmpty()) Text("Links: " + preview.links.joinToString { it.title }, style = MaterialTheme.typography.bodySmall)
                    if (preview.siteSources.isNotEmpty()) Text("Sites consultados: " + preview.siteSources.joinToString { it.url }, style = MaterialTheme.typography.bodySmall)
                    Text("Ligações: " + when (preview.callPolicy) { "off" -> "não recusar"; "unknown" -> "recusar só fora da agenda"; else -> "recusar todas" } +
                        (if (preview.triggerEnabled) " · Palavra-chave: ${preview.triggerWords}" else ""), style = MaterialTheme.typography.bodySmall)
                    Text("Depois você pode editar qualquer campo na aba Loja.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onApply(preview)
                    Store.setWizardDoneAt(ctx, System.currentTimeMillis())
                    Wizard.clear(ctx)
                    result = null
                    onClose()
                }) { Text("Salvar e aplicar") }
            },
            dismissButton = { TextButton(onClick = { result = null }) { Text("Continuar conversando") } })
    }
}
