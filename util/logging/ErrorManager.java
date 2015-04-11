

package java.util.logging;


public class ErrorManager {
   private boolean reported = false;


    public final static int GENERIC_FAILURE = 0;
    public final static int WRITE_FAILURE = 1;
    public final static int FLUSH_FAILURE = 2;
    public final static int CLOSE_FAILURE = 3;
    public final static int OPEN_FAILURE = 4;
    public final static int FORMAT_FAILURE = 5;

    public synchronized void error(String msg, Exception ex, int code) {
        if (reported) {
            return;
        }
        reported = true;
        String text = "java.util.logging.ErrorManager: " + code;
        if (msg != null) {
            text = text + ": " + msg;
        }
        System.err.println(text);
        if (ex != null) {
            ex.printStackTrace();
        }
    }
}
