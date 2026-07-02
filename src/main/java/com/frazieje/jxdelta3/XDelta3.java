package com.frazieje.jxdelta3;

import com.frazieje.jxdelta3.internal.NativeXDelta3;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Objects;

/**
 * Public entry point for xdelta3 binary delta (VCDIFF / RFC 3284) encode and decode.
 *
 * <p>Two families of operations are provided:
 * <ul>
 *   <li><b>Bytes API</b> ({@code byte[]}) — everything runs in a single native call; best for data
 *       that fits comfortably in heap.</li>
 *   <li><b>File-descriptor API</b> ({@link FileDescriptor}) — drives the streaming state machine in
 *       C, reading/writing in windows without loading whole files into the JVM heap; best for large
 *       files (e.g. Android APK patching, where callers pass {@code ParcelFileDescriptor.getFileDescriptor()}).</li>
 * </ul>
 */
public final class XDelta3 {

    private static final XDelta3Config DEFAULT_CONFIG = XDelta3Config.builder().build();

    private XDelta3() {
    }

    // ---------------------------------------------------------------------
    // Bytes API
    // ---------------------------------------------------------------------

    /** Encodes a VCDIFF delta from {@code source} to {@code target} using default config. */
    public static byte[] encode(byte[] source, byte[] target) throws XDelta3Exception {
        return encode(source, target, DEFAULT_CONFIG);
    }

    /**
     * Encodes a VCDIFF delta that transforms {@code source} into {@code target}.
     *
     * @param source the reference ("old") data; may be {@code null} or empty for a source-less delta
     * @param target the ("new") data to reconstruct on decode
     * @return the VCDIFF delta bytes
     */
    public static byte[] encode(byte[] source, byte[] target, XDelta3Config config)
            throws XDelta3Exception {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(config, "config");
        return NativeXDelta3.encodeMemory(
                source, target, config.flags(), config.windowSize(), config.sourceWindowSize());
    }

    /** Applies {@code delta} to {@code source} to reconstruct the target, using default config. */
    public static byte[] decode(byte[] source, byte[] delta) throws XDelta3Exception {
        return decode(source, delta, DEFAULT_CONFIG);
    }

    /**
     * Applies a VCDIFF {@code delta} to {@code source}, reconstructing the original target.
     *
     * @param source the reference ("old") data; must match what was used to encode
     * @param delta  the VCDIFF delta produced by {@link #encode}
     * @return the reconstructed target bytes
     */
    public static byte[] decode(byte[] source, byte[] delta, XDelta3Config config)
            throws XDelta3Exception {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(config, "config");
        return NativeXDelta3.decodeMemory(source, delta, config.flags(), config.sourceWindowSize());
    }

    // ---------------------------------------------------------------------
    // File-descriptor API
    // ---------------------------------------------------------------------

    /**
     * Encodes a delta from open file descriptors. All descriptors are read/written from offset 0.
     *
     * @param source     descriptor of the reference file (read from offset 0), or {@code null} for none
     * @param sourceSize size of the source in bytes (reserved for source-window tuning)
     * @param target     descriptor of the target file to diff against
     * @param deltaOut   descriptor the VCDIFF delta is written to
     */
    public static void encode(FileDescriptor source, long sourceSize,
                              FileDescriptor target, FileDescriptor deltaOut,
                              XDelta3Config config) throws XDelta3Exception, IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(deltaOut, "deltaOut");
        Objects.requireNonNull(config, "config");
        NativeXDelta3.encodeFd(
                fd(source), sourceSize, fd(target), fd(deltaOut),
                config.flags(), config.windowSize(), config.sourceWindowSize());
    }

    /**
     * Applies a delta from open file descriptors. All descriptors are read/written from offset 0.
     *
     * @param source     descriptor of the reference file (read from offset 0), or {@code null} for none
     * @param sourceSize size of the source in bytes (reserved for source-window tuning)
     * @param delta      descriptor of the VCDIFF delta to apply
     * @param targetOut  descriptor the reconstructed target is written to
     */
    public static void decode(FileDescriptor source, long sourceSize,
                              FileDescriptor delta, FileDescriptor targetOut,
                              XDelta3Config config) throws XDelta3Exception, IOException {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(targetOut, "targetOut");
        Objects.requireNonNull(config, "config");
        NativeXDelta3.decodeFd(fd(source), sourceSize, fd(delta), fd(targetOut), config.flags());
    }

    /**
     * Extracts the OS file-descriptor int from a {@link FileDescriptor}. Returns {@code -1} for a
     * {@code null} descriptor (meaning "no source"). On Android, prefer opening via
     * {@code ParcelFileDescriptor} and passing its {@code getFileDescriptor()}.
     *
     * <p>On the JVM this reflects the private {@code fd} field, which requires
     * {@code --add-opens java.base/java.io=ALL-UNNAMED} on JDK 9+.
     */
    private static int fd(FileDescriptor descriptor) throws IOException {
        if (descriptor == null) {
            return -1;
        }
        try {
            Field field = FileDescriptor.class.getDeclaredField("fd");
            field.setAccessible(true);
            return field.getInt(descriptor);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new IOException("Unable to read native file descriptor from FileDescriptor; "
                    + "on JDK 9+ run with --add-opens java.base/java.io=ALL-UNNAMED, or use "
                    + "ParcelFileDescriptor on Android", e);
        }
    }
}
