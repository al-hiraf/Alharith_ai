plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.alharith.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alharith.ai"
        // رابط خادم رفيق العام لتسجيل المستخدمين بلا إعداد (يُضبط من متغير RAFIQ_SERVER_URL في GitHub)
        // إن لم يُحدد: خادم رفيق الرسمي (نفس ما في النسخ الموقّعة 3.4.6 و3.4.7)
        val rafiqServer = (System.getenv("RAFIQ_SERVER_URL")?.takeIf { it.isNotBlank() }
            ?: (project.findProperty("rafiqServer") as String?)
            ?: "https://rafiq01.duckdns.org").trim().trimEnd('/')
        buildConfigField("String", "PROVISION_URL", "\"$rafiqServer\"")
        // ٩ فأحدث: الانتقال لمفتاح التوقيع الجديد (توقيع v3 مع lineage) يعمل من أندرويد ٩
        minSdk = 28
        targetSdk = 35
        versionCode = 45
        versionName = "3.5.1"
    }

    // التوقيع: المفتاح القديم انكشف فلم يعد البناء يستخدمه أبدًا.
    // نسخة الإصدار تُبنى بلا توقيع ثم تُوقَّع خارج البناء بالمفتاح الجديد مع ملف الانتقال (signing/rafiq.lineage).
    // وإن أُضيفت أسرار GitHub (RAFIQ_KEYSTORE + RAFIQ_KEY_PASSWORD) تُوقَّع هنا بالمفتاح الجديد مباشرة.
    signingConfigs {
        val releaseStore = System.getenv("RAFIQ_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
        val releasePass = System.getenv("RAFIQ_KEY_PASSWORD").orEmpty()
        if (releaseStore != null && releasePass.isNotEmpty()) {
            create("rafiq") {
                storeFile = releaseStore
                storePassword = releasePass
                keyAlias = "rafiq"
                keyPassword = releasePass
            }
        }
    }

    flavorDimensions += "edition"
    productFlavors {
        // كاملة: قراءة SMS وإشعارات واتساب (قد يحظرها Play Protect عند التثبيت من خارج المتجر)
        create("full") { dimension = "edition" }
        // خفيفة: بدون قراءة SMS والإشعارات، تُثبَّت دون حظر
        create("lite") { dimension = "edition" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("rafiq")?.let { signingConfig = it }  // بلا أسرار: بلا توقيع
        }
        debug {
            // نسخة الاختبار تُوقَّع بمفتاح الاختبار الافتراضي (لا تُوزَّع)
            // نسخة الاختبار لا تسجّل أبدًا في خادم رفيق الحقيقي (الاختبارات تمرر خادمها المحلي صراحة)
            buildConfigField("String", "PROVISION_URL", "\"http://127.0.0.1:9\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.md"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // جلب إشعارات الخادم في الخلفية والتطبيق مغلق (كل ١٥ دقيقة على الأقل، حسب نظام أندرويد)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // إشعارات فورية: رسالة إيقاظ بلا محتوى من الخادم، والإعداد يصل من الخادم وقت التشغيل (بلا ملف google-services في الكود)
    implementation("com.google.firebase:firebase-messaging:24.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // كلمة التنبيه "يا الحارث"
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // البريد عبر IMAP/SMTP (Gmail بكلمة مرور التطبيقات وغيرها)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
