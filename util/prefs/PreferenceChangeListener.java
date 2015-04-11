
package java.util.prefs;

@FunctionalInterface
public interface PreferenceChangeListener extends java.util.EventListener {
    void preferenceChange(PreferenceChangeEvent evt);
}
