package android.app;
public interface SharedPreferences {
    boolean getBoolean(String k, boolean d); int getInt(String k, int d); long getLong(String k, long d);
    float getFloat(String k, float d); String getString(String k, String d);
    java.util.Set<String> getStringSet(String k, java.util.Set<String> d);
}
