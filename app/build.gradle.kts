plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.flashcut.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.flashcut.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.22.0"

        // WorkBuddy 本机服务地址：可通过 gradle 属性覆盖
        //   ./gradlew assembleDebug -PworkbuddyBaseUrl=http://10.66.11.69:8791/v1
        val workbuddyBaseUrl = (project.findProperty("workbuddyBaseUrl") as String?)
            ?: "http://127.0.0.1:8791/v1"
        buildConfigField("String", "WORKBUDDY_BASE_URL", "\"$workbuddyBaseUrl\"")

        // WorkBuddy API Key：不在源码里写死，构建时通过
        //   -PworkbuddyApiKey=xxx  或环境变量 WORKBUDDY_API_KEY 传入
        val workbuddyKey = (project.findProperty("workbuddyApiKey") as String?)
            ?: System.getenv("WORKBUDDY_API_KEY")
            ?: ""
        buildConfigField("String", "WORKBUDDY_API_KEY", "\"$workbuddyKey\"")

        // 容器/本机服务可能的多个地址，供 App 内快速切换
        // 手机侧可达的地址：容器服务通过宿主机 IP 暴露
        val candidates = (project.findProperty("workbuddyCandidates") as String?)
            ?: "http://127.0.0.1:8791/v1,http://10.66.11.69:8791/v1"
        buildConfigField("String", "WORKBUDDY_CANDIDATES", "\"$candidates\"")
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    testImplementation("junit:junit:4.13.2")
}
