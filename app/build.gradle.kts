import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreFileEnv = System.getenv("VIDRO_KEYSTORE_FILE")
val keystoreBase64 = System.getenv("VIDRO_KEYSTORE_BASE64")
val keystorePassword = System.getenv("VIDRO_KEYSTORE_PASSWORD")
val keyAliasEnv = System.getenv("VIDRO_KEY_ALIAS")
val keyPasswordEnv = System.getenv("VIDRO_KEY_PASSWORD")

fun decodeKeystore(): File? {
    if (!keystoreFileEnv.isNullOrBlank()) return file(keystoreFileEnv)
    if (keystoreBase64.isNullOrBlank()) return null
    val dir = layout.buildDirectory.dir("signing").get().asFile
    dir.mkdirs()
    val out = dir.resolve("vidro-release.keystore")
    val cleaned = keystoreBase64.trim().replace("\n", "").replace("\r", "")
    try {
        out.writeBytes(Base64.getDecoder().decode(cleaned))
    } catch (e: IllegalArgumentException) {
        throw GradleException("VIDRO_KEYSTORE_BASE64 could not be decoded")
    }
    return out
}

val releaseKeystore = decodeKeystore()
if (
    releaseKeystore != null &&
    (keystorePassword.isNullOrBlank() || keyAliasEnv.isNullOrBlank() || keyPasswordEnv.isNullOrBlank())
) {
    throw GradleException(
        "Release keystore is set but VIDRO_KEYSTORE_PASSWORD, VIDRO_KEY_ALIAS, or VIDRO_KEY_PASSWORD is missing",
    )
}

android {
    namespace = "app.vidro"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.vidro"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
            storeFile = releaseKeystore
            storePassword = keystorePassword
            keyAlias = keyAliasEnv
            keyPassword = keyPasswordEnv
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf(
            "MissingTranslation",
            "ObsoleteSdkInt",
            "IconLauncherShape",
            "ConvertToWebp",
            "GradleDependency",
            "AndroidGradlePluginVersion",
            "UseTomlInstead",
            "MonochromeLauncherIcon",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.guava)
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}

tasks.withType<Test> {
    maxHeapSize = "1536m"
}
