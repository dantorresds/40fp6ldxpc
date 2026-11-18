package com.atendenteia.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Entrega de produto digital pelo Google Drive da loja, sem servidor.
 * Escopo "drive.file": o app só enxerga os arquivos que ele mesmo enviou
 * (não lê o resto do Drive). Os arquivos ficam privados; ao confirmar o
 * pagamento, o app dá acesso de leitura só ao Gmail de quem pagou.
 */
object GoogleDrive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val API = "https://www.googleapis.com/drive/v3"

    class DriveException(message: String, val needsLogin: Boolean = false) : Exception(message)

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /**
     * Token de acesso (vale ~1 h). Sem tela: funciona depois que o dono
     * autorizou uma vez pela aba Vendas. Se o Google pedir login de novo,
     * lança [DriveException] com needsLogin = true.
     */
    fun token(ctx: Context): String {
        val result: AuthorizationResult = try {
            Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw DriveException("Google Drive indisponível: ${e.message}", needsLogin = true)
        }
        if (result.hasResolution()) throw DriveException("Conecte o Google Drive na aba Vendas.", needsLogin = true)
        return result.accessToken ?: throw DriveException("O Google não devolveu acesso ao Drive.", needsLogin = true)
    }

    private fun http(token: String, method: String, url: String, json: JSONObject? = null): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (json != null) conn.outputStream.use { it.write(json.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorOf(text: String) =
        runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault(text.take(200))

    /** E-mail da conta Google conectada. */
    fun accountEmail(token: String): String {
        val (code, text) = http(token, "GET", "$API/about?fields=user(emailAddress)")
        if (code !in 200..299) throw DriveException("Drive HTTP $code: ${errorOf(text)}")
        return JSONObject(text).optJSONObject("user")?.optString("emailAddress").orEmpty()
    }

    /**
     * Envia um arquivo do celular para o Drive (privado) e devolve o id.
     * [noDownload] = leitores não podem baixar, imprimir nem copiar.
     */
    fun upload(ctx: Context, token: String, uri: Uri, noDownload: Boolean): Pair<String, String> {
        var name = "produto"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val meta = JSONObject().put("name", name).put("mimeType", mime)
        if (noDownload) meta.put("copyRequiresWriterPermission", true)

        // Envio "resumable": primeiro os dados do arquivo, depois o conteúdo (serve para arquivos grandes).
        val start = (URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,name")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Upload-Content-Type", mime)
        }
        val location = try {
            start.outputStream.use { it.write(meta.toString().toByteArray()) }
            if (start.responseCode !in 200..299) {
                throw DriveException("Drive HTTP ${start.responseCode}: ${errorOf(start.errorStream?.bufferedReader()?.readText().orEmpty())}")
            }
            start.getHeaderField("Location") ?: throw DriveException("O Drive não abriu o envio.")
        } finally {
            start.disconnect()
        }
        val put = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 300_000
            setChunkedStreamingMode(256 * 1024)
            setRequestProperty("Content-Type", mime)
        }
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input -> put.outputStream.use { input.copyTo(it, 256 * 1024) } }
            val code = put.responseCode
            val text = (if (code in 200..299) put.inputStream else put.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw DriveException("Drive HTTP $code: ${errorOf(text)}")
            return JSONObject(text).optString("id") to name
        } finally {
            put.disconnect()
        }
    }

    /** Dá acesso de leitura ao e-mail (conta Google) e devolve o link do arquivo. */
    fun shareWith(token: String, fileId: String, email: String): String {
        val body = JSONObject().put("type", "user").put("role", "reader").put("emailAddress", email)
        val (code, text) = http(token, "POST",
            "$API/files/${URLEncoder.encode(fileId, "UTF-8")}/permissions?sendNotificationEmail=false&fields=id", body)
        if (code !in 200..299) {
            val msg = errorOf(text)
            throw DriveException(
                if (msg.contains("notification", true) || msg.contains("invalidSharing", true) || code == 400)
                    "O e-mail $email não é uma conta Google (Gmail)." else "Drive HTTP $code: $msg")
        }
        return "https://drive.google.com/file/d/$fileId/view"
    }
}
