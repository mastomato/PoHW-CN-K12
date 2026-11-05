package android.content;

import java.util.HashMap;
import java.util.Map;

public class Context {
    public static final int MODE_PRIVATE = 0;
    private static final Map<String, MemPrefs> DISK = new HashMap<String, MemPrefs>();
    private static final Map<String, MemPrefs> LIVE = new HashMap<String, MemPrefs>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        MemPrefs p = LIVE.get(name);
        if (p == null) {
            p = DISK.get(name);
            if (p == null) { p = new MemPrefs(); DISK.put(name, p); }
            LIVE.put(name, p);
        }
        return p;
    }

    public static void restartProcess() {
        for (MemPrefs p : LIVE.values()) p.reloadFromDisk();
        LIVE.clear();
    }

    public static void resetAll() {
        DISK.clear();
        LIVE.clear();
    }
}
