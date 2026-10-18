plugins {
    `java-library`
    alias(libs.plugins.kover)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(17) }
dependencies {
    // api: la app usa los tipos de Retrofit, OkHttp y kotlinx.serialization que expone este módulo.
    api(libs.retrofit)
    implementation(libs.retrofit.serialization)
    api(libs.okhttp)
    api(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.core)
}
