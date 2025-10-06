# MockkHttp Library ProGuard Rules

# Keep the interceptor class in DEBUG builds only
# In release builds, R8 will strip this entirely

-keep class com.sergiy.dev.mockkhttp.interceptor.MockkHttpInterceptor {
    # Only keep constructor for debug builds
    public <init>(...);
}

# Keep model classes for GSON serialization (debug only)
-keep class com.sergiy.dev.mockkhttp.interceptor.FlowData { *; }
-keep class com.sergiy.dev.mockkhttp.interceptor.RequestData { *; }
-keep class com.sergiy.dev.mockkhttp.interceptor.ResponseData { *; }
-keep class com.sergiy.dev.mockkhttp.interceptor.ModifiedResponseData { *; }

# Warn if MockkHttp is being compiled into a release build
# This should NEVER happen!
-whyareyoukeeping class com.sergiy.dev.mockkhttp.interceptor.**
