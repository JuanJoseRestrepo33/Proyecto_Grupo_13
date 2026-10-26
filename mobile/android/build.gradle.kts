plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt.yml"))
        source.setFrom(file("src"), file("build.gradle.kts"))
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(true)
        }
    }
}

dependencies {
    kover(project(":app"))
    kover(project(":core:network"))
    kover(project(":core:designsystem"))
    kover(project(":feature:onboarding"))
    kover(project(":feature:consentimiento"))
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.R",
                    "*.R$*",
                    "*ComposableSingletons*",
                    "co.solventa.mobile.MainActivity",
                    "co.solventa.mobile.MainActivity$*",
                )
            }
        }
        total {
            xml { xmlFile.set(layout.buildDirectory.file("reports/kover/report.xml")) }
            verify {
                rule { minBound(70) }
            }
        }
    }
}
