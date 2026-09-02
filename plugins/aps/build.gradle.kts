plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    id("kotlin-android")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.aps"
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:objects"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    // UnscentedKalmanFilterPlugin.smoothForDisplay() -- see OpenAPSAutoISFPlugin.kt ukfRawBgl persistence
    implementation(project(":plugins:smoothing"))

    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:tests"))

    api(libs.androidx.appcompat)
    api(libs.androidx.swiperefreshlayout)
    api(libs.androidx.gridlayout)
    api(kotlin("reflect"))

    // APS (it should be androidTestImplementation but it doesn't work)
    api(libs.org.mozilla.rhino)

    //Logger
    api(libs.org.slf4j.api)

    ksp(libs.com.google.dagger.android.processor)

    // List2 "Install newest AAPS333 APK" -- Shizuku shell pm install -r, no system Install sheet.
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)
}

// Read-only JVM process used by tools/oref-digital-twin. It executes the production
// DetermineBasalAutoISF source from the unit-test classpath and is never packaged in an APK.
tasks.register<JavaExec>("runAutoIsfReplayAdapter") {
    group = "verification"
    description = "Run the source-pinned AutoISF replay JSON adapter on stdin/stdout"
    // Fixed 2026-08-26, in two stages:
    // 1) This project builds multiple flavors (full/aapsclient/aapsclient2), so Kotlin/AGP only
    //    ever produce flavor-qualified output -- there is no plain "debug"/"debugUnitTest"
    //    directory, configuration, or task, only "fullDebug"/"fullDebugUnitTest" etc. (confirmed
    //    by listing build/tmp/kotlin-classes/, which contains fullDebug, fullDebugUnitTest,
    //    fullRelease, aapsclientRelease, aapsclient2Release -- never bare "debug"). The original
    //    un-qualified names caused "Configuration with name 'debugUnitTestRuntimeClasspath' not
    //    found", and separately "compileDebugUnitTestKotlin" only ever appeared to work when typed
    //    directly on the Gradle CLI (which does fuzzy/abbreviated task-name matching) -- dependsOn()
    //    requires the literal real task path.
    // 2) Flavor-qualifying the names above ("fullDebugUnitTestRuntimeClasspath") still failed with
    //    an unresolvable-variant error: a transitive project dependency (:shared:tests) publishes
    //    several equally-valid AGP artifact-type variants (android-aar-metadata, android-classes-jar,
    //    ...) for the same build type/flavor, and JavaExec.classpath() fed a raw Configuration
    //    object doesn't supply the extra disambiguating attributes (artifactType/LibraryElements)
    //    that AGP's own real testFullDebugUnitTest task supplies automatically when it resolves
    //    that same configuration internally. Reusing that task's own already-resolved classpath
    //    sidesteps the problem entirely -- and Gradle infers the correct compile/dependency
    //    ordering automatically from the FileCollection's own producing tasks, so no separate
    //    dependsOn is needed here any more.
    classpath = tasks.named<Test>("testFullDebugUnitTest").get().classpath
    mainClass.set("app.aaps.plugins.aps.openAPSAutoISF.AutoIsfReplayAdapterMain")
    standardInput = System.`in`
}
