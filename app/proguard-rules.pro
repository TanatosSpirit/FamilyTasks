-keep class fi.iki.elonen.** { *; }
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# kotlinx.serialization: keep generated serializers for our @Serializable DTOs
# (see https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro)
-keepclassmembers class com.familytasks.app.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.familytasks.app.**
-keepclassmembers class com.familytasks.app.<1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.familytasks.app.** {
    *** Companion;
}
-keep,includedescriptorclasses class com.familytasks.app.**$$serializer { *; }
