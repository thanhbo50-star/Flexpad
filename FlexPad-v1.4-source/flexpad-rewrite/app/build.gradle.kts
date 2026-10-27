plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nguyen.flexpad"
    compileSdk = 35
    // Ghim build-tools 35.0.0: AGP 8.7 mac dinh tim ban 34.0.0, ma workflow chi cai 35.0.0.
    buildToolsVersion = "35.0.0"
    // Ghim đúng bản NDK mà workflow GitHub Actions cài, để biên dịch JNI của backend uinput (B2).
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.nguyen.flexpad"
        minSdk = 30
        targetSdk = 35
        // versionCode 8 / 1.4: viết lại FoldObserver — đường Activity (v0.1 đã kiểm chứng)
        // + WindowContext cho service, hết đói dữ liệu combine, lỗi tracker hiện rõ,
        // suy ra TABLETOP từ góc bản lề, vùng pad không còn phụ thuộc tracker mù.
        versionCode = 8
        versionName = "1.4"
        // Find N5 là arm64; vẫn build thêm armeabi-v7a cho máy khác nếu cần.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // androidx.window: nhận diện tư thế gập (MỐC 4 dùng; khai báo sẵn từ MỐC 1).
    implementation(libs.androidx.window)
    // Shizuku: UserService chạy dưới uid shell/root để mở /dev/uhid, /dev/uinput.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // DataStore: lưu cấu hình (theme, deadzone, haptic, tuỳ chọn hiển thị pad).
    implementation(libs.androidx.datastore.preferences)
}
