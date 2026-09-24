import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

@Suppress("ConstPropertyName")
object Versions {

    // On change edit aaps-ci.yml
    // UKF3426 is this port. The -dev keeps it a development build. A name with no hyphen is treated as a release.
    const val appVersion = "UKF3426-dev"
    const val versionCode = 1500

    const val compileSdk = 37
    const val minSdk = 31
    const val targetSdk = 35
    const val wearMinSdk = 30
    const val wearTargetSdk = 30

    val javaVersion = JavaVersion.VERSION_21
    val jvmTarget = JvmTarget.JVM_21
    const val jacoco = "0.8.11"
}
