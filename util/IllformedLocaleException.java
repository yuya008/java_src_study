

package java.util;

public class IllformedLocaleException extends RuntimeException {

    private static final long serialVersionUID = -5245986824925681401L;

    private int _errIdx = -1;

    public IllformedLocaleException() {
        super();
    }

    public IllformedLocaleException(String message) {
        super(message);
    }

    public IllformedLocaleException(String message, int errorIndex) {
        super(message + ((errorIndex < 0) ? "" : " [at index " + errorIndex + "]"));
        _errIdx = errorIndex;
    }

    public int getErrorIndex() {
        return _errIdx;
    }
}
