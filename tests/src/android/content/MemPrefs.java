package android.content;

import java.util.HashMap;
import java.util.Map;

public class MemPrefs implements SharedPreferences {

    private Map<String, Object> mem = new HashMap<String, Object>();
    private final Map<String, Object> disk = new HashMap<String, Object>();
    private boolean failCommit = false;

    public void setFailCommit(boolean v) { failCommit = v; }

    void reloadFromDisk() { mem = new HashMap<String, Object>(disk); }

    private Object get(String k, Object def) {
        Object v = mem.get(k);
        return v == null ? def : v;
    }

    public String getString(String k, String def) { return (String) get(k, def); }
    public int getInt(String k, int def) { return (Integer) get(k, def); }
    public boolean getBoolean(String k, boolean def) { return (Boolean) get(k, def); }
    public long getLong(String k, long def) { return (Long) get(k, def); }

    public Editor edit() { return new EditorImpl(); }

    private static final Object REMOVED = new Object();

    private class EditorImpl implements Editor {
        final Map<String, Object> pending = new HashMap<String, Object>();
        public Editor putString(String k, String v) { pending.put(k, v); return this; }
        public Editor putInt(String k, int v) { pending.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
        public Editor putLong(String k, long v) { pending.put(k, v); return this; }
        public Editor remove(String k) { pending.put(k, REMOVED); return this; }
        public void apply() { flush(mem); pending.clear(); }
        public boolean commit() {
            if (failCommit) { pending.clear(); return false; }
            flush(mem);
            flush(disk);
            pending.clear();
            return true;
        }
        private void flush(Map<String, Object> target) {
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                if (e.getValue() == REMOVED) target.remove(e.getKey());
                else target.put(e.getKey(), e.getValue());
            }
        }
    }
}
