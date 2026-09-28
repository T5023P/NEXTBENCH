-keepattributes *Annotation*

# Firestore creates model objects and assigns their fields through reflection.
-keepclassmembers class com.nextbench.data.model.** {
    <fields>;
    <init>(...);
}
