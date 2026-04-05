# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sting.openclaw.**$$serializer { *; }
-keepclassmembers class com.sting.openclaw.** {
    *** Companion;
}
-keepclasseswithmembers class com.sting.openclaw.** {
    kotlinx.serialization.KSerializer serializer(...);
}
