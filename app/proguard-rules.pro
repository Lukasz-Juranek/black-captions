# Vosk calls into native code through JNA, which finds classes and fields by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# sherpa-onnx's native code reads its config objects' fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
