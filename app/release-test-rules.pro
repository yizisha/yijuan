# error-prone's annotation metadata references a javac-only enum through Espresso/Guava.
# This is only the instrumentation APK; no application/JNI keep rules are changed.
-dontwarn javax.lang.model.element.Modifier
-keep class com.flowreader.app.experience.ReleaseExperienceTest { *; }
