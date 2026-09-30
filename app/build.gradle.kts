plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val debugAbi = providers.gradleProperty("android.injected.build.abi").orNull
    ?.split(',')?.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: "arm64-v8a"

val sdkDirPath: String = run {
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        val dir = local.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("=")
            ?.replace("\\\\", "\\")
            ?.trim()
        if (!dir.isNullOrBlank()) return@run dir
    }
    System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "${System.getProperty("user.home")}/AppData/Local/Android/Sdk"
}

val ndkVersionWanted = "26.1.10909125"
val ndkDirPath: String = System.getenv("ANDROID_NDK_HOME")
    ?: "$sdkDirPath/ndk/$ndkVersionWanted"

val jniLibsArm64Path = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile.absolutePath
val fastCamSrcPath = layout.projectDirectory.dir("src/main/cpp/fast_cam").asFile.absolutePath
val hostTag = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
val fastCamOutDirPath = layout.buildDirectory.dir("fastCam").get().asFile.absolutePath

tasks.register("syncLibcxxShared") {
    group = "build"
    description = "Copy NDK libc++_shared.so into jniLibs for FastCam"
    val ndk = ndkDirPath
    val jni = jniLibsArm64Path
    val host = hostTag
    val version = ndkVersionWanted
    val destFile = File(jni, "libc++_shared.so")
    outputs.file(destFile)
    doLast {
        val ndkDir = File(ndk)
        if (!ndkDir.isDirectory) {
            throw GradleException("NDK $version not found at $ndk (set ANDROID_NDK_HOME)")
        }
        val src = File(
            ndkDir,
            "toolchains/llvm/prebuilt/$host/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
        )
        if (!src.isFile) {
            throw GradleException("NDK STL not found: $src")
        }
        File(jni).mkdirs()
        if (!destFile.isFile || destFile.length() != src.length()) {
            src.copyTo(destFile, overwrite = true)
        }
    }
}

tasks.register("buildFastCam") {
    group = "build"
    description = "Build FastCam capture PIE and client .so for arm64"
    dependsOn("syncLibcxxShared")
    val ndk = ndkDirPath
    val jni = jniLibsArm64Path
    val srcRoot = fastCamSrcPath
    val host = hostTag
    val windows = isWindows
    val outDirPath = fastCamOutDirPath
    inputs.dir("$srcRoot/src")
    inputs.dir("$srcRoot/include")
    val clientOut = File(jni, "libfast_cam_client.so")
    val captureLib = File(jni, "libfast_cam_capture.so")
    outputs.file(clientOut)
    outputs.file(captureLib)
    doLast {
        val ndkDir = File(ndk)
        if (!ndkDir.isDirectory) {
            throw GradleException("NDK not found at $ndk")
        }
        val clangName = if (windows) {
            "aarch64-linux-android30-clang.cmd"
        } else {
            "aarch64-linux-android30-clang"
        }
        val clangxxName = if (windows) {
            "aarch64-linux-android30-clang++.cmd"
        } else {
            "aarch64-linux-android30-clang++"
        }
        val clang = File(ndkDir, "toolchains/llvm/prebuilt/$host/bin/$clangName")
        val clangxx = File(ndkDir, "toolchains/llvm/prebuilt/$host/bin/$clangxxName")
        if (!clang.isFile) {
            throw GradleException("NDK clang not found: $clang")
        }
        File(jni).mkdirs()
        val include = File(srcRoot, "include").absolutePath
        val outDir = File(outDirPath)
        outDir.mkdirs()

        fun run(vararg args: String) {
            val result = ProcessBuilder(*args)
                .redirectErrorStream(true)
                .start()
            val output = result.inputStream.bufferedReader().readText()
            val code = result.waitFor()
            if (code != 0) {
                throw GradleException("Command failed ($code): ${args.joinToString(" ")}\n$output")
            }
        }

        val captureOut = File(outDir, "fast_cam_capture")
        run(
            clang.absolutePath,
            "-O3", "-Wall", "-fPIE", "-pie",
            "-I$include", "-D_GNU_SOURCE",
            File(srcRoot, "src/main.c").absolutePath,
            "-o", captureOut.absolutePath,
            "-ldl", "-llog",
            "-Wl,-rpath,/vendor/lib64",
            "-Wl,-rpath,/system/lib64"
        )
        captureOut.copyTo(captureLib, overwrite = true)

        run(
            clangxx.absolutePath,
            "-O3", "-Wall", "-fPIC", "-shared",
            "-I$include", "-D_GNU_SOURCE", "-fvisibility=hidden",
            File(srcRoot, "src/fast_cam_bridge.cpp").absolutePath,
            "-o", clientOut.absolutePath,
            "-Wl,-soname,libfast_cam_client.so"
        )
    }
}

android {
    namespace = "com.strike"
    compileSdk = 36
    ndkVersion = ndkVersionWanted

    defaultConfig {
        applicationId = "com.strike"
        // minSdk 30: the Shark 6 head unit runs Android 11.
        // targetSdk 25: keeps parked surveillance free of O background limits.
        minSdk = 30
        targetSdk = 25
        // CI passes -PversionName/-PversionCode. The local default must stay parseable by the
        // updater and older than any dated CI build.
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 5
        versionName = providers.gradleProperty("versionName").orNull ?: "0.5"

        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }

    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    signingConfigs {
        val storePath = System.getenv("SIGNING_STORE_FILE")
        val storePass = System.getenv("SIGNING_STORE_PASSWORD")
        val alias = System.getenv("SIGNING_KEY_ALIAS")
        val keyPass = System.getenv("SIGNING_KEY_PASSWORD")
        if (listOf(storePath, storePass, alias, keyPass).none { it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(storePath!!)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk { abiFilters += debugAbi }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    androidResources { noCompress += listOf("html", "css", "js") }

    lint { disable += "ExpiredTargetSdkVersion" }

    // android.util.Log throws from the mockable android.jar without this.
    testOptions { unitTests.isReturnDefaultValues = true }

    packaging { resources.excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE-notice.md") }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**")
        // app_process loads native libraries from extracted files.
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so", "**/libfast_cam_client.so")
        }
    }
}

tasks.named("preBuild").configure { dependsOn("buildFastCam") }

tasks.configureEach {
    if (name.startsWith("merge") && name.contains("JniLibFolders")) {
        dependsOn("buildFastCam")
    }
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") ||
        name.startsWith("externalNativeBuild")
    ) {
        dependsOn("buildFastCam")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.dadb)
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
    // Use real org.json in unit tests; android.jar only provides stubs.
    testImplementation(libs.json)
}
