# --- Vosk + JNA (native speech recognition): accessed reflectively from native code ---
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# --- kotlinx.serialization: keep generated serializers for our @Serializable models ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class com.shrey.buddy.** {
    *** Companion;
}
-keepclasseswithmembers class com.shrey.buddy.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.shrey.buddy.**$$serializer { *; }

# --- Ktor / supabase-kt optional platform bits ---
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room: entities and DAOs are generated; keep entity field names for schema stability ---
-keep class com.shrey.buddy.data.db.** { *; }
