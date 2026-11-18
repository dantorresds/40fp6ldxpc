plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.atendenteia.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.atendenteia.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.15.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true }
    // Duas cópias que podem ficar instaladas juntas (ex.: dois números no mesmo celular).
    // "principal" mantém o pacote de sempre (instala por cima, sem perder dados).
    flavorDimensions += "copia"
    productFlavors {
        create("principal") { dimension = "copia" }
        create("segundo") {
            dimension = "copia"
            applicationIdSuffix = ".dois"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Assinado com a chave de debug para instalar direto (sem Play Store).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.all { it.systemProperty("siteTest", project.findProperty("siteTest") ?: "false") }
    }
}

dependencies {
    // Motor do WhatsApp (Go/whatsmeow) gerado pelo gomobile: ver engine/LEIA-ME.md
    implementation(files("libs/waengine.aar"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.foundation:foundation:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-core:1.5.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Autorização do Google Drive (entrega de produto digital)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    testImplementation("junit:junit:4.13.2")
    // org.json real (no Android o org.json vem do sistema; nos testes do PC, não)
    testImplementation("org.json:json:20240303")
}
