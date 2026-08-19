-keep class com.alite.ssh.SshNative { *; }
-keep class com.alite.ssh.SshNative$Listener { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
