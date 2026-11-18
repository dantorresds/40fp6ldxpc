package com.atendenteia.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val IMPORT_TYPES = arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Prompt de configuração", text))
    Toast.makeText(ctx, "Copiado. Cole na outra IA.", Toast.LENGTH_SHORT).show()
}

private fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(send, "Compartilhar prompt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun clipboardText(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotBlank() }
}

/** Botões "copiar" e "compartilhar" do prompt-entrevista para outra IA (n = 0 orquestrador, 1..3 persona). */
@Composable
private fun PromptButtons(n: Int, copyLabel: String, compact: Boolean = false) {
    val ctx = LocalContext.current
    fun text() = runCatching { ConfigImport.promptText(ctx, n) }.getOrElse {
        Toast.makeText(ctx, "Não consegui abrir o prompt: ${it.message}", Toast.LENGTH_LONG).show(); null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            TextButton(onClick = { text()?.let { copyText(ctx, it) } }) { Text(copyLabel) }
            TextButton(onClick = { text()?.let { shareText(ctx, it) } }) { Text("Compartilhar") }
        } else {
            Button(onClick = { text()?.let { copyText(ctx, it) } }) { Text(copyLabel) }
            OutlinedButton(onClick = { text()?.let { shareText(ctx, it) } }) { Text("Compartilhar") }
        }
    }
}

/** Exportar PDF, exportar TXT e compartilhar (PDF) do documento mostrado agora. */
@Composable
private fun ExportButtons(text: () -> String, title: () -> String, pdfName: () -> String, txtName: () -> String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingText by remember { mutableStateOf("") }
    var pendingTitle by remember { mutableStateOf("") }
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { PdfExport.write(pendingText, pendingTitle, it) } }
            }
            r.onSuccess { toast("PDF salvo.") }.onFailure { toast("Não consegui salvar o PDF: ${it.message}") }
        }
    }
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(pendingText.toByteArray(Charsets.UTF_8)) } }
            }
            r.onSuccess { toast("Arquivo salvo.") }.onFailure { toast("Não consegui salvar: ${it.message}") }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pendingText = text(); pendingTitle = title(); pdfSaver.launch(pdfName()) }) { Text("⬇️ Exportar PDF") }
        OutlinedButton(onClick = { pendingText = text(); txtSaver.launch(txtName()) }) { Text("Exportar TXT") }
        OutlinedButton(onClick = {
            val t = text(); val ti = title(); val name = pdfName()
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { PdfExport.toCacheFile(ctx, t, ti, name) } }
                r.onSuccess { PdfExport.share(ctx, it) }.onFailure { toast("Não consegui gerar o PDF: ${it.message}") }
            }
        }) { Text("Compartilhar") }
    }
}

/** Caixa só de leitura com fonte monoespaçada, recolhida em ~10 linhas. */
@Composable
private fun PromptBox(text: String, collapsible: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, Modifier.padding(10.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                maxLines = if (expanded || !collapsible) Int.MAX_VALUE else 10, overflow = TextOverflow.Ellipsis)
        }
    }
    if (collapsible) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Recolher" else "Ver tudo") }
}

