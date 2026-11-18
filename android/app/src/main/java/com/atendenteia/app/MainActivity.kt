package com.atendenteia.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Brain.verifiedHook = { Store.setAiVerified(applicationContext) }
        Brain.lessonsFor = { p -> Memory.lessons(applicationContext, p).map { it.text } }
        setContent {
            AtendenteTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App(resumeTick.value) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }
}

private enum class Dest(val label: String, val icon: ImageVector) {
    INICIO("Início", Icons.Default.Home),
    CONVERSAS("Conversas", Icons.Default.Email),
    PERSONAS("Personas", Icons.Default.Person),
    VENDAS("Vendas", Icons.Default.ShoppingCart),
    MAIS("Mais", Icons.Default.Menu),
}

/** Telas guardadas no menu "Mais". */
private enum class MoreItem(val label: String, val desc: String, val icon: ImageVector) {
    FOTOS("Fotos e links", "Fotos, links e sites que a IA pode usar", Icons.Default.Star),
    VOZ("Voz", "Respostas em áudio, voz pronta ou clonada", Icons.Default.Face),
    REGISTRO("Registro", "O que o robô fez e conversas com atendimento humano", Icons.Default.List),
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun App(resumeTick: Int) {
    val ctx = LocalContext.current
    var dest by remember { mutableStateOf(Dest.INICIO) }
    var more by remember { mutableStateOf<MoreItem?>(null) }
    var convMode by remember { mutableStateOf(0) } // 0 = Atender, 1 = Funil
    var config by remember { mutableStateOf(Store.load(ctx)) }
    var personas by remember { mutableStateOf(Personas.load(ctx)) }
    var pid by remember { mutableStateOf(1) }
    // pid 4 = Orquestrador (só na aba Personas); nas outras telas vale a persona 1.
    val persona = personas.firstOrNull { it.id == pid } ?: personas.first()
    fun save(c: Config) {
        Store.save(ctx, c)
        config = Store.load(ctx)
    }
    /** Salva as telas Personas/Fotos: os campos da persona vão para ela, o resto para a configuração geral. */
    fun savePersona(edited: Config, p: Persona) {
        val (global, np) = Personas.split(config, edited, p)
        Store.save(ctx, global)
        val list = personas.map { if (it.id == np.id) np else it }
        Personas.save(ctx, list)
        personas = Personas.load(ctx)
        config = Store.load(ctx)
    }
    // Personas e Fotos mostram a persona escolhida (com as chaves gerais).
    val personaView = Personas.effective(config, persona).copy(apiKey = config.apiKey)

    // Estado do robô no topo: online se algum número está conectado.
    var online by remember { mutableStateOf(false) }
    LaunchedEffect(config.numbers) {
        while (true) {
            online = (1..config.numbers).any {
                runCatching { JSONObject(AtendenteService.statusJson(it)).optString("state") }.getOrDefault("") == "connected"
            }
            delay(5000)
        }
    }

    BackHandler(enabled = dest == Dest.MAIS && more != null) { more = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val sub = if (dest == Dest.MAIS) more else null
                        if (sub != null) {
                            IconButton(onClick = { more = null }, Modifier.padding(end = 4.dp)) { Icon(Icons.Default.ArrowBack, "Voltar") }
                            Text(sub.label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        } else {
                            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(32.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Atendente IA", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        }
                        StatusChip(online)
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Dest.values().forEach { d ->
                    NavigationBarItem(selected = dest == d,
                        onClick = { if (d == Dest.MAIS && dest == Dest.MAIS) more = null; dest = d },
                        icon = { Icon(d.icon, null) },
                        label = { Text(d.label, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (dest) {
                Dest.INICIO -> HomeScreen(config, ::save, resumeTick)
                Dest.CONVERSAS -> Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Atender", "Funil").forEachIndexed { i, label ->
                            FilterChip(selected = convMode == i, onClick = { convMode = i }, label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                                leadingIcon = if (convMode == i) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null)
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (convMode == 0) TestScreen(resumeTick) else ConversationsScreen(resumeTick)
                    }
                }
                Dest.PERSONAS -> Column {
                    PersonaPicker(personas, pid, withOrchestrator = true) { pid = it }
                    if (pid == ORCHESTRATOR_ID) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp)) {
                            OrchestratorSection(config, personas)
                        }
                    } else {
                        key(pid) { StoreScreen(personaView, persona, personas, config, { c, p -> savePersona(c, p) }) }
                    }
                }
                Dest.VENDAS -> SalesScreen()
                Dest.MAIS -> when (more) {
                    null -> MoreScreen { more = it }
                    MoreItem.FOTOS -> Column {
                        PersonaPicker(personas, persona.id) { pid = it }
                        key(pid) { MediaScreen(personaView) { savePersona(it, persona) } }
                    }
                    MoreItem.VOZ -> VoiceScreen(config, ::save)
                    MoreItem.REGISTRO -> LogScreen(resumeTick)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(online: Boolean) {
    val color = if (online) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MoreScreen(onOpen: (MoreItem) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionCard("Mais opções") {
            MoreItem.values().forEach { m ->
                ListItem(
                    modifier = Modifier.heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium).clickable { onOpen(m) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(m.icon, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(m.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(m.desc) },
                    trailingContent = { Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}

/** Estado vazio amigável: ícone grande e suave + texto centralizado. */
@Composable
internal fun EmptyState(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) = SectionCard(title, null, content = content)

/** Cartão de seção: plano, com borda suave; título forte, texto de ajuda opcional e conteúdo com espaçamento padrão. */
@Composable
internal fun SectionCard(title: String, subtitle: String? = null, emoji: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text((if (emoji != null) "$emoji " else "") + title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, minHeight: Int = 0, password: Boolean = false,
                  number: Boolean = false, hint: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
        shape = MaterialTheme.shapes.medium,
        singleLine = minHeight == 0,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        supportingText = hint?.let { { Text(it) } },
    )
}

/** Botão principal da tela (ex.: Salvar): largura total, 52dp de altura. */
@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Escolha da persona editada nas telas Personas e Fotos. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun PersonaPicker(personas: List<Persona>, pid: Int, withOrchestrator: Boolean = false, onPick: (Int) -> Unit) {
    val ctx = LocalContext.current
    val on = Color(0xFF2E9E5B)
    val off = MaterialTheme.colorScheme.outline
    @Composable
    fun Dot(active: Boolean) = Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) on else off))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        personas.forEach { p ->
            FilterChip(selected = p.id == pid, onClick = { onPick(p.id) }, modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = if (p.id == pid) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                label = { Row(verticalAlignment = Alignment.CenterVertically) { Dot(p.active); Spacer(Modifier.width(6.dp)); Text(p.label, maxLines = 1) } })
        }
        if (withOrchestrator) {
            // O Orquestrador não é uma persona: fica à parte, depois de um separador.
            val orchOn = remember(pid) { Orchestrator.load(ctx).enabled }
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
            FilterChip(selected = pid == ORCHESTRATOR_ID, onClick = { onPick(ORCHESTRATOR_ID) }, modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = if (pid == ORCHESTRATOR_ID) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                label = { Row(verticalAlignment = Alignment.CenterVertically) { Dot(orchOn); Spacer(Modifier.width(6.dp)); Text("🧭 Orquestrador", maxLines = 1) } })
        }
    }
}

/** Id interno da tela do Orquestrador (à parte das 3 personas), que distribui as conversas entre elas. */
internal const val ORCHESTRATOR_ID = 4

// ---------------- Início ----------------

@Composable
private fun HomeScreen(config: Config, save: (Config) -> Unit, resumeTick: Int) {
    val ctx = LocalContext.current
    fun readStatus() = (1..3).associateWith { runCatching { JSONObject(AtendenteService.statusJson(it)) }.getOrDefault(JSONObject()) }
    var status by remember { mutableStateOf(readStatus()) }
    var queued by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            status = readStatus()
            queued = AiQueue.waitingCount
            delay(1500)
        }
    }
    val anyRunning = (1..config.numbers).any { status[it]?.optString("state", "stopped") != "stopped" }
    val phones = remember { mutableStateMapOf<Int, String>() }
    var keys by remember { mutableStateOf(listOf(config.apiKey, config.apiKey2, config.apiKey3)) }
    var model by remember { mutableStateOf(config.model) }
    var rpm by remember { mutableStateOf(config.rpmLimit.toString()) }
    var confirmLogout by remember { mutableStateOf(0) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val batteryOk = remember(resumeTick) {
        (ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val notifOk = remember(resumeTick) {
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val personas = remember(resumeTick, config) { Personas.load(ctx) }

    val connected = (1..config.numbers).count { status[it]?.optString("state") == "connected" }
    val pairing = (1..config.numbers).any { status[it]?.optString("state") in listOf("needs_pairing", "logged_out", "pairing") }
    val dark = isSystemInDarkTheme()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val p = MaterialTheme.colorScheme.primary
        val heroBrush = Brush.linearGradient(listOf(if (dark) lerp(p, Color.Black, 0.45f) else p, lerp(p, Color.Black, if (dark) 0.65f else 0.45f)))
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(MaterialTheme.shapes.extraLarge).background(heroBrush).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (anyRunning) "Robô ligado" else "Robô desligado", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(when {
                !anyRunning -> "Ligue o motor para o robô voltar a responder o WhatsApp."
                pairing -> "Conecte o número do WhatsApp mais abaixo."
                else -> "$connected de ${config.numbers} ${if (config.numbers > 1) "números conectados" else "número conectado"}. Responde o WhatsApp com o Gemini, direto deste celular."
            }, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            if (!anyRunning) {
                Button(modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0A5F57)),
                    onClick = {
                        if (!notifOk && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                        AtendenteService.start(ctx)
                    }) { Text("Ligar o motor do WhatsApp") }
            } else {
                OutlinedButton(modifier = Modifier.fillMaxWidth().height(52.dp), shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
                    onClick = { AtendenteService.start(ctx, AtendenteService.ACTION_STOP) }) { Text("Desligar motor") }
            }
        }

        Section("1. Números de WhatsApp") {
            Text("Quantos números este app atende (cada persona escolhe o seu na aba Personas):")
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..3).forEach { n ->
                    RadioButton(selected = config.numbers == n, onClick = {
                        save(config.copy(numbers = n))
                        if (anyRunning) AtendenteService.start(ctx)
                    })
                    Text("$n", Modifier.padding(end = 12.dp))
                }
            }
        }

        (1..config.numbers).forEach { conn ->
            val st = status[conn] ?: JSONObject()
            val state = st.optString("state", "stopped")
            val who = personas.filter { it.active && it.connection == conn }.joinToString { it.label }
            Section(if (config.numbers > 1) "WhatsApp $conn" else "WhatsApp da loja") {
                val label = when (state) {
                    "connected" -> "✅ Conectado" + st.optString("phone").let { if (it.isNotBlank()) " (+$it)" else "" }
                    "needs_pairing" -> "Número não conectado"
                    "pairing" -> "Aguardando o código no WhatsApp"
                    "disconnected" -> "Sem conexão, tentando de novo…"
                    "logged_out" -> "Número desconectado pelo celular"
                    "banned" -> "⛔ Bloqueado temporariamente pelo WhatsApp"
                    "stopped" -> "Motor desligado"
                    else -> "Ligando…"
                }
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(if (who.isBlank()) "⚠️ Nenhuma persona ativa usa este número (ele não responde ninguém)." else "Personas: $who",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (who.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                st.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state == "needs_pairing" || state == "logged_out" || state == "pairing") {
                    val phone = phones[conn] ?: ""
                    Field("Número com DDI e DDD", phone, { phones[conn] = it }, number = true, hint = "Ex.: 5511999998888")
                    Button(enabled = phone.filter(Char::isDigit).length >= 12,
                        onClick = { AtendenteService.start(ctx, AtendenteService.ACTION_PAIR, phone, conn) }) { Text("Gerar código de conexão") }
                    val code = st.optString("pairCode")
                    if (code.isNotBlank()) {
                        Text(code.chunked(4).joinToString("-"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("No WhatsApp deste número: ⋮ › Aparelhos conectados › Conectar aparelho › Conectar com número de telefone, e digite o código acima.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state == "connected" || state == "disconnected") {
                    OutlinedButton(onClick = { confirmLogout = conn }) { Text("Desconectar número") }
                }
            }
        }

        Section("2. Inteligência (Gemini)") {
            Text("Crie a chave grátis em aistudio.google.com › Get API key. Fica só neste celular. A chave 1 é obrigatória; " +
                "as chaves 2 e 3 são opcionais (cada persona escolhe qual usa; vazia = usa a chave 1).", style = MaterialTheme.typography.bodySmall)
            (0..2).forEach { i ->
                Field("Chave ${i + 1} do Gemini", keys[i], { v -> keys = keys.toMutableList().also { it[i] = v } }, password = true)
            }
            Text("O limite grátis do Google é por projeto, não por chave: duas chaves do mesmo projeto dividem a mesma cota. " +
                "Para separar, crie cada chave em \"Create API key in new project\".", style = MaterialTheme.typography.bodySmall)
            val dup = keys.map { it.trim() }.filter { it.isNotBlank() }.let { it.size != it.toSet().size }
            if (dup) Text("⚠️ Há chaves repetidas.", color = MaterialTheme.colorScheme.error)
            Field("Modelo", model, { model = it }, hint = "Recomendado: gemini-3.6-flash")
            Field("Chamadas por minuto em cada chave", rpm, { rpm = it.filter(Char::isDigit) }, number = true,
                hint = "Plano grátis: deixe 10. Com muitas conversas ao mesmo tempo, as respostas entram numa fila e podem atrasar.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !testing && keys.any { it.isNotBlank() }, onClick = {
                    testing = true; testResult = "Testando…"
                    scope.launch {
                        val m = model.trim().ifBlank { "gemini-3.6-flash" }
                        testResult = withContext(Dispatchers.IO) {
                            keys.mapIndexedNotNull { i, k -> k.trim().takeIf { it.isNotBlank() }?.let { i to it } }.joinToString("\n") { (i, k) ->
                                runCatching {
                                    val contents = org.json.JSONArray().put(JSONObject().put("role", "user")
                                        .put("parts", org.json.JSONArray().put(JSONObject().put("text", "Diga só: ok"))))
                                    val t0 = System.currentTimeMillis()
                                    Gemini.generate(k, m, "Responda em uma palavra.", contents)
                                    val secs = (System.currentTimeMillis() - t0) / 1000.0
                                    val used = Gemini.lastWorkingModel
                                    Store.setAiVerified(ctx)
                                    "✅ Chave ${i + 1}: funcionou com $used em ${"%.1f".format(secs)} s" +
                                        if (used != m) " (⚠️ \"$m\" não respondeu; troque o Modelo para \"$used\")" else ""
                                }.getOrElse { "❌ Chave ${i + 1}: ${it.message}" }
                            }
                        }
                        testing = false
                    }
                }) { Text("Testar chaves") }
            }
            if (testResult.isNotBlank()) Text(testResult, style = MaterialTheme.typography.bodySmall)
            PrimaryButton("Salvar") {
                save(config.copy(apiKey = keys[0].trim(), apiKey2 = keys[1].trim(), apiKey3 = keys[2].trim(),
                    model = model.trim().ifBlank { "gemini-3.6-flash" }, rpmLimit = rpm.toIntOrNull()?.coerceIn(1, 1000) ?: 10))
            }
        }

        Section("3. Atendimento automático") {
            SwitchRow("Responder clientes automaticamente", config.enabled) { on ->
                save(config.copy(enabled = on))
                if (on) AtendenteService.start(ctx)
            }
            if (config.enabled && config.apiKey.isBlank()) Text("⚠️ Cadastre a chave 1 do Gemini acima.", color = MaterialTheme.colorScheme.error)
            if (config.enabled && personas.none { it.active && it.instructions.isNotBlank() })
                Text("⚠️ Escreva as instruções na aba Personas.", color = MaterialTheme.colorScheme.error)
        }

        Section("Diagnóstico") {
            val received = remember(status) { Store.counter(ctx, "received") }
            val answered = remember(status) { Store.counter(ctx, "answered") }
            val err = remember(status) { Store.lastError(ctx) }
            Text("Mensagens que chegaram ao app: $received")
            Text("Respostas enviadas: $answered")
            if (queued > 0) Text("Na fila da IA agora: $queued")
            if (err != null) {
                Text("Último erro (${SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(err.second))}): ${err.first}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text("Se chegam mensagens e não há resposta, a aba Registro mostra o motivo de cada uma.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { Store.resetDiagnostics(ctx) }) { Text("Zerar contadores") }
        }

        Section("4. Funcionar em segundo plano") {
            Text(if (batteryOk) "✅ Economia de bateria liberada" else "O Android pode fechar o atendente para economizar bateria.")
            if (!batteryOk) Button(onClick = {
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + ctx.packageName)))
                }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Liberar uso em segundo plano") }
            Text(if (notifOk) "✅ Avisos liberados" else "Libere os avisos para saber quando um cliente pedir atendente.")
            if (!notifOk && Build.VERSION.SDK_INT >= 33) Button(onClick = { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Liberar avisos") }
            Text("Em Xiaomi, Samsung e Motorola, também deixe o app sem restrição em Configurações › Apps › Atendente IA › Bateria.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (confirmLogout > 0) {
        val conn = confirmLogout
        AlertDialog(onDismissRequest = { confirmLogout = 0 },
            title = { Text("Desconectar o número${if (config.numbers > 1) " $conn" else ""}?") },
            text = { Text("O atendente para de responder nesse número até você conectar de novo com um código.") },
            confirmButton = { TextButton(onClick = { confirmLogout = 0; AtendenteService.start(ctx, AtendenteService.ACTION_LOGOUT, conn = conn) }) { Text("Desconectar") } },
            dismissButton = { TextButton(onClick = { confirmLogout = 0 }) { Text("Cancelar") } })
    }
}

// ---------------- Personas ----------------

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun StoreScreen(config: Config, persona: Persona, all: List<Persona>, base: Config,
                        savePersona: (Config, Persona) -> Unit) {
    var pp by remember(persona) { mutableStateOf(persona) }
    var followMax by remember(base) { mutableStateOf(base.followMaxPerHour.toString()) }
    val ctx = LocalContext.current
    fun save(edited: Config) {
        // Fotos que saíram do prompt: o arquivo só é apagado se nenhuma persona usa.
        val removed = persona.photos.map { it.file } - edited.photos.map { it.file }.toSet()
        savePersona(edited.copy(followMaxPerHour = followMax.toIntOrNull()?.coerceIn(1, 200) ?: 20), pp)
        val inUse = Personas.allPhotoFiles(ctx)
        removed.filter { it.isNotBlank() && it !in inUse }.forEach { File(Store.photosDir(ctx), it).delete() }
    }
    var contactsOk by remember { mutableStateOf(Contacts.hasPermission(ctx)) }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOk = it }
    var c by remember(config) { mutableStateOf(config) }
    var saved by remember { mutableStateOf(false) }
    var wizardOpen by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var doneAt by remember { mutableStateOf(Store.wizardDoneAt(ctx)) }
    val aiOk = remember { Store.aiVerified(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section("Persona: ${pp.label}") {
            Text("Até 3 personas (ex.: vendedores de produtos diferentes), cada uma com instruções, fotos e links próprios. " +
                "No mesmo número, a persona é escolhida pela palavra-chave ou pela IA (pelo \"quando usar\") e fica presa à conversa.",
                style = MaterialTheme.typography.bodySmall)
            SwitchRow("Persona ativa", pp.active) { pp = pp.copy(active = it) }
            if (pp.id == 1 && !pp.active) Text("⚠️ Com a persona 1 desligada, o número dela só responde se outra persona usar o mesmo número.",
                style = MaterialTheme.typography.bodySmall)
            Field("Nome da persona (só para você)", pp.label, { pp = pp.copy(label = it) })
            Field("Quando usar esta persona", pp.whenToUse, { pp = pp.copy(whenToUse = it) }, minHeight = 60,
                hint = "Ex.: cliente pergunta de bolos, doces ou encomendas. A IA lê isto para escolher.")
            Field("Palavras-chave que escolhem esta persona", pp.keywords, { pp = pp.copy(keywords = it) },
                hint = "Separe por vírgula. Escolhe na hora, sem gastar IA.")
            if (base.numbers > 1) {
                Text("Número de WhatsApp desta persona:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (1..base.numbers).forEach { n ->
                        RadioButton(selected = pp.connection == n, onClick = { pp = pp.copy(connection = n) })
                        Text("$n", Modifier.padding(end = 12.dp))
                    }
                }
            }
            Text("Chave do Gemini desta persona:")
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..3).forEach { n ->
                    RadioButton(selected = pp.keySlot == n, onClick = { pp = pp.copy(keySlot = n) })
                    Text("$n", Modifier.padding(end = 12.dp))
                }
            }
            val myKey = Personas.keyFor(base, pp.keySlot)
            val sameKey = all.filter { it.id != pp.id && it.active && Personas.keyFor(base, it.keySlot) == myKey }.map { it.label }
            if (pp.keySlot > 1 && (if (pp.keySlot == 2) base.apiKey2 else base.apiKey3).isBlank())
                Text("A chave ${pp.keySlot} está vazia: esta persona vai usar a chave 1.", style = MaterialTheme.typography.bodySmall)
            if (pp.active && myKey.isNotBlank() && sameKey.isNotEmpty())
                Text("⚠️ A mesma chave é usada por: ${sameKey.joinToString()}. Elas dividem o mesmo limite do Google.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("Para uma chave em projeto separado: aistudio.google.com › Get API key › Create API key › \"Create API key in new project\", " +
                "e cole na aba Início como chave 2 ou 3.", style = MaterialTheme.typography.bodySmall)
            Text("A persona fica com a conversa até o fim. Só troca se o prompt mestre do Orquestrador mandar.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Papel do robô:")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Personas.ROLES.forEach { r ->
                    FilterChip(selected = pp.role == r, onClick = { pp = pp.copy(role = r) }, label = { Text(Personas.roleLabel(r), maxLines = 1) })
                }
            }
            if (pp.role == "vendedor") {
                Text("Como fecha a venda:")
                Personas.CLOSE_MODES.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = pp.closeMode == m, onClick = { pp = pp.copy(closeMode = m) })
                        Text(Personas.closeLabel(m), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (pp.closeMode == "pagina" || pp.closeMode == "afiliado")
                    Field("Link da página de vendas / afiliado", pp.salesPageUrl, { pp = pp.copy(salesPageUrl = it.trim()) },
                        hint = "Começa com https://")
            }
        }
        PersonaPromptCard(base, Personas.fromConfig(pp, c)) { imp ->
            pp = imp
            c = c.copy(storeName = imp.storeName, instructions = imp.instructions, preferences = imp.preferences,
                handoffMessage = imp.handoffMessage, links = imp.links, siteSources = imp.siteSources, photos = imp.photos)
        }
        LessonsSection(pp)
        Section("Vendas e retomada (follow-up)") {
            SwitchRow("Modo vendedor (técnicas de venda e etiquetas do funil)", pp.salesMode) { pp = pp.copy(salesMode = it) }
            Field("Ofertas que a IA pode usar", pp.offers, { pp = pp.copy(offers = it) }, minHeight = 60,
                hint = "Descontos, bônus e prazos permitidos. Vazio = a IA não oferece nada além do preço.")
            SwitchRow("Retomar quem parou de responder", pp.followUp) { pp = pp.copy(followUp = it) }
            if (pp.followUp) {
                Text("Só para quem escreveu primeiro; para assim que o cliente responde. Início do funil: 1ª retomada e mais uma no dia seguinte. " +
                    "Quem já viu preço: 1ª retomada, dia 1, dia 3 (descobrir a objeção) e despedida no dia 6. \"Pare\" = não perturbar.",
                    style = MaterialTheme.typography.bodySmall)
                Field("1ª retomada depois de (minutos sem resposta)", pp.followFirstMinutes.toString(),
                    { pp = pp.copy(followFirstMinutes = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(5, 1440) ?: 30) }, number = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        Field("Das (hora)", pp.followStartHour.toString(),
                            { pp = pp.copy(followStartHour = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(0, 23) ?: 8) }, number = true)
                    }
                    Box(Modifier.weight(1f)) {
                        Field("Até (hora)", pp.followEndHour.toString(),
                            { pp = pp.copy(followEndHour = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 24) ?: 20) }, number = true)
                    }
                }
                SwitchRow("Mandar no domingo", pp.followSunday) { pp = pp.copy(followSunday = it) }
                Field("Máximo de retomadas por hora em cada número", followMax, { followMax = it.filter(Char::isDigit) }, number = true,
                    hint = "Vale para todas. Proteção contra bloqueio do WhatsApp (padrão 20).")
            }
        }
        Section("Assistente de configuração") {
            if (doneAt == 0L) {
                Text("A IA faz perguntas (com opções para tocar), lê seu site, PDF ou foto do cardápio, entende áudio " +
                    "e preenche todos os campos desta aba no padrão certo.", style = MaterialTheme.typography.bodySmall)
                if (aiOk) {
                    Button(onClick = { wizardOpen = true }) { Text(if (Wizard.load(ctx).isEmpty()) "Configurar com o assistente" else "Continuar o assistente") }
                } else {
                    Text("⚠️ Primeiro cadastre e teste a chave da IA na aba Início (botão \"Testar conexão\").", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("✅ Configurado pelo assistente em " + SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(Date(doneAt)) +
                    ". Você pode editar os campos abaixo à vontade.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmRestore = true }) { Text("Restaurar e refazer com o assistente") }
            }
        }
        Section("Instruções da loja (${pp.label})") {
            Field("Nome da loja", c.storeName, { c = c.copy(storeName = it) })
            Field("O que a IA precisa saber", c.instructions, { c = c.copy(instructions = it) }, minHeight = 220,
                hint = "Produtos e preços, horário, endereço, entrega/frete, formas de pagamento, prazos, o que NÃO fazer…")
            Field("Preferências de atendimento", c.preferences, { c = c.copy(preferences = it) }, minHeight = 120,
                hint = "Tom de voz, como cumprimentar, quando oferecer o catálogo, emojis…")
        }
        Section("Atendimento humano") {
            Field("Mensagem ao passar para a equipe", c.handoffMessage, { c = c.copy(handoffMessage = it) }, minHeight = 80)
            Field("Quando o cliente pede atendente, pausar por (horas)", c.pauseHours.toString(),
                { c = c.copy(pauseHours = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 720) ?: 1) }, number = true)
            SwitchRow("Pausar quando eu responder pelo meu WhatsApp", c.pauseOnOwnerReply) { c = c.copy(pauseOnOwnerReply = it) }
            if (c.pauseOnOwnerReply) Field("Robô volta depois de quantos minutos sem eu responder", c.ownerPauseMinutes.toString(),
                { c = c.copy(ownerPauseMinutes = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 1440) ?: 5) }, number = true,
                hint = "Cada mensagem sua na conversa recomeça a contagem.")
        }
        Section("Ativar só com palavra-chave") {
            Text("Para usar no WhatsApp pessoal: o robô só responde numa conversa depois que a pessoa escreve a palavra-chave " +
                "(aceita maiúscula, minúscula, sem acento e pequenos erros). As outras conversas são ignoradas sem registro, " +
                "e as ligações seguem a opção de ligações abaixo.", style = MaterialTheme.typography.bodySmall)
            SwitchRow("Responder só depois da palavra-chave", c.triggerEnabled) { c = c.copy(triggerEnabled = it) }
            if (c.triggerEnabled) {
                Field("Palavras-chave", c.triggerWords, { c = c.copy(triggerWords = it) },
                    hint = "Separe por vírgula. Ex.: atendimento, quero comprar, catálogo")
                Field("Conversa fica ativa por (minutos)", c.triggerMinutes.toString(),
                    { c = c.copy(triggerMinutes = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 1440) ?: 30) }, number = true,
                    hint = "Renova a cada mensagem. Depois disso, precisa da palavra-chave de novo.")
                if (c.triggerWords.isBlank()) Text("⚠️ Sem palavra-chave o robô não responde ninguém.", color = MaterialTheme.colorScheme.error)
            }
        }
        Section("Comportamento") {
            Field("Esperar antes de responder (segundos)", c.delaySeconds.toString(),
                { c = c.copy(delaySeconds = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(2, 60) ?: 8) }, number = true,
                hint = "Junta várias mensagens seguidas do cliente numa resposta só.")
            SwitchRow("Responder como gente (humanizar)", c.humanize) { c = c.copy(humanize = it) }
            if (c.humanize) {
                Text("Divide respostas longas em mensagens curtas, varia o jeito de falar, acompanha o tom do cliente " +
                    "e evita frases de robô (\"Fico à disposição\", \"Como posso ajudar?\").", style = MaterialTheme.typography.bodySmall)
                Field("Intervalo entre as mensagens divididas (segundos)", c.partGapSeconds.toString(),
                    { c = c.copy(partGapSeconds = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 30) ?: 3) }, number = true,
                    hint = "Tempo de \"digitando…\" antes de cada mensagem seguinte. Padrão: 3.")
                Field("Espera antes de mandar link ou foto (segundos)", c.mediaGapSeconds.toString(),
                    { c = c.copy(mediaGapSeconds = it.filter(Char::isDigit).toIntOrNull()?.coerceIn(0, 30) ?: 3) }, number = true,
                    hint = "O texto vai primeiro; o link ou a foto vem depois, como alguém procurando o arquivo.")
                Text("Emojis por resposta:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(0 to "Nenhum", 1 to "Até 1", 2 to "Até 2", 3 to "Até 3").forEach { (v, label) ->
                        RadioButton(selected = c.emojiLimit == v, onClick = { c = c.copy(emojiLimit = v) })
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            SwitchRow("Entender áudios", c.understandAudio) { c = c.copy(understandAudio = it) }
            SwitchRow("Entender fotos do cliente", c.understandImages) { c = c.copy(understandImages = it) }
            SwitchRow("Marcar como lida", c.markRead) { c = c.copy(markRead = it) }
            SwitchRow("Ignorar grupos", c.ignoreGroups) { c = c.copy(ignoreGroups = it) }
            Text("Ligações de voz/vídeo pelo WhatsApp:")
            listOf(
                "off" to "Não recusar ligações",
                "all" to "Recusar todas as ligações",
                "unknown" to "Recusar só de quem não está na minha agenda",
            ).forEach { (v, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = c.callPolicy == v, onClick = {
                        c = c.copy(callPolicy = v)
                        if (v == "unknown" && !Contacts.hasPermission(ctx)) contactsPerm.launch(Manifest.permission.READ_CONTACTS)
                    })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (c.callPolicy == "unknown" && !contactsOk) {
                Text("⚠️ Libere o acesso à agenda; sem ele o app não recusa nenhuma ligação.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { contactsPerm.launch(Manifest.permission.READ_CONTACTS) }) { Text("Liberar acesso à agenda") }
            }
            if (c.callPolicy != "off") Field("Mensagem depois de recusar (deixe vazio para não mandar nada)", c.callNotice,
                { c = c.copy(callNotice = it) }, minHeight = 80)
            Field("Responder só estes números (opcional)", c.onlyNumbers, { c = c.copy(onlyNumbers = it) },
                hint = "Separe por vírgula. Útil para testar com o seu número antes.")
            Field("Nunca responder estes números", c.blockedNumbers, { c = c.copy(blockedNumbers = it) }, hint = "Separe por vírgula.")
        }
        Spacer(Modifier.height(6.dp))
        PrimaryButton(if (saved) "Salvo ✓" else "Salvar") { save(c); saved = true }
        LaunchedEffect(c, pp, followMax) { saved = false }
        Spacer(Modifier.height(24.dp))
    }
    if (wizardOpen) {
        WizardDialog(config, onClose = { wizardOpen = false; doneAt = Store.wizardDoneAt(ctx) }, onApply = { save(it) })
    }
    if (confirmRestore) {
        AlertDialog(onDismissRequest = { confirmRestore = false },
            title = { Text("Restaurar a configuração?") },
            text = { Text("Apaga nome, instruções, preferências, links e sites desta aba (chaves, fotos, voz e vendas ficam). " +
                "Depois o assistente abre para configurar tudo de novo.") },
            confirmButton = { TextButton(onClick = {
                confirmRestore = false
                save(Wizard.restore(config))
                Store.setWizardDoneAt(ctx, 0L); doneAt = 0L
                Wizard.clear(ctx)
                wizardOpen = true
            }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancelar") } })
    }
}

/** Aprendizados salvos pelo botão "Orientar a IA" (ou escritos aqui). */
@Composable
private fun LessonsSection(p: Persona) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val list = remember(tick, p.id) { runCatching { Memory.lessons(ctx, p.id) }.getOrDefault(emptyList()) }
    var newText by remember { mutableStateOf("") }
    Section("Aprendizados (${list.size})") {
        Text("Regras que a IA aprendeu com as suas orientações (botão \"Orientar a IA\" na aba Conversas). " +
            "Valem em todas as próximas conversas desta persona.", style = MaterialTheme.typography.bodySmall)
        list.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (l.persona == 0) "🌐 " else "") + l.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { Memory.deleteLesson(ctx, l.id); tick++ }) { Icon(Icons.Default.Delete, "Apagar") }
            }
        }
        if (list.any { it.persona == 0 }) Text("🌐 = vale para todas as personas.", style = MaterialTheme.typography.labelSmall)
        Field("Escrever um aprendizado", newText, { newText = it }, minHeight = 60)
        OutlinedButton(enabled = newText.isNotBlank(), onClick = { Memory.addLesson(ctx, p.id, newText, "manual"); newText = ""; tick++ }) {
            Text("Adicionar")
        }
    }
}

// ---------------- Fotos e links ----------------

@Composable
private fun MediaScreen(config: Config, save: (Config) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var newPhoto by remember { mutableStateOf<String?>(null) }
    var newDesc by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var linkTitle by remember { mutableStateOf("") }
    var linkUrl by remember { mutableStateOf("") }
    var byLinkOpen by remember { mutableStateOf(false) }
    var byLink by remember { mutableStateOf("") }
    var byLinkBusy by remember { mutableStateOf(false) }
    var byLinkError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val name = withContext(Dispatchers.IO) { copyPhoto(ctx, uri) }
            if (name != null) {
                newPhoto = name; newDesc = ""; newUrl = ""; newId = "foto${config.photos.size + 1}"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Section("Fotos que a IA pode enviar") {
            Text("Descreva bem cada foto: é pela descrição que a IA decide quando mandar.", style = MaterialTheme.typography.bodySmall)
            if (config.photos.isEmpty()) EmptyState(Icons.Default.Star, "Nenhuma foto ainda. Adicione fotos dos seus produtos para a IA enviar aos clientes.")
            config.photos.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Thumb(File(Store.photosDir(ctx), p.file))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(p.id, fontWeight = FontWeight.SemiBold)
                        Text(p.description, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        save(config.copy(photos = config.photos - p))
                        // Só apaga o arquivo se nenhuma outra persona usa a mesma foto.
                        if (p.file !in Personas.allPhotoFiles(ctx)) File(Store.photosDir(ctx), p.file).delete()
                    }) { Icon(Icons.Default.Delete, "Apagar") }
                }
            }
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Default.Add, null); Text(" Adicionar foto")
            }
            OutlinedButton(onClick = { byLink = ""; byLinkError = null; byLinkOpen = true }) { Text("Adicionar por link") }
        }
        Section("Links que a IA pode enviar") {
            config.links.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.title, fontWeight = FontWeight.SemiBold)
                        Text(l.url, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { save(config.copy(links = config.links - l)) }) { Icon(Icons.Default.Delete, "Apagar") }
                }
            }
            Field("Para que serve (ex.: Catálogo, Instagram, Localização)", linkTitle, { linkTitle = it })
            Field("Endereço (https://…)", linkUrl, { linkUrl = it })
            Button(enabled = linkTitle.isNotBlank() && linkUrl.trim().startsWith("http"), onClick = {
                save(config.copy(links = config.links + LinkItem(linkTitle.trim(), linkUrl.trim())))
                linkTitle = ""; linkUrl = ""
            }) { Text("Adicionar link") }
        }
        SiteSourcesSection(config, save)
    }

