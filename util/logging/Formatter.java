

package java.util.logging;


public abstract class Formatter {

    protected Formatter() {
    }

    public abstract String format(LogRecord record);


    public String getHead(Handler h) {
        return "";
    }

    public String getTail(Handler h) {
        return "";
    }


    public synchronized String formatMessage(LogRecord record) {
        String format = record.getMessage();
        java.util.ResourceBundle catalog = record.getResourceBundle();
        if (catalog != null) {
            try {
                format = catalog.getString(record.getMessage());
            } catch (java.util.MissingResourceException ex) {
                format = record.getMessage();
            }
        }
        try {
            Object parameters[] = record.getParameters();
            if (parameters == null || parameters.length == 0) {
                return format;
            }
            if (format.indexOf("{0") >= 0 || format.indexOf("{1") >=0 ||
                        format.indexOf("{2") >=0|| format.indexOf("{3") >=0) {
                return java.text.MessageFormat.format(format, parameters);
            }
            return format;

        } catch (Exception ex) {
            return format;
        }
    }
}
