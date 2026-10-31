package com.alharith.ai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Account-scoped private avatar. Never request broad media permissions or keep the source URI. */
object ProfilePhoto {
    private val mutex = Mutex()
    private lateinit var context: Context
    fun init(ctx: Context) { context = ctx.applicationContext }
    private fun cache(identity: String): java.io.File {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(context.filesDir, "profile-photo-$hash.txt")
    }
    val image = MutableStateFlow<Bitmap?>(null)
    private var owner = ""
    private fun key() = if (Prefs.sharedBrainReady) Prefs.serverUrl + ":" + java.security.MessageDigest.getInstance("SHA-256").digest(Prefs.serverToken.toByteArray()).joinToString("") { "%02x".format(it) } else "local"
    fun current(): Bitmap? = if (owner == key()) image.value else null
    private fun show(data: String) {
        owner = key()
        image.value = runCatching { val bytes = Base64.decode(data.substringAfter(","), Base64.DEFAULT); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock {
        val identity = key()
        show(if (cache(identity).isFile) cache(identity).readText() else "")
        if (Prefs.sharedBrainReady) {
            val data = SharedBrain.request("GET", "/api/settings").getJSONObject("settings").optString("profile_photo")
            if (key() == identity) { cache(identity).writeText(data); show(data) }
        }
    } }
    suspend fun save(context: Context, uri: Uri?) = withContext(Dispatchers.IO) { mutex.withLock {
        val identity = key()
        val data = if (uri == null) "" else {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited() } ?: error("تعذر فتح الصورة")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            require(opts.outWidth > 0 && opts.outHeight > 0) { "اختر صورة صالحة" }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 1024) sample *= 2
            val source = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("تعذر قراءة الصورة")
            val side = minOf(source.width, source.height)
            val square = Bitmap.createBitmap(source, (source.width-side)/2, (source.height-side)/2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, 256, 256, true)
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
        if (Prefs.sharedBrainReady) SharedBrain.request("PUT", "/api/settings", JSONObject().put("profile_photo", data))
        check(identity == key()) { "تغير الحساب؛ حاول مجددًا" }
        cache(identity).writeText(data); show(data)
    } }
    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 20 * 1024 * 1024) { val n = read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
        val bytes = output.toByteArray()
        require(bytes.size <= 20 * 1024 * 1024) { "اختر صورة أصغر من ٢٠ ميغابايت" }
        return bytes
    }
}
