# Redline R8 rules. Keep capability report model names stable for JSON export.
-keepclassmembers class com.redline.camera.capability.** { *; }
# B20 JNI: the native symbols are bound by class and method name.
-keepclasseswithmembernames,includedescriptorclasses class com.redline.camera.nativebridge.RedlineNative {
    native <methods>;
}
# B30 LiteRT: JNI-bound runtime classes.
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**
# B30.1: ai-delivery is excluded; LiteRT's unused AI-pack provider references it.
-dontwarn com.google.android.play.**
