package com.alharith.ai.tools

import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings

object DeviceTools {

    /** أسماء شائعة بالعربية ← كلمات تطابق اسم التطبيق أو الحزمة */
    private val aliases = mapOf(
        "واتساب" to listOf("whatsapp"), "واتس" to listOf("whatsapp"), "الواتس" to listOf("whatsapp"),
        "تيليجرام" to listOf("telegram"), "تلقرام" to listOf("telegram"), "تلغرام" to listOf("telegram"),
        "سيجنال" to listOf("signal"), "يوتيوب" to listOf("youtube"), "سناب" to listOf("snapchat"),
        "انستقرام" to listOf("instagram"), "انستا" to listOf("instagram"), "تويتر" to listOf("twitter", "x"),
        "اكس" to listOf("x", "twitter"), "تيك توك" to listOf("tiktok"), "كروم" to listOf("chrome"),
        "خرائط" to listOf("maps"), "قوقل ماب" to listOf("maps"), "جيميل" to listOf("gmail"),
        "الصور" to listOf("photos", "gallery"), "المعرض" to listOf("gallery", "photos"),
        "الحاسبه" to listOf("calculator"), "الالة الحاسبه" to listOf("calculator"),
        "الساعه" to listOf("clock"), "التقويم" to listOf("calendar"), "الملفات" to listOf("files", "my files"),
        "المتجر" to listOf("play store", "galaxy store"), "متجر قوقل" to listOf("play store"),
        "توكلنا" to listOf("tawakkalna"), "ابشر" to listOf("absher"), "نفاذ" to listOf("nafath"),
        "الراجحي" to listOf("alrajhi", "al rajhi"), "اوبر" to listOf("uber"), "كريم" to listOf("careem"),
        "جاهز" to listOf("jahez"), "هنقرستيشن" to listOf("hungerstation"), "سبوتيفاي" to listOf("spotify"),
        "نتفلكس" to listOf("netflix"), "شاهد" to listOf("shahid"), "تيمز" to listOf("teams"),
        "زوم" to listOf("zoom"), "اوتلوك" to listOf("outlook"), "الرسائل" to listOf("messages"),
        "الهاتف" to listOf("phone", "dialer"), "جهات الاتصال" to listOf("contacts")
    )

