import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cn.luoluna.sweven.ai"
    // compileSdk 36：quickjs-kt（eval_javascript 工具）要求 minCompileSdk=36；targetSdk 维持 35 不变
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.luoluna.flux"
        minSdk = 26
        targetSdk = 35
            versionCode = 50
            versionName = "0.25.2"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

// quickjs-kt 以更高版本 Kotlin 构建（元数据 2.3/2.4），本模块 Kotlin 2.0.21 默认拒绝；
// 该库只使用稳定的协程/集合 API，放行元数据校验（新增依赖若报同类错误请先评估再放行）。
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.jmdns)
    // 本地工具：JS 执行沙箱（eval_javascript 工具）。
    // quickjs-kt 以 Kotlin 2.3+ 构建（元数据版本高于本项目的 2.0.21，编译期由
    // -Xskip-metadata-version-check 放行）；其依赖的 kotlinx-coroutines 版本较新
    // （Mutex.unlock 新签名），必须让其升到该版本，否则运行期 NoSuchMethodError；
    // 仅排除传递引入的 kotlin-stdlib，统一用项目自身版本。
    implementation(libs.quickjs) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }

    // 单元测试：JVM 侧跑 JS 沙箱（quickjs 桌面原生库）与消息数组组装
    testImplementation(libs.junit)
}

// 本地 JVM 测试需要桌面原生库而不是 Android AAR（与 rikkahub 同做法）
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}
