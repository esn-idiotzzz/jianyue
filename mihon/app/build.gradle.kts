import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuildConfigField
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import mihon.gradle.Config
import mihon.gradle.getCurrentTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.getLatestCommitTime
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apollo)
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

val keystorePropertiesFile = layout.settingsDirectory.file("keystore.properties").asFile

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "com.joe.jmlite"

        ndk {
            abiFilters += "arm64-v8a"
        }

        versionCode = 35
        versionName = "5.2.1"

        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (System.getenv("MIHON_GITHUB_RELEASE").toBoolean()) {
        signingConfigs {
            named("debug") {
                storeFile = file(System.getenv("storeFile"))
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }

        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val debug = getByName("debug") {
            // JM keeps its package for upgrades.
            isPseudoLocalesEnabled = true
        }
        val release = getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = debug.signingConfig

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("nightly") {
            initWith(release)

            applicationIdSuffix = ".debug"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }

        if (Config.includeTelemetry) {
            configureEach {
                configure<CrashlyticsExtension> {
                    mappingFileUploadEnabled = Config.uploadCrashlyticsMapping
                }
            }
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../../app/src/main/java")
            res.srcDir("../../app/src/main/res")
            assets.srcDir("../../app/src/main/assets")
        }
        getByName("nightly").res.directories.add("src/debug/res")
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    packaging {
        jniLibs {
            // Store native libraries compressed inside the APK. This keeps every bundled
            // reader/decoder/WebGPU feature while making the downloadable APK noticeably smaller.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("commons-io:commons-io:2.20.0")
    implementation("commons-codec:commons-codec:1.19.0")
    implementation("org.apache.commons:commons-lang3:3.18.0")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("org.slf4j:slf4j-api:2.0.17")
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    implementation(projects.icons.customMaterialSymbols)
    implementation(projects.icons.materialSymbols)
    implementation(projects.icons.simpleIcons)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.metro)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.telemetry)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)
    implementation(libs.androidx.navigationEvent)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.kotlinx.datetime)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)
    implementation(libs.metro.runtime)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    implementation(libs.webgpuviewer)

    // UI libraries
    implementation(libs.material)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // String similarity
    implementation(libs.stringSimilarity)

    // GraphQL generation
    implementation(libs.apollo)
    implementation(libs.apollo.adapters)

    // Tests
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.bundles.test)
    testImplementation("org.json:json:20240303")
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

apollo {
    val schemaBasePath = "src/main/graphql/mihon/graphql"
    service("anilist") {
        packageName.set("mihon.graphql.anilist")
        val srcDir = "$schemaBasePath/anilist"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://graphql.anilist.co")
            schemaFile.set(file("$srcDir/anilist.graphqls"))
        }

        // ISO 3166-1 alpha-2 country code
        mapScalarToKotlinString("CountryCode")
    }

    service("kitsu") {
        packageName.set("mihon.graphql.kitsu")
        val srcDir = "$schemaBasePath/kitsu"
        srcDir(file(srcDir))
        android.defaultConfig.versionCode

        introspection {
            // Kitsu doesn't like requests without a UA
            headers.put(
                "User-Agent",
                "Mihon v${android.defaultConfig.versionName} (${android.defaultConfig.applicationId})",
            )
            endpointUrl.set("https://kitsu.app/api/graphql")
            schemaFile.set(file("$srcDir/kitsu.graphqls"))
        }

        // A date, expressed as an ISO8601 string
        mapScalarToKotlinString("Date")
        // An ISO 8601-encoded datetime
        mapScalar("ISO8601DateTime", "kotlin.time.Instant", "com.apollographql.adapter.core.KotlinInstantAdapter")
        // A loose key-value map in GraphQL
        mapScalar(
            "Map",
            "kotlin.collections.Map<String, Any?>",
            "mihon.graphql.kitsu.adapter.KitsuMapAdapter",
        )
    }

    service("shikimori") {
        packageName.set("mihon.graphql.shikimori")
        val srcDir = "$schemaBasePath/shikimori"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://shikimori.io/api/graphql")
            schemaFile.set(file("$srcDir/shikimori.graphqls"))
        }

        // An ISO 8601-encoded date
        mapScalarToKotlinString("ISO8601Date")
    }

    service("suwayomi") {
        packageName.set("mihon.graphql.suwayomi")
        val srcDir = "$schemaBasePath/suwayomi"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("http://localhost:4567/api/graphql")
            schemaFile.set(file("$srcDir/suwayomi.graphqls"))
        }
    }
}

val latestCommitCount = getLatestCommitCount()
val latestCommitSha = getLatestCommitSha()
val latestCommitTime = getLatestCommitTime()
val currentTime = getCurrentTime()

fun ApplicationVariant.buildConfigField(type: String, name: String, value: Provider<String>) {
    buildConfigFields?.put(name, value.map { BuildConfigField(type, it, null) })
}

androidComponents {
    onVariants { variant ->
        val isUnstableBuild = variant.buildType == "debug" || variant.buildType == "nightly"
        val buildTime = if (isUnstableBuild) currentTime else latestCommitTime

        variant.buildConfigField("String", "COMMIT_COUNT", latestCommitCount.map { "\"$it\"" })
        variant.buildConfigField("String", "COMMIT_SHA", latestCommitSha.map { "\"$it\"" })
        variant.buildConfigField("String", "BUILD_TIME", buildTime.map { "\"$it\"" })

        if (isUnstableBuild) {
            variant.outputs.forEach { output ->
                val versionName = output.versionName.get()
                output.versionName.set(latestCommitCount.map { "$versionName-$it" })
            }
        }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