    fun tools(env: ToolEnv): List<Tool> = listOf(

        Tool(
            "open_app", "يفتح التطبيق",
            "يفتح أي تطبيق مثبت على الهاتف بالاسم (عربي أو إنجليزي).",
            schema("name" to prop("string", "اسم التطبيق كما قاله المستخدم"), required = listOf("name"))
        ) { input ->
            val pm = env.context.packageManager
            val launchables = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
                .filter { it.second != env.context.packageName }
                .distinctBy { it.second }

            val q = Arabic.norm(input.str("name")).removePrefix("تطبيق ").trim()
            val keys = (listOf(q) + (aliases.entries.firstOrNull { Arabic.norm(it.key) == q || Arabic.norm(it.key) == q.removePrefix("ال") }?.value ?: emptyList()))
                .map { Arabic.norm(it) }.filter { it.isNotBlank() }

            fun score(label: String, pkg: String): Int {
                val l = Arabic.norm(label)
                var best = 0
                for (k in keys) {
                    best = maxOf(best, when {
                        l == k -> 100
                        l.startsWith(k) -> 80
                        l.contains(k) -> 60
                        k.length >= 3 && pkg.lowercase().contains(k.replace(" ", "")) -> 50
                        k.contains(l) && l.length >= 3 -> 40
                        else -> 0
                    })
                }
                return best
            }

            val ranked = launchables.map { Triple(it.first, it.second, score(it.first, it.second)) }
                .filter { it.third > 0 }.sortedByDescending { it.third }
            if (ranked.isEmpty()) return@Tool ToolResult.error("لم أجد تطبيقًا باسم \"${input.str("name")}\" على الهاتف.")
            val top = ranked.first()
            if (ranked.size > 1 && ranked[1].third == top.third && top.third < 100) {
                return@Tool ToolResult.error(
                    "أكثر من تطبيق يطابق، اسأل المستخدم: " + ranked.take(5).joinToString("، ") { it.first }
                )
            }
            val intent = pm.getLaunchIntentForPackage(top.second)
                ?: return@Tool ToolResult.error("لا يمكن فتح ${top.first}.")
            val r = env.launch(intent)
            if (r.isError) r else ToolResult.ok("فُتح ${top.first}.")
        },

        Tool(
            "open_system_screen", "يفتح الشاشة",
            "يفتح شاشة من النظام: الإعدادات وأقسامها، الكاميرا، لوحة الاتصال، المنبهات، التقويم، جهات الاتصال. " +
                "ملاحظة: Android لا يسمح للتطبيقات بتشغيل الواي فاي أو البلوتوث مباشرة، لذلك تُفتح لوحتها ليضغط المستخدم.",
            schema(
                "screen" to prop(
                    "string", "الشاشة",
                    listOf(
                        "settings", "wifi", "bluetooth", "mobile_data", "display", "sound", "battery",
                        "location", "apps", "notifications", "storage", "about_phone", "date_time",
                        "language", "accessibility", "security", "camera", "video_camera", "dialer",
                        "alarms", "calendar", "contacts", "gallery"
                    )
                ),
                "phone_number" to prop("string", "رقم يوضع في لوحة الاتصال (اختياري)"),
                required = listOf("screen")
            )
        ) { input ->
            val panels = Build.VERSION.SDK_INT >= 29
            val intent = when (input.str("screen")) {
                "wifi" -> Intent(if (panels) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
                "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "mobile_data" -> Intent(if (panels) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)
                "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                "sound" -> Intent(if (panels) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS)
                "battery" -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
                "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                "apps" -> Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                "notifications" -> Intent("android.settings.NOTIFICATION_SETTINGS")
                "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                "about_phone" -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                "date_time" -> Intent(Settings.ACTION_DATE_SETTINGS)
                "language" -> Intent(Settings.ACTION_LOCALE_SETTINGS)
                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "security" -> Intent(Settings.ACTION_SECURITY_SETTINGS)
                "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                "video_camera" -> Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
                "dialer" -> Intent(Intent.ACTION_DIAL).apply {
                    input.str("phone_number").takeIf { it.isNotBlank() }?.let { data = Uri.parse("tel:$it") }
                }
                "alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
                "calendar" -> Intent(Intent.ACTION_VIEW).setData(
                    CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        .appendPath(System.currentTimeMillis().toString()).build()
                )
                "contacts" -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
                "gallery" -> Intent(Intent.ACTION_VIEW).setType("image/*")
                else -> Intent(Settings.ACTION_SETTINGS)
            }
            env.launch(intent)
        },

        Tool(
            "set_flashlight", "يتحكم بالكشاف",
            "يشغّل أو يطفئ كشاف الهاتف.",
            schema("on" to prop("boolean", "تشغيل أم إطفاء"), required = listOf("on"))
        ) { input ->
            try {
                val cm = env.context.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                } ?: return@Tool ToolResult.error("لا يوجد كشاف في هذا الجهاز.")
                val on = input.optBoolean("on", true)
                cm.setTorchMode(id, on)
                ToolResult.ok(if (on) "شُغّل الكشاف." else "أُطفئ الكشاف.")
            } catch (e: Exception) {
                ToolResult.error("تعذّر التحكم بالكشاف (قد تكون الكاميرا مستخدمة).")
            }
        },

        Tool(
            "set_volume", "يضبط الصوت",
            "يضبط مستوى الصوت كنسبة مئوية.",
            schema(
                "stream" to prop("string", "نوع الصوت", listOf("media", "ring", "alarm", "notification")),
                "percent" to prop("integer", "من 0 إلى 100"),
                required = listOf("stream", "percent")
            )
        ) { input ->
            val am = env.context.getSystemService(AudioManager::class.java)
            val stream = when (input.str("stream")) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
            val max = am.getStreamMaxVolume(stream)
            val v = (input.optInt("percent").coerceIn(0, 100) * max + 50) / 100
            try {
                am.setStreamVolume(stream, v, AudioManager.FLAG_SHOW_UI)
                ToolResult.ok("ضُبط الصوت على ${input.optInt("percent")}٪.")
            } catch (e: SecurityException) {
                ToolResult.error("لا يسمح النظام بتغيير هذا الصوت أثناء وضع عدم الإزعاج.")
            }
        },

        Tool(
            "get_device_status", "يتحقق من حالة الجهاز",
            "يعيد حالة البطارية والشحن والاتصال بالإنترنت ووضع الرنين.",
            schema()
        ) { _ ->
            val bat = env.context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bat?.let {
                val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0) l * 100 / s else null
            }
            val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val cm = env.context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val net = when {
                caps == null -> "غير متصل"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "واي فاي"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الجوال"
                else -> "متصل"
            }
            val ringer = when (env.context.getSystemService(AudioManager::class.java).ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "صامت"
                AudioManager.RINGER_MODE_VIBRATE -> "اهتزاز"
                else -> "عادي"
            }
            ToolResult.ok(
                "البطارية: ${level ?: "؟"}٪${if (charging) " (يشحن)" else ""}\nالإنترنت: $net\nالرنين: $ringer\n" +
                    "الجهاز: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}"
            )
        }
    )

    @Suppress("unused")
    private fun isInstalled(pm: PackageManager, pkg: String) =
        runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
}
