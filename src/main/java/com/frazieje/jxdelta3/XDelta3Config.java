package com.frazieje.jxdelta3;

/**
 * Immutable configuration for xdelta3 encode/decode operations.
 *
 * <p>Maps to the subset of {@code xd3_config} plus the {@code flags} bitfield that this binding
 * exposes. Flag bit values mirror {@code xd3_flags} in {@code xdelta3.h} (the upstream submodule is
 * pinned, so these values are stable).
 *
 * <p>Secondary compression is <b>compile-time gated</b> upstream. This build enables DJW and FGK
 * via {@code -DSECONDARY_DJW=1 -DSECONDARY_FGK=1} (see {@code xdelta3/build.gradle.kts}); LZMA is
 * intentionally not built (it needs liblzma), so it is absent from {@link SecondaryCompression}.
 */
public final class XDelta3Config {

    // --- xd3_flags bit values (xdelta3.h) ---
    private static final int XD3_SEC_DJW = 1 << 5;   // use DJW static huffman
    private static final int XD3_SEC_FGK = 1 << 6;   // use FGK adaptive huffman
    private static final int XD3_ADLER32 = 1 << 10;  // enable checksum in encoder
    private static final int XD3_COMPLEVEL_SHIFT = 20; // complevel occupies bits 20..23

    /** Hard 64 MB decode-window guard (XD3_HARDMAXWINSIZE); larger windows are undecodable. */
    public static final long MAX_WINDOW_SIZE = 1 << 26;

    /** Secondary compression algorithm. Only algorithms compiled into the native lib are listed. */
    public enum SecondaryCompression {
        NONE,
        /** DJW static Huffman (requires {@code -DSECONDARY_DJW=1}). */
        DJW,
        /** FGK adaptive Huffman (requires {@code -DSECONDARY_FGK=1}). */
        FGK
    }

    private final long windowSize;
    private final long sourceWindowSize;
    private final int compressionLevel;
    private final boolean checksum;
    private final SecondaryCompression secondaryCompression;

    private XDelta3Config(Builder b) {
        this.windowSize = b.windowSize;
        this.sourceWindowSize = b.sourceWindowSize;
        this.compressionLevel = b.compressionLevel;
        this.checksum = b.checksum;
        this.secondaryCompression = b.secondaryCompression;
    }

    /** Encoder window size in bytes; {@code 0} means use the xdelta3 default (8 MB). */
    public long windowSize() {
        return windowSize;
    }

    /** Source window size in bytes; {@code 0} means use the xdelta3 default (64 MB). */
    public long sourceWindowSize() {
        return sourceWindowSize;
    }

    /** Compression level 1..9; {@code 0} means use the xdelta3 default (3). */
    public int compressionLevel() {
        return compressionLevel;
    }

    public boolean checksum() {
        return checksum;
    }

    public SecondaryCompression secondaryCompression() {
        return secondaryCompression;
    }

    /** Composes the {@code xd3_flags} integer passed across the JNI boundary. */
    public int flags() {
        int f = 0;
        if (checksum) {
            f |= XD3_ADLER32;
        }
        if (compressionLevel > 0) {
            f |= (compressionLevel << XD3_COMPLEVEL_SHIFT);
        }
        switch (secondaryCompression) {
            case DJW:
                f |= XD3_SEC_DJW;
                break;
            case FGK:
                f |= XD3_SEC_FGK;
                break;
            case NONE:
            default:
                break;
        }
        return f;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long windowSize = 0;
        private long sourceWindowSize = 0;
        private int compressionLevel = 0;
        private boolean checksum = true;
        private SecondaryCompression secondaryCompression = SecondaryCompression.NONE;

        /** Encoder window size in bytes. Must be in (0, {@value #MAX_WINDOW_SIZE}]. 0 = default. */
        public Builder windowSize(long bytes) {
            if (bytes < 0 || bytes > MAX_WINDOW_SIZE) {
                throw new IllegalArgumentException(
                        "windowSize must be in [0, " + MAX_WINDOW_SIZE + "] (0 = default); got " + bytes);
            }
            this.windowSize = bytes;
            return this;
        }

        /** Source window size in bytes; 0 = default. */
        public Builder sourceWindowSize(long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("sourceWindowSize must be >= 0; got " + bytes);
            }
            this.sourceWindowSize = bytes;
            return this;
        }

        /** Compression level 1..9; 0 = default. */
        public Builder compressionLevel(int level) {
            if (level < 0 || level > 9) {
                throw new IllegalArgumentException("compressionLevel must be in [0, 9]; got " + level);
            }
            this.compressionLevel = level;
            return this;
        }

        public Builder checksum(boolean enabled) {
            this.checksum = enabled;
            return this;
        }

        public Builder secondaryCompression(SecondaryCompression algo) {
            if (algo == null) {
                throw new IllegalArgumentException("secondaryCompression must not be null");
            }
            this.secondaryCompression = algo;
            return this;
        }

        public XDelta3Config build() {
            return new XDelta3Config(this);
        }
    }
}
