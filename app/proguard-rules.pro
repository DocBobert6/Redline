# Redline R8 rules. Keep capability report model names stable for JSON export.
-keepclassmembers class com.redline.camera.capability.** { *; }
# B20 JNI: the native symbols are bound by class and method name.
-keepclasseswithmembernames,includedescriptorclasses class com.redline.camera.nativebridge.RedlineNative {
    native <methods>;
}
