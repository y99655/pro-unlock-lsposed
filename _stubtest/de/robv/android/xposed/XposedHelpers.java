package de.robv.android.xposed;
import java.lang.reflect.Method;
public class XposedHelpers {
    public static Object getObjectField(Object o, String name) { return null; }
    public static void setObjectField(Object o, String name, Object v) {}
    public static XC_MethodHook.Unhook findAndHookMethod(String cn, ClassLoader cl, String mn, Object... params) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c, String mn, Object... params) { return null; }
    public static Object callMethod(Object o, String name, Object... args) { return null; }
    public static Method findMethod(Class<?> c, String name) { return null; }
    public static Method findMethodExact(Class<?> c, String name, Class<?>... types) { return null; }
    public static Method findMethodExact(String cn, ClassLoader cl, String name, Class<?>... types) { return null; }
    public static Object callStaticMethod(Class<?> c, String name, Object... args) { return null; }
}
