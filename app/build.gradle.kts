plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.comexamplet9keyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.comexamplet9keyboard"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    // words_tr.bin, AssetManager.openFd() ile bellek eşlemesi yapılıyor; openFd yalnızca
    // SIKIŞTIRILMAMIŞ asset'lerde çalışır. Aksi halde sözlük sessizce .txt yedeğine düşer.
    androidResources {
        noCompress += "bin"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}