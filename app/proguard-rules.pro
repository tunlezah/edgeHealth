# Kinetiq proguard rules

# kotlinx.serialization — keep serializers for the exercise database + export models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class au.mark.kinetiq.**$$serializer { *; }
-keepclassmembers class au.mark.kinetiq.** {
    *** Companion;
}
-keepclasseswithmembers class au.mark.kinetiq.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Health Connect client
-keep class androidx.health.connect.client.** { *; }
