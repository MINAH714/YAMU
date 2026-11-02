import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)   // 화면 경로(@Serializable)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// local.properties(Git에 안 올림) — 모의 POS 단말 키 yamu.posKey · release 서명 키 yamu.release.* · 실서버 시험 yamu.baseUrl · yamu.realApi
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// release 서명(17 문서) — 네 값이 다 있으면 그 키로 서명, 하나도 없으면 서명 없는 APK. 값은 읽기만 한다(찍지 않음)
val releaseKeyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseKey: Map<String, String>? = releaseKeyNames
    .associateWith { localProps.getProperty("yamu.release.$it").orEmpty() }
    .let { values ->
        val missing = values.filterValues { it.isBlank() }.keys
        when {
            missing.isEmpty() -> values
            missing.size == releaseKeyNames.size -> null
            else -> error("local.properties에 yamu.release.${missing.joinToString(", yamu.release.")}가 없어요")   // 이름만
        }
    }

// 기기 시험용: -Pyamu.devSign → release를 debug 키로 서명하고 버전 끝에 -devsign(M-01에 보임). 배포 금지
val devSign = providers.gradleProperty("yamu.devSign").map { it != "false" }.getOrElse(false)

// 실서버 연결 시험(debug만) — local.properties의 yamu.baseUrl · yamu.realApi.
// 비우면 로컬 BE(8080) 주소에 전부 가짜. yamu.realApi=cashflow,link처럼 적은 기능만 진짜 Repository, all이면 전부
val debugBaseUrl = localProps.getProperty("yamu.baseUrl").orEmpty().trim().ifEmpty { "http://localhost:8080/api/" }
    .also { check(it.endsWith("/")) { "yamu.baseUrl은 /로 끝나야 해요: $it" } }
val apiFeatures = listOf("auth", "link", "cashflow", "home", "payment", "performance", "forecast", "report", "my", "pos")   // ApiFeature와 같은 이름
val debugRealApi = localProps.getProperty("yamu.realApi").orEmpty()
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
    .let { names -> if ("all" in names) apiFeatures else names }
    .also { names ->
        val unknown = names - apiFeatures.toSet()
        check(unknown.isEmpty()) { "yamu.realApi에 모르는 이름: ${unknown.joinToString()} (쓸 수 있는 이름: all, ${apiFeatures.joinToString()})" }
    }

android {
    namespace = "com.yamu.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yamu.app"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "POS_KEY", "\"\"")
    }

    // 같은 프로젝트에서 앱 두 개 — 고객 앱(customer)과 모의 POS 단말(pos, 가맹점 쪽 · 따로 설치). 10 문서 6-5
    flavorDimensions += "role"
    productFlavors {
        create("customer") {
            dimension = "role"
            isDefault = true
        }
        create("pos") {
            dimension = "role"
            applicationIdSuffix = ".pos"
            versionNameSuffix = "-pos"
            buildConfigField("String", "POS_KEY", "\"${localProps.getProperty("yamu.posKey", "")}\"")
        }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = rootProject.file(releaseKey.getValue("storeFile")).also {
                    check(it.isFile) { "yamu.release.storeFile 파일이 없어요" }
                }
                storePassword = releaseKey.getValue("storePassword")
                keyAlias = releaseKey.getValue("keyAlias")
                keyPassword = releaseKey.getValue("keyPassword")
            }
        }
    }

    // 앱 글자는 한국어뿐 — 라이브러리의 다른 언어 번역은 뺀다(기본 values는 남음)
    androidResources {
        localeFilters += "ko"
    }

    buildTypes {
        debug {
            // 로컬 BE(Spring · 8080). 폰 · 에뮬레이터 모두 adb reverse tcp:8080 tcp:8080 뒤 localhost, 에뮬레이터는 10.0.2.2도 됨
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
            // BE API가 아직 없는 기능은 가짜(Fake) 데이터로 화면을 먼저 만든다 — debug에서만. 진짜로 쓸 기능 이름을 쉼표로
            buildConfigField("String", "REAL_API", "\"${debugRealApi.joinToString(",")}\"")
        }
        release {
            // TODO 배포 서버 주소(HTTPS) 확정 후 바꾸기
            buildConfigField("String", "BASE_URL", "\"https://example.invalid/api/\"")   // 경로는 BE와 같은 /api/ — 호스트만 바꾸면 된다
            buildConfigField("String", "REAL_API", "\"${apiFeatures.joinToString(",")}\"")
            // R8: 코드 줄이기 · 이름 바꾸기 + 안 쓰는 리소스 빼기. 앱 규칙은 proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (devSign) signingConfigs.getByName("debug") else signingConfigs.findByName("release")
            if (devSign) versionNameSuffix = "-devsign"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// 모의 POS release는 64비트 폰(arm64-v8a) · 에뮬레이터(x86_64)용만 — ML Kit 네이티브가 ABI마다 3~6MB(17 문서 17-6).
// DSL abiFilters는 맛 · 빌드 종류 단위라 posRelease 하나만 못 골라서 여기서 32비트 두 개를 뺀다(결과는 같음)
androidComponents {
    onVariants(selector().withName("posRelease")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/armeabi-v7a/**", "lib/x86/**")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.viewmodel.compose)

    // 3단계: 네트워크 · 저장
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)

    // 4단계: 지문(BiometricPrompt는 FragmentActivity가 필요)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // 5단계: 1원 문자 자동 입력(SMS User Consent — 사용자가 허용한 문자 1통만, READ_SMS 권한 없음)
    implementation(libs.play.services.auth.api.phone)

    // 6단계: 결제 코드(QR · Code128) 그리기 — 카메라 없이 그림만
    implementation(libs.zxing.core)

    // 6단계: 모의 POS 단말만 — 카메라로 고객 코드 찍기(CameraX + ML Kit)
    "posImplementation"(libs.androidx.camera.camera2)
    "posImplementation"(libs.androidx.camera.lifecycle)
    "posImplementation"(libs.androidx.camera.view)
    "posImplementation"(libs.androidx.camera.mlkit.vision)
    "posImplementation"(libs.mlkit.barcode.scanning)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
