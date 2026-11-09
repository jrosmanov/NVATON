# No shrinking in the MVP (isMinifyEnabled = false).
# If you turn it on: Vosk (wake word) calls its native library through JNA, which needs these classes intact.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
