
package java.util.prefs;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.AccessController;
import java.security.Permission;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.ServiceConfigurationError;

import java.lang.RuntimePermission;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Float;
import java.lang.Double;

public abstract class Preferences {

    private static final PreferencesFactory factory = factory();

    private static PreferencesFactory factory() {
        String factoryName = AccessController.doPrivileged(
            new PrivilegedAction<String>() {
                public String run() {
                    return System.getProperty(
                        "java.util.prefs.PreferencesFactory");}});
        if (factoryName != null) {
            try {
                return (PreferencesFactory)
                    Class.forName(factoryName, false,
                                  ClassLoader.getSystemClassLoader())
                    .newInstance();
            } catch (Exception ex) {
                try {
                    SecurityManager sm = System.getSecurityManager();
                    if (sm != null) {
                        sm.checkPermission(new java.security.AllPermission());
                    }
                    return (PreferencesFactory)
                        Class.forName(factoryName, false,
                                      Thread.currentThread()
                                      .getContextClassLoader())
                        .newInstance();
                } catch (Exception e) {
                    throw new InternalError(
                        "Can't instantiate Preferences factory "
                        + factoryName, e);
                }
            }
        }

        return AccessController.doPrivileged(
            new PrivilegedAction<PreferencesFactory>() {
                public PreferencesFactory run() {
                    return factory1();}});
    }

    private static PreferencesFactory factory1() {
        Iterator<PreferencesFactory> itr = ServiceLoader
            .load(PreferencesFactory.class, ClassLoader.getSystemClassLoader())
            .iterator();

        while (itr.hasNext()) {
            try {
                return itr.next();
            } catch (ServiceConfigurationError sce) {
                if (sce.getCause() instanceof SecurityException) {
                    continue;
                }
                throw sce;
            }
        }

        String osName = System.getProperty("os.name");
        String platformFactory;
        if (osName.startsWith("Windows")) {
            platformFactory = "java.util.prefs.WindowsPreferencesFactory";
        } else if (osName.contains("OS X")) {
            platformFactory = "java.util.prefs.MacOSXPreferencesFactory";
        } else {
            platformFactory = "java.util.prefs.FileSystemPreferencesFactory";
        }
        try {
            return (PreferencesFactory)
                Class.forName(platformFactory, false,
                              Preferences.class.getClassLoader()).newInstance();
        } catch (Exception e) {
            throw new InternalError(
                "Can't instantiate platform default Preferences factory "
                + platformFactory, e);
        }
    }

    public static final int MAX_KEY_LENGTH = 80;

    public static final int MAX_VALUE_LENGTH = 8*1024;

    public static final int MAX_NAME_LENGTH = 80;

    public static Preferences userNodeForPackage(Class<?> c) {
        return userRoot().node(nodeName(c));
    }

    public static Preferences systemNodeForPackage(Class<?> c) {
        return systemRoot().node(nodeName(c));
    }

    private static String nodeName(Class<?> c) {
        if (c.isArray())
            throw new IllegalArgumentException(
                "Arrays have no associated preferences node.");
        String className = c.getName();
        int pkgEndIndex = className.lastIndexOf('.');
        if (pkgEndIndex < 0)
            return "/<unnamed>";
        String packageName = className.substring(0, pkgEndIndex);
        return "/" + packageName.replace('.', '/');
    }

    private static Permission prefsPerm = new RuntimePermission("preferences");

    public static Preferences userRoot() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(prefsPerm);

        return factory.userRoot();
    }

    public static Preferences systemRoot() {
        SecurityManager security = System.getSecurityManager();
        if (security != null)
            security.checkPermission(prefsPerm);

        return factory.systemRoot();
    }

    protected Preferences() {
    }

    public abstract void put(String key, String value);

    public abstract String get(String key, String def);

    public abstract void remove(String key);

    public abstract void clear() throws BackingStoreException;

    public abstract void putInt(String key, int value);

    public abstract int getInt(String key, int def);

    public abstract void putLong(String key, long value);

    public abstract long getLong(String key, long def);

    public abstract void putBoolean(String key, boolean value);

    public abstract boolean getBoolean(String key, boolean def);

    public abstract void putFloat(String key, float value);

    public abstract float getFloat(String key, float def);

    public abstract void putDouble(String key, double value);

    public abstract double getDouble(String key, double def);

    public abstract void putByteArray(String key, byte[] value);

    public abstract byte[] getByteArray(String key, byte[] def);

    public abstract String[] keys() throws BackingStoreException;

    public abstract String[] childrenNames() throws BackingStoreException;

    public abstract Preferences parent();

    public abstract Preferences node(String pathName);

    public abstract boolean nodeExists(String pathName)
        throws BackingStoreException;

    public abstract void removeNode() throws BackingStoreException;

    public abstract String name();

    public abstract String absolutePath();

    public abstract boolean isUserNode();

    public abstract String toString();

    public abstract void flush() throws BackingStoreException;

    public abstract void sync() throws BackingStoreException;

    public abstract void addPreferenceChangeListener(
        PreferenceChangeListener pcl);

    public abstract void removePreferenceChangeListener(
        PreferenceChangeListener pcl);

    public abstract void addNodeChangeListener(NodeChangeListener ncl);

    public abstract void removeNodeChangeListener(NodeChangeListener ncl);

    public abstract void exportNode(OutputStream os)
        throws IOException, BackingStoreException;

    public abstract void exportSubtree(OutputStream os)
        throws IOException, BackingStoreException;

    public static void importPreferences(InputStream is)
        throws IOException, InvalidPreferencesFormatException
    {
        XmlSupport.importPreferences(is);
    }
}
