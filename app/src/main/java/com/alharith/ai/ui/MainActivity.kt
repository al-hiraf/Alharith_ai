package com.alharith.ai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.alharith.ai.data.ConversationStore
import com.alharith.ai.data.SharedInbox
import com.alharith.ai.service.AssistantService
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            HarithTheme {
                var screen by rememberSaveable { mutableStateOf("chat") }
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                when (screen) {
                    "settings" -> SettingsScreen(onBack = { screen = "chat" })
                    else -> ChatScreen(onOpenSettings = { screen = "settings" })
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        // تشغيل الخدمة (وكلمة التنبيه) ما دامت صلاحية الميكروفون ممنوحة
        if (hasMic()) AssistantService.send(this, AssistantService.ACTION_START)
        pendingListen?.let { pendingListen = null; if (it) startListening() }
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        if (!hasMic()) {
            ConversationStore.setError("امنح صلاحية الميكروفون أولًا من الإعدادات ← الصلاحيات.")
            return
        }
        AssistantService.send(this, AssistantService.ACTION_LISTEN)
    }

    private var pendingListen: Boolean? = null

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> receiveShare(intent)
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, ACTION_LISTEN_NOW -> pendingListen = true
        }
    }

    /** ملف أو نص شاركه المستخدم من تطبيق آخر: يُنسخ محليًا ويُرفق بالطلب التالي. */
    private fun receiveShare(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri != null) {
            val name = displayName(uri)
            val local = runCatching { copyToCache(uri, name) }.getOrNull()
            SharedInbox.set(SharedInbox.Item(local ?: uri, text, name))
        } else if (!text.isNullOrBlank()) {
            SharedInbox.set(SharedInbox.Item(null, text, text.take(40)))
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "ملف"

    private fun copyToCache(uri: Uri, name: String): Uri {
        val dir = File(cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val out = File(dir, safe)
        contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(out)
    }

    companion object {
        const val ACTION_LISTEN_NOW = "com.alharith.ai.LISTEN"

        /** هل الواجهة ظاهرة الآن؟ (فتح التطبيقات من الخلفية يحتاج صلاحية إضافية) */
        @Volatile
        var isVisible = false
            private set
    }
}
