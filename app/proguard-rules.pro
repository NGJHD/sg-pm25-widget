# Nothing needs a -keep: activities and receivers are referenced from the
# manifest (aapt keeps them), and WorkManager's consumer rules keep every
# Worker's (Context, WorkerParameters) constructor.

# Strip verbose logging from release builds - the fetch path logs the
# device's coordinates. Errors (Log.e) are kept.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
