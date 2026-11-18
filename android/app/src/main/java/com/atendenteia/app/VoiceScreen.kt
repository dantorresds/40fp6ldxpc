package com.atendenteia.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SAMPLE_TEXT =
    "Oi, tudo bem? Aqui é da loja. Obrigado por chamar a gente! Hoje temos várias opções fresquinhas, " +
        "e eu posso te mandar as fotos e os preços agora mesmo. Se preferir, você também pode fazer o pedido " +
        "pelo nosso catálogo. Qualquer dúvida, é só me mandar uma mensagem ou um áudio que eu te respondo."

@Composable
fun VoiceScreen(config: Config, save: (Config) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(config.voiceStyle) }
    var testText by remember { mutableStateOf("Oi! Temos sim, saiu agora do forno. Quer que eu te mande as fotos?") }
    var busy by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    val sampleFile = remember { File(ctx.filesDir, "voz-amostra.wav") }
    val consentFile = remember { File(ctx.filesDir, "voz-consentimento.wav") }
    var sampleSecs by remember { mutableStateOf(if (sampleFile.exists()) ((sampleFile.length() - 44) / 2 / Voice.SAMPLE_RATE).toInt() else 0) }
    var consentSecs by remember { mutableStateOf(if (consentFile.exists()) ((consentFile.length() - 44) / 2 / Voice.SAMPLE_RATE).toInt() else 0) }
    var recorder by remember { mutableStateOf<WavRecorder?>(null) }
    var recordingWhat by remember { mutableStateOf("") }
    var recSecs by remember { mutableStateOf(0) }

    var micOk by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }

    LaunchedEffect(recorder) {
        while (recorder != null) { recSecs = recorder?.seconds ?: 0; delay(250) }
    }

    fun startRec(what: String) {
        if (!micOk) { micPerm.launch(Manifest.permission.RECORD_AUDIO); return }
        val r = WavRecorder(if (what == "sample") sampleFile else consentFile)
        r.start(); recorder = r; recordingWhat = what
    }
    fun stopRec() {
        val r = recorder ?: return
        r.stop()
        if (recordingWhat == "sample") sampleSecs = r.seconds else consentSecs = r.seconds
        recorder = null; recordingWhat = ""
    }

    fun preview(file: File) {
        scope.launch(Dispatchers.IO) { runCatching { Voice.play(Voice.stripWavHeader(file.readBytes())) } }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Responder com áudio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("A IA escreve a resposta e o Gemini transforma em mensagem de voz.", style = MaterialTheme.typography.bodyMedium)
        if (!Voice.canEncodeVoiceNote) {
            Text("⚠️ Mensagem de voz exige Android 10 ou mais novo. Neste celular as respostas continuam em texto.",
                color = MaterialTheme.colorScheme.error)
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Qual voz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                listOf(
                    "off" to "Desligado (só texto)",
                    "prebuilt" to "Voz pronta do Gemini — grátis",
                    "mine" to "Minha voz (clonada) — exige faturamento no Gemini",
                ).forEach { (mode, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = config.voiceMode == mode, onClick = { save(config.copy(voiceMode = mode)) })
                        Text(label)
                    }
                }
                if (config.voiceMode != "off") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Responder em áudio sempre (não só quando o cliente manda áudio)", Modifier.weight(1f))
                        Switch(config.voiceAlways, { save(config.copy(voiceAlways = it)) })
                    }
                    OutlinedTextField(style, { style = it }, Modifier.fillMaxWidth(), label = { Text("Jeito de falar (opcional)") },
                        supportingText = { Text("Ex.: simpática, sorrindo, ritmo calmo, sotaque mineiro") })
                    TextButton(onClick = { save(config.copy(voiceStyle = style.trim())) }) { Text("Salvar jeito de falar") }
                }
            }
        }

        if (config.voiceMode == "prebuilt") {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Voz pronta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Voice.PREBUILT.forEach { (id, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.prebuiltVoice == id, onClick = { save(config.copy(prebuiltVoice = id)) })
                            Text(label)
                        }
                    }
                }
            }
        }

        if (config.voiceMode == "mine") {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Minha voz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (config.myVoiceId.isNotBlank()) {
                        Text("✅ Sua voz está criada (${config.myVoiceId.take(18)}…).")
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("Apagar minha voz") }
                    } else {
                        Text("O Google exige conta do Gemini com faturamento ativo (Tier 1) para clonar voz. " +
                            "Custa por volta de R$ 0,08 por minuto de áudio gerado. A chave é a mesma da aba Início.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Grave num lugar silencioso, com o celular a um palmo da boca. Só a sua voz.",
                            style = MaterialTheme.typography.bodySmall)

                        Text("1. Leia este texto em voz natural (10 a 30 segundos):", fontWeight = FontWeight.SemiBold)
                        Text("“$SAMPLE_TEXT”", style = MaterialTheme.typography.bodyMedium)
                        RecordRow("sample", sampleSecs, recordingWhat, recSecs, ::startRec, ::stopRec) { preview(sampleFile) }
                        if (sampleSecs in 1..9) Text("Precisa de pelo menos 10 segundos.", color = MaterialTheme.colorScheme.error)
                        if (sampleSecs > 30) Text("Passou de 30 segundos: grave de novo um pouco mais curto.", color = MaterialTheme.colorScheme.error)

                        Text("2. Agora diga exatamente a frase de autorização:", fontWeight = FontWeight.SemiBold)
                        Text("“${Voice.CONSENT_PT_BR}”", style = MaterialTheme.typography.bodyMedium)
                        RecordRow("consent", consentSecs, recordingWhat, recSecs, ::startRec, ::stopRec) { preview(consentFile) }

                        Button(enabled = busy.isEmpty() && sampleSecs in 10..30 && consentSecs >= 3 && config.apiKey.isNotBlank(), onClick = {
                            busy = "Criando sua voz…"; message = ""
                            scope.launch {
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { Voice.createVoice(config.apiKey, config.ttsModel, sampleFile, consentFile, config.storeName) }
                                }
                                busy = ""
                                r.onSuccess { id ->
                                    save(config.copy(myVoiceId = id))
                                    sampleFile.delete(); consentFile.delete()
                                    message = "✅ Voz criada! Teste abaixo."
                                }.onFailure { message = "⚠️ ${it.message}" }
                            }
                        }) { Text("3. Criar minha voz") }
                        if (config.apiKey.isBlank()) Text("Cadastre a chave do Gemini na aba Início.", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (config.voiceMode != "off") {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ouvir como fica", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(testText, { testText = it }, Modifier.fillMaxWidth(), label = { Text("Frase de teste") })
                    Button(enabled = busy.isEmpty() && config.activeVoice() != null && config.apiKey.isNotBlank(), onClick = {
                        busy = "Gerando áudio…"; message = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    val pcm = Voice.synthesize(config.apiKey, config.ttsModel, config.activeVoice()!!,
                                        Voice.cleanForSpeech(testText), style.trim())
                                    Voice.play(pcm)
                                }
                            }
                            busy = ""
                            r.onFailure { message = "⚠️ ${it.message}" }
                        }
                    }) { Text("▶ Ouvir") }
                }
            }
        }

        if (busy.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp)); Text("  $busy")
        }
        if (message.isNotEmpty()) Text(message, Modifier.padding(vertical = 8.dp))
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Apagar sua voz?") },
            text = { Text("A voz clonada é apagada da sua conta do Gemini. Para usar de novo, grave outra vez.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = config.myVoiceId
                    save(config.copy(myVoiceId = "", voiceMode = "off"))
                    scope.launch(Dispatchers.IO) { runCatching { Voice.deleteVoice(config.apiKey, id) } }
                }) { Text("Apagar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun RecordRow(
    what: String, savedSecs: Int, recordingWhat: String, recSecs: Int,
    start: (String) -> Unit, stop: () -> Unit, preview: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recordingWhat == what) {
            Button(onClick = stop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Text("■ Parar ($recSecs s)")
            }
        } else {
            Button(enabled = recordingWhat.isEmpty(), onClick = { start(what) }) { Text(if (savedSecs > 0) "● Gravar de novo" else "● Gravar") }
            if (savedSecs > 0) {
                OutlinedButton(onClick = preview) { Text("▶ $savedSecs s") }
            }
        }
    }
}
