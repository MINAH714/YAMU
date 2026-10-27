// 플러그인 버전은 gradle/libs.versions.toml 한 곳에서
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false   // AGP 9는 Kotlin을 내장 — 여기선 쓸 Kotlin 버전만 고정
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
