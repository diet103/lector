# Project-specific R8 keep rules.
#
# Deliberately short. The libraries that need rules ship their own consumer rules (Media3, OkHttp,
# ML Kit, Compose, coroutines), and the kotlinx.serialization DTO rules PLAN.md anticipated are not
# needed — that library was never adopted, so every response here is parsed by hand with org.json
# and nothing in this app is constructed reflectively.

# jsoup compiles against nullability annotations that are not on the runtime classpath. They are
# only ever read by tooling, so the references are genuinely absent rather than a packaging mistake.
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**

# Keep our own crash frames readable: a stack trace pasted into an issue is the only bug report a
# sideloaded APK is going to produce, and there is no symbol-upload step to un-obfuscate it later.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
