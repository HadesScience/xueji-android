package app.xueji.study

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

const val MAX_SOURCE_TEXT = 60_000
private const val MAX_FILE_BYTES = 25 * 1024 * 1024

fun readBounded(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        require(output.size() + count <= limit) { "资料或服务响应超出大小限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

enum class ExportFormat(val extension: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    DOC("doc", "application/msword"),
    TEXT("txt", "text/plain"),
}

data class ExportRequest(val title: String, val body: String, val format: ExportFormat) {
    fun fileName(): String {
        val safe = title.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().trimEnd('.')
        return "${safe.take(45).ifBlank { "学迹资料" }}.${format.extension}"
    }
}

data class Imported(val name: String, val text: String)

class StudyFiles(private val context: Context) {
    suspend fun read(uri: Uri): Imported = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "导入资料"
        val mime = resolver.getType(uri).orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { readBounded(it, MAX_FILE_BYTES) }
            ?: error("无法打开所选文件")
        val extension = name.substringAfterLast('.', "").lowercase()
        val content = when {
            extension == "pdf" || mime == "application/pdf" -> readPdf(bytes)
            extension == "docx" || mime.contains("wordprocessingml") -> readDocx(bytes)
            extension == "doc" || mime == "application/msword" -> WordExtractor(bytes.inputStream()).use { it.text }
            extension in listOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif") ||
                mime.startsWith("image/") -> recognizeFile(bytes)
            extension in listOf("txt", "md", "csv") || mime.startsWith("text/") -> decodeText(bytes)
            else -> error("请选择 PDF、DOC、DOCX、图片或纯文本文件")
        }.replace("\u0000", "").trim()
        require(content.isNotBlank()) { "没有识别到可用文字" }
        require(content.length <= MAX_SOURCE_TEXT) { "提取结果超过六万字符，请将资料拆成几份后导入" }
        Imported(name.substringBeforeLast('.', name), content)
    }

    private fun decodeText(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            Charset.forName("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
    }

    private suspend fun readPdf(bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context)
        val pages = PDDocument.load(bytes).use { pdf ->
            require(pdf.numberOfPages <= 60) { "PDF 最多支持 60 页，请先拆分文件" }
            require(pdf.currentAccessPermission.canExtractContent()) { "此 PDF 禁止提取文字" }
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            (1..pdf.numberOfPages).map { page ->
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(pdf).trim()
            }
        }
        if (pages.all(String::isNotBlank)) return pages.joinToString("\n\n")
        val temporary = java.io.File.createTempFile("scan-", ".pdf", context.cacheDir)
        try {
            temporary.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try { PdfRenderer(descriptor) } catch (failure: Exception) {
                descriptor.close()
                throw failure
            }
            return renderer.use {
                pages.mapIndexed { index, existing ->
                    if (existing.isNotBlank()) existing else {
                        val bitmap = renderer.openPage(index).use { page ->
                            val scale = 1700f / maxOf(page.width, page.height).coerceAtLeast(1)
                            Bitmap.createBitmap((page.width * scale).roundToInt().coerceAtLeast(1),
                                (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                .apply {
                                    eraseColor(Color.WHITE)
                                    page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                }
                        }
                        recognize(bitmap)
                    }
                }.joinToString("\n\n")
            }
        } finally {
            temporary.delete()
        }
    }

    private suspend fun recognizeFile(bytes: ByteArray): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "设备无法打开此图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("设备无法打开此图片")
        val rotation = runCatching {
            android.media.ExifInterface(bytes.inputStream()).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(1)
        val matrix = android.graphics.Matrix()
        when (rotation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        val upright = if (matrix.isIdentity) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (upright !== bitmap) bitmap.recycle()
        return recognize(upright)
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { result ->
                recognizer.close()
                bitmap.recycle()
                if (continuation.isActive) {
                    if (result.isSuccessful) continuation.resume(result.result.text)
                    else continuation.resumeWithException(IllegalStateException("图片文字识别失败", result.exception))
                }
            }
        }
    }

    suspend fun export(uri: Uri, request: ExportRequest) = withContext(Dispatchers.IO) {
        // Assemble the document in memory before writing to the selected destination.
        val bytes = ByteArrayOutputStream().also { output ->
            when (request.format) {
                ExportFormat.PDF -> writePdf(request.title, request.body, output)
                ExportFormat.DOCX -> writeDocx(request.title, request.body, output)
                ExportFormat.DOC -> context.assets.open("blank.doc").use { input ->
                    HWPFDocument(input).use { doc ->
                        doc.range.insertBefore("${request.title}\r\r${request.body.replace("\n", "\r")}")
                        doc.write(output)
                    }
                }
                ExportFormat.TEXT -> output.write("${request.title}\n\n${request.body}".encodeToByteArray())
            }
        }
        val destination = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
        destination.use { bytes.writeTo(it) }
    }
}

fun readDocx(bytes: ByteArray): String {
    val xml = ZipInputStream(bytes.inputStream()).use { zip ->
        var xmlBytes: ByteArray? = null
        var entries = 0
        var inflated = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++entries <= 250) { "Word 文件包含过多条目" }
            if (entry.name == "word/document.xml") {
                xmlBytes = readBounded(zip, 4 * 1024 * 1024)
                break
            }
            // Seeking forward through ZipInputStream also inflates earlier entries.
            inflated += readBounded(zip, 8 * 1024 * 1024).size
            require(inflated <= 25 * 1024 * 1024) { "Word 文件的解压内容过大" }
        }
        xmlBytes ?: error("Word 文件缺少正文")
    }
    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(xml.inputStream(), null)
    }
    val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val strictWord = "http://purl.oclc.org/ooxml/wordprocessingml/main"
    return buildString {
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            val kind = parser.eventType
            if ((kind == XmlPullParser.START_TAG || kind == XmlPullParser.END_TAG) &&
                (parser.namespace == word || parser.namespace == strictWord)) {
                when {
                    kind == XmlPullParser.START_TAG && parser.name == "t" -> append(parser.nextText())
                    kind == XmlPullParser.START_TAG && parser.name == "tab" -> append('\t')
                    kind == XmlPullParser.START_TAG && parser.name in listOf("br", "cr") -> append('\n')
                    kind == XmlPullParser.END_TAG && parser.name == "p" -> append('\n')
                }
            }
            parser.next()
            require(length <= MAX_SOURCE_TEXT) { "Word 正文超过六万字符，请先拆分资料" }
        }
    }
}

