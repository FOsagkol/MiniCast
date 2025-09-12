# ProGuard rules for MiniCast

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Keep Cast framework classes (important!)
-keep class com.google.android.gms.cast.** { *; }
-keep class androidx.mediarouter.** { *; }

# Add your custom rules here if needed
