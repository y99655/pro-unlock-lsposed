package de.robv.android.xposed;
public class XC_MethodHook {
    public static class MethodHookParam {
        public Object thisObject; public Object[] args; private Object result;
        public Object getResult() { return result; }
        public void setResult(Object r) { result = r; }
        public void setResult(boolean r) { result = Boolean.valueOf(r); }
        public void setResult(int r) { result = Integer.valueOf(r); }
        public Object getRawResult() { return result; }
        public Object getResultOrThrowable() { return result; }
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public class Unhook { public void unhook() {} }
}
