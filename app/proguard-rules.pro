# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, RuntimeVisibleAnnotations, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class id.mitoon.app.**$$serializer { *; }
-keepclassmembers class id.mitoon.app.** { *** Companion; }
-keepclasseswithmembers class id.mitoon.app.** { kotlinx.serialization.KSerializer serializer(...); }
# Retrofit: interface API dibaca lewat refleksi
-keep interface id.mitoon.app.Api { *; }
-keepattributes Signature
-keep class kotlin.coroutines.Continuation
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-keep class id.mitoon.app.MiToonMessaging { *; }
