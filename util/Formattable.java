
package java.util;

import java.io.IOException;

public interface Formattable {

    void formatTo(Formatter formatter, int flags, int width, int precision);
}
