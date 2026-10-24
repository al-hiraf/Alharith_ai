package com.alharith.ai.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.alharith.ai.data.ActivityLog
import com.alharith.ai.data.Prefs
import com.alharith.ai.ui.MainActivity

/**
 * زر رفيق الخارجي: الضغط المطوّل على رفع الصوت (أو ضغطتان سريعتان على خفض الصوت) يستدعي رفيق.
 * يستقبل أزرار الصوت فقط ولا يقرأ أي محتوى من الشاشة. الضغطة القصيرة تغيّر الصوت كالمعتاد.
 */
class VolumeButtonService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var holding = false
    private var fired = false
    private var lastDownPress = 0L
    private var swallowNextDownUp = false

    private val longPress = Runnable {
        if (holding && !fired) { fired = true; summon() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!Prefs.volumeTrigger) return false
        // أثناء المكالمات أو الرنين تبقى أزرار الصوت للنظام
        val am = getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE) {
            return false
        }
        return when (Prefs.volumeMode) {
            MODE_DOUBLE_DOWN -> handleDoubleDown(event, am)
            else -> handleLongUp(event, am)
        }
    }

    /** ضغط مطوّل على رفع الصوت: نؤجل تغيير الصوت حتى نعرف هل الضغطة قصيرة أم مطوّلة */
    private fun handleLongUp(e: KeyEvent, am: AudioManager): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) {
                    holding = true
                    fired = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                } else if (!fired && (e.isLongPress || e.repeatCount >= 2)) {
                    fired = true
                    handler.removeCallbacks(longPress)
                    summon()
                }
            }
            KeyEvent.ACTION_UP -> {
                holding = false
                handler.removeCallbacks(longPress)
                // ضغطة قصيرة: نرفع الصوت بأنفسنا كما يفعل النظام تمامًا
                if (!fired) am.adjustSuggestedStreamVolume(
                    AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI
                )
                fired = false
            }
        }
        return true
    }

    /** ضغطتان سريعتان على خفض الصوت: الأولى تعمل طبيعيًا، والثانية تستدعي رفيق وتعيد الصوت كما كان */
    private fun handleDoubleDown(e: KeyEvent, am: AudioManager): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastDownPress < DOUBLE_MS) {
                lastDownPress = 0
                swallowNextDownUp = true
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
                summon()
                return true
            }
            lastDownPress = now
            return false
        }
        if (e.action == KeyEvent.ACTION_UP && swallowNextDownUp) {
            swallowNextDownUp = false
            return true
        }
        return false
    }

    private fun summon() {
        vibrate()
        ActivityLog.record("زر الصوت", "", "استدعاء رفيق", "بدأ الاستماع")
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_LISTEN_NOW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { startActivity(i) }
    }

    private fun vibrate() {
        runCatching {
            val v = getSystemService(Vibrator::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        const val MODE_LONG_UP = "long_up"
        const val MODE_DOUBLE_DOWN = "double_down"
        private const val LONG_PRESS_MS = 550L
        private const val DOUBLE_MS = 450L

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, VolumeButtonService::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
