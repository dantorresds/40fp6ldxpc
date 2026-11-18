package com.atendenteia.app

import android.content.Context
import android.util.Base64
import com.atendenteia.wa.waengine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Produto digital: o arquivo fica privado no Google Drive da loja. */
data class DigitalProduct(
    val id: String,
    val name: String,
    val price: Double,
    val description: String,
    val driveFileId: String,
    val fileName: String,
    /** Página de vendas ou link de afiliado (tipos info_link e affiliate). */
    val salesUrl: String = "",
    /**
     * info_app  = infoproduto próprio: o app cobra (Pix/cartão) e entrega pelo Drive;
     * info_link = infoproduto vendido na página (Hotmart, Kiwify, site): a IA manda o link;
     * affiliate = produto físico de afiliado: a IA manda o link de afiliado.
     */
    val kind: String = "info_app",
    /** Link de entrega já compartilhado pelo dono (Drive "qualquer pessoa com o link", Dropbox…). Sem Google Cloud. */
    val deliveryUrl: String = "",
) {
    /** Entrega pelo link salvo (true) ou pelo arquivo privado no Drive (false). */
    val byLink get() = deliveryUrl.isNotBlank()
}

data class Order(
    val id: String,
    val chat: String,
    val productId: String,
    val productName: String,
    val amount: Double,
    val email: String,
    val paymentId: String,
    val copyPaste: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** pending → delivered | paid (pago, entrega falhou) | expired | cancelled | rejected… */
    val status: String,
    val link: String = "",
    val notifiedPaid: Boolean = false,
    /** Valor no cartão (com a taxa) e o link; 0/"" se cartão desligado. */
    val cardAmount: Double = 0.0,
    val cardLink: String = "",
)

/**
 * Venda de produto digital pelo WhatsApp, sem servidor:
 * Pix do Mercado Pago → consulta a cada 20 s → libera o arquivo do Drive só
 * para o Gmail do comprador → manda o link. A IA nunca vê token, link ou
 * valor: ela só diz qual produto e qual e-mail; o preço vem deste cadastro.
 */
object Sales {
    private const val PREFS = "atendente_vendas"
    const val DEFAULT_DELIVERY_LINK = "Pagamento confirmado! ✅\n\nAqui está seu *{produto}*:\n{link}\n\nGuarde este link: é por ele que você acessa o material."
    const val DEFAULT_DELIVERY = "Pagamento confirmado! ✅\n\nAqui está seu *{produto}*:\n{link}\n\nAbra estando logado(a) no Google com o e-mail {email} — o acesso é só seu."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    /** Mensagem personalizada; sem ela, o padrão de cada jeito de entregar. */
    fun deliveryMessage(ctx: Context, byLink: Boolean): String {
        val custom = prefs(ctx).getString("delivery", null)
        return if (custom.isNullOrBlank() || custom == DEFAULT_DELIVERY || custom == DEFAULT_DELIVERY_LINK)
            (if (byLink) DEFAULT_DELIVERY_LINK else DEFAULT_DELIVERY) else custom
    }
    fun setDeliveryMessage(ctx: Context, v: String) = prefs(ctx).edit().putString("delivery", v).apply()
    fun driveEmail(ctx: Context) = prefs(ctx).getString("driveEmail", "").orEmpty()
    fun setDriveEmail(ctx: Context, v: String) = prefs(ctx).edit().putString("driveEmail", v).apply()

    fun cardEnabled(ctx: Context) = prefs(ctx).getBoolean("card", true)
    fun setCardEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("card", on).apply()
    fun cardFeePercent(ctx: Context) = prefs(ctx).getFloat("cardFee", 6f).toDouble()
    fun setCardFeePercent(ctx: Context, v: Double) = prefs(ctx).edit().putFloat("cardFee", v.toFloat()).apply()
    fun cardPrice(ctx: Context, price: Double) = Math.round(price * (1 + cardFeePercent(ctx) / 100) * 100) / 100.0

    fun mpToken(ctx: Context) = SecureStore.get(ctx, "mp_token")
    fun setMpToken(ctx: Context, v: String) = SecureStore.put(ctx, "mp_token", v.trim())

    // ---------------- Produtos ----------------

