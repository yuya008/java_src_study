
package java.util.spi;

import java.util.ResourceBundle;

public interface ResourceBundleControlProvider {
    public ResourceBundle.Control getControl(String baseName);
}
