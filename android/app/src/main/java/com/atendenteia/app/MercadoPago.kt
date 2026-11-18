package com.atendenteia.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Pix pelo Mercado Pago, chamado direto do celular (sem servidor):
 * cria a cobrança e consulta se foi paga (no lugar do webhook, que exigiria
 * um endereço público). Docs: developers.mercadopago.com (API de pagamentos).
 */
object MercadoPago {
    private const val BASE = "https://api.mercadopago.com"

    data class Pix(val paymentId: String, val copyPaste: String, val qrPngBase64: String, val expiresAt: Long)
    data class Status(val status: String, val amount: Double, val externalReference: String)

    class MpException(message: String) : Exception(message)

    private fun call(token: String, method: String, path: String, body: JSONObject? = null, idempotency: String? = null): JSONObject {
        if (token.isBlank()) throw MpException("Cadastre o Access Token do Mercado Pago (aba Vendas).")
        val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            if (idempotency != null) setRequestProperty("X-Idempotency-Key", idempotency)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault(text.take(200))
                throw MpException(when (code) {
                    401 -> "Token do Mercado Pago inválido ou revogado."
                    else -> "Mercado Pago HTTP $code: $msg"
                })
            }
            return JSONObject(text.ifBlank { "{}" })
        } finally {
            conn.disconnect()
        }
    }

    /** Confere o token e devolve o apelido/e-mail da conta. */
    fun account(token: String): String {
        val me = call(token, "GET", "/users/me")
        return listOf(me.optString("nickname"), me.optString("email")).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /** Cria um Pix com vencimento em [minutes] minutos. */
    fun createPix(token: String, amount: Double, description: String, payerEmail: String, externalReference: String, minutes: Int = 30): Pix {
        val expires = System.currentTimeMillis() + minutes * 60_000L
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val body = JSONObject()
            .put("transaction_amount", Math.round(amount * 100) / 100.0)
            .put("description", description.take(200))
            .put("payment_method_id", "pix")
            .put("external_reference", externalReference)
            .put("date_of_expiration", fmt.format(Date(expires)))
            .put("payer", JSONObject().put("email", payerEmail))
        val res = call(token, "POST", "/v1/payments", body, idempotency = externalReference + "-" + UUID.randomUUID())
        val data = res.optJSONObject("point_of_interaction")?.optJSONObject("transaction_data")
            ?: throw MpException("O Mercado Pago não devolveu o Pix. A conta tem chave Pix cadastrada?")
        return Pix(
            paymentId = res.optLong("id").toString(),
            copyPaste = data.optString("qr_code"),
            qrPngBase64 = data.optString("qr_code_base64"),
            expiresAt = expires,
        )
    }

    fun status(token: String, paymentId: String): Status {
        val p = call(token, "GET", "/v1/payments/$paymentId")
        return Status(p.optString("status"), p.optDouble("transaction_amount"), p.optString("external_reference"))
    }

    /**
     * Link de pagamento só com cartão (Checkout Pro), com o mesmo código do
     * pedido do Pix. Vale por [hours] horas. Devolve o link para o cliente.
     */
    fun createCardLink(token: String, amount: Double, title: String, payerEmail: String, externalReference: String, hours: Int = 2): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("items", org.json.JSONArray().put(JSONObject()
                .put("title", title.take(250)).put("quantity", 1).put("currency_id", "BRL")
                .put("unit_price", Math.round(amount * 100) / 100.0)))
            .put("payer", JSONObject().put("email", payerEmail))
            .put("external_reference", externalReference)
            .put("payment_methods", JSONObject()
                .put("excluded_payment_types", org.json.JSONArray()
                    .put(JSONObject().put("id", "ticket"))
                    .put(JSONObject().put("id", "bank_transfer"))
                    .put(JSONObject().put("id", "atm"))))
            .put("expires", true)
            .put("expiration_date_from", fmt.format(Date(now - 60_000)))
            .put("expiration_date_to", fmt.format(Date(now + hours * 3_600_000L)))
        val res = call(token, "POST", "/checkout/preferences", body)
        return res.optString("init_point").ifBlank { throw MpException("O Mercado Pago não devolveu o link de cartão.") }
    }

    /** Pagamentos aprovados de um pedido (Pix ou cartão), pelo código do pedido. */
    fun approvedFor(token: String, externalReference: String): List<Status> {
        val res = call(token, "GET", "/v1/payments/search?sort=date_created&criteria=desc&external_reference=" +
            java.net.URLEncoder.encode(externalReference, "UTF-8"))
        val arr = res.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Status(it.optString("status"), it.optDouble("transaction_amount"), it.optString("external_reference")) }
            .filter { it.status == "approved" && it.externalReference == externalReference }
    }
}
