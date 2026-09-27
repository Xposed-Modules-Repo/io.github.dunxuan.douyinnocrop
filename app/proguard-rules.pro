# 若将来开启混淆（isMinifyEnabled = true），以下规则必需：

# 让 R8 在重命名入口类后同步改写 META-INF/xposed/java_init.list 中的类名
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 入口类保留构造器（框架反射实例化）
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 模块自身成员保守保留（反射调用点少，可按需放开）
-keepclassmembers class io.github.dunxuan.douyinnocrop.** { *; }
