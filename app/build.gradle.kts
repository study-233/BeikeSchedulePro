import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.caeamer.beikeschedule"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 独立发行版，与原版共存；数据独立保存，首次安装需重新导入。
        applicationId = "io.github.study233.beikeschedulepro"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = signingProps.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // 开启 R8 压缩/优化（依赖库均自带 consumer keep 规则：Compose/Room/kotlinx.serialization）
            isMinifyEnabled = true
            // 资源裁剪：与 R8 配套，去掉未被引用的资源（APK 体积的主因仍是
            // material-icons-extended，这一步只收窄剩余部分）
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // 单测跑在 JVM 上，android.jar 里的方法默认是抛异常的 stub。
            // 让未实现的系统方法返回默认值，使 android.util.Log 之类的调用
            // 不会把纯逻辑单测打挂（解析器另有真 org.json 实现，见 dependencies）
            isReturnDefaultValues = true
        }
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    // Room schema 导出：迁移可入库版本化，配合 MigrationTestHelper 可测
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.webkit)
    implementation(libs.zxing.core)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    // org.json 在 JVM 单测里是 Android SDK 的 stub（方法返回 null/抛异常），
    // 解析器单测必须用真实实现替换它
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
