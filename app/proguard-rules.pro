# ISSUE-116 — R8 keep rules for the `perf` build type.
# Obfuscation is disabled so traces stay readable while the parse pipeline is being tuned.
-dontobfuscate

# Room generates *_Impl classes reflectively resolved by name at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# WorkManager instantiates workers by class name from the enqueued request.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Entities crossing the Supabase JSON boundary are matched by field name.
-keep class com.voicetoinvoice.app.data.local.entity.** { *; }
-keepclassmembers class com.voicetoinvoice.app.data.local.entity.** { <fields>; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
