import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/**
 * Copies the WebToEpub extension (cloned into ../webtoepub by CI or by you) into the
 * app's assets and wires in the Android shim (chrome.* APIs, native networking) and the
 * driver script that lets the app run WebToEpub headlessly.
 */
abstract class PrepareWebToEpub : DefaultTask() {
    @get:InputDirectory
    abstract val pluginDir: DirectoryProperty

    @get:InputDirectory
    abstract val shimDir: DirectoryProperty

    @get:InputDirectory
    abstract val foliateDir: DirectoryProperty

    @get:InputDirectory
    abstract val readerDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val src = pluginDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dest = File(out, "w2e")
        src.copyRecursively(dest, overwrite = true)
        // Android's asset packager drops folders starting with "_", so expose the
        // WebToEpub translations under a name that survives packaging.
        File(dest, "_locales").takeIf { it.exists() }?.copyRecursively(File(dest, "locales"), overwrite = true)
        shimDir.get().asFile.listFiles()!!.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
        check(File(dest, "@zip.js/zip.js/dist/zip-no-worker.min.js").exists()) {
            "zip.js missing: run 'npm install' in webtoepub/ and copy dependencies (see README)"
        }
        val popup = File(dest, "popup.html")
        var html = popup.readText()
        val headRe = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        check(headRe.containsMatchIn(html)) { "popup.html has no <head>" }
        val head = headRe.find(html)!!
        html = html.substring(0, head.range.last + 1) + "\n<script src=\"android-shim.js\"></script>" +
            html.substring(head.range.last + 1)
        val bodyEnd = html.lastIndexOf("</body>")
        check(bodyEnd > 0) { "popup.html has no </body>" }
        html = html.substring(0, bodyEnd) + "<script src=\"android-driver.js\"></script>\n" + html.substring(bodyEnd)
        popup.writeText(html)

        // Reader: foliate-js (the EPUB engine used by Readest) + the app's reader page.
        val foliate = File(out, "foliate")
        val skip = setOf("tests", "rollup", "node_modules", ".git")
        val root = foliateDir.get().asFile
        val pdfjs = File(root, "vendor/pdfjs")
        foliateDir.get().asFile.walkTopDown()
            .onEnter { it.name !in skip }
            .filter { f ->
                f.isFile && !f.name.endsWith(".map") && (
                    f.extension == "js" || f.extension == "mjs" || f.name == "LICENSE" ||
                        // pdf.js (PDF support): its fonts, CMaps and styles too.
                        f.startsWith(pdfjs)
                    )
            }
            .forEach { f ->
                val rel = f.relativeTo(foliateDir.get().asFile)
                f.copyTo(File(foliate, rel.path), overwrite = true)
            }
        check(File(foliate, "view.js").exists()) { "foliate-js missing: clone it into ./foliate (see README)" }
        readerDir.get().asFile.copyRecursively(File(out, "reader"), overwrite = true)
    }
}

val prepareWebToEpub = tasks.register<PrepareWebToEpub>("prepareWebToEpub") {
    pluginDir.set(rootProject.layout.projectDirectory.dir("webtoepub/plugin"))
    shimDir.set(layout.projectDirectory.dir("src/main/w2e-shim"))
    foliateDir.set(rootProject.layout.projectDirectory.dir("foliate"))
    readerDir.set(layout.projectDirectory.dir("src/main/reader-web"))
}

val keystoreFile = rootProject.file("app/release.jks")

android {
    namespace = "com.epubforge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.epubforge"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = (System.getenv("VERSION_NAME") ?: "0.0.0-dev").removePrefix("v")
        // GitHub repository ("owner/name") whose releases the in-app updater checks. CI sets it to the
        // repository being built; local builds, forks and store builds leave it empty (updater off).
        buildConfigField("String", "UPDATE_REPO", "\"" + (System.getenv("UPDATE_REPO") ?: "") + "\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "epubforge"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareWebToEpub, PrepareWebToEpub::outputDir)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.w3c:epubcheck:5.1.0")
}
