# Release (R8) keep rules for StreamPros. Library rules (Compose, AndroidX, DataStore) come with
# their dependencies; these cover what the app itself relies on.

# Install results arrive at this receiver from PackageInstaller through an explicit PendingIntent.
# The manifest already keeps it; this states it outright.
-keep class com.example.hub.InstallReceiver { <init>(); }

# DataStore Preferences stores data with a bundled protobuf-lite; its message fields are read
# reflectively.
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Catalog, trending and hub-version JSON are parsed by hand with org.json (part of Android), so no
# model classes need to keep their names.

# Keep line numbers so crash reports from release builds can be traced.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
