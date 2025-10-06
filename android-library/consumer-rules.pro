# MockkHttp ProGuard Rules
# These rules ensure the interceptor is completely removed from release builds

# WARNING: If MockkHttp classes are found in release builds, ProGuard will remove them
# This is a security measure to prevent accidental inclusion in production

# Remove all MockkHttp classes in release builds
-assumenosideeffects class com.sergiy.dev.mockkhttp.interceptor.MockkHttpInterceptor {
    *;
}

# If the interceptor somehow made it to release, strip all its methods
-assumenosideeffects class com.sergiy.dev.mockkhttp.interceptor.** {
    *;
}

# Don't warn about missing MockkHttp classes (they should be missing in release!)
-dontwarn com.sergiy.dev.mockkhttp.interceptor.**
