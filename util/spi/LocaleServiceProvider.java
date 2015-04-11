
package java.util.spi;

import java.util.Locale;

public abstract class LocaleServiceProvider {

    protected LocaleServiceProvider() {
    }

    public abstract Locale[] getAvailableLocales();

    public boolean isSupportedLocale(Locale locale) {
        locale = locale.stripExtensions(); // throws NPE if locale == null
        for (Locale available : getAvailableLocales()) {
            if (locale.equals(available.stripExtensions())) {
                return true;
}
        }
        return false;
    }
}
