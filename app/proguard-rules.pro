-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn io.nekohasekai.**
-keep class io.nekohasekai.** { *; }
