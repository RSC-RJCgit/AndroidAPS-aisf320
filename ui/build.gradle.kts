plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.ui"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:graph"))
    implementation(project(":core:graphview"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:libraries"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    // UnscentedKalmanFilterPlugin, for AutoIsfHistoryExporter's own UKF3 (LibreSpecial-from-UKF1)
    // recomputation from raw readings -- see computeUkf3RawMgdl()'s doc comment. plugins:smoothing
    // depends only on core:data/core:interfaces/core:ui, none of which depend back on this module, so
    // this isn't circular.
    implementation(project(":plugins:smoothing"))

    testImplementation(project(":shared:tests"))

    api(libs.androidx.core)
    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)
}