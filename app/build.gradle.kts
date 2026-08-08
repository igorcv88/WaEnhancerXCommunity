import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.materialthemebuilder)
    alias(libs.plugins.kotlinAndroid)
}

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.waenhancer"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!PublicSuffixDatabase.list"
    }

    flavorDimensions += "version"

    productFlavors {
        create("whatsapp") {
            dimension = "version"
            applicationIdSuffix = ""
        }
    }

    defaultConfig {
        applicationId = "com.waenhancer.community"
        minSdk = 28
        targetSdk = 34
        versionCode = project.findProperty("VERSION_CODE")?.toString()?.toInt() ?: 1
        versionName = project.findProperty("VERSION_NAME")?.toString() ?: "1.0.0"

        val env = Properties()
        val envFile = rootProject.file(".env")
        if (envFile.exists()) {
            runCatching { env.load(FileInputStream(envFile)) }
        }

        val noticesUrl = (project.findProperty("NOTICES_URL")?.toString()
            ?: env.getProperty("NOTICES_URL")
            ?: "https://api.github.com/repos/igorcv88/WaEnhancerX/releases/latest").trim()
        buildConfigField("String", "NOTICES_URL", "\"$noticesUrl\"")
        multiDexEnabled = true
        resourceConfigurations += listOf("en", "ar", "de", "es", "fr", "id", "in", "it", "iw", "pt", "ru", "tr", "zh")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        signingConfigs.create("config") {
            val keystorePropertiesFile = rootProject.file("local.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                keystoreProperties.load(FileInputStream(keystorePropertiesFile))
            }

            val androidStoreFile = project.findProperty("androidStoreFile") as String?
                ?: keystoreProperties.getProperty("androidStoreFile")

            if (!androidStoreFile.isNullOrEmpty()) {
                storeFile = rootProject.file(androidStoreFile)
                storePassword = project.findProperty("androidStorePassword") as String?
                    ?: keystoreProperties.getProperty("androidStorePassword")
                keyAlias = project.findProperty("androidKeyAlias") as String?
                    ?: keystoreProperties.getProperty("androidKeyAlias")
                keyPassword = project.findProperty("androidKeyPassword") as String?
                    ?: keystoreProperties.getProperty("androidKeyPassword")
                enableV1Signing = true
                enableV2Signing = true
            }
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/maven/**"
            excludes += "META-INF/proguard/**"
            excludes += "okhttp3/**"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "**.bin"
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
            excludes += "assets/PublicSuffixDatabase.list"
        }
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/x86/**"
            excludes += "lib/x86_64/**"
        }
        dex {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs["debug"]
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            if (signingConfigs["config"].storeFile != null) {
                signingConfig = signingConfigs["config"]
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        disable += "SelectedPhotoAccess"
        disable += "MissingDefaultResource"
        abortOnError = false
    }

    materialThemeBuilder {
        themes {
            for ((name, color) in listOf(
                "Green" to "4FAF50",
                "Blue" to "3B82F6",
                "Cyan" to "06B6D4",
                "Purple" to "8B5CF6",
                "Orange" to "F97316",
                "Red" to "EF4444",
                "Pink" to "EC4899"
            )) {
                create("Material$name") {
                    lightThemeFormat = "ThemeOverlay.Light.%s"
                    darkThemeFormat = "ThemeOverlay.Dark.%s"
                    primaryColor = "#$color"
                }
            }
        }
        generatePalette = false
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.forEach {
            val output = it as? com.android.build.gradle.api.ApkVariantOutput
            if (output != null) {
                val suffix = if (variant.buildType.name == "debug") "_debug" else "_release"
                output.outputFileName = "WaEnhancerCommunity-v${variant.versionName}${suffix}.apk"
            }
        }
    }
}

dependencies {
    implementation(libs.blurview)
    implementation(libs.colorpicker)
    implementation(libs.dexkit)
    compileOnly(libs.libxposed.legacy)

    implementation(libs.androidx.activity)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.rikkax.appcompat)
    implementation(libs.rikkax.core)
    implementation(libs.material)
    implementation(libs.rikkax.material)
    implementation(libs.rikkax.material.preference)
    implementation(libs.rikkax.widget.borderview)
    implementation(libs.jstyleparser)
    implementation(libs.okhttp)
    implementation(libs.filepicker)
    implementation(libs.betterypermissionhelper)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.arscblamer)
    implementation("com.google.auto.value:auto-value-annotations:1.11.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    implementation(libs.markwon.core)
    implementation(libs.markwon.html)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

configurations.all {
    exclude("androidx.appcompat", "appcompat")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

afterEvaluate {
    listOf("installWhatsappDebug").forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            runCatching {
                val injected = project.objects.newInstance<InjectedExecOps>()
                runBlocking {
                    injected.execOps.exec {
                        commandLine(
                            "adb",
                            "shell",
                            "am",
                            "force-stop",
                            project.properties["debug_package_name"]?.toString()
                        )
                    }
                    delay(500)
                    injected.execOps.exec {
                        commandLine(
                            "adb",
                            "shell",
                            "monkey",
                            "-p",
                            project.properties["debug_package_name"].toString(),
                            "1"
                        )
                    }
                }
            }
        }
    }
}