    if (byLinkOpen) {
        AlertDialog(onDismissRequest = { if (!byLinkBusy) byLinkOpen = false },
            title = { Text("Adicionar foto por link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Link da foto (https://…)", byLink, { byLink = it.trim() })
                    Text("O app baixa a imagem para o celular (até 8 MB). O link também entra no prompt da persona.",
                        style = MaterialTheme.typography.bodySmall)
                    if (byLinkBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    byLinkError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !byLinkBusy && byLink.startsWith("http"), onClick = {
                    byLinkBusy = true; byLinkError = null
                    val url = byLink
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { PhotoSync.download(ctx, url) } }
                        byLinkBusy = false
                        r.onSuccess { name ->
                            newPhoto = name; newDesc = ""; newUrl = url; newId = "foto${config.photos.size + 1}"; byLinkOpen = false
                        }.onFailure { byLinkError = "Não consegui baixar: ${it.message}" }
                    }
                }) { Text("Baixar") }
            },
            dismissButton = { TextButton(enabled = !byLinkBusy, onClick = { byLinkOpen = false }) { Text("Cancelar") } })
    }

    newPhoto?.let { file ->
        AlertDialog(onDismissRequest = {},
            title = { Text("Nova foto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Thumb(File(Store.photosDir(ctx), file), 140)
                    Field("Nome curto (id)", newId, { newId = it.lowercase().replace(Regex("[^a-z0-9_-]"), "") })
                    Field("Descrição (o que é, preço…)", newDesc, { newDesc = it }, minHeight = 80)
                    Field("Link da foto (opcional)", newUrl, { newUrl = it.trim() },
                        hint = "Se preencher, o link entra no prompt e a foto pode ser baixada de novo em outro aparelho.")
                }
            },
            confirmButton = {
                TextButton(enabled = newId.isNotBlank() && newDesc.isNotBlank() && config.photos.none { it.id == newId }, onClick = {
                    save(config.copy(photos = config.photos + PhotoItem(newId, newDesc.trim(), file, newUrl.trim().takeIf { it.startsWith("http") } ?: "")))
                    newPhoto = null
                }) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { File(Store.photosDir(ctx), file).delete(); newPhoto = null }) { Text("Cancelar") }
            })
    }
}

