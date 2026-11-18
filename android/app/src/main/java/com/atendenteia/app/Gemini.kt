package com.atendenteia.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class GeminiException(message: String) : Exception(message)

/** Chamada direta à API do Gemini, do próprio celular (sem servidor). */
object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * Modelos de reserva (nov/2026), do mais rápido para o mais forte. Usados
     * quando o escolhido está sobrecarregado (503), no limite (429), demora ou
     * não existe. Todos têm nível grátis.
     */
    val FALLBACK_MODELS = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-3.1-flash-lite")

    /** Tempo máximo por modelo antes de passar para o próximo. */
    private const val PER_MODEL_TIMEOUT_MS = 25_000

    /** Último modelo que respondeu com sucesso (mostrado no app). */
    @Volatile var lastWorkingModel: String = ""

    /** Modelo que deu certo por último: vai primeiro nas próximas chamadas (por 10 min). */
    @Volatile private var preferred: Pair<String, Long>? = null

    private class GeminiHttp(val code: Int, message: String) : Exception(message) {
        val isKeyProblem: Boolean get() = Regex("api key|API_KEY|permission", RegexOption.IGNORE_CASE).containsMatchIn(message ?: "")
    }

    /**
     * Chama o Gemini de forma tolerante:
     *  - sem `temperature` (não aceito do Gemini 3 em diante) e com raciocínio baixo;
     *  - formato recusado (400) → tenta formatos mais simples no mesmo modelo;
     *  - modelo inexistente, sobrecarregado, no limite ou lento → próximo modelo.
     *  - chave inválida ou sem permissão → para na hora (não adianta insistir).
     */
    fun generate(
        apiKey: String,
        model: String,
        system: String,
        contents: JSONArray,
        schema: JSONObject? = null,
        maxModels: Int = 5,
    ): String {
        if (apiKey.isBlank()) throw GeminiException("Cadastre a chave da API do Gemini.")
        if (!Regex("^[\\w.-]{3,90}$").matches(model)) throw GeminiException("Nome de modelo inválido: $model")

        fun fast() = JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        val configs = buildList {
            if (schema != null) {
                add(fast().put("responseMimeType", "application/json").put("responseSchema", schema))
                add(JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
                add(JSONObject().put("responseMimeType", "application/json"))
            } else {
                add(fast())
            }
            add(null)
        }
        val recent = preferred?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000 }?.first
        val models = (listOfNotNull(recent) + model + FALLBACK_MODELS).distinct().take(maxModels)

        val errors = mutableListOf<String>()
        for (m in models) {
            for (gen in configs) {
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                if (gen != null) body.put("generationConfig", gen)
                try {
                    val out = post(apiKey, m, body.toString())
                    lastWorkingModel = m
                    preferred = m to System.currentTimeMillis()
                    return out
                } catch (e: GeminiHttp) {
                    if (e.code == 400 && !e.isKeyProblem) continue // formato recusado: tenta mais simples
                    if (e.isKeyProblem || e.code == 401 || e.code == 403) throw GeminiException(e.message ?: "chave recusada")
                    errors += e.message ?: "erro"
                    break // 404, 408 (demora), 429, 5xx, sem rede: próximo modelo
                }
            }
        }
        throw GeminiException(errors.joinToString(" | ").ifBlank { "o Gemini não respondeu" })
    }

    private fun post(apiKey: String, model: String, body: String): String {
        val conn = (URL(BASE + model + ":generateContent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = PER_MODEL_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) return extractText(text)
            val msg = errorMessage(text)
            val label = when (code) {
                503 -> "sobrecarregado"
                429 -> "limite de uso"
                404 -> "modelo não existe"
                else -> "HTTP $code"
            }
            throw GeminiHttp(code, "$model: $label — $msg")
        } catch (e: GeminiHttp) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw GeminiHttp(408, "$model: demorou mais de ${PER_MODEL_TIMEOUT_MS / 1000} s")
        } catch (e: GeminiException) {
            throw GeminiHttp(500, "$model: ${e.message}")
        } catch (e: Exception) {
            throw GeminiHttp(0, "$model: sem conexão (${e.message})")
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(raw: String): String = try {
        JSONObject(raw).getJSONObject("error").optString("message").take(200)
    } catch (e: Exception) {
        raw.take(200)
    }

    private fun extractText(raw: String): String {
        val root = JSONObject(raw)
        val cands = root.optJSONArray("candidates")
        if (cands == null || cands.length() == 0) {
            val reason = root.optJSONObject("promptFeedback")?.optString("blockReason") ?: "sem resposta"
            throw GeminiException("o Gemini não respondeu ($reason).")
        }
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought")) sb.append(p.optString("text"))
        }
        if (sb.isBlank()) throw GeminiException("resposta vazia")
        return sb.toString()
    }
}
