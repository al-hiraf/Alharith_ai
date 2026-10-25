package com.alharith.ai.service

import android.app.Activity
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alharith.ai.AlHarithApp
import com.alharith.ai.R
import com.alharith.ai.data.ActivityLog

/**
 * منبّه التذكيرات الصوتي: نغمة عالية على قناة صوت المنبّهات تتكرر حتى يوقفها المستخدم،
 * مع شاشة كاملة فوق القفل. طبقة إضافية فوق إشعارات التذكير الحالية — لا تغيّرها.
 */
object AlarmRinger {
    const val NOTIF_ID = 909_001
    const val EXTRA_TEXT = "alarm_text"

    /** يعرض إشعار منبّه بملء الشاشة ويشغّل النغمة المتكررة. آمن عند فشل أي جزء. */
    fun ring(context: Context, text: String) {
        val app = context.applicationContext
        val fullScreen = PendingIntent.getActivity(
            app, 11,
            Intent(app, AlarmAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            app, 12, Intent(app, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(app, AlHarithApp.CHANNEL_REMINDER_ALARM)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("منبّه رفيق")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setDeleteIntent(stopPi)
            .addAction(0, "إيقاف المنبّه", stopPi)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
            return
        }
        try {
            app.startForegroundService(
                Intent(app, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_RING).putExtra(EXTRA_TEXT, text)
            )
        } catch (e: Exception) {
            // قيود الخلفية (أندرويد 12+ من عامل مجدول): نكتفي بإشعار ملء الشاشة وصوت القناة مرة واحدة
            ActivityLog.record("التذكيرات", text.take(80), "منبّه", "تعذر تشغيل صوت المنبّه في الخلفية: ${e.javaClass.simpleName}")
        }
    }

    /** يوقف النغمة ويغلق إشعار المنبّه. */
    fun stop(context: Context) {
        val app = context.applicationContext
        try { app.startService(Intent(app, AlarmRingService::class.java).setAction(AlarmRingService.ACTION_STOP)) } catch (_: Exception) {}
        NotificationManagerCompat.from(app).cancel(NOTIF_ID)
    }
}

/** خدمة أمامية قصيرة العمر تشغّل نغمة المنبّه بشكل متكرر على قناة USAGE_ALARM حتى الإيقاف (أو ٣ دقائق كحد أقصى). */
class AlarmRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_RING -> { if (!ringing) startRinging(intent.getStringExtra(AlarmRinger.EXTRA_TEXT) ?: "تذكير من رفيق") }
        }
        return START_STICKY
    }

    private fun startRinging(text: String) {
        ringing = true
        val stopPi = PendingIntent.getService(
            this, 12, Intent(this, AlarmRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 11,
            Intent(this, AlarmAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmRinger.EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, AlHarithApp.CHANNEL_REMINDER_ALARM)
            .setSmallIcon(R.drawable.ic_stat_harith)
            .setContentTitle("منبّه رفيق")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(openPi)
            .setFullScreenIntent(openPi, true)
            .setDeleteIntent(stopPi)
            .addAction(0, "إيقاف المنبّه", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(AlarmRinger.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(AlarmRinger.NOTIF_ID, n)
        }
        wakeLock = (getSystemService(PowerManager::class.java))?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "rafiq:alarm"
        )?.apply { acquire(MAX_RING_MS + 10_000L) }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600), 0))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(longArrayOf(0, 600, 300, 600), 0)
        }
        // تأخير بسيط حتى لا تتداخل النغمة مع صوت قناة الإشعار نفسه في اللحظة الأولى
        handler.postDelayed({ runCatching { startPlayer() } }, 2_100)
        // أمان: لا رنين لا نهائي إن ترك المستخدم الجوال
        handler.postDelayed({ shutdown() }, MAX_RING_MS)
    }

    private fun startPlayer() {
        if (player != null) return
        val afd = resources.openRawResourceFd(R.raw.rafiq_alarm) ?: return
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            isLooping = true
            prepare()
            start()
        }
        runCatching { afd.close() }
    }

    private fun shutdown() {
        ringing = false
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(AlarmRinger.NOTIF_ID)
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.alharith.ai.ALARM_RING"
        const val ACTION_STOP = "com.alharith.ai.ALARM_STOP"
        private const val MAX_RING_MS = 3 * 60_000L
    }
}

/** شاشة المنبّه الكاملة: تظهر فوق شاشة القفل وتشغّل الشاشة، وفيها زر إيقاف كبير. */
class AlarmAlertActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(KeyguardManager::class.java))?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val text = intent.getStringExtra(AlarmRinger.EXTRA_TEXT) ?: "تذكير من رفيق"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A2530"))
            setPadding(56, 56, 56, 56)
        }
        val bell = TextView(this).apply {
            this.text = "🔔"
            textSize = 80f
            gravity = Gravity.CENTER
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 48)
        }
        val btn = Button(this).apply {
            this.text = "إيقاف المنبّه"
            textSize = 22f
            setOnClickListener {
                AlarmRinger.stop(this@AlarmAlertActivity)
                finish()
            }
        }
        root.addView(bell, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }
}
