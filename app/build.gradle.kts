plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.txor.commix"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.txor.commix"
        minSdk = 28
        targetSdk = 35
        versionCode = 15
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        resourceConfigurations += listOf("en")

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++20",
                    "-O3",
                    "-flto",
                    "-ffunction-sections",
                    "-fdata-sections",
                    "-fno-exceptions",
                    "-fno-rtti"
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("SIGNING_STORE_FILE") ?: System.getenv("KEYSTORE_PATH")
            if (storeFilePath != null && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi"
        )
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version"
            )
        }
    }
}

dependencies {
    // Kotlin & Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // AndroidX Core & Lifecycle
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose (BOM)
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Camera & Media
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.heifwriter:heifwriter:1.0.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.register<Exec>("testNative") {
    group = "verification"
    description = "Compiles and executes the C++20 Aether engine suite plus the JNI boundary suite."
    workingDir = projectDir
    commandLine(
        "bash", "-c",
        """
        set -e
        mkdir -p build/test-native

        # Host warning flags mirror app/src/main/cpp/CMakeLists.txt (-Wall -Wextra)
        # and include the same -Wno-unused-parameter the NDK build uses, so the
        # suite compiles the shipped sources under the shipped warning policy.

        # 1) Engine suite: every engine source except jni_bridge.cpp.
        g++ -std=c++20 -Wall -Wextra -Werror -Wno-unused-parameter \
          src/test/cpp/native_engine_test.cpp \
          src/main/cpp/computational_photo.cpp \
          src/main/cpp/stabilization_engine.cpp \
          src/main/cpp/color_science.cpp \
          src/main/cpp/image_pipeline.cpp \
          src/main/cpp/scopes_engine.cpp \
          src/main/cpp/best_shot_engine.cpp \
          src/main/cpp/keyframe_engine.cpp \
          src/main/cpp/commix_telemetry.cpp \
          -lpthread -lm -o build/test-native/native_engine_test
        ./build/test-native/native_engine_test

        # 2) JNI boundary suite: jni_bridge.cpp is #included verbatim into this test
        #    translation unit (its entry points are static) and compiled against the
        #    host stubs in src/test/cpp/jni_stubs. It must NOT also be listed as a
        #    source here - a second copy would collide at link time. Same engine
        #    sources provide the symbols its natives call.
        g++ -std=c++20 -Wall -Wextra -Werror -Wno-unused-parameter \
          -I src/test/cpp -I src/test/cpp/jni_stubs \
          src/test/cpp/jni_bridge_test.cpp \
          src/main/cpp/computational_photo.cpp \
          src/main/cpp/stabilization_engine.cpp \
          src/main/cpp/color_science.cpp \
          src/main/cpp/image_pipeline.cpp \
          src/main/cpp/scopes_engine.cpp \
          src/main/cpp/best_shot_engine.cpp \
          src/main/cpp/keyframe_engine.cpp \
          src/main/cpp/commix_telemetry.cpp \
          -lpthread -lm -o build/test-native/jni_bridge_test
        ./build/test-native/jni_bridge_test
        """.trimIndent()
    )
}

tasks.named("check") {
    dependsOn("testNative")
}
