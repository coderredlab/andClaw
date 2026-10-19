import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

android {
    namespace = "com.coderred.andclaw"
    compileSdk = 36
    ndkVersion = "28.0.13004108"

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile", "../andclaw-release.keystore"))
            storePassword = keystoreProperties.getProperty("storePassword", System.getenv("KEYSTORE_PASSWORD") ?: "")
            keyAlias = keystoreProperties.getProperty("keyAlias", "andclaw")
            keyPassword = keystoreProperties.getProperty("keyPassword", System.getenv("KEYSTORE_PASSWORD") ?: "")
        }
    }

    defaultConfig {
        applicationId = "com.coderred.andclaw"
        minSdk = 26
        targetSdk = 36
        versionCode = 354
        versionName = "2.3.47"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("prod") {
            dimension = "env"
            buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"andclaw\"")
            buildConfigField(
                "String",
                "OPENROUTER_CALLBACK_URL",
                "\"https://andclaw.coderred.com/callback?scheme=andclaw&package=com.coderred.andclaw\"",
            )
            buildConfigField("String", "OAUTH_APP_RETURN_URI", "\"andclaw://auth/codex-callback\"")
            manifestPlaceholders["oauthCallbackScheme"] = "andclaw"
        }
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            versionCode = 129
            versionNameSuffix = "-dev"
            resValue("string", "app_name", "andClaw Dev")
            buildConfigField("String", "OAUTH_CALLBACK_SCHEME", "\"andclaw-dev\"")
            buildConfigField(
                "String",
                "OPENROUTER_CALLBACK_URL",
                "\"https://andclaw.coderred.com/callback?scheme=andclaw-dev&package=com.coderred.andclaw.dev\"",
            )
            buildConfigField("String", "OAUTH_APP_RETURN_URI", "\"andclaw-dev://auth/codex-callback\"")
            manifestPlaceholders["oauthCallbackScheme"] = "andclaw-dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // 디버그 APK: PAD 에셋을 직접 포함 (AAB에서는 PAD가 처리)
    sourceSets {
        getByName("debug") {
            assets.srcDirs("../install_time_assets/src/main/assets")
        }
    }

    androidResources {
        noCompress += listOf("tar.gz", "bin")
        // AAPT 기본 무시 패턴 재정의 (_* 파일이 무시되지 않도록)
        ignoreAssetsPattern = "!.svn:!.git:!*.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    // proroot 바이너리를 nativeLibraryDir에 추출하기 위해 필수
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Play Asset Delivery - 대용량 assets를 asset pack으로 분리
    assetPacks += listOf(":install_time_assets")
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity & Lifecycle
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.9")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Browser (Chrome Custom Tabs)
    implementation("androidx.browser:browser:1.8.0")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WorkManager (watchdog recovery path)
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Google Play In-App Review
    implementation("com.google.android.play:review-ktx:2.0.2")

    // Apache Commons Compress - tar.gz 압축 해제용
    implementation("org.apache.commons:commons-compress:1.27.1")

    // ZXing - QR 코드 렌더링 (WhatsApp 페어링)
    implementation("com.google.zxing:core:3.5.3")

    // OkHttp - 게이트웨이 WebSocket 통신
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

tasks.register<Test>("testDebugUnitTest") {
    group = "verification"
    description = "Compatibility alias for prod debug unit tests"
    val prodDebugTask = tasks.named<Test>("testProdDebugUnitTest").get()
    testClassesDirs = prodDebugTask.testClassesDirs
    classpath = prodDebugTask.classpath
}

// Exercise the production JNI reader on the host; each Test JVM gets its own build output.
val gatewaySnapshotTestNames = android.productFlavors.flatMap { flavor ->
    android.buildTypes.map { buildType ->
        "test${flavor.name.replaceFirstChar { it.uppercaseChar() }}${buildType.name.replaceFirstChar { it.uppercaseChar() }}UnitTest"
    }
} + "testDebugUnitTest"
val gatewaySnapshotSources = fileTree("src/main/cpp") {
    include("CMakeLists.txt", "*.c")
}
val gatewaySnapshotCmake = android.sdkDirectory.resolve("cmake/3.22.1/bin/cmake")
val gatewaySnapshotNinja = android.sdkDirectory.resolve("cmake/3.22.1/bin/ninja")
val gatewaySnapshotLibraryProperty = "andclaw.gatewayLockSnapshotLibrary"

gatewaySnapshotTestNames.forEach { testName ->
    val suffix = testName.replaceFirstChar { it.uppercaseChar() }
    val hostDirectory = layout.buildDirectory.dir("intermediates/gatewaySnapshotHost/$testName")
    val hostLibrary = hostDirectory.map { it.file("library/libgateway-lock-snapshot.so") }
    val configureNative = tasks.register<Exec>("configureGatewaySnapshotHost$suffix") {
        inputs.files(gatewaySnapshotSources)
        inputs.property("javaHome", System.getProperty("java.home"))
        outputs.file(hostDirectory.map { it.file("CMakeCache.txt") })
        environment("JAVA_HOME", System.getProperty("java.home"))
        commandLine(
            gatewaySnapshotCmake.absolutePath, "-S", file("src/main/cpp").absolutePath,
            "-B", hostDirectory.get().asFile.absolutePath, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${gatewaySnapshotNinja.absolutePath}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${hostLibrary.get().asFile.parentFile.absolutePath}",
        )
    }
    val buildNative = tasks.register<Exec>("buildGatewaySnapshotHost$suffix") {
        dependsOn(configureNative)
        inputs.files(gatewaySnapshotSources)
        outputs.file(hostLibrary)
        commandLine(gatewaySnapshotCmake.absolutePath, "--build", hostDirectory.get().asFile.absolutePath)
    }
    tasks.withType<Test>().matching { it.name == testName }.configureEach {
        dependsOn(buildNative)
        doFirst {
            if (!systemProperties.containsKey(gatewaySnapshotLibraryProperty) &&
                jvmArgs.orEmpty().none { it.startsWith("-D$gatewaySnapshotLibraryProperty=") }
            ) {
                systemProperty(
                    gatewaySnapshotLibraryProperty,
                    System.getProperty(gatewaySnapshotLibraryProperty) ?: hostLibrary.get().asFile.absolutePath,
                )
            }
        }
    }
}

tasks.register("connectedDebugAndroidTest") {
    group = "verification"
    description = "Compatibility alias for prod debug instrumentation tests"
    dependsOn("connectedProdDebugAndroidTest")
}

tasks.register("lintDebug") {
    group = "verification"
    description = "Compatibility alias for prod debug lint checks"
    dependsOn("lintProdDebug")
}
