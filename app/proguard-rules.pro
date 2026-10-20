-keepattributes *Annotation*,InnerClasses,Signature
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keepclasseswithmembers class com.agentforge.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}
