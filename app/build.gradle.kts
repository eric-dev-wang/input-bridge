fun String.toBuildConfigStringLiteral(): String =
  "\"${replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

plugins {
  alias(libs.plugins.inputbridge.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.inputbridge.koin)
}

android {
    namespace = "com.ericdevwang.inputbridge"
    defaultConfig {
        applicationId = "com.ericdevwang.inputbridge"
        versionCode = rootProject.extra["bridgeVersionCode"] as Int
        versionName = rootProject.version.toString()
        val sharedSecret = rootProject.extra["inputBridgeSharedSecret"] as String
        buildConfigField("String", "INPUT_BRIDGE_SHARED_SECRET", sharedSecret.toBuildConfigStringLiteral())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

dependencies {
  implementation(project(":core:designsystem"))
  implementation(project(":core:data"))
  implementation(project(":protocol"))

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.koin.androidx.compose)

  // Local TCP server
  implementation(project(":core:connection-server"))
  testImplementation(project(":core:connection-client"))
  testImplementation(project(":core:framing"))

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
