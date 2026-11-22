# yt-dlp wrapper and its JSON mapping
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
