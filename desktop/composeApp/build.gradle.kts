import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(compose.desktop.currentOs)

            // Ktor для WebSocket-сервера (подключаем всю группу)
            implementation(libs.bundles.ktor)
            // Заменяем строки на ссылки из каталога
            implementation(libs.ktor.server.contentNegotiation.jvm)
            implementation(libs.ktor.serialization.kotlinxJson.jvm)
            implementation(libs.ktor.server.netty.jvm)

            // JmDNS для mDNS (Zeroconf)
            implementation(libs.jmdns)

            // ZXing для генерации QR-кодов
            implementation(libs.zxing.core)
            implementation(libs.zxing.javase)

            // Bouncy Castle для работы с сертификатами
            implementation(libs.bouncycastle.bcpkix)

            // SLF4J также заменяем
            implementation(libs.slf4j.simple)

            implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.lapcevichme.bookweaverdesktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.lapcevichme.bookweaverdesktop"
            packageVersion = "1.0.0"

        }
    }
}
