# The gomobile AAR ships its own consumer rules keeping `go.**` and the
# generated `dev.sidecar.binding.**` classes, which R8 applies automatically.
#
# What those rules do not cover is our own Kotlin classes that implement the
# binding's callback interfaces (ClientDelegate, DownloadDelegate, and the rest).
# Go invokes those by interface dispatch through JNI, so R8 sees no reference to
# them and would strip or rename their methods.
-keep class * implements go.Seq$Proxy { *; }
