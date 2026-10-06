package com.alharith.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Base64
import com.alharith.ai.data.Prefs
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * قراءة الملفات وتحويلها إلى محتوى يفهمه Claude:
 * PDF وصور تُرسل كما هي (Claude يقرؤها مباشرة)، Word/Excel/PowerPoint يُستخرج نصها، والنصوص تُقرأ مباشرة.
 */
object FileReader {

    data class Meta(val name: String, val mime: String, val size: Long)

    fun meta(context: Context, uri: Uri): Meta {
        var name = uri.lastPathSegment ?: "ملف"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } }
        }
        val mime = context.contentResolver.getType(uri) ?: guessMime(name)
        return Meta(name, mime, size)
    }

    private fun guessMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "txt", "md", "log" -> "text/plain"; "csv" -> "text/csv"; "json" -> "application/json"
        "html", "htm" -> "text/html"; "xml" -> "text/xml"
        else -> "application/octet-stream"
    }

    private fun bytes(context: Context, uri: Uri, max: Long): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    /** يعيد نتيجة جاهزة: نص و/أو مرفق (document/image block) */
    fun read(context: Context, uri: Uri): ToolResult {
        val m = meta(context, uri)
        val ext = m.name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                m.mime == "application/pdf" || ext == "pdf" -> {
                    val b = bytes(context, uri, 15L * 1024 * 1024)
                        ?: return ToolResult.error("ملف PDF أكبر من 15 ميجابايت.")
                    ToolResult(
                        "الملف \"${m.name}\" (PDF) مرفق أدناه.",
                        attachments = listOf(block("document", "application/pdf", b))
                    )
                }
                m.mime.startsWith("image/") -> {
                    val b = bytes(context, uri, 30L * 1024 * 1024) ?: return ToolResult.error("الصورة كبيرة جدًا.")
                    val (data, type) = shrinkImage(b, m.mime)
                    ToolResult("الصورة \"${m.name}\" مرفقة أدناه.", attachments = listOf(block("image", type, data)))
                }
                ext == "docx" -> office(context, uri, m.name) { it == "word/document.xml" }
                ext == "pptx" -> office(context, uri, m.name) { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                ext == "xlsx" -> office(context, uri, m.name) {
                    it == "xl/sharedStrings.xml" || (it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml"))
                }
                m.mime.startsWith("text/") || ext in setOf("txt", "md", "csv", "json", "xml", "html", "log", "kt", "java", "py", "js") -> {
                    val b = bytes(context, uri, 2L * 1024 * 1024) ?: return ToolResult.error("الملف النصي كبير جدًا.")
                    ToolResult.ok("محتوى \"${m.name}\":\n" + String(b, Charsets.UTF_8).take(60_000))
                }
                else -> ToolResult.error("نوع الملف (${m.mime}) غير مدعوم للقراءة. المدعوم: PDF، صور، Word، Excel، PowerPoint، نصوص.")
            }
        } catch (e: SecurityException) {
            ToolResult.error("لا أملك صلاحية فتح هذا الملف. اختر المجلد من الإعدادات مجددًا.")
        } catch (e: Exception) {
            ToolResult.error("تعذّرت قراءة الملف: ${e.message}")
        }
    }

    private fun block(kind: String, mediaType: String, data: ByteArray) = JSONObject().apply {
        put("type", kind)
        put("source", JSONObject().apply {
            put("type", "base64")
            put("media_type", mediaType)
            put("data", Base64.encodeToString(data, Base64.NO_WRAP))
        })
    }

    private fun shrinkImage(b: ByteArray, mime: String): Pair<ByteArray, String> {
        val supported = mime in setOf("image/jpeg", "image/png", "image/gif", "image/webp")
        if (supported && b.size <= 3_500_000) return b to mime
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return b to mime
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        return out.toByteArray() to "image/jpeg"
    }

    private fun office(context: Context, uri: Uri, name: String, want: (String) -> Boolean): ToolResult {
        val parts = sortedMapOf<String, String>(compareBy<String>({ it.length }, { it }))
        context.contentResolver.openInputStream(uri)?.use { ins ->
            ZipInputStream(ins).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && want(e.name)) parts[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        if (parts.isEmpty()) return ToolResult.error("لم أستطع استخراج نص من الملف.")
        val text = parts.entries.joinToString("\n\n") { (k, xml) ->
            val t = xml
                .replace(Regex("</w:p>|</a:p>|</row>"), "\n")
                .replace(Regex("</c>|</w:tc>"), " | ")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace(Regex("[ \\t]+"), " ")
                .trim()
            if (k.startsWith("ppt/slides/")) "— شريحة ${k.filter { it.isDigit() }}:\n$t" else t
        }
        return ToolResult.ok("محتوى \"$name\":\n" + text.take(60_000))
    }
}