/** Diálogo em tela cheia para editar o texto do prompt. [onApply] devolve null se deu certo ou a mensagem de erro. */
@Composable
private fun EditPromptDialog(title: String, initial: String, busy: Boolean, error: String?, onApply: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Mantenha os títulos \"## ...\". Ao aplicar, os campos da tela são atualizados.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy && text.isNotBlank(), onClick = { onApply(text) }) { Text("Aplicar") }
                    OutlinedButton(onClick = onClose) { Text("Cancelar") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Cartão "Prompt da persona": o documento gerado ao vivo dos campos da tela, com edição, importação e exportação. */
@Composable
internal fun PersonaPromptCard(base: Config, persona: Persona, onApply: (Persona) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rendered = remember(persona) { ConfigDoc.renderPersona(persona) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Prepared?>(null) }

    fun run(fromDialog: Boolean, block: suspend () -> Prepared) {
        busy = true; error = null; editError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { block() } }
            busy = false
            r.onSuccess { result = it; editing = false }.onFailure {
                val m = it.message ?: "Não consegui ler o prompt."
                if (fromDialog) editError = m else { error = m; Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) run(false) { ConfigImport.importPersona(ctx, base, persona, uri) }
    }

    SectionCard("📄 Prompt da persona",
        "Montado ao vivo com os campos desta tela. Mudou um campo, o prompt muda; editou o prompt, os campos mudam. " +
            "Ficam de fora só o número do WhatsApp e a chave do Gemini.") {
        PromptBox(rendered)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { editError = null; editing = true }) { Text("✏️ Editar prompt") }
            OutlinedButton(enabled = !busy, onClick = {
                val t = clipboardText(ctx)
                if (t == null) { error = "A área de transferência está vazia."; Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show() }
                else run(false) { ConfigImport.preparePersona(ctx, persona, t) }
            }) { Text("📋 Colar prompt") }
        }
        OutlinedButton(enabled = !busy, onClick = { picker.launch(IMPORT_TYPES) }) { Text("📄 Importar arquivo (PDF/TXT)") }
        ExportButtons({ rendered }, { persona.label }, { ConfigDoc.fileName(persona, "pdf") }, { ConfigDoc.fileName(persona, "txt") })
        if (busy && !editing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Lendo o prompt…")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PromptButtons(persona.id, "Montar com outra IA (copiar prompt)", compact = true)
    }

    if (editing) EditPromptDialog("Editar prompt da persona ${persona.id}", rendered, busy, editError,
        onApply = { t -> run(true) { ConfigImport.preparePersona(ctx, persona, t) } }, onClose = { editing = false; editError = null })

    result?.let { r ->
        AlertDialog(onDismissRequest = { PhotoSync.deleteFiles(ctx, r.newFiles); result = null },
            title = { Text("Conferir antes de aplicar") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ConfigImport.summary(r.persona), style = MaterialTheme.typography.bodyMedium)
                    r.warnings.forEach { Text("⚠️ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Depois de aplicar, toque em Salvar no fim da tela.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { onApply(r.persona); result = null }) { Text("Aplicar") } },
            dismissButton = { TextButton(onClick = { PhotoSync.deleteFiles(ctx, r.newFiles); result = null }) { Text("Cancelar") } })
    }
}

/** Orquestrador: escolhe qual persona atende cada conversa quando há mais de uma no mesmo número. */
@Composable
internal fun OrchestratorSection(base: Config, all: List<Persona>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(Orchestrator.load(ctx)) }
    var saved by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDraft by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { ConfigImport.importRules(ctx, base, cfg.keySlot, uri) } }
            reading = false
            r.onSuccess { cfg = cfg.copy(masterPrompt = it); saved = false }
                .onFailure { error = it.message ?: "Não consegui ler o arquivo."; Toast.makeText(ctx, error, Toast.LENGTH_LONG).show() }
        }
    }
    val active = all.filter { it.active }
    val shared = active.groupBy { it.connection }.values.any { it.size >= 2 }
    val fullDoc = remember(cfg, all) { ConfigDoc.renderOrchestrator(cfg, all) }
    SectionCard("🧭 Orquestrador",
        "Quando 2 ou 3 personas usam o mesmo número, ele escolhe quem atende cada conversa pelo código ou pelo assunto.") {
        SwitchRow("Orquestrador ativo", cfg.enabled) { cfg = cfg.copy(enabled = it); saved = false }
        if (cfg.enabled && !shared) Text(
            "Hoje cada número tem uma persona só; o orquestrador só entra em ação quando houver mais de uma no mesmo número.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text("Chave do Gemini do orquestrador:")
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..3).forEach { n ->
                RadioButton(selected = cfg.keySlot == n, onClick = { cfg = cfg.copy(keySlot = n); saved = false })
                Text("$n", Modifier.padding(end = 12.dp))
            }
        }
        Text("PERSONAS (montada sozinha a partir das personas)", style = MaterialTheme.typography.labelLarge)
        PromptBox("## PERSONAS\n" + ConfigDoc.orchestratorPersonasSection(all).ifBlank { "-" }, collapsible = false)
        Text("Só leitura: muda quando você edita nome, \"quando usar\", papel ou códigos de uma persona.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field("Prompt mestre (só as regras)", cfg.masterPrompt, { cfg = cfg.copy(masterPrompt = it); saved = false }, minHeight = 220,
            hint = "Diz como escolher a persona e quando trocar no meio da conversa. Vazio = regra simples.")
        OutlinedButton(onClick = {
            if (cfg.masterPrompt.isNotBlank()) confirmDraft = true
            else { cfg = cfg.copy(masterPrompt = Orchestrator.draftMasterPrompt(Personas.active(ctx))); saved = false }
        }) { Text("Gerar rascunho das regras") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !reading, onClick = {
                val t = clipboardText(ctx)
                if (t == null) { error = "A área de transferência está vazia." }
                else ConfigDoc.parseOrchestrator(t)
                    .onSuccess { cfg = cfg.copy(masterPrompt = it); saved = false; error = null }
                    .onFailure { error = it.message }
            }) { Text("📋 Colar prompt") }
            OutlinedButton(enabled = !reading, onClick = { picker.launch(IMPORT_TYPES) }) { Text("📄 Importar (PDF/TXT)") }
        }
        ExportButtons({ fullDoc }, { "Orquestrador" }, { "Orquestrador.pdf" }, { "Orquestrador.txt" })
        if (reading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Lendo o arquivo…")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PromptButtons(0, "Montar com outra IA (copiar prompt)", compact = true)
        Button(onClick = { Orchestrator.save(ctx, cfg); saved = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "Salvo ✓" else "Salvar orquestrador")
        }
    }
    if (confirmDraft) AlertDialog(onDismissRequest = { confirmDraft = false },
        title = { Text("Substituir as regras?") },
        text = { Text("O texto que está no campo será trocado por um rascunho novo.") },
        confirmButton = { TextButton(onClick = {
            cfg = cfg.copy(masterPrompt = Orchestrator.draftMasterPrompt(Personas.active(ctx))); saved = false; confirmDraft = false
        }) { Text("Substituir") } },
        dismissButton = { TextButton(onClick = { confirmDraft = false }) { Text("Cancelar") } })
}
