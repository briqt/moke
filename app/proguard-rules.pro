# v0.1 release 未开启混淆。以下规则为将来开启 R8 时预留。

# sshj / BouncyCastle：保留，避免反射相关问题
-keep class net.schmizz.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**

# 终端渲染内核（vendored, Apache-2.0）
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