private fun escapeXml(text: String): String = buildString {
    text.codePoints().forEach { code ->
        when (code) {
            38 -> append("&amp;")
            60 -> append("&lt;")
            62 -> append("&gt;")
            34 -> append("&quot;")
            39 -> append("&apos;")
            9, 10, 13, in 32..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> appendCodePoint(code)
        }
    }
}

fun writeDocx(title: String, body: String, output: OutputStream) {
    val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val relationship = "http://schemas.openxmlformats.org"
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.encodeToByteArray())
            zip.closeEntry()
        }
        entry("[Content_Types].xml", """<Types xmlns="$relationship/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
        entry("_rels/.rels", """<Relationships xmlns="$relationship/package/2006/relationships"><Relationship Id="rId1" Type="$relationship/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        val paragraphs = buildString {
            append("""<w:p><w:r><w:rPr><w:b/><w:sz w:val="38"/></w:rPr><w:t xml:space="preserve">${escapeXml(title)}</w:t></w:r></w:p><w:p/>""")
            body.lineSequence().forEach { line ->
                append("""<w:p><w:r><w:t xml:space="preserve">${escapeXml(line)}</w:t></w:r></w:p>""")
            }
        }
        entry("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$word"><w:body>$paragraphs<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1020" w:right="1040" w:bottom="1020" w:left="1040"/></w:sectPr></w:body></w:document>""")
    }
}

private fun writePdf(title: String, body: String, output: OutputStream) {
    val width = 595
    val height = 842
    val margin = 50
    val text = "$title\n\n$body"
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = Color.rgb(30, 46, 37) }
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 2 * margin)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(5f, 1f).build()
    val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.DKGRAY }
    PdfDocument().use { doc ->
        var firstLine = 0
        var index = 1
        while (firstLine < layout.lineCount) {
            val start = layout.getLineTop(firstLine)
            var nextLine = firstLine + 1
            while (nextLine < layout.lineCount && layout.getLineBottom(nextLine) - start <= height - 2 * margin) {
                nextLine++
            }
            val end = layout.getLineBottom(nextLine - 1)
            val page = doc.startPage(PdfDocument.PageInfo.Builder(width, height, index).create())
            val canvas = page.canvas
            canvas.save()
            canvas.clipRect(margin, margin, width - margin, margin + (end - start))
            canvas.translate(margin.toFloat(), (margin - start).toFloat())
            layout.draw(canvas)
            canvas.restore()
            canvas.drawText("学迹   /   $index", margin.toFloat(), (height - 28).toFloat(), footer)
            doc.finishPage(page)
            firstLine = nextLine
            index++
        }
        doc.writeTo(output)
    }
}
