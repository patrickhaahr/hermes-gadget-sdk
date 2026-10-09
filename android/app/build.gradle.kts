import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app ships with the firmware, plugin and Python package under one version.
val gadgetVersion = Regex("""(?m)^__version__ = "([^"]+)"""")
    .find(rootProject.file("../python/hermes_gadget/__init__.py").readText())!!.groupValues[1]

// Filled by downloadWakeModels (below).
val wakeModels = layout.buildDirectory.dir("wake-models")

android {
    namespace = "io.github.adolanium.hermesgadget"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.adolanium.hermesgadget"
        minSdk = 30
        targetSdk = 35
        versionName = gadgetVersion
        versionCode = gadgetVersion.split(".").take(3).fold(0) { code, part -> code * 100 + part.takeWhile(Char::isDigit).toInt() }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main").assets.srcDir(wakeModels)
        // The same recorded audio drives the host tests (by path) and the phone tests (as assets).
        getByName("androidTest").assets.srcDir("src/wakeFixtures")
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    // Native WebRTC for Live calls: Google's libwebrtc, packaged by Stream (see THIRD_PARTY_NOTICES.md).
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in host tests; this is the reference implementation.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// -- Wake word models ---------------------------------------------------------------
// Downloaded at build time and checked against pinned hashes, not committed: the
// openWakeWord feature models are CC BY-NC-SA 4.0 (see THIRD_PARTY_NOTICES.md).
// The APK carries them under assets/wake/.

/** Downloads [url] to [target] unless it is already there, and checks its SHA-256. */
fun fetchPinned(url: String, sha256: String, target: File) {
    fun digest(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (target.exists() && digest(target) == sha256) return
    target.parentFile.mkdirs()
    val partial = File(target.path + ".part")
    URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
    val actual = digest(partial)
    if (actual != sha256) {
        partial.delete()
        throw GradleException("$url has SHA-256 $actual, expected $sha256")
    }
    partial.renameTo(target)
}

val wakeModelPins = mapOf(
    "melspectrogram.tflite" to ("https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.tflite" to
        "96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8"),
    "embedding_model.tflite" to ("https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.tflite" to
        "c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8"),
    "hey_hermes.tflite" to ("https://raw.githubusercontent.com/NousResearch/hermes-agent/818c13be1dc4fd28987e1e881a9408224afd4535/tools/wakewords/hey_hermes.tflite" to
        "744fdd81fedc28ff1b9268baee20876cdf9bf1bc6f06bc2ed5c9cbebc1a44b1d"),
)
val downloadWakeModels by tasks.registering {
    description = "Downloads the pinned wake word models into the app's assets."
    inputs.property("pins", wakeModelPins.toString())
    outputs.dir(wakeModels)
    doLast {
        for ((name, pin) in wakeModelPins) fetchPinned(pin.first, pin.second, wakeModels.get().file("wake/$name").asFile)
    }
}
tasks.named("preBuild") { dependsOn(downloadWakeModels) }

// The host tests run the models with the TensorFlow Lite C library from the
// pyopen-wakeword wheel, the runtime Hermes Agent's desktop wake word uses.
// It exists for Linux x86-64 only; elsewhere those tests are skipped.
val hostTflite = layout.buildDirectory.dir("host-tflite")
val hostCanRunTflite = System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64")
val downloadHostTflite by tasks.registering {
    description = "Downloads the TensorFlow Lite C library the host tests run the wake models with."
    onlyIf { hostCanRunTflite }
    outputs.dir(hostTflite)
    doLast {
        val wheel = hostTflite.get().file("pyopen_wakeword-1.1.0-py3-none-manylinux_2_35_x86_64.whl").asFile
        fetchPinned("https://files.pythonhosted.org/packages/f0/d7/228edc51d9b35b9d46cf685509e79144262ffd8756a49243a27da3302059/pyopen_wakeword-1.1.0-py3-none-manylinux_2_35_x86_64.whl",
            "754347a59de2b3d378a0cbc404a8b41164036e74fa1c185f88056974e4bfb6b4", wheel)
        ZipInputStream(wheel.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "pyopen_wakeword/lib/libtensorflowlite_c.so" }
            hostTflite.get().file("libtensorflowlite_c.so").asFile.outputStream().use { zip.copyTo(it) }
        }
    }
}

// The JVM unit tests load hgjni built for this machine (src/test/cpp), so they
// exercise the JNI bridge and the device core without a phone.
val hostJni = layout.buildDirectory.dir("host-jni")
val configureHostJni by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp"), fileTree("src/test/cpp"))
    commandLine("cmake", "-S", file("src/test/cpp").path, "-B", hostJni.get().asFile.path,
        "-DCMAKE_BUILD_TYPE=Release", "-DJNI_INCLUDE=${System.getProperty("java.home")}/include")
}
val buildHostJni by tasks.registering(Exec::class) {
    dependsOn(configureHostJni)
    inputs.files(fileTree("src/main/cpp"), fileTree("src/test/cpp"), fileTree("../../firmware/core"), fileTree("../../firmware/sim"))
    // CMake maintains this directory across configure/build. Declaring it as this
    // task's output lets Gradle delete the just-configured cache on a fresh checkout.
    commandLine("cmake", "--build", hostJni.get().asFile.path, "--parallel", "--target", "hgjni", "hgtflite")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostJni, downloadWakeModels, downloadHostTflite)
    systemProperty("java.library.path", hostJni.get().asFile.path)
    systemProperty("hg.wake.models", wakeModels.get().dir("wake").asFile.path)
    systemProperty("hg.wake.fixtures", file("src/wakeFixtures").path)
    systemProperty("hg.tflite.library", hostTflite.get().file("libtensorflowlite_c.so").asFile.path)
}
