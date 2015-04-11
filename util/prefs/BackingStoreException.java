
package java.util.prefs;

import java.io.NotSerializableException;

public class BackingStoreException extends Exception {
    public BackingStoreException(String s) {
        super(s);
    }

    public BackingStoreException(Throwable cause) {
        super(cause);
    }

    private static final long serialVersionUID = 859796500401108469L;
}
