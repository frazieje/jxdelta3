package com.frazieje.jxdelta3;

import java.io.IOException;

/**
 * Thrown when an xdelta3 native operation fails.
 *
 * <p>The {@link #errorCode()} is the raw integer returned by the native layer. It is either an
 * {@code xd3_rvalues} code (large negative sentinel, e.g. {@code XD3_INVALID_INPUT == -17712}) or a
 * positive {@code errno} value such as {@code ENOSPC}/{@code ENOMEM} surfaced by the in-memory API.
 * The message is resolved on the C side (see the JNI wrapper): {@code xd3_strerror} for xd3 codes,
 * {@code strerror} for positive errno, with the stream's own message preferred when available.
 */
public class XDelta3Exception extends IOException {

    private static final long serialVersionUID = 1L;

    private final int errorCode;

    /** Invoked from native code. */
    public XDelta3Exception(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** The raw native return code (an {@code xd3_rvalues} code or a positive {@code errno}). */
    public int errorCode() {
        return errorCode;
    }
}
