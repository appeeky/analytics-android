# Appeeky Analytics consumer rules, applied to the app that depends on the SDK.
#
# The SDK uses no reflection, no serialization library and no JNI: its JSON is
# written and parsed by hand, so R8 may shrink and obfuscate it freely.
# The Play Install Referrer library ships its own consumer rules.
#
# Keep the public entry point's name so stack traces and Logcat stay readable.
-keepnames class com.appeeky.analytics.Appeeky
