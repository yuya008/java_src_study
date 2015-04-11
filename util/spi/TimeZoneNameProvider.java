
package java.util.spi;

import java.util.Locale;

public abstract class TimeZoneNameProvider extends LocaleServiceProvider {

    protected TimeZoneNameProvider() {
    }

    public abstract String getDisplayName(String ID, boolean daylight, int style, Locale locale);

    public String getGenericDisplayName(String ID, int style, Locale locale) {
        return null;
    }
}
