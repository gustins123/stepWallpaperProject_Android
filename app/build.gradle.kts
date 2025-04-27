import java.util.*
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}


android {
    namespace = "com.example.stepwallpaperprojectfinal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.stepwallpaperprojectfinal"
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"


        var apiKey = ""

        try {
            val properties = Properties().apply {
                rootProject.file("local.properties").reader().use(::load)
            }
            apiKey = properties["unsplash.accessKey"] as String
        } catch (e: Exception) {
            println("Warning: Could not load local.properties: ${e.message}")
        }


        // Read API key from local.properties
        if (apiKey.isEmpty()) {
            println("Warning: unsplash.accessKey not found in local.properties. API calls will likely fail.")
        }
        // Define the build config field
        buildConfigField("String", "UNSPLASH_ACCESS_KEY", "\"$apiKey\"")


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.3"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Retrofit for networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0") // Use the latest version

    // Gson converter for Retrofit (JSON parsing)
    implementation("com.squareup.retrofit2:converter-gson:2.9.0") // Match Retrofit version

    // Coroutines for background tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3") // Use the latest version

    // Optional: OkHttp Logging Interceptor (for debugging network calls)
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0") // Use the latest version

    // Coil for image loading in Compose
    implementation("io.coil-kt:coil-compose:2.6.0") // Use the latest version of Coil

    // Jetpack Preferences DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1") // Use the latest version

    // WorkManager for background tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0") // Use the latest stable version
}