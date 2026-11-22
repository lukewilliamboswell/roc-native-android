import java.util.Properties

plugins {
    id("com.android.application")
}

// ---------------------------------------------------------------------------
// Roc apps: Gradle runs `roc build --target=arm64musl` for every app under
// ../../examples and packages each `lib<name>.so`, so `./gradlew installDebug`
// (or Run in Android Studio) is the whole build. Each build is an ordinary
// task with inputs and outputs, so an unchanged app is not rebuilt.
//
// The compiler is `roc.exe` in local.properties, else $ROC, else `roc` on
// PATH. Building from this repository's source also needs the host archives
// (`zig build` in ../../host, with `zig.exe` / $ZIG / `zig`); a
// published platform bundle carries them instead.

val repo: File = rootDir.parentFile
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun tool(prop: String, env: String, default: String): String =
    localProps.getProperty(prop) ?: System.getenv(env) ?: default
val rocExe = tool("roc.exe", "ROC", "roc")
val zigExe = tool("zig.exe", "ZIG", "zig")

val rocJniLibs = layout.buildDirectory.dir("roc/jniLibs")
val platformDir = File(repo, "platform")
val hostArchive = File(platformDir, "targets/arm64musl/libhost.a")

val rocHost = tasks.register<Exec>("rocHost") {
    description = "Builds the Zig JNI host archives (platform/targets/*/libhost.a)."
    inputs.files(fileTree(File(repo, "host")) { include("*.zig") })
    outputs.file(hostArchive)
    outputs.file(File(platformDir, "targets/arm64mac/libhost.a"))
    workingDir = File(repo, "host")
    commandLine(zigExe, "build", "--prefix", File(platformDir, "targets").path, "--cache-dir", File(repo, "build/zig-cache").path)
}

// `-Proc.examples=counter,todos` builds only those; `-Proc.examples=` none
// (the release's shell dex needs no app).
val chosen = providers.gradleProperty("roc.examples").orNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
val examples = File(repo, "examples").listFiles { f -> File(f, "main.roc").isFile }
    .orEmpty().map { it.name }.filter { chosen == null || it in chosen }.sorted()

val rocApps = examples.map { name ->
    tasks.register<Exec>("roc_$name") {
        description = "Builds examples/$name with roc for arm64 Android."
        dependsOn(rocHost)
        inputs.dir(File(repo, "examples/$name"))
        inputs.files(fileTree(platformDir) { include("**/*.roc", "targets/arm64musl/**") })
        val out = rocJniLibs.map { it.file("arm64-v8a/lib$name.so") }
        outputs.file(out)
        workingDir = repo
        commandLine(rocExe, "build", "--target=arm64musl", "--output=${out.get().asFile.path}", "examples/$name/main.roc")
    }
}

android {
    namespace = "dev.roc.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.roc.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        // Roc apps are built for arm64 only.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        // Only for the shell's classes.dex (scripts/release.roc): shrunk,
        // and never signed or installed as it is.
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").jniLibs.srcDir(rocJniLibs.get().asFile)
    // The desktop smoke test (../desktop) lives outside this source set.
}

tasks.named("preBuild") { dependsOn(rocApps) }
