-keep class com.alite.ssh.SshNative { *; }
-keep class com.alite.ssh.SshNative$Listener { *; }
-keep class com.alite.ssh.PortMapping { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