object FileTools {

    private data class Found(val name: String, val uri: Uri, val mime: String, val size: Long, val modified: Long, val path: String)

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "search_files", "يبحث في الملفات",
            "يبحث عن ملفات بالاسم داخل المجلد الذي اختاره المستخدم للحارث (مثل التنزيلات أو المستندات). " +
                "يعيد معرّف كل ملف لاستخدامه مع read_file.",
            schema(
                "query" to prop("string", "جزء من اسم الملف، مثل: العقد"),
                "limit" to prop("integer", "العدد (افتراضي 10)"),
                required = listOf("query")
            )
        ) { input ->
            val tree = Prefs.filesTreeUri
            if (tree.isBlank()) return@Tool ToolResult.error(
                "لم يُحدَّد مجلد للبحث. اطلب من المستخدم اختيار مجلد (مثل Download أو Documents) من إعدادات الحارث ← الملفات."
            )
            val query = input.str("query")
            val limit = input.intOr("limit", 10).coerceIn(1, 30)
            val found = try { search(env.context, Uri.parse(tree), query, limit) } catch (e: SecurityException) {
                return@Tool ToolResult.error("انتهت صلاحية الوصول للمجلد. اطلب من المستخدم اختياره مجددًا من الإعدادات.")
            }
            if (found.isEmpty()) return@Tool ToolResult.ok("لم أجد ملفات يحتوي اسمها على \"$query\".")
            val fmt = SimpleDateFormat("d MMM yyyy", Locale("ar"))
            ToolResult.ok(found.joinToString("\n") {
                "${it.path} — ${fmt.format(Date(it.modified))} — ${it.size / 1024} ك.ب\nfile_id: ${it.uri}"
            })
        },

        Tool(
            "read_file", "يقرأ الملف",
            "يقرأ محتوى ملف (PDF، صورة، Word، Excel، PowerPoint، نص) لتلخيصه أو شرحه أو الإجابة عنه.",
            schema("file_id" to prop("string", "قيمة file_id من search_files"), required = listOf("file_id"))
        ) { input ->
            val id = input.str("file_id")
            if (!id.startsWith("content://")) return@Tool ToolResult.error("معرّف الملف غير صالح. استخدم search_files أولًا.")
            FileReader.read(env.context, Uri.parse(id))
        }
    )

    private fun search(context: Context, tree: Uri, query: String, limit: Int): List<Found> {
        val words = Arabic.norm(query).split(' ').filter { it.isNotBlank() }
        val results = mutableListOf<Found>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque<Pair<String, String>>().apply { add(rootId to "") }
        var visited = 0
        while (queue.isNotEmpty() && visited < 5000) {
            val (docId, path) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    visited++
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    val p = if (path.isEmpty()) name else "$path/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (p.count { it == '/' } < 8 && !name.startsWith(".")) queue.add(id to p)
                    } else {
                        val n = Arabic.norm(name)
                        if (words.all { n.contains(it) || n.contains(it.removePrefix("ال")) }) {
                            results += Found(
                                name, DocumentsContract.buildDocumentUriUsingTree(tree, id),
                                mime, c.getLong(3), c.getLong(4), p
                            )
                        }
                    }
                }
            }
        }
        return results.sortedByDescending { it.modified }.take(limit)
    }
}
