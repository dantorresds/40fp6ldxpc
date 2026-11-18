package com.atendenteia.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sites que a IA lê antes de responder (aba Fotos). */
@Composable
fun SiteSourcesSection(config: Config, save: (Config) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sites que a IA consulta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Antes de responder, o app lê estes endereços (no máximo a cada 3 minutos) para saber se a loja está aberta, " +
                "o que tem disponível e os preços.",
                style = MaterialTheme.typography.bodySmall)
            config.siteSources.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontWeight = FontWeight.SemiBold)
                        Text(s.url, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { save(config.copy(siteSources = config.siteSources - s)) }) { Icon(Icons.Default.Delete, "Apagar") }
                }
            }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Nome (ex.: Pronta entrega, Encomendas)") })
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Endereço (https://…)") },
                supportingText = { Text("Ex.: https://loja.example.com/produtos") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = title.isNotBlank() && url.trim().startsWith("http"), onClick = {
                    save(config.copy(siteSources = config.siteSources + LinkItem(title.trim(), url.trim())))
                    title = ""; url = ""
                }) { Text("Adicionar site") }
                OutlinedButton(enabled = !loading && config.siteSources.isNotEmpty(), onClick = {
                    loading = true
                    scope.launch {
                        preview = withContext(Dispatchers.IO) {
                            runCatching { SiteReader.read(config.siteSources, force = true) }.getOrElse { "Erro: ${it.message}" }
                        }
                        loading = false
                    }
                }) { Text(if (loading) "Lendo…" else "Ver o que a IA lê") }
            }
        }
    }

    preview?.let { text ->
        AlertDialog(onDismissRequest = { preview = null },
            title = { Text("O que a IA vai saber") },
            text = { Text(text, Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Fechar") } })
    }
}
