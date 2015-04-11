
package java.util.jar;

public
class JarException extends java.util.zip.ZipException {
    private static final long serialVersionUID = 7159778400963954473L;

    public JarException() {
    }

    public JarException(String s) {
        super(s);
    }
}
