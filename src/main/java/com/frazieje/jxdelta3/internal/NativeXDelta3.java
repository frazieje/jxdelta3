package com.frazieje.jxdelta3.internal;

import com.frazieje.jxdelta3.XDelta3Exception;

/**
 * Native method declarations bound to the {@code jxdelta3_jni.c} JNI wrapper.
 *
 * <p>Not part of the public API — use {@link com.frazieje.jxdelta3.XDelta3}. Methods are
 * {@code public} only because Java has no cross-package "sub-package" access; the {@code internal}
 * package name signals they are not for direct use.
 *
 * <p>Window sizes are {@code long} to match the 64-bit {@code usize_t}/{@code xoff_t} defaults and
 * avoid silent truncation. A value of {@code 0} means "use the xdelta3 default".
 */
public final class NativeXDelta3 {

    private NativeXDelta3() {
    }

    static {
        // Produces libxdelta3.so from the :xdelta3 cpp-library subproject.
        System.loadLibrary("xdelta3");
    }

    /** Forces the static initializer (library load) to run. */
    public static void ensureLoaded() {
    }

    public static native byte[] encodeMemory(
            byte[] source, byte[] target, int flags, long winSize, long srcWinSize)
            throws XDelta3Exception;

    public static native byte[] decodeMemory(
            byte[] source, byte[] delta, int flags, long srcWinSize)
            throws XDelta3Exception;

    public static native void encodeFd(
            int sourceFd, long sourceSize, int targetFd, int deltaFd,
            int flags, long winSize, long srcWinSize)
            throws XDelta3Exception;

    public static native void decodeFd(
            int sourceFd, long sourceSize, int deltaFd, int targetFd, int flags)
            throws XDelta3Exception;
}
