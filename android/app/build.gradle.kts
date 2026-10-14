plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fabapps.gomonitor"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fabapps.gomonitor"
        minSdk = 26
        // Se mantiene en 35 a propósito: ver D9 en docs/DECISIONS.md antes de subirlo.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { test ->
            // La prueba de punta a punta necesita el binario del agente; `make check-e2e` lo pasa.
            // Sin esta propiedad esa prueba se omite y el resto corre igual.
            providers.gradleProperty("gomonitorAgentBin").orNull?.let { agent ->
                test.systemProperty("gomonitor.agent.bin", agent)
                test.inputs.file(agent)
            }
        }
    }

    lint {
        // targetSdk fijo por D9 y texto claro permitido por D2: son decisiones, no descuidos.
        disable += listOf("OldTargetApi", "InsecureBaseConfiguration")
        // OkHttp se queda en la rama 4.x (D10).
        disable += "NewerVersionAvailable"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
