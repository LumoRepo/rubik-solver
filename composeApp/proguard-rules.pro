# Add project specific ProGuard rules here.

# Keep min2phase solver (JNI / reflection access)
-keep class cs.min2phase.** { *; }

# Keep Rubik model classes used via reflection or serialization
-keep class com.xmelon.rubik_solver.model.** { *; }
