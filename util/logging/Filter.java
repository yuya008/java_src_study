

package java.util.logging;

@FunctionalInterface
public interface Filter {

    public boolean isLoggable(LogRecord record);
}
