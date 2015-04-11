
package java.util.logging;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;


public class Level implements java.io.Serializable {
    private static final String defaultBundle = "sun.util.logging.resources.logging";

    private final String name;

    private final int value;

    private final String resourceBundleName;

    private transient String localizedLevelName;
    private transient Locale cachedLocale;

    public static final Level OFF = new Level("OFF",Integer.MAX_VALUE, defaultBundle);

    public static final Level SEVERE = new Level("SEVERE",1000, defaultBundle);

    public static final Level WARNING = new Level("WARNING", 900, defaultBundle);

    public static final Level INFO = new Level("INFO", 800, defaultBundle);

    public static final Level CONFIG = new Level("CONFIG", 700, defaultBundle);

    public static final Level FINE = new Level("FINE", 500, defaultBundle);

    public static final Level FINER = new Level("FINER", 400, defaultBundle);

    public static final Level FINEST = new Level("FINEST", 300, defaultBundle);

    public static final Level ALL = new Level("ALL", Integer.MIN_VALUE, defaultBundle);

    protected Level(String name, int value) {
        this(name, value, null);
    }

    protected Level(String name, int value, String resourceBundleName) {
        this(name, value, resourceBundleName, true);
    }

    private Level(String name, int value, String resourceBundleName, boolean visible) {
        if (name == null) {
            throw new NullPointerException();
        }
        this.name = name;
        this.value = value;
        this.resourceBundleName = resourceBundleName;
        this.localizedLevelName = resourceBundleName == null ? name : null;
        this.cachedLocale = null;
        if (visible) {
            KnownLevel.add(this);
        }
    }

    public String getResourceBundleName() {
        return resourceBundleName;
    }

    public String getName() {
        return name;
    }

    public String getLocalizedName() {
        return getLocalizedLevelName();
    }

    final String getLevelName() {
        return this.name;
    }

    private String computeLocalizedLevelName(Locale newLocale) {
        ResourceBundle rb = ResourceBundle.getBundle(resourceBundleName, newLocale);
        final String localizedName = rb.getString(name);

        final boolean isDefaultBundle = defaultBundle.equals(resourceBundleName);
        if (!isDefaultBundle) return localizedName;

        final Locale rbLocale = rb.getLocale();
        final Locale locale =
                Locale.ROOT.equals(rbLocale)
                || name.equals(localizedName.toUpperCase(Locale.ROOT))
                ? Locale.ROOT : rbLocale;

        return Locale.ROOT.equals(locale) ? name : localizedName.toUpperCase(locale);
    }

    final String getCachedLocalizedLevelName() {

        if (localizedLevelName != null) {
            if (cachedLocale != null) {
                if (cachedLocale.equals(Locale.getDefault())) {
                    return localizedLevelName;
                }
            }
        }

        if (resourceBundleName == null) {
            return name;
        }

        return null;
    }

    final synchronized String getLocalizedLevelName() {

        final String cachedLocalizedName = getCachedLocalizedLevelName();
        if (cachedLocalizedName != null) {
            return cachedLocalizedName;
        }

        final Locale newLocale = Locale.getDefault();
        try {
            localizedLevelName = computeLocalizedLevelName(newLocale);
        } catch (Exception ex) {
            localizedLevelName = name;
        }
        cachedLocale = newLocale;
        return localizedLevelName;
    }

    static Level findLevel(String name) {
        if (name == null) {
            throw new NullPointerException();
        }

        KnownLevel level;

        level = KnownLevel.findByName(name);
        if (level != null) {
            return level.mirroredLevel;
        }

        try {
            int x = Integer.parseInt(name);
            level = KnownLevel.findByValue(x);
            if (level == null) {
                Level levelObject = new Level(name, x);
                level = KnownLevel.findByValue(x);
            }
            return level.mirroredLevel;
        } catch (NumberFormatException ex) {
        }

        level = KnownLevel.findByLocalizedLevelName(name);
        if (level != null) {
            return level.mirroredLevel;
        }

        return null;
    }

    @Override
    public final String toString() {
        return name;
    }

    public final int intValue() {
        return value;
    }

    private static final long serialVersionUID = -8176160795706313070L;

    private Object readResolve() {
        KnownLevel o = KnownLevel.matches(this);
        if (o != null) {
            return o.levelObject;
        }

        Level level = new Level(this.name, this.value, this.resourceBundleName);
        return level;
    }

    public static synchronized Level parse(String name) throws IllegalArgumentException {
        name.length();

        KnownLevel level;

        level = KnownLevel.findByName(name);
        if (level != null) {
            return level.levelObject;
        }

        try {
            int x = Integer.parseInt(name);
            level = KnownLevel.findByValue(x);
            if (level == null) {
                Level levelObject = new Level(name, x);
                level = KnownLevel.findByValue(x);
            }
            return level.levelObject;
        } catch (NumberFormatException ex) {
        }

        level = KnownLevel.findByLocalizedLevelName(name);
        if (level != null) {
            return level.levelObject;
        }

        throw new IllegalArgumentException("Bad level \"" + name + "\"");
    }

    @Override
    public boolean equals(Object ox) {
        try {
            Level lx = (Level)ox;
            return (lx.value == this.value);
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return this.value;
    }

    static final class KnownLevel {
        private static Map<String, List<KnownLevel>> nameToLevels = new HashMap<>();
        private static Map<Integer, List<KnownLevel>> intToLevels = new HashMap<>();
        final Level levelObject;     // instance of Level class or Level subclass
        final Level mirroredLevel;   // mirror of the custom Level
        KnownLevel(Level l) {
            this.levelObject = l;
            if (l.getClass() == Level.class) {
                this.mirroredLevel = l;
            } else {
                this.mirroredLevel = new Level(l.name, l.value, l.resourceBundleName, false);
            }
        }

        static synchronized void add(Level l) {
            KnownLevel o = new KnownLevel(l);
            List<KnownLevel> list = nameToLevels.get(l.name);
            if (list == null) {
                list = new ArrayList<>();
                nameToLevels.put(l.name, list);
            }
            list.add(o);

            list = intToLevels.get(l.value);
            if (list == null) {
                list = new ArrayList<>();
                intToLevels.put(l.value, list);
            }
            list.add(o);
        }

        static synchronized KnownLevel findByName(String name) {
            List<KnownLevel> list = nameToLevels.get(name);
            if (list != null) {
                return list.get(0);
            }
            return null;
        }

        static synchronized KnownLevel findByValue(int value) {
            List<KnownLevel> list = intToLevels.get(value);
            if (list != null) {
                return list.get(0);
            }
            return null;
        }

        static synchronized KnownLevel findByLocalizedLevelName(String name) {
            for (List<KnownLevel> levels : nameToLevels.values()) {
                for (KnownLevel l : levels) {
                    String lname = l.levelObject.getLocalizedLevelName();
                    if (name.equals(lname)) {
                        return l;
                    }
                }
            }
            return null;
        }

        static synchronized KnownLevel matches(Level l) {
            List<KnownLevel> list = nameToLevels.get(l.name);
            if (list != null) {
                for (KnownLevel level : list) {
                    Level other = level.mirroredLevel;
                    if (l.value == other.value &&
                           (l.resourceBundleName == other.resourceBundleName ||
                               (l.resourceBundleName != null &&
                                l.resourceBundleName.equals(other.resourceBundleName)))) {
                        return level;
                    }
                }
            }
            return null;
        }
    }

}
