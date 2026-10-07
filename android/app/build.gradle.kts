plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The app ships with the firmware, plugin and Python package under one version.
val gadgetVersion = Regex("""(?m)^__version__ = "([^"]+)"""")
    .find(rootProject.file("../python/hermes_gadget/__init__.py").readText())!!.groupValues[1]

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
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
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
    outputs.dir(hostJni)
    commandLine("cmake", "--build", hostJni.get().asFile.path, "--parallel", "--target", "hgjni")
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostJni)
    systemProperty("java.library.path", hostJni.get().asFile.path)
}
