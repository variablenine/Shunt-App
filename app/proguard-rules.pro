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

# androidx.security-crypto stores the Tessie token encrypted, via Google Tink.
# Tink is annotated with errorprone annotations that aren't on the runtime
# classpath (they're compile-only), which fails the R8 missing-class check
# outright — the release build does not even produce an APK without this.
-dontwarn com.google.errorprone.annotations.**
# Tink's KeysDownloader (remote keysets over google-api-client / joda-time) is
# not on our path at all — we only use local EncryptedSharedPreferences.
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
# Tink loads key managers reflectively by type URL, so stripping or renaming
# them would break reading the stored credentials on release builds only.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# OkHttp / Okio ship their own consumer rules; MapLibre ships rules in its AAR.
# Silence benign warnings from optional transitive references.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
