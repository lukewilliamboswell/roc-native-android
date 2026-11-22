# The release build exists to make the shell's classes.dex, which the
# android.roc tool ships and packages with each app. Its manifest is the
# tool's, not Gradle's, and the host calls into Kotlin through JNI, so every
# class of the shell is kept; R8 still shrinks the Kotlin standard library.
-keep class dev.roc.android.** { *; }