@Composable
private fun Thumb(file: File, size: Int = 64) {
    val bmp = remember(file.path) {
        runCatching {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            BitmapFactory.decodeFile(file.path, o)?.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size.dp), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/** Copia a foto escolhida para o app, reduzida para no máximo 1600 px (JPEG). */
private fun copyPhoto(ctx: android.content.Context, uri: Uri): String? = runCatching {
    val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    val name = UUID.randomUUID().toString() + ".jpg"
    File(Store.photosDir(ctx), name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
    name
}.getOrNull()

// ---------------- Testar ----------------

private fun agoText(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        t <= 0 -> ""
        m < 1 -> "agora"
        m < 60 -> "há $m min"
        m < 60 * 24 -> "há ${m / 60} h"
        else -> "há ${m / (60 * 24)} d"
    }
}

/** Aba Atender: conversas reais recentes (toque para assumir). */
@Composable
private fun TestScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var openKey by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5000); tick++ } }

    openKey?.let { k -> ConversationDetail(k) { openKey = null }; return }

    val recent = remember(tick, resumeTick) {
        runCatching { Memory.list(ctx, 60).sortedByDescending { maxOf(it.updated, it.lastIn) }.take(30) }.getOrDefault(emptyList())
    }
    val found = remember(q, tick) { runCatching { Memory.search(ctx, q, 30) }.getOrDefault(emptyList()) }
    val shown = if (q.isBlank()) recent else found
    val personas = remember(tick) { Personas.load(ctx) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Assumir conversa: nome ou parte do número") })
            if (q.isNotBlank() && found.isEmpty()) Text(
                if (q.filter(Char::isDigit).length in 1..2) "Digite pelo menos 3 números." else "Nenhum cliente encontrado.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(if (q.isBlank()) "Conversas recentes" else "Resultado da busca",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (shown.isEmpty() && q.isBlank()) item {
            EmptyState(Icons.Default.Email, "Ainda não há conversas. Quando alguém escrever no WhatsApp, aparece aqui.")
        }
        items(shown, key = { it.key }) { cv ->
            val paused = Store.isPaused(ctx, cv.chat) || Store.isPaused(ctx, cv.key)
            val pLabel = personas.firstOrNull { it.id == cv.persona }?.label
            Card(Modifier.fillMaxWidth().clickable { openKey = cv.key }, shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cv.name.ifBlank { Store.nameOf(ctx, cv.chat) }.ifBlank { "Sem nome" }, Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(agoText(maxOf(cv.updated, cv.lastIn)), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(buildString {
                        if (cv.phone.isNotBlank()) append("+${cv.phone}")
                        if (pLabel != null) append(if (isEmpty()) pLabel else " · $pLabel")
                        if (paused) append(if (isEmpty()) "⏸ pausada" else " · ⏸ pausada (atendimento humano)")
                    }, style = MaterialTheme.typography.bodySmall,
                        color = if (paused) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cv.lastText.isNotBlank()) Text(cv.lastText, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ---------------- Registro ----------------

@Composable
private fun LogScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    val log = remember(tick, resumeTick) { Store.logLines(ctx) }
    val paused = remember(tick, resumeTick) { Store.pausedMap(ctx) }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }
    var confirmWipe by remember { mutableStateOf(false) }
    if (confirmWipe) AlertDialog(onDismissRequest = { confirmWipe = false },
        title = { Text("Apagar a memória?") },
        text = { Text("Apaga todas as conversas guardadas no app (histórico, resumos, etiquetas e retomadas agendadas). Não dá para desfazer.") },
        confirmButton = { TextButton(onClick = { confirmWipe = false; Memory.deleteAll(ctx); History.clearAll(ctx); tick++ }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancelar") } })

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Section("Conversas com atendimento humano") {
                if (paused.isEmpty()) Text("Nenhuma. O robô está respondendo todo mundo.", style = MaterialTheme.typography.bodySmall)
                paused.forEach { (chat, until) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Store.nameOf(ctx, chat), fontWeight = FontWeight.SemiBold)
                            Text("pausado até ${fmt.format(Date(until))}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { Store.resume(ctx, chat); tick++ }) { Text("Voltar o robô") }
                    }
                }
            }
            Row {
                TextButton(onClick = { Store.clearLog(ctx); tick++ }) { Text("Limpar registro") }
                TextButton(onClick = { confirmWipe = true }) { Text("Apagar memória das conversas") }
            }
        }
        if (log.isEmpty()) item { EmptyState(Icons.Default.List, "O registro está vazio. Cada mensagem recebida e respondida aparece aqui.") }
        items(log) { l ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(fmt.format(Date(l.time)), style = MaterialTheme.typography.labelSmall)
                Text(l.text, style = MaterialTheme.typography.bodyMedium)
                Divider(Modifier.padding(top = 6.dp))
            }
        }
    }
}
