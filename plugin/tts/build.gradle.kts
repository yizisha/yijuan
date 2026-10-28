// Historical module path retained; speech now ships as a normal library inside the reader.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.flowreader.plugin.tts"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":plugin-api"))
}
