/*
 * Copyright (c) 2008-2009 David Muller <roxon@users.sourceforge.net>.
 * All rights reserved. Use of the code is allowed under the
 * Artistic License 2.0 terms, as specified in the LICENSE file
 * distributed with this code, or available from
 * http://www.opensource.org/licenses/artistic-license-2.0.php
 */
package org.pwsafe.lib.crypto;

import androidx.annotation.NonNull;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 implementation backed by the platform's MessageDigest provider
 * (Conscrypt/BoringSSL on Android), which is hardware accelerated and faster
 * than the former bundled native loop.
 *
 * @author Glen Smith
 * @author Jeff Harris
 */
public class SHA256Pws
{
    /**
     * Hash the incoming bytes iter+1 times
     */
    public static byte[] digestN(byte[] p, int iter)
    {
        MessageDigest digest = getSha();
        byte[] output = digest.digest(p);
        for (int i = 0; i < iter; ++i) {
            output = digest.digest(output);
        }
        return output;
    }

    /**
     * Hash the incoming bytes
     */
    public static byte[] digest(byte[] incoming)
    {
        return getSha().digest(incoming);
    }

    @NonNull
    private static MessageDigest getSha()
    {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
