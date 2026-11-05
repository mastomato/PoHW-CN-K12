package android.content;

public interface SharedPreferences {
    String getString(String k, String def);
    int getInt(String k, int def);
    boolean getBoolean(String k, boolean def);
    long getLong(String k, long def);
    Editor edit();

    interface Editor {
        Editor putString(String k, String v);
        Editor putInt(String k, int v);
        Editor putBoolean(String k, boolean v);
        Editor putLong(String k, long v);
        Editor remove(String k);
        void apply();
        boolean commit();
    }
}
