package com.frazieje.jxdelta3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip and error-path tests for the bytes and file-descriptor APIs. */
public class XDelta3Test {

    /** target = source with a small edit in the middle, so a delta is meaningful. */
    private static byte[][] sourceAndTarget(int size) {
        Random rnd = new Random(42);
        byte[] source = new byte[size];
        rnd.nextBytes(source);
        byte[] target = source.clone();
        for (int i = size / 2; i < size / 2 + 64 && i < size; i++) {
            target[i] = (byte) ~target[i];
        }
        return new byte[][] {source, target};
    }

    // ---------------------- Bytes API ----------------------

    @Test
    void byteRoundTripDefault() throws Exception {
        byte[][] st = sourceAndTarget(1 << 16);
        byte[] source = st[0], target = st[1];

        byte[] delta = XDelta3.encode(source, target);
        assertNotNull(delta);
        assertTrue(delta.length > 0, "delta should be non-empty");
        assertTrue(delta.length < target.length, "delta of a small edit should be smaller than target");

        byte[] decoded = XDelta3.decode(source, delta);
        assertArrayEquals(target, decoded);
    }

    @Test
    void byteRoundTripWithConfig() throws Exception {
        byte[][] st = sourceAndTarget(1 << 17);
        byte[] source = st[0], target = st[1];

        XDelta3Config cfg = XDelta3Config.builder()
                .compressionLevel(9)
                .checksum(true)
                .secondaryCompression(XDelta3Config.SecondaryCompression.DJW)
                .windowSize(1 << 16)
                .build();

        byte[] delta = XDelta3.encode(source, target, cfg);
        byte[] decoded = XDelta3.decode(source, delta, cfg);
        assertArrayEquals(target, decoded);
    }

    @Test
    void byteRoundTripFgk() throws Exception {
        byte[][] st = sourceAndTarget(1 << 16);
        XDelta3Config cfg = XDelta3Config.builder()
                .secondaryCompression(XDelta3Config.SecondaryCompression.FGK)
                .build();
        byte[] delta = XDelta3.encode(st[0], st[1], cfg);
        assertArrayEquals(st[1], XDelta3.decode(st[0], delta, cfg));
    }

    @Test
    void byteRoundTripNoSource() throws Exception {
        Random rnd = new Random(7);
        byte[] target = new byte[4096];
        rnd.nextBytes(target);

        byte[] delta = XDelta3.encode(null, target);
        byte[] decoded = XDelta3.decode(null, delta);
        assertArrayEquals(target, decoded);
    }

    @Test
    void decodeCorruptDeltaThrows() {
        byte[] source = new byte[1024];
        byte[] garbage = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};
        assertThrows(XDelta3Exception.class, () -> XDelta3.decode(source, garbage));
    }

    // ---------------------- File-descriptor API ----------------------

    @Test
    void fdRoundTrip(@TempDir File dir) throws Exception {
        byte[][] st = sourceAndTarget(1 << 18); // 256 KiB: exercises multi-block source getblk
        byte[] source = st[0], target = st[1];

        File srcFile = new File(dir, "source.bin");
        File tgtFile = new File(dir, "target.bin");
        File deltaFile = new File(dir, "delta.vcdiff");
        File outFile = new File(dir, "decoded.bin");
        Files.write(srcFile.toPath(), source);
        Files.write(tgtFile.toPath(), target);

        XDelta3Config cfg = XDelta3Config.builder().windowSize(1 << 16).build();

        // Encode: source + target -> delta
        try (FileInputStream srcIn = new FileInputStream(srcFile);
             FileInputStream tgtIn = new FileInputStream(tgtFile);
             FileOutputStream deltaOut = new FileOutputStream(deltaFile)) {
            XDelta3.encode(srcIn.getFD(), source.length, tgtIn.getFD(), deltaOut.getFD(), cfg);
            deltaOut.getFD().sync();
        }
        assertTrue(deltaFile.length() > 0, "delta file should be written");

        // Decode: source + delta -> reconstructed target
        try (FileInputStream srcIn = new FileInputStream(srcFile);
             FileInputStream deltaIn = new FileInputStream(deltaFile);
             FileOutputStream tgtOut = new FileOutputStream(outFile)) {
            XDelta3.decode(srcIn.getFD(), source.length, deltaIn.getFD(), tgtOut.getFD(), cfg);
            tgtOut.getFD().sync();
        }

        assertArrayEquals(target, Files.readAllBytes(outFile.toPath()));
    }

    @Test
    void fdDecodeCorruptThrows(@TempDir File dir) throws IOException {
        File srcFile = new File(dir, "s.bin");
        File deltaFile = new File(dir, "bad.vcdiff");
        File outFile = new File(dir, "o.bin");
        Files.write(srcFile.toPath(), new byte[1024]);
        Files.write(deltaFile.toPath(), new byte[] {9, 9, 9, 9, 9, 9, 9, 9});

        XDelta3Config cfg = XDelta3Config.builder().build();
        try (FileInputStream srcIn = new FileInputStream(srcFile);
             FileInputStream deltaIn = new FileInputStream(deltaFile);
             FileOutputStream tgtOut = new FileOutputStream(outFile)) {
            assertThrows(XDelta3Exception.class, () ->
                    XDelta3.decode(srcIn.getFD(), 1024, deltaIn.getFD(), tgtOut.getFD(), cfg));
        }
    }

    // ---------------------- Config validation ----------------------

    @Test
    void windowSizeOverMaxRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> XDelta3Config.builder().windowSize(XDelta3Config.MAX_WINDOW_SIZE + 1));
    }

    @Test
    void compressionLevelOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> XDelta3Config.builder().compressionLevel(10));
    }
}
