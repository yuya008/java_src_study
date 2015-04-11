
package java.util.spi;

import java.util.Calendar;
import java.util.Locale;

public abstract class CalendarDataProvider extends LocaleServiceProvider {

    protected CalendarDataProvider() {
    }

    public abstract int getFirstDayOfWeek(Locale locale);

    public abstract int getMinimalDaysInFirstWeek(Locale locale);
}