    fun products(ctx: Context): List<DigitalProduct> {
        val a = JSONArray(prefs(ctx).getString("products", "[]") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            DigitalProduct(it.optString("id"), it.optString("name"), it.optDouble("price"), it.optString("description"),
                it.optString("driveFileId"), it.optString("fileName"), it.optString("salesUrl"), it.optString("kind", "info_app"), it.optString("deliveryUrl"))
        }
    }

    fun saveProducts(ctx: Context, list: List<DigitalProduct>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("price", it.price).put("description", it.description)
                .put("driveFileId", it.driveFileId).put("fileName", it.fileName).put("salesUrl", it.salesUrl).put("kind", it.kind).put("deliveryUrl", it.deliveryUrl))
        }
        prefs(ctx).edit().putString("products", a.toString()).apply()
    }

    /** Infoprodutos que o app cobra e entrega: precisam do link de entrega (ou arquivo no Drive) e do token do Mercado Pago. */
    fun payProducts(ctx: Context) =
        if (mpToken(ctx).isBlank()) emptyList() else products(ctx).filter { it.kind == "info_app" && (it.byLink || it.driveFileId.isNotBlank()) }

    /** Produtos vendidos por link (página de vendas ou afiliado). */
    fun linkProducts(ctx: Context) = products(ctx).filter { it.kind != "info_app" && it.salesUrl.isNotBlank() }

    /** Há algo para vender agora. */
    fun active(ctx: Context) = enabled(ctx) && (payProducts(ctx).isNotEmpty() || linkProducts(ctx).isNotEmpty())

    /** O app cobra e entrega (há infoproduto próprio pronto para venda). */
    fun charging(ctx: Context) = enabled(ctx) && payProducts(ctx).isNotEmpty()

    // ---------------- Pedidos ----------------

    fun orders(ctx: Context): List<Order> {
        val a = JSONArray(prefs(ctx).getString("orders", "[]") ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Order(it.optString("id"), it.optString("chat"), it.optString("productId"), it.optString("productName"),
                it.optDouble("amount"), it.optString("email"), it.optString("paymentId"), it.optString("copyPaste"),
                it.optLong("createdAt"), it.optLong("expiresAt"), it.optString("status"), it.optString("link"),
                it.optBoolean("notifiedPaid"), it.optDouble("cardAmount", 0.0), it.optString("cardLink"))
        }
    }

    @Synchronized
    private fun saveOrder(ctx: Context, o: Order) {
        val list = orders(ctx).filter { it.id != o.id } + o
        val a = JSONArray()
        list.takeLast(300).forEach {
            a.put(JSONObject().put("id", it.id).put("chat", it.chat).put("productId", it.productId).put("productName", it.productName)
                .put("amount", it.amount).put("email", it.email).put("paymentId", it.paymentId).put("copyPaste", it.copyPaste)
                .put("createdAt", it.createdAt).put("expiresAt", it.expiresAt).put("status", it.status).put("link", it.link)
                .put("notifiedPaid", it.notifiedPaid).put("cardAmount", it.cardAmount).put("cardLink", it.cardLink))
        }
        prefs(ctx).edit().putString("orders", a.toString()).apply()
    }

    fun hasOpenOrders(ctx: Context) = orders(ctx).any { it.status == "pending" || it.status == "paid" }

    private fun brl(v: Double) = "R$ " + String.format(Locale("pt", "BR"), "%.2f", v)

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /**
     * Gera o Pix e manda QR Code + copia e cola. Devolve uma linha para o
     * Registro. [alert] avisa o dono no celular.
     */
    fun checkout(ctx: Context, e: Engine, chat: String, productId: String, emailRaw: String, alert: (String, String) -> Unit): String {
        if (!charging(ctx)) return "⚠️ Venda: a IA tentou cobrar, mas não há infoproduto próprio pronto para venda."
        val product = payProducts(ctx).firstOrNull { it.id == productId }
            ?: return "⚠️ Venda: a IA pediu o produto \"$productId\", que não existe no cadastro."
        val email = emailRaw.trim().lowercase()
        if (!EMAIL.matches(email)) {
            runCatching { e.sendText(Memory.chatOf(chat), if (product.byLink) "Esse e-mail parece incompleto. Pode me mandar de novo?" else "Esse e-mail parece incompleto. Pode me mandar de novo o seu Gmail?") }
            return "⚠️ Venda: e-mail inválido \"$emailRaw\"."
        }
        // Mesmo produto com Pix ainda válido: reenvia em vez de cobrar de novo.
        orders(ctx).firstOrNull {
            it.chat == chat && it.productId == productId && it.status == "pending" && it.expiresAt - System.currentTimeMillis() > 3 * 60_000
        }?.let { open ->
            runCatching {
                e.sendText(Memory.chatOf(chat), "Seu Pix de ${brl(open.amount)} continua válido. Aqui está o código copia e cola de novo:")
                e.sendText(Memory.chatOf(chat), open.copyPaste)
            }
            return "🔁 Pix reenviado para ${Store.nameOf(ctx, chat)} (${open.productName})."
        }
        val orderId = "atd" + System.currentTimeMillis()
        return try {
            val pix = MercadoPago.createPix(mpToken(ctx), product.price, product.name, email, orderId)
            // Cartão: mesmo pedido, com a taxa somada. Se falhar, segue só com o Pix.
            val cardAmount = if (cardEnabled(ctx)) cardPrice(ctx, product.price) else 0.0
            val cardLink = if (cardAmount > 0) runCatching {
                MercadoPago.createCardLink(mpToken(ctx), cardAmount, product.name, email, orderId)
            }.getOrElse { Store.log(ctx, "⚠️ Link de cartão não gerado: ${it.message}"); "" } else ""
            saveOrder(ctx, Order(orderId, chat, product.id, product.name, product.price, email, pix.paymentId, pix.copyPaste,
                System.currentTimeMillis(), pix.expiresAt, "pending", cardAmount = if (cardLink.isBlank()) 0.0 else cardAmount, cardLink = cardLink))
            val until = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(pix.expiresAt))
            if (pix.qrPngBase64.isNotBlank()) {
                val f = File(ctx.cacheDir, "pix-$orderId.png")
                f.writeBytes(Base64.decode(pix.qrPngBase64, Base64.DEFAULT))
                runCatching { e.sendImage(Memory.chatOf(chat), f.absolutePath, "Pix de ${brl(product.price)} — ${product.name}. Vale até $until.") }
                f.delete()
            }
            e.sendText(Memory.chatOf(chat), if (product.byLink) "Se preferir, use o Pix copia e cola (a próxima mensagem). Assim que o pagamento cair, te mando o acesso aqui mesmo."
                else "Se preferir, use o Pix copia e cola (a próxima mensagem). Assim que o pagamento cair, eu te mando o acesso liberado para o e-mail $email.")
            e.sendText(Memory.chatOf(chat), pix.copyPaste)
            if (cardLink.isNotBlank()) {
                e.sendText(Memory.chatOf(chat), "Prefere cartão? Pague por este link do Mercado Pago: ${brl(cardAmount)} " +
                    "(inclui ${String.format(Locale("pt", "BR"), "%.0f", cardFeePercent(ctx))}% de taxa do cartão):\n$cardLink")
            }
            "💳 Pix de ${brl(product.price)} enviado para ${Store.nameOf(ctx, chat)} (${product.name}, $email)."
        } catch (ex: Exception) {
            runCatching { e.sendText(Memory.chatOf(chat), "Tive um probleminha para gerar o Pix agora. Já chamei a equipe para te ajudar! 🙏") }
            alert("Falha ao gerar Pix", ex.message ?: "erro")
            "⚠️ Venda: não gerei o Pix para ${Store.nameOf(ctx, chat)}: ${ex.message}"
        }
    }

    /** Confere os pagamentos em aberto e entrega os aprovados. Chamado a cada 20 s pelo serviço. */
    fun pollOnce(ctx: Context, engineFor: (Int) -> Engine?, alert: (String, String) -> Unit) {
        val open = orders(ctx).filter { it.status == "pending" || it.status == "paid" }
        if (open.isEmpty()) return
        val token = mpToken(ctx)
        val now = System.currentTimeMillis()
        for (o in open) {
            if (o.status == "pending") {
                // Pix vence em 30 min; o link de cartão vale 2 h: confere até 2 h 30 min.
                if (now > o.createdAt + 150 * 60_000L) { saveOrder(ctx, o.copy(status = "expired")); continue }
                // Pix ou cartão: os dois usam o código do pedido; vale o valor certo de cada um.
                val approved = runCatching { MercadoPago.approvedFor(token, o.id) }.getOrNull() ?: continue
                if (approved.isEmpty()) continue
                val ok = approved.any {
                    kotlin.math.abs(it.amount - o.amount) <= 0.01 || (o.cardAmount > 0 && kotlin.math.abs(it.amount - o.cardAmount) <= 0.01)
                }
                if (!ok) {
                    saveOrder(ctx, o.copy(status = "divergente"))
                    alert("Pagamento com divergência", "${o.productName}: valor pago não confere. Verifique no Mercado Pago.")
                    continue
                }
                deliver(ctx, engineFor(Memory.connOf(o.chat)), o.copy(status = "paid"), alert)
            } else {
                deliver(ctx, engineFor(Memory.connOf(o.chat)), o, alert) // pago, entrega tinha falhado: tenta de novo
            }
        }
    }

    private fun deliver(ctx: Context, e: Engine?, o: Order, alert: (String, String) -> Unit) {
        val product = products(ctx).firstOrNull { it.id == o.productId }
        try {
            if (e == null) throw IllegalStateException("WhatsApp desligado")
            if (product == null) throw IllegalStateException("produto apagado do cadastro")
            val link = if (product.byLink) product.deliveryUrl
                else GoogleDrive.shareWith(GoogleDrive.token(ctx), product.driveFileId, o.email)
            val msg = deliveryMessage(ctx, product.byLink).replace("{produto}", o.productName).replace("{link}", link).replace("{email}", o.email)
            e.sendText(Memory.chatOf(o.chat), msg)
            saveOrder(ctx, o.copy(status = "delivered", link = link))
            Memory.update(ctx, o.chat) { put("stage", "comprou"); put("temperature", "quente"); put("followup_at", 0L) }
            Store.count(ctx, "sales")
            Store.log(ctx, "✅ Venda paga e entregue: ${o.productName} (${brl(o.amount)}) para ${Store.nameOf(ctx, o.chat)} — ${o.email}")
            alert("Venda paga ✅", "${o.productName} — ${brl(o.amount)} — ${Store.nameOf(ctx, o.chat)}")
        } catch (ex: Exception) {
            var saved = o
            if (!o.notifiedPaid && e != null) {
                val gmailProblem = ex.message?.contains("não é uma conta Google") == true
                runCatching {
                    e.sendText(Memory.chatOf(o.chat), if (gmailProblem)
                        "Pagamento confirmado! ✅ Só que o e-mail ${o.email} não é uma conta Google. Me manda um Gmail para eu liberar seu acesso?"
                    else "Pagamento confirmado! ✅ Estou liberando seu acesso e já te envio por aqui.")
                }
                saved = o.copy(notifiedPaid = true)
                alert("Venda paga, entrega pendente", "${o.productName} para ${Store.nameOf(ctx, o.chat)}: ${ex.message}")
                Store.log(ctx, "⚠️ Venda paga mas não entregue (${o.productName}, ${o.email}): ${ex.message}. Vou tentar de novo.")
            }
            saveOrder(ctx, saved)
        }
    }

    /** Cliente diz que não recebeu: reenvia o que é dele (só deste número). */
    fun resend(ctx: Context, e: Engine, chat: String, newEmail: String?, alert: (String, String) -> Unit): String? {
        val mine = orders(ctx).filter { it.chat == chat }
        // Pago com e-mail que não é Google, e o cliente mandou outro: refaz a entrega com o novo.
        val paid = mine.lastOrNull { it.status == "paid" }
        if (paid != null) {
            val email = newEmail?.trim()?.lowercase()?.takeIf { EMAIL.matches(it) } ?: paid.email
            deliver(ctx, e, paid.copy(email = email, notifiedPaid = true), alert)
            return "🔁 Nova tentativa de entrega para ${Store.nameOf(ctx, chat)}."
        }
        val delivered = mine.filter { it.status == "delivered" }
        if (delivered.isNotEmpty()) {
            delivered.forEach { o ->
                val byLink = products(ctx).firstOrNull { it.id == o.productId }?.byLink ?: false
                val msg = deliveryMessage(ctx, byLink).replace("{produto}", o.productName).replace("{link}", o.link).replace("{email}", o.email)
                runCatching { e.sendText(Memory.chatOf(chat), msg) }
            }
            return "🔁 Acesso reenviado para ${Store.nameOf(ctx, chat)}."
        }
        val pending = mine.lastOrNull { it.status == "pending" }
        if (pending != null) {
            runCatching { e.sendText(Memory.chatOf(chat), "Ainda não recebi a confirmação do seu Pix. Assim que cair, envio na hora! Se precisar, o código copia e cola é este:"); e.sendText(Memory.chatOf(chat), pending.copyPaste) }
            return "🔁 Pix pendente lembrado para ${Store.nameOf(ctx, chat)}."
        }
        return null
    }

    /** Texto para a IA. Do infoproduto próprio vão só nome, preço e descrição (nunca o arquivo). */
    fun promptSection(ctx: Context): String {
        if (!active(ctx)) return ""
        fun desc(p: DigitalProduct) = "${p.name}${if (p.price > 0) " — ${brl(p.price)}" else ""}${if (p.description.isNotBlank()) " — ${p.description}" else ""}"
        val parts = mutableListOf<String>()
        val pay = payProducts(ctx)
        if (pay.isNotEmpty()) {
            val card = if (cardEnabled(ctx)) " e um link para pagar no cartão (com ${String.format(Locale("pt", "BR"), "%.0f", cardFeePercent(ctx))}% de taxa)" else ""
            parts += listOf(
                "## Infoprodutos que você vende e o app entrega por aqui",
                pay.joinToString("\n") { "- id \"${it.id}\": ${desc(it)}${if (it.byLink) "" else " (exige Gmail)"}" },
                "Como vender estes (siga sempre):",
                "1. Quando o cliente quiser comprar, peça o e-mail dele (vai no comprovante do Mercado Pago). Para produto marcado \"exige Gmail\", tem que ser Gmail (conta Google): o acesso é liberado só para esse e-mail.",
                "2. Com o produto e o e-mail, preencha \"checkout\" com {\"productId\": \"<id>\", \"email\": \"<e-mail>\"} e responda só uma frase curta (ex.: \"Perfeito! Vou gerar seu pagamento agora.\"). O app envia o QR Code e o Pix copia e cola$card.",
                "3. Nunca escreva você mesma valor de Pix, código Pix, link de pagamento ou link do produto. Nunca diga que o pagamento foi recebido: a confirmação e a entrega são automáticas.",
                "4. Se o cliente disser que pagou e não recebeu, ou pedir o acesso de novo, marque \"resendDelivery\": true (e, se ele mandar outro e-mail, coloque em \"checkout.email\").",
            ).joinToString("\n")
        }
        val info = linkProducts(ctx).filter { it.kind == "info_link" }
        if (info.isNotEmpty()) {
            parts += listOf(
                "## Infoprodutos vendidos na página de compra",
                info.joinToString("\n") { "- ${desc(it)}\n  Página de compra: ${it.salesUrl}" },
                "Como vender estes: tire as dúvidas e, quando o cliente quiser comprar, mande o link da página de compra (copie exatamente). Nunca prometa resultado. Nunca preencha \"checkout\" para estes.",
            ).joinToString("\n")
        }
        val aff = linkProducts(ctx).filter { it.kind == "affiliate" }
        if (aff.isNotEmpty()) {
            parts += listOf(
                "## Produtos físicos que você indica (link de afiliado)",
                aff.joinToString("\n") { "- ${desc(it)}\n  Link: ${it.salesUrl}" },
                "Como indicar estes: entenda o que o cliente procura e indique o produto certo com o link exatamente como está. Preço pode mudar na loja: peça para conferir o valor no link. Nunca preencha \"checkout\" para estes.",
            ).joinToString("\n")
        }
        return parts.joinToString("\n\n")
    }
}
