plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("kotlin-parcelize")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.configuration"
}


dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:objects"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation(project(":shared:impl"))
    // AutoIsfHistoryExporter, for kicking off an AIV export alongside the log export -- see
    // MaintenancePlugin.sendLogs(). No dependency in the other direction (ui doesn't depend on this
    // module), so this doesn't create a cycle.
    implementation(project(":ui"))

    testImplementation(project(":shared:tests"))
    testImplementation(project(":implementation"))

    //WorkManager
    api(libs.androidx.work.runtime)
    // Maintenance
    api(libs.androidx.gridlayout)
    
    // HTTP client for Google Drive API
    implementation(libs.com.squareup.okhttp3.okhttp)

    // Chrome Custom Tabs for OAuth flow
    api(libs.androidx.browser)

    ksp(libs.com.google.dagger.compiler)
    ksp(libs.com.google.dagger.android.processor)
}