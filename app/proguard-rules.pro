# NewPipeExtractor uses reflection and a JavaScript engine (Rhino) to decode stream URLs.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.jspecify.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keeps stack traces readable; this app is open source so obfuscation adds nothing.
-dontobfuscate
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.protobuf.**
