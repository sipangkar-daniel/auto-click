# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles settings in build.gradle.kts.

# Keep Room compiler generated classes
-keep class * extends androidx.room.RoomDatabase

# Keep Dagger Hilt classes
-keep class * extends class * implements dagger.hilt.internal.GeneratedComponent
-keep @dagger.hilt.android.lifecycle.HiltViewModel class *

# Keep OpenCV native methods and classes
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { *; }
-dontwarn org.opencv.**
