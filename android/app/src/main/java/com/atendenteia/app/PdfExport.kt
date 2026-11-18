package com.atendenteia.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/**
 * Gera o PDF do prompt (A4, texto selecionável). O texto sai igual ao do [ConfigDoc]
 * (só o "##" dos títulos vira negrito), então o PDF pode ser importado de volta.
 */
object PdfExport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 48
    private const val PRIMARY = 0xFF00796B.toInt()
    private val GRAY = Color.rgb(120, 120, 120)

    private fun paint(size: Float, color: Int, bold: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private enum class Kind { HEADER, TITLE, HEADING, BODY, GAP }
    private class Para(val kind: Kind, val text: String)

    private fun paragraphs(text: String, title: String): List<Para> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd().lines()
        val out = mutableListOf<Para>()
        var i = 0
        if (lines.firstOrNull()?.startsWith("ATENDENTE-IA-CONFIG") == true) { out += Para(Kind.HEADER, lines[0]); i = 1 }
        out += Para(Kind.TITLE, title)
        for (line in lines.drop(i)) {
            val h = Regex("^##\\s+(.+)$").matchEntire(line)
            out += when {
                h != null -> Para(Kind.HEADING, h.groupValues[1].trim())
                line.isBlank() -> Para(Kind.GAP, "")
                else -> Para(Kind.BODY, line)
            }
        }
        return out
    }

    /** Escreve o PDF em [out]. [title] = nome da persona ou "Orquestrador". */
    fun write(text: String, title: String, out: OutputStream) {
        val doc = PdfDocument()
        val width = PAGE_W - 2 * MARGIN
        val bottom = PAGE_H - MARGIN - 14f
        val footerPaint = paint(8.5f, GRAY, false).apply { textAlign = Paint.Align.CENTER }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun finish() {
            page?.let { pg ->
                pg.canvas.drawText("Atendente IA · página $pageNo", PAGE_W / 2f, PAGE_H - 28f, footerPaint)
                doc.finishPage(pg)
            }
            page = null
        }
        fun open() {
            finish()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            y = MARGIN.toFloat()
        }
        open()

        for (para in paragraphs(text, title)) {
            if (para.kind == Kind.GAP) { y += 6f; if (y > bottom) open(); continue }
            val p = when (para.kind) {
                Kind.HEADER -> paint(8.5f, GRAY, false)
                Kind.TITLE -> paint(20f, Color.BLACK, true)
                Kind.HEADING -> paint(12.5f, PRIMARY, true)
                else -> paint(10.5f, Color.BLACK, false)
            }
            if (para.kind == Kind.HEADING) { y += 8f; if (y + 48f > bottom) open() }
            if (para.kind == Kind.TITLE) y += 4f
            val layout = StaticLayout.Builder.obtain(para.text, 0, para.text.length, p, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(false).build()
            for (i in 0 until layout.lineCount) {
                val top = layout.getLineTop(i).toFloat()
                val h = layout.getLineBottom(i) - top
                if (y + h > bottom) open()
                val canvas = page!!.canvas
                canvas.save()
                canvas.translate(MARGIN.toFloat(), y - top)
                canvas.clipRect(0f, top, width.toFloat(), top + h)
                layout.draw(canvas)
                canvas.restore()
                y += h
            }
            if (para.kind == Kind.TITLE) y += 8f
        }
        finish()
        doc.writeTo(out)
        doc.close()
    }

    /** Gera o PDF em cacheDir/export/ e devolve o arquivo. */
    fun toCacheFile(ctx: Context, text: String, title: String, fileName: String): File {
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val f = File(dir, fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        f.outputStream().use { write(text, title, it) }
        return f
    }

    /** Compartilha o PDF pelo FileProvider do app. */
    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Compartilhar PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
