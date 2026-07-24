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

# BRouter loads its path model (btools.router.KinematicModel / StdModel) and
# other engine classes by name via reflection (RoutingContext: Class.forName),
# so R8 must not strip or rename the vendored engine — otherwise release builds
# fail at route time with "Cannot create path-model: ClassNotFoundException".
-keep class btools.** { *; }

# OkHttp / Okio ship their own consumer rules; MapLibre ships rules in its AAR.
# Silence benign warnings from optional transitive references.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
