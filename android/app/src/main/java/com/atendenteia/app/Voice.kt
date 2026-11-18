package com.atendenteia.app

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voz do atendente com o Gemini TTS (sem servidor):
 * - voz pronta do Gemini (nível grátis), ou
 * - a voz do dono, clonada a partir de uma gravação de 10–30 s
 *   ("voice replication", exige conta do Gemini com faturamento ativo).
 * Docs: ai.google.dev/gemini-api/docs/voice-replication e speech-generation.
 */
object Voice {
    const val SAMPLE_RATE = 24_000
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"

    /** Vozes prontas do Gemini (nome da API → descrição). */
    val PREBUILT = linkedMapOf(
        "Kore" to "Kore (feminina, firme)",
        "Aoede" to "Aoede (feminina, leve)",
        "Leda" to "Leda (feminina, jovem)",
        "Puck" to "Puck (masculina, animada)",
        "Charon" to "Charon (masculina, informativa)",
        "Orus" to "Orus (masculina, firme)",
    )

    /** Frase de consentimento em pt-BR exigida pelo Google para clonar a voz. */
    const val CONSENT_PT_BR = "Eu sou o proprietário desta voz e autorizo o Google a usá-la para criar um modelo de voz sintética."

    private fun call(apiKey: String, method: String, path: String, body: JSONObject?): JSONObject {
        if (apiKey.isBlank()) throw GeminiException("Cadastre a chave da API do Gemini.")
        var last = ""
        for (attempt in 0 until 3) {
            val conn = (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("x-goog-api-key", apiKey)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) return if (text.isBlank()) JSONObject() else JSONObject(text)
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrDefault(text.take(300))
                last = "Gemini HTTP $code: $msg"
                if (code != 429 && code < 500) throw GeminiException(last)
            } catch (e: GeminiException) {
                throw e
            } catch (e: Exception) {
                last = "Sem conexão com o Gemini: ${e.message}"
            } finally {
                conn.disconnect()
            }
            Thread.sleep(2_000L * (attempt + 1))
        }
        throw GeminiException(last)
    }

    /** Gera a fala e devolve PCM 16-bit mono 24 kHz. */
    fun synthesize(apiKey: String, model: String, voice: String, text: String, style: String = ""): ByteArray {
        val content = JSONObject().put("type", "text").put("text", text)
        if (style.isNotBlank()) {
            content.put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(content))))
            .put("response_format", JSONObject().put("type", "audio").put("mime_type", "audio/l16").put("sample_rate", SAMPLE_RATE))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        val res = call(apiKey, "POST", "interactions", body)
        val b64 = findAudio(res) ?: throw GeminiException("O Gemini não devolveu áudio.")
        return stripWavHeader(Base64.decode(b64, Base64.DEFAULT))
    }

    /** Procura o primeiro bloco de áudio base64 na resposta (steps[].content[].data). */
    private fun findAudio(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val type = node.optString("type")
                val mime = node.optString("mime_type", node.optString("mimeType"))
                val data = node.optString("data")
                if (data.length > 100 && (type == "audio" || mime.startsWith("audio"))) return data
                node.optJSONObject("output_audio")?.optString("data")?.takeIf { it.length > 100 }?.let { return it }
                for (k in node.keys()) findAudio(node.opt(k))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findAudio(node.opt(i))?.let { return it }
        }
        return null
    }

    /** Se vier WAV (RIFF), devolve só os dados PCM do bloco "data". */
    fun stripWavHeader(bytes: ByteArray): ByteArray {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") return bytes
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val len = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") return bytes.copyOfRange(i + 8, minOf(bytes.size, i + 8 + len))
            i += 8 + len + (len and 1)
        }
        return bytes.copyOfRange(44, bytes.size)
    }

    /** Cria a voz clonada (guardada na conta do Gemini) e devolve o id "voice_…". */
    fun createVoice(apiKey: String, model: String, sampleWav: File, consentWav: File, name: String): String {
        fun audio(f: File) = JSONObject().put("mime_type", "audio/wav")
            .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        val body = JSONObject()
            .put("store", true)
            .put("voice", JSONObject()
                .put("model", model)
                .put("type", "replicated")
                .put("display_name", name.ifBlank { "Atendente IA" })
                .put("replicated", JSONObject()
                    .put("source_audio", audio(sampleWav))
                    .put("consent_audio", audio(consentWav))))
        val res = call(apiKey, "POST", "voices", body)
        val id = listOf(res, res.optJSONObject("voice") ?: JSONObject())
            .flatMap { listOf(it.optString("id"), it.optString("name").substringAfterLast('/')) }
            .firstOrNull { it.startsWith("voice") }
        return id ?: throw GeminiException("O Gemini não devolveu o id da voz: ${res.toString().take(200)}")
    }

    fun deleteVoice(apiKey: String, id: String) {
        if (id.startsWith("voice_")) call(apiKey, "DELETE", "voices/$id", null)
    }

    /** Tira links, marcação do WhatsApp e emojis antes de falar. */
    fun cleanForSpeech(text: String): String = text
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*_~`]"), "")
        .replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), "")
        .replace(Regex("[ \\t]+"), " ")
        .trim()

    fun links(text: String): List<String> = Regex("https?://\\S+").findAll(text).map { it.value.trimEnd('.', ',', ')', '!') }.toList()

    fun seconds(pcm: ByteArray) = (pcm.size / 2 / SAMPLE_RATE.toDouble()).let { kotlin.math.ceil(it).toInt() }

    // ---------------- Tocar (testar no app) ----------------

    fun play(pcm: ByteArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(maxOf(pcm.size, 4096))
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size / 2 * 1000L / SAMPLE_RATE + 300)
        track.release()
    }

    // ---------------- PCM → OGG/Opus (mensagem de voz do WhatsApp) ----------------

    /** O Android só codifica Opus em OGG a partir do Android 10. */
    val canEncodeVoiceNote: Boolean get() = Build.VERSION.SDK_INT >= 29

    fun pcmToOggOpus(pcm: ByteArray, out: File) {
        if (!canEncodeVoiceNote) throw IllegalStateException("Mensagem de voz exige Android 10 ou mais novo.")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        var track = -1
        var offset = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), pcm.size - offset, 960 * 2 * 4)
                        val ptsUs = offset / 2 * 1_000_000L / SAMPLE_RATE
                        if (n <= 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            offset += n
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            runCatching { if (track >= 0) muxer.stop() }; muxer.release()
        }
    }
}

/** Grava o microfone em WAV 24 kHz mono 16-bit (formato pedido pelo Google). */
class WavRecorder(private val file: File) {
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var bytes = 0L
        private set

    val seconds: Int get() = (bytes / 2 / Voice.SAMPLE_RATE).toInt()

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(Voice.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, Voice.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        running = true
        bytes = 0
        thread = Thread {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                raf.write(ByteArray(44))
                val buf = ByteArray(4096)
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) { raf.write(buf, 0, n); bytes += n }
                }
                rec.stop(); rec.release()
                raf.seek(0)
                raf.write(wavHeader(bytes))
            }
        }.apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
    }

    private fun wavHeader(dataLen: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataLen).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(Voice.SAMPLE_RATE); b.putInt(Voice.SAMPLE_RATE * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataLen.toInt())
        return b.array()
    }
}
