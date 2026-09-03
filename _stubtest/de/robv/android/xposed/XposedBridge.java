package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XposedBridge {
    public static void log(String s) {}
    public static void log(Throwable t) {}
    public static XC_MethodHook.Unhook hookMethod(Member m, XC_MethodHook cb) { return null; }
    public static Object invokeOriginalMethod(Member m, Object thiz, Object[] args) { return null; }
}
