import java.io.FileInputStream
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Semnatura release: citim parolele din ../keystore.properties (gitignored).
val keystorePropsFile = rootProject.file("../keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.rolling7.solar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rolling7.solar"
        minSdk = 26
        targetSdk = 34
        versionCode = 48
        versionName = "3.44"
        // Serverul CASA; numai build-ul debug il poate schimba (vezi mai jos), release il pastreaza mereu.
        buildConfigField("String", "CASA_BASE", "\"https://vyra.go.ro:31443/solar/casa\"")
    }

    signingConfigs {
        create("release") {
            val configuredStoreFile = keystoreProps.getProperty("storeFile")
            if (configuredStoreFile != null) {
                val configuredFile = file(configuredStoreFile)
                storeFile = if (configuredFile.exists()) {
                    configuredFile
                } else {
                    rootProject.file("../${File(configuredStoreFile).name}")
                }
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Doar pentru verificarea in emulator cu un server demo local: -PcasaBase=http://10.0.2.2:18100
            // (cleartext permis numai spre 10.0.2.2, vezi src/debug/res/xml/network_security_config.xml).
            (project.findProperty("casaBase") as String?)?.let {
                buildConfigField("String", "CASA_BASE", "\"$it\"")
            }
        }
        release {
            isMinifyEnabled = true       // R8: scoate cod nefolosit
            isShrinkResources = true     // scoate resurse nefolosite
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    // org.json din android.jar e doar stub in testele JVM; pentru parserul BMS trebuie cel real.
    testImplementation("org.json:json:20231013")
}
