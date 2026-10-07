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
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    // مفتاح توقيع ثابت: كل نسخة جديدة تُثبَّت فوق السابقة دون حذفها
    signingConfigs {
        create("harith") {
            storeFile = file("harith.jks")
            storePassword = "alharith2026"
            keyAlias = "harith"
            keyPassword = "alharith2026"
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
            signingConfig = signingConfigs.getByName("harith")
        }
        debug {
            signingConfig = signingConfigs.getByName("harith")
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

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // كلمة التنبيه "يا الحارث"
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // البريد عبر IMAP/SMTP (Gmail بكلمة مرور التطبيقات وغيرها)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
