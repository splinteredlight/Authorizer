/**
 * Authorizer
 *
 *  Copyright 2016 by Tjado Mäcke <tjado@maecke.de>
 *  Licensed under GNU General Public License 3.0.
 *
 * @license GPL-3.0 <https://opensource.org/licenses/GPL-3.0>
 */

package net.tjado.authorizer;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

public abstract class UsbHidKbd {

    /**
     * The layout for a language, or en_US if there is none. Layout classes
     * are found by name, so proguard-rules.pro keeps them.
     */
    public static UsbHidKbd forLanguage(OutputInterface.Language lang) {
        try {
            return (UsbHidKbd)Class.forName("net.tjado.authorizer.UsbHidKbd_" + lang)
                                   .getDeclaredConstructor()
                                   .newInstance();
        } catch (Exception e) {
            return new UsbHidKbd_en_US();
        }
    }

    // ToDo: replace byte with ByteArray... eveywhere
    protected Map<String, byte[]> kbdVal= new HashMap<String, byte[]>();

    public byte[] getScancode(String key) {
        byte[] value = (byte[]) kbdVal.get(key);

        if ( value == null ) {
            throw new NoSuchElementException("Scancode for '" + key + "' not found (" + this.kbdVal.size() + ")");
        }

        return value;
    }

}
