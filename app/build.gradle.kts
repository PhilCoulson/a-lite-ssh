import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionCode = 4
val appVersionName = "1.2.0"

android {
    namespace = "com.alite.ssh"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.alite.ssh"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField(
            "String",
            "UPDATE_API_URL",
            "\"https://api.github.com/repos/PhilCoulson/a-lite-ssh/releases/latest\"",
        )

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=none",
                    "-DANDROID_ARM_NEON=TRUE",
                )
                targets += "alite_ssh"
            }
        }
    }

    signingConfigs {
        val keystoreProps = rootProject.file("keystore.properties")
        val envStore = System.getenv("RELEASE_STORE_FILE")
        if (keystoreProps.isFile) {
            val props = Properties()
            keystoreProps.inputStream().use { props.load(it) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        } else if (!envStore.isNullOrBlank()) {
            create("release") {
                storeFile = file(envStore)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { cfg ->
                if (cfg.storeFile?.isFile == true) {
                    signingConfig = cfg
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf("**/libalite_ssh.so")
        }
    }
}

tasks.register("writeUpdateMetadata") {
    val json = layout.buildDirectory.file("outputs/apk/release/version.json")
    outputs.file(json)
    outputs.upToDateWhen { false }
    doLast {
        val file = json.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """{"versionCode":$appVersionCode,"versionName":"$appVersionName"}""" + "\n",
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
}
