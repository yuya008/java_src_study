
package java.util.logging;


public interface LoggingMXBean {

    public java.util.List<String> getLoggerNames();

    public String getLoggerLevel(String loggerName);

    public void setLoggerLevel(String loggerName, String levelName);

    public String getParentLoggerName(String loggerName);
}
