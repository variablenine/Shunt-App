# kotlinx.serialization — keep generated serializers for every @Serializable
# type (models live in :solver and :tesla). Canonical rules from the
# kotlinx.serialization README.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# java.lang.System.Logger (used by :solver for fallback logging) exists on
# Android API 26+; minSdk is 29, so it's always present at runtime.
-dontwarn java.lang.System$Logger
-dontwarn java.lang.System$Logger$Level

# OkHttp / Okio ship their own consumer rules; MapLibre ships rules in its AAR.
# Silence benign warnings from optional transitive references.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
