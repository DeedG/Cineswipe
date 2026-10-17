import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Clé de signature du projet : la même pour toutes les compilations (GitHub Actions et
// Android Studio), pour que chaque nouvelle version s'installe par-dessus la précédente
// sans perdre les données. Voir README.
// Dépôt public : la clé n'y est pas, la CI la reconstitue depuis les secrets du dépôt. Sans clé
// (copie du dépôt, compilation locale), l'APK n'est pas signé avec la clé du projet.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasProjectKey = rootProject.file("keystore/cineswipe.jks").exists() && signingProperties.getProperty("storePassword") != null

// Numéro de version fourni par la CI (numéro du run GitHub, ou celui de `version.properties` sur
// le dépôt public), 1 en local.
val ciVersionCode = providers.environmentVariable("CINESWIPE_VERSION_CODE").orNull?.toIntOrNull() ?: 1

android {
    namespace = "io.github.deedg.cineswipe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.deedg.cineswipe"
        minSdk = 26
        targetSdk = 36
        // Clé TMDB intégrée (secret TMDB_BUILTIN_KEY de la CI) : sans elle, chacun met la sienne.
        buildConfigField("String", "TMDB_BUILTIN_KEY", "\"${System.getenv("TMDB_BUILTIN_KEY").orEmpty().filter { it.isLetterOrDigit() }}\"")
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("cineswipe") {
            storeFile = rootProject.file("keystore/cineswipe.jks")
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasProjectKey) signingConfig = signingConfigs.getByName("cineswipe")
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasProjectKey) signingConfig = signingConfigs.getByName("cineswipe")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric : tests d'intégration (base Room, parcours des écrans) sans émulateur.
        unitTests.isIncludeAndroidResources = true
        // Captures d'écran (outil « Captures », lancé à la main) : désactivées dans les tests habituels.
        val captures = providers.gradleProperty("captures").orElse("false")
        val capturesDir = layout.buildDirectory.dir("captures")
        unitTests.all { test ->
            test.systemProperty("cineswipe.captures", captures.get())
            test.systemProperty("cineswipe.capturesDir", capturesDir.get().asFile.absolutePath)
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.client)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.androidx.work.testing)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
