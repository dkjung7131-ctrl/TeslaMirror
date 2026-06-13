import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 버전은 CI에서 git 태그로 주입(-PversionName / -PversionCode). 로컬 빌드는 아래 기본값 사용.
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.example.teslamirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.teslamirror"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    // 릴리스 서명 — 자동 업데이트가 동작하려면 매 빌드가 동일한 키로 서명돼야 한다.
    // CI는 환경변수로, 로컬 릴리스 빌드는 app/keystore.properties로 키를 주입.
    // 키 정보가 없으면 signingConfig 미설정(디버그 서명) → 로컬 디버그 빌드는 그대로 동작.
    val keystorePropsFile = rootProject.file("app/keystore.properties")
    val ksFromEnv = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            when {
                ksFromEnv != null -> {
                    storeFile = file(ksFromEnv)
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
                keystorePropsFile.exists() -> {
                    val props = Properties().apply {
                        keystorePropsFile.inputStream().use { load(it) }
                    }
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }
    val hasReleaseKey = ksFromEnv != null || keystorePropsFile.exists()

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Ktor server (embedded)
    val ktorVersion = "2.3.13"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
