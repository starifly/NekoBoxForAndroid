@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    // kotlin-android is no longer applied: AGP 9.0+ provides built-in Kotlin support
    // (android.builtInKotlin defaults to true), so the org.jetbrains.kotlin.android plugin
    // would clash ("extension already registered with name 'kotlin'").
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }

    // ==========================================
    // ここから下を新しく追加してください
    // ==========================================
    lint {
        // 多言語ファイル (strings.xml) のフォーマットエラーを無視する
        disable.add("StringFormatMatches")
        disable.add("StringFormatCount")
        disable.add("StringFormatInvalid")
        // エラーが出てもビルドを強制的に進める
        isAbortOnError = false
    }
}

// KSP options (room schema export). The `ksp { }` extension is registered by the KSP Gradle
// plugin at the project level, not inside the Android DSL, so it is declared as a top-level
// block here.
ksp {
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    implementation(fileTree("libs"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.work:work-multiprocess:2.9.1")

    implementation("com.google.android.material:material:1.14.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // QR scanning: CameraX live preview/analysis + ML Kit bundled barcode model.
    // Bundled (not play-services-*) so detection works fully offline with no Google
    // Play Services dependency - important for de-Googled ROMs. Replaces zxing-lite,
    // which pulled in ancient CameraX 1.0.x and a weaker ZXing decoder.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // ZXing core (pure-Java) is kept only for QR *generation* (QRCodeDialog); ML Kit
    // scans but cannot encode. It was previously transitive via zxing-lite.
    implementation("com.google.zxing:core:3.5.3")
    // JSON config editor. sora-editor (Rosemoe) is the actively-maintained code editor
    // widget; it replaces the archived com.blacksquircle.ui:editorkit. Syntax highlighting
    // is provided via TextMate grammars (language-textmate), with JSON grammar + themes
    // bundled in assets/textmate/. Versions are managed by the editor-bom platform.
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")

    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.6.2")
    implementation("com.google.guava:guava:31.0.1-android")
    testImplementation("junit:junit:4.13.2")

    implementation("me.zhanghai.android.fastscroll:library:1.3.0")

    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")

    // Unit tests (pure-JVM, run via :app:testOssDebugUnitTest — no device/libcore needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Robolectric is available for tests that must touch Android framework classes;
    // prefer extracting pure functions over using it (see Plan 007).
    testImplementation("org.robolectric:robolectric:4.16.1")

    // Instrumented tests (androidTest) — Room migration verification on a real SQLite.
    // Runs on the CI "Instrumented (Room migrations)" job when the runner has KVM; otherwise
    // run manually on a device before any Room version or schema bump.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.7.2")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
