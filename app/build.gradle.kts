import org.gradle.internal.os.OperatingSystem
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Go / gomobile
//
// The sync engine is SushitrainCore (Go, MPL-2.0), pinned as a submodule under
// external/sushitrain and bound to Java by gomobile. gomobile locates `gobind`
// only via $PATH, so both binaries are built into toolchains/bin first.
// ---------------------------------------------------------------------------

/** ABIs we ship. x86 is omitted: no realistic 32-bit x86 Android targets remain. */
val abiFilters = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

fun goArchFor(abi: String): String = when (abi) {
    "arm64-v8a" -> "arm64"
    "armeabi-v7a" -> "arm"
    "x86_64" -> "amd64"
    "x86" -> "386"
    else -> error("unsupported ABI: $abi")
}

/**
 * ABIs to build, narrowable with `-Pabi=arm64-v8a` (comma-separated). Narrowing
 * also shrinks the gomobile bind, which dominates build time.
 */
val selectedAbis: List<String> =
    (findProperty("abi") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.onEach { require(it in abiFilters) { "unknown ABI '$it'; expected one of $abiFilters" } }
        ?: abiFilters

val exeExt = if (OperatingSystem.current().isWindows) ".exe" else ""
val toolchainsDir = rootProject.layout.projectDirectory.dir("toolchains")
val goRootDir = toolchainsDir.dir("go")
val goToolBinDir = toolchainsDir.dir("bin")
val coreSrcDir = rootProject.layout.projectDirectory.dir("core")
val sushitrainSrcDir =
    rootProject.layout.projectDirectory.dir("external/sushitrain/SushitrainCore/src")

/**
 * Base version code. Each ABI gets its own derived code because `splits.abi`
 * does nothing on AGP 9, so per-ABI APKs come from separate builds and each
 * needs a distinct, increasing code for update checks to work.
 */
val baseVersionCode = 1

/**
 * Version name. CI exports VERSION_NAME from the git tag (minus its `v`) so a
 * release is named after the tag it was built from rather than a constant that
 * has to be remembered and bumped by hand; `-PversionName=` does the same
 * locally. The fallback is what an untagged build reports.
 */
val versionNameValue: String =
    (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }
        ?: "0.1.0"

/** Stable ordinal per ABI; must never be reordered once released. */
fun abiOrdinal(abi: String): Int = when (abi) {
    "armeabi-v7a" -> 1
    "arm64-v8a" -> 3
    "x86_64" -> 4
    else -> error("no version code ordinal for $abi")
}

val minSdkVersion = 26
val ndkVersionUsed = "30.0.16248370"

/**
 * Version reported by the engine. Syncthing reads this from a linker-set
 * variable; without it the app reports "unknown-dev".
 */
val engineVersion: String = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    workingDir = rootProject.file("external/sushitrain")
}.standardOutput.asText.map { it.trim() }.orElse("unknown").get()

// AGP 9 exposes the SDK/NDK locations as providers rather than as properties on
// the `android` extension.
val sdkDirProvider = androidComponents.sdkComponents.sdkDirectory
val ndkDirProvider = androidComponents.sdkComponents.ndkDirectory

interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

/** Absolute path to `go`, preferring the project-local toolchain. */
fun goExecutable(): File {
    val local = goRootDir.file("bin/go$exeExt").asFile
    return if (local.exists()) local else File("go$exeExt")
}

fun ExecSpec.applyGoEnvironment() {
    val goRoot = goRootDir.asFile
    if (goRoot.exists()) {
        environment("GOROOT", goRoot.absolutePath)
    }
    environment("GOPATH", toolchainsDir.dir("gopath").asFile.absolutePath)
    environment("ANDROID_HOME", sdkDirProvider.get().asFile.absolutePath)
    environment("ANDROID_NDK_HOME", ndkDirProvider.get().asFile.absolutePath)
    // gomobile shells out to `gobind` and to `javac`, both of which it can only
    // find on PATH. The JDK is taken from the JVM running Gradle so the build
    // does not depend on the daemon's inherited PATH.
    environment(
        "PATH",
        listOf(
            goToolBinDir.asFile.absolutePath,
            goRootDir.dir("bin").asFile.absolutePath,
            File(System.getProperty("java.home"), "bin").absolutePath,
            System.getenv("PATH") ?: "",
        ).joinToString(File.pathSeparator),
    )
}

/** Builds the `gobind` and `gomobile` binaries at the version pinned in core/go.mod. */
val gomobileTools = tasks.register("gomobileTools") {
    description = "Builds the gomobile and gobind executables into toolchains/bin"

    inputs.files(coreSrcDir.file("go.mod"), coreSrcDir.file("go.sum"))
    outputs.files(
        goToolBinDir.file("gobind$exeExt"),
        goToolBinDir.file("gomobile$exeExt"),
    )

    val injected = project.objects.newInstance<InjectedExecOps>()
    doLast {
        injected.execOps.exec {
            executable(goExecutable())
            args("install", "golang.org/x/mobile/cmd/gobind", "golang.org/x/mobile/cmd/gomobile")
            environment("GOBIN", goToolBinDir.asFile.absolutePath)
            applyGoEnvironment()
            workingDir(coreSrcDir.asFile)
        }
    }
}

