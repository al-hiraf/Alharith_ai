package com.alharith.ai.tools

import com.alharith.ai.data.Prefs
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeUtility
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FlagTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm

/**
 * بريد عبر بروتوكولي IMAP و SMTP القياسيين.
 * مع Gmail: فعّل التحقق بخطوتين ثم أنشئ "كلمة مرور للتطبيقات" وضعها في الإعدادات.
 */
object EmailClient {

    data class Summary(
        val uid: Long, val from: String, val subject: String, val date: Date?,
        val unread: Boolean, val snippet: String
    )

    private fun session(): Session {
        val p = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", Prefs.imapHost)
            put("mail.imaps.port", Prefs.imapPort)
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "20000")
            put("mail.imaps.timeout", "30000")

            val smtpPort = Prefs.smtpPort
            put("mail.smtp.host", Prefs.smtpHost)
            put("mail.smtp.port", smtpPort)
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "30000")
            if (smtpPort == "465") put("mail.smtp.ssl.enable", "true")
            else put("mail.smtp.starttls.enable", "true")
        }
        return Session.getInstance(p)
    }

    private fun <T> withStore(block: (Store) -> T): T {
        val store = session().getStore("imaps")
        store.connect(Prefs.imapHost, Prefs.emailAddress, Prefs.emailPassword)
        try { return block(store) } finally { runCatching { store.close() } }
    }

    private fun folderName(store: Store, kind: String): String {
        if (kind == "inbox" || kind.isBlank()) return "INBOX"
        val attr = when (kind) {
            "sent" -> "\\Sent"; "drafts" -> "\\Drafts"; "important" -> "\\Important"
            "starred" -> "\\Flagged"; "all" -> "\\All"; else -> return kind
        }
        val all = store.defaultFolder.list("*")
        for (f in all) {
            val imap = f as? IMAPFolder ?: continue
            if (imap.attributes.any { it.equals(attr, ignoreCase = true) }) return f.fullName
        }
        return when (kind) { "sent" -> "Sent"; "drafts" -> "Drafts"; else -> "INBOX" }
    }

    suspend fun list(
        folderKind: String, query: String, unreadOnly: Boolean, sinceDays: Int?, limit: Int
    ): List<Summary> = withContext(Dispatchers.IO) {
        withStore { store ->
            val folder = store.getFolder(folderName(store, folderKind)) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val terms = mutableListOf<SearchTerm>()
                if (sinceDays != null) {
                    val since = Date(System.currentTimeMillis() - sinceDays * 86_400_000L)
                    terms += ReceivedDateTerm(ComparisonTerm.GE, since)
                }
                if (unreadOnly) terms += FlagTerm(Flags(Flags.Flag.SEEN), false)
                if (query.isNotBlank()) {
                    terms += OrTerm(arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query)))
                }
                val msgs: Array<Message> = when (terms.size) {
                    0 -> {
                        val n = folder.messageCount
                        if (n == 0) emptyArray() else folder.getMessages(maxOf(1, n - limit + 1), n)
                    }
                    1 -> folder.search(terms[0])
                    else -> folder.search(AndTerm(terms.toTypedArray()))
                }
                val picked = msgs.takeLast(limit).toTypedArray()
                folder.fetch(picked, FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(UIDFolder.FetchProfileItem.UID)
                })
                picked.reversed().mapIndexed { i, m ->
                    Summary(
                        uid = folder.getUID(m),
                        from = fromOf(m),
                        subject = m.subject.orEmpty().ifBlank { "(بدون عنوان)" },
                        date = m.receivedDate ?: m.sentDate,
                        unread = !m.isSet(Flags.Flag.SEEN),
                        // مقتطف للرسائل الأحدث فقط حتى لا يطول الانتظار
                        snippet = if (i < 8) runCatching { textOf(m).take(280) }.getOrDefault("") else ""
                    )
                }
            } finally {
                runCatching { folder.close(false) }
            }
        }
    }

    suspend fun read(folderKind: String, uid: Long): String = withContext(Dispatchers.IO) {
        withStore { store ->
            val folder = store.getFolder(folderName(store, folderKind)) as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val m = folder.getMessageByUID(uid) ?: return@withStore "لم أجد الرسالة."
                val fmt = SimpleDateFormat("EEEE d MMMM yyyy، h:mm a", Locale("ar"))
                buildString {
                    appendLine("من: ${fromOf(m)}")
                    appendLine("إلى: ${m.getRecipients(Message.RecipientType.TO)?.joinToString { it.toString() }.orEmpty()}")
                    appendLine("العنوان: ${m.subject.orEmpty()}")
                    (m.receivedDate ?: m.sentDate)?.let { appendLine("التاريخ: ${fmt.format(it)}") }
                    val att = attachmentNames(m)
                    if (att.isNotEmpty()) appendLine("المرفقات: ${att.joinToString("، ")}")
                    appendLine()
                    append(textOf(m).take(15_000))
                }
            } finally {
                runCatching { folder.close(false) }
            }
        }
    }

    private fun buildMessage(
        session: Session, to: String, subject: String, body: String, replyUid: Long?, store: Store?
    ): MimeMessage {
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(Prefs.emailAddress))
        var subj = subject
        if (replyUid != null && store != null) {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            try {
                inbox.getMessageByUID(replyUid)?.let { orig ->
                    val id = orig.getHeader("Message-ID")?.firstOrNull()
                    if (id != null) {
                        msg.setHeader("In-Reply-To", id)
                        val refs = orig.getHeader("References")?.firstOrNull()
                        msg.setHeader("References", listOfNotNull(refs, id).joinToString(" "))
                    }
                    if (subj.isBlank()) subj = orig.subject.orEmpty()
                    if (!subj.startsWith("Re:", ignoreCase = true)) subj = "Re: $subj"
                }
            } finally { runCatching { inbox.close(false) } }
        }
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        msg.setSubject(subj, "UTF-8")
        msg.setText(body, "UTF-8")
        msg.sentDate = Date()
        return msg
    }

    suspend fun send(to: String, subject: String, body: String, replyUid: Long?) = withContext(Dispatchers.IO) {
        val s = session()
        val msg = if (replyUid != null) withStore { buildMessage(s, to, subject, body, replyUid, it) }
        else buildMessage(s, to, subject, body, null, null)
        Transport.send(msg, Prefs.emailAddress, Prefs.emailPassword)
    }

    suspend fun saveDraft(to: String, subject: String, body: String, replyUid: Long?) = withContext(Dispatchers.IO) {
        val s = session()
        withStore { store ->
            val msg = buildMessage(s, to, subject, body, replyUid, store)
            msg.setFlag(Flags.Flag.DRAFT, true)
            msg.saveChanges()
            val drafts = store.getFolder(folderName(store, "drafts"))
            drafts.appendMessages(arrayOf(msg))
        }
    }

    // ——— أدوات مساعدة
    private fun fromOf(m: Message): String {
        val a = m.from?.firstOrNull() as? InternetAddress ?: return m.from?.firstOrNull()?.toString().orEmpty()
        val name = a.personal?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        return if (name.isNullOrBlank()) a.address else "$name <${a.address}>"
    }

    private fun textOf(p: Part): String {
        return when {
            p.isMimeType("text/plain") -> p.content?.toString().orEmpty()
            p.isMimeType("text/html") -> htmlToText(p.content?.toString().orEmpty())
            p.isMimeType("multipart/alternative") -> {
                val mp = p.content as Multipart
                var html: String? = null
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    if (bp.isMimeType("text/plain")) return bp.content.toString()
                    if (bp.isMimeType("text/html")) html = bp.content.toString()
                }
                html?.let { htmlToText(it) }.orEmpty()
            }
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                (0 until mp.count).map { mp.getBodyPart(it) }
                    .filter { !Part.ATTACHMENT.equals(it.disposition, ignoreCase = true) }
                    .joinToString("\n") { textOf(it) }
            }
            p.isMimeType("message/rfc822") -> textOf(p.content as Part)
            else -> ""
        }.trim()
    }

    private fun attachmentNames(p: Part): List<String> = when {
        p.isMimeType("multipart/*") -> {
            val mp = p.content as Multipart
            (0 until mp.count).flatMap { attachmentNames(mp.getBodyPart(it)) }
        }
        Part.ATTACHMENT.equals(p.disposition, ignoreCase = true) && p.fileName != null ->
            listOf(runCatching { MimeUtility.decodeText(p.fileName) }.getOrDefault(p.fileName))
        else -> emptyList()
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()
}
