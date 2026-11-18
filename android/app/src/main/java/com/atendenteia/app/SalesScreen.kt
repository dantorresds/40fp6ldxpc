package com.atendenteia.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** SHA-1 do certificado que assina o app (pedido pelo Google Cloud ao criar o acesso ao Drive). */
private fun signingSha1(ctx: Context): String = runCatching {
    val sigs = if (Build.VERSION.SDK_INT >= 28) {
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures!!
    }
    MessageDigest.getInstance("SHA-1").digest(sigs[0].toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrDefault("?")

@Composable
private fun SalesBox(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun SalesScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(Sales.enabled(ctx)) }
    var token by remember { mutableStateOf("") }
    var hasToken by remember { mutableStateOf(Sales.mpToken(ctx).isNotBlank()) }
    var mpResult by remember { mutableStateOf("") }
    var driveEmail by remember { mutableStateOf(Sales.driveEmail(ctx)) }
    var driveResult by remember { mutableStateOf("") }
    // Arquivo privado no Drive (exige Google Cloud) é opcional; o normal é entregar pelo link salvo.
    var privateMode by remember { mutableStateOf(Sales.driveEmail(ctx).isNotBlank()) }
    var pLink by remember { mutableStateOf("") }
    var delivery by remember { mutableStateOf(Sales.deliveryMessage(ctx, byLink = !privateMode)) }
    var card by remember { mutableStateOf(Sales.cardEnabled(ctx)) }
    var pKind by remember { mutableStateOf("info_app") }
    var pUrl by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf(String.format(Locale("pt", "BR"), "%.1f", Sales.cardFeePercent(ctx)).replace(",0", "")) }
    val products = remember(tick) { Sales.products(ctx) }
    val orders = remember(tick) { Sales.orders(ctx).takeLast(20).reversed() }

    val brl = { v: Double -> "R$ " + String.format(Locale("pt", "BR"), "%.2f", v) }
    var pName by remember { mutableStateOf("") }
    var pPrice by remember { mutableStateOf("") }
    var pDesc by remember { mutableStateOf("") }
    var pNoDownload by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf("") }

    fun afterDriveToken() {
        scope.launch {
            driveResult = withContext(Dispatchers.IO) {
                runCatching {
                    val email = GoogleDrive.accountEmail(GoogleDrive.token(ctx))
                    Sales.setDriveEmail(ctx, email); driveEmail = email
                    "✅ Conectado: $email"
                }.getOrElse { "❌ ${it.message}" }
            }
        }
    }

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data) }
            .onSuccess { afterDriveToken() }
            .onFailure { driveResult = "❌ Não autorizado: ${it.message}" }
    }

    fun connectDrive() {
        driveResult = "Conectando…"
        Identity.getAuthorizationClient(ctx).authorize(GoogleDrive.request())
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else afterDriveToken()
            }
            .addOnFailureListener { driveResult = "❌ ${it.message} (confira o passo a passo do Google Cloud abaixo)" }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val price = pPrice.replace(",", ".").toDoubleOrNull()
        if (price == null || price <= 0 || pName.isBlank()) { uploading = "Preencha nome e preço antes."; return@rememberLauncherForActivityResult }
        uploading = "Enviando para o seu Drive…"
        scope.launch {
            uploading = withContext(Dispatchers.IO) {
                runCatching {
                    val (fileId, fileName) = GoogleDrive.upload(ctx, GoogleDrive.token(ctx), uri, pNoDownload)
                    val base = pName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "produto" }
                    val id = if (products.none { it.id == base }) base else base + "-" + (products.size + 1)
                    Sales.saveProducts(ctx, Sales.products(ctx) + DigitalProduct(id, pName.trim(), price, pDesc.trim(), fileId, fileName, kind = "info_app"))
                    "✅ \"$fileName\" enviado (privado). Produto \"$id\" cadastrado."
                }.getOrElse { "❌ ${it.message}" }
            }
            pName = ""; pPrice = ""; pDesc = ""; tick++
        }
    }

    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Vendas pelo WhatsApp", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Infoproduto próprio: o app cobra e entrega sozinho. Infoproduto em plataforma e produto de afiliado: a IA manda o link de compra.",
            style = MaterialTheme.typography.bodySmall)

        SalesBox("1. Ligar vendas") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vender produtos pelo WhatsApp", Modifier.weight(1f))
                Switch(enabled, { enabled = it; Sales.setEnabled(ctx, it) })
            }
            Text("Cada produto (item 4) tem um tipo:", style = MaterialTheme.typography.bodySmall)
            Text("• Infoproduto próprio: o app cobra por Pix/cartão e, depois de pago, manda o link de entrega que você salvar (itens 2, 4 e 5).\n" +
                "• Infoproduto na página de vendas: a IA manda o link (Hotmart, Kiwify, site).\n" +
                "• Produto físico de afiliado: a IA manda o seu link de afiliado.",
                style = MaterialTheme.typography.bodySmall)
            val ready = remember(tick, hasToken) { Sales.active(ctx) }
            if (enabled && !ready) {
                Text("⚠️ Nenhum produto pronto para venda: cadastre um produto (item 4). Infoproduto próprio também precisa do token do Mercado Pago (item 2).",
                    color = MaterialTheme.colorScheme.error)
            }
        }

        SalesBox("2. Mercado Pago (só infoproduto próprio)") {
            Text(if (hasToken) "✅ Token salvo (criptografado neste celular)" else "Sem token",
                fontWeight = FontWeight.SemiBold)
            Text("Pegue em mercadopago.com.br/developers › Suas integrações › Criar aplicação › Credenciais de produção › Access Token. " +
                "A conta precisa ter chave Pix cadastrada. Se perder o celular, revogue o token lá.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (hasToken) "Trocar Access Token" else "Access Token (APP_USR-…)") },
                visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = token.isNotBlank(), onClick = {
                    Sales.setMpToken(ctx, token); token = ""; hasToken = true; mpResult = "Salvo."
                }) { Text("Salvar") }
                OutlinedButton(enabled = hasToken, onClick = {
                    mpResult = "Testando…"
                    scope.launch {
                        mpResult = withContext(Dispatchers.IO) {
                            runCatching { "✅ Conta: " + MercadoPago.account(Sales.mpToken(ctx)) }.getOrElse { "❌ ${it.message}" }
                        }
                    }
                }) { Text("Testar") }
                if (hasToken) TextButton(onClick = { Sales.setMpToken(ctx, ""); hasToken = false; mpResult = "Token apagado." }) { Text("Apagar") }
            }
            if (mpResult.isNotBlank()) Text(mpResult, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Aceitar cartão também (link do Mercado Pago)", Modifier.weight(1f))
                Switch(card, { card = it; Sales.setCardEnabled(ctx, it) })
            }
            if (card) OutlinedTextField(fee, { v ->
                fee = v
                v.replace(",", ".").toDoubleOrNull()?.takeIf { it in 0.0..30.0 }?.let { Sales.setCardFeePercent(ctx, it) }
            }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Taxa somada no cartão (%)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("Ex.: produto de R$ 29,90 sai por ${brl(Sales.cardPrice(ctx, 29.90))} no cartão.") })
        }

        SalesBox("3. Arquivo privado no Google Drive (opcional)") {
            Text("Normalmente não precisa: o produto próprio é entregue pelo link que você cola no item 4 " +
                "(no Drive, compartilhe como \"qualquer pessoa com o link\"). Quem tiver o link consegue abrir.",
                style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Liberar só para o Gmail de quem pagou (exige Google Cloud)", Modifier.weight(1f))
                Switch(privateMode, { privateMode = it })
            }
            if (privateMode) {
            Text(if (driveEmail.isNotBlank()) "✅ Conectado: $driveEmail" else "Não conectado", fontWeight = FontWeight.SemiBold)
            Text("O app só enxerga os arquivos que ele mesmo enviar (não lê o resto do seu Drive).", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { connectDrive() }) { Text(if (driveEmail.isBlank()) "Conectar Google Drive" else "Reconectar") }
            if (driveResult.isNotBlank()) Text(driveResult, style = MaterialTheme.typography.bodySmall)
            Text("Primeira vez: crie o acesso no Google Cloud (passo a passo no documento) usando estes dados:",
                style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text("Pacote: ${ctx.packageName}\nSHA-1: ${signingSha1(ctx)}", style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold)
            }
            }
        }

        SalesBox("4. Produtos digitais") {
            products.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val kindLabel = when (p.kind) { "info_link" -> "página de vendas"; "affiliate" -> "afiliado"; else -> "vendo aqui" }
                        Text("${p.name} — ${brl(p.price)} · $kindLabel", fontWeight = FontWeight.SemiBold)
                        Text("id ${p.id} · " + listOf(p.fileName, p.salesUrl, p.deliveryUrl).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { Sales.saveProducts(ctx, Sales.products(ctx).filter { it.id != p.id }); tick++ }) {
                        Icon(Icons.Default.Delete, "Tirar da venda")
                    }
                }
            }
            OutlinedTextField(pName, { pName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Nome (ex.: Ebook Receitas de Natal)") })
            OutlinedTextField(pPrice, { pPrice = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (pKind == "info_app") "Preço (ex.: 29,90)" else "Preço (opcional, ex.: 29,90)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(pDesc, { pDesc = it }, Modifier.fillMaxWidth(), label = { Text("Descrição curta para a IA") })
            Text("Tipo do produto:")
            listOf("info_app" to "Infoproduto próprio (vendo e entrego aqui)", "info_link" to "Infoproduto na página de vendas", "affiliate" to "Produto físico de afiliado")
                .forEach { (v, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pKind == v, onClick = { pKind = v })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            if (pKind != "info_app") {
                OutlinedTextField(pUrl, { pUrl = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (pKind == "affiliate") "Seu link de afiliado (https://…)" else "Link da página de vendas (https://…)") })
                Button(enabled = pName.isNotBlank() && pUrl.trim().startsWith("http"), onClick = {
                    val price = pPrice.replace(",", ".").toDoubleOrNull() ?: 0.0
                    val base = pName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "produto" }
                    val id = if (products.none { it.id == base }) base else base + "-" + (products.size + 1)
                    Sales.saveProducts(ctx, Sales.products(ctx) + DigitalProduct(id, pName.trim(), price, pDesc.trim(), "", "", pUrl.trim(), pKind))
                    pName = ""; pPrice = ""; pDesc = ""; pUrl = ""; tick++
                }) { Text("Cadastrar produto") }
            }
            if (pKind == "info_app" && !privateMode) {
                OutlinedTextField(pLink, { pLink = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Link de entrega (Google Drive, Dropbox…)") },
                    supportingText = { Text("Enviado ao cliente só depois do pagamento aprovado. No Drive: Compartilhar › Qualquer pessoa com o link.") })
                val linkPrice = pPrice.replace(",", ".").toDoubleOrNull()
                Button(enabled = pName.isNotBlank() && linkPrice != null && linkPrice > 0 && pLink.trim().startsWith("http"), onClick = {
                    val base = pName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "produto" }
                    val id = if (products.none { it.id == base }) base else base + "-" + (products.size + 1)
                    Sales.saveProducts(ctx, Sales.products(ctx) + DigitalProduct(id, pName.trim(), linkPrice ?: 0.0, pDesc.trim(), "", "",
                        kind = "info_app", deliveryUrl = pLink.trim()))
                    pName = ""; pPrice = ""; pDesc = ""; pLink = ""; tick++
                }) { Text("Cadastrar produto") }
            }
            if (pKind == "info_app" && privateMode) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pNoDownload, { pNoDownload = it })
                Text("Impedir download, impressão e cópia (só ler no Drive)", style = MaterialTheme.typography.bodySmall)
            }
            if (pKind == "info_app" && privateMode) {
                Button(enabled = driveEmail.isNotBlank() && uploading != "Enviando para o seu Drive…", onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Text("Escolher arquivo e cadastrar")
                }
                if (driveEmail.isBlank()) Text("Conecte o Google Drive primeiro (item 3).", style = MaterialTheme.typography.bodySmall)
            }
            if (uploading.isNotBlank()) Text(uploading, style = MaterialTheme.typography.bodySmall)
        }

        SalesBox("5. Mensagem de entrega (infoproduto próprio)") {
            OutlinedTextField(delivery, { delivery = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
                supportingText = { Text("{produto}, {link} e {email} são trocados automaticamente.") })
            if (delivery != Sales.DEFAULT_DELIVERY && delivery != Sales.DEFAULT_DELIVERY_LINK)
                TextButton(onClick = { delivery = if (privateMode) Sales.DEFAULT_DELIVERY else Sales.DEFAULT_DELIVERY_LINK; Sales.setDeliveryMessage(ctx, "") }) { Text("Voltar ao texto padrão") }
            Button(onClick = { Sales.setDeliveryMessage(ctx, delivery) }) { Text("Salvar") }
        }

        SalesBox("6. Pedidos") {
            if (orders.isEmpty()) Text("Nenhum pedido ainda.", style = MaterialTheme.typography.bodySmall)
            orders.forEach { o ->
                val st = when (o.status) {
                    "pending" -> "⏳ aguardando Pix"
                    "paid" -> "💰 pago, entrega pendente"
                    "delivered" -> "✅ pago e entregue"
                    "expired" -> "⌛ Pix vencido"
                    "divergente" -> "⚠️ valor divergente"
                    else -> o.status
                }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${o.productName} — ${brl(o.amount)}${if (o.cardAmount > 0) " (cartão ${brl(o.cardAmount)})" else ""} · $st", fontWeight = FontWeight.SemiBold)
                    Text("${fmt.format(Date(o.createdAt))} · ${Store.nameOf(ctx, o.chat)} · ${o.email}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { tick++ }) { Text("Atualizar") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
