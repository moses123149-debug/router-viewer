plugins {
    id("com.android.application") version "9.3.2"
}

android {
    namespace = "com.routerviewer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.routerviewer"
        // 28: cell bandwidth, LTE signal values and requestDismissKeyguard need API 26-28
        minSdk = 28
        // <= 25: manifest receiver still gets ACTION_POWER_CONNECTED (auto-start); <= 27 also needed for hotspot hidden APIs
        targetSdk = 25
        versionCode = 1
        versionName = "1.0"
    }
}