val gomobileAar = layout.buildDirectory.file("gomobile/dinghy-core.aar")

val gomobileBind = tasks.register("gomobileBind") {
    description = "Binds SushitrainCore to an Android AAR via gomobile"
    dependsOn(gomobileTools)

    inputs.files(coreSrcDir.asFileTree.matching { include("**/*.go", "go.mod", "go.sum") })
    inputs.files(sushitrainSrcDir.asFileTree.matching { include("**/*.go") })
    inputs.property("abis", selectedAbis)
    inputs.property("minSdk", minSdkVersion)
    inputs.property("engineVersion", engineVersion)
    outputs.file(gomobileAar)

    val injected = project.objects.newInstance<InjectedExecOps>()
    doLast {
        val out = gomobileAar.get().asFile
        out.parentFile.mkdirs()
        injected.execOps.exec {
            executable(goToolBinDir.file("gomobile$exeExt").asFile)
            args(
                "bind",
                "-target=${selectedAbis.joinToString(",") { "android/${goArchFor(it)}" }}",
                "-androidapi=$minSdkVersion",
                "-javapkg=ch.steigis.dinghy.binding",
                // Excludes Syncthing's embedded web UI assets; we ship our own UI.
                "-tags=noassets",
                // Syncthing reaches Android's network interfaces through
                // github.com/wlynxg/anet, which //go:linkname's into net's
                // unexported zoneCache. Go >=1.23 rejects that at link time
                // unless the check is disabled. anet v0.0.5 is the latest
                // release and still needs this.
                // -X sets Syncthing's linker-provided version string.
                "-ldflags=-checklinkname=0 " +
                    "-X github.com/syncthing/syncthing/lib/build.Version=$engineVersion",
                "-o=${out.absolutePath}",
                "t-shaped.nl/sushitrain/v2/src",
                "steigis.ch/dinghy/core",
            )
            applyGoEnvironment()
            workingDir(coreSrcDir.asFile)
        }
    }
}

/**
 * Release signing. Credentials come from keystore.properties (never committed)
 * or, in CI, from the environment. An unsigned release build is still useful
 * for checking that R8 is happy, so a missing keystore is not an error.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

val keystorePath = signingValue("storeFile", "DINGHY_KEYSTORE_FILE")
val hasSigningConfig = keystorePath != null && File(keystorePath).exists()

android {
    namespace = "ch.steigis.dinghy"
    compileSdk = 36
    ndkVersion = ndkVersionUsed

    defaultConfig {
        applicationId = "ch.steigis.dinghy"
        // 26 is the floor for StorageManager.openProxyFileDescriptor, which the
        // DocumentsProvider needs to serve files that are not downloaded yet.
        minSdk = minSdkVersion
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = versionNameValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The compiled Go engine is ~25 MiB per ABI, so an all-ABI APK is
            // ~83 MiB. `splits.abi` is silently a no-op in AGP 9.4 (no warning,
            // output-metadata.json still reports a SINGLE element), so per-ABI
            // APKs are produced by building once per ABI instead:
            //     ./gradlew :app:assembleDebug -Pabi=arm64-v8a
            // Releases build each ABI in turn. Per-ABI versionCode offsets still
            // need the Variant/Artifacts API; see M6.
            abiFilters += selectedAbis
        }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = File(keystorePath!!)
                storePassword = signingValue("storePassword", "DINGHY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DINGHY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DINGHY_KEY_PASSWORD")
                // v1 is pointless at minSdk 26 and slows installs; v3 carries
                // the rotation proof that lets the signing key be changed later
                // without orphaning existing installs.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// AGP 9 removed the legacy applicationVariants output API, so per-ABI version
// codes are set through the Variant API instead.
androidComponents {
    onVariants { variant ->
        val abi = selectedAbis.singleOrNull() ?: return@onVariants
        variant.outputs.forEach { output ->
            output.versionCode.set(baseVersionCode * 10 + abiOrdinal(abi))
            output.versionName.set(versionNameValue)
        }
    }
}

tasks.named("preBuild") { dependsOn(gomobileBind) }

/**
 * Copies the built APK to dist/ under a name that identifies the ABI and
 * version, which is what gets attached to a release.
 */
val dist = tasks.register<Copy>("dist") {
    description = "Stages the release APK into dist/ with a release-ready name"
    // Without this the copy can run before the APK exists and silently stage a
    // stale one from a previous build.
    dependsOn("assembleRelease")
    val abi = selectedAbis.singleOrNull() ?: "universal"
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "dinghy-$versionNameValue-$abi.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("dist"))
}

dependencies {
    implementation(files(gomobileAar))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
