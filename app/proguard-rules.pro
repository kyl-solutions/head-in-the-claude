# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep SSH library classes
-keep class com.jcraft.jsch.** { *; }

# Keep application classes
-keep class com.kylsolutions.hitc.** { *; }
