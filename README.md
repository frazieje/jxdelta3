# jxdelta3

A JNI wrapper around [xdelta3](https://github.com/jmacd/xdelta) that exposes binary
delta (VCDIFF / [RFC 3284](https://www.rfc-editor.org/rfc/rfc3284)) **encode** and
**decode** to Java and Android.

xdelta3 computes a compact *delta* (patch) that transforms one binary — the *source*
(old) — into another — the *target* (new). Applying that delta to the source
reconstructs the target exactly. This is ideal for shipping small updates instead of
whole files; the primary use case here is **Android APK delta patching**.

## Features

- **Bytes API** — encode/decode entirely in a single native call; best for data that
  fits comfortably in heap (up to tens of MB).
- **File-descriptor API** — drives the xdelta3 streaming state machine in C, reading and
  writing in windows without loading whole files into the JVM heap; best for large files.
  On Android, open files with `ParcelFileDescriptor` and pass `getFileDescriptor()`.
- Configurable window sizes, compression level (1–9), Adler-32 checksums, and DJW/FGK
  secondary compression.
- Ships the upstream xdelta3 C source as a git submodule and builds a native library
  (`libxdelta3.so`) via Gradle's native toolchain — no separate autoconf step required.

> **Status:** first-pass implementation. The Bytes and File-descriptor APIs are in place.
> The streaming API (Layer 3, for non-seekable sources) is deferred — see
> [`PLAN.md`](PLAN.md) for the full design and roadmap.

## Requirements

- **JDK 9+.** The file-descriptor API reflects the private `fd` field of
  `java.io.FileDescriptor`, which on JDK 9+ requires the JVM flag
  `--add-opens java.base/java.io=ALL-UNNAMED` (already set for the test task).
- A working **C toolchain** discoverable by Gradle (GCC/Clang, or MSVC on Windows). The
  upstream sources compile as C11.
- **Git** with submodule support.

## Building

Clone with the xdelta3 submodule:

```bash
git clone --recurse-submodules <repo-url>
# or, if already cloned:
git submodule update --init --recursive
```

Build the native library and the Java code:

```bash
./gradlew build
```

This compiles the JNI wrapper (`xdelta3/src/main/c/jxdelta3_jni.c`, which `#include`s the
upstream xdelta3 amalgam) into `libxdelta3.so` under
`xdelta3/build/lib/main/debug/`, and builds the Java binding.

Run the tests (round-trip and error-path coverage for both APIs):

```bash
./gradlew test
```

The `test` task depends on `:xdelta3:linkDebug` and puts the freshly built native library
on `java.library.path` automatically.

### Loading the native library at runtime

`NativeXDelta3` calls `System.loadLibrary("xdelta3")`, so the directory containing
`libxdelta3.so` must be on `java.library.path`:

```bash
java -Djava.library.path=/path/to/xdelta3/build/lib/main/debug \
     --add-opens java.base/java.io=ALL-UNNAMED \
     -cp build/classes/java/main YourApp
```

## Usage

All operations go through the `com.frazieje.jxdelta3.XDelta3` entry point.

### Bytes API

```java
import com.frazieje.jxdelta3.XDelta3;

byte[] source = ...;   // old data
byte[] target = ...;   // new data

// Encode a VCDIFF delta transforming source -> target
byte[] delta = XDelta3.encode(source, target);

// Later, reconstruct the target from source + delta
byte[] reconstructed = XDelta3.decode(source, delta);
// reconstructed.equals(target)
```

`source` may be `null` or empty for a source-less (self-contained) delta.

### File-descriptor API

Streams source, target, and delta through open file descriptors — nothing is loaded fully
into the JVM heap.

```java
import com.frazieje.jxdelta3.XDelta3;
import com.frazieje.jxdelta3.XDelta3Config;

try (var source   = new FileInputStream("old.apk");
     var target   = new FileInputStream("new.apk");
     var deltaOut = new FileOutputStream("patch.vcdiff")) {

    long sourceSize = new File("old.apk").length();
    XDelta3.encode(source.getFD(), sourceSize,
                   target.getFD(), deltaOut.getFD(),
                   XDelta3Config.builder().build());
}

// Apply the patch: source + delta -> target
try (var source    = new FileInputStream("old.apk");
     var delta      = new FileInputStream("patch.vcdiff");
     var targetOut  = new FileOutputStream("restored.apk")) {

    long sourceSize = new File("old.apk").length();
    XDelta3.decode(source.getFD(), sourceSize,
                   delta.getFD(), targetOut.getFD(),
                   XDelta3Config.builder().build());
}
```

`sourceSize` is required so the source window can be configured (reliably obtaining a
file's size from a raw fd is not guaranteed across all Android versions). Pass a `null`
source `FileDescriptor` for a source-less delta.

On **Android**, open files with `ParcelFileDescriptor` and pass
`pfd.getFileDescriptor()`.

### Configuration

`XDelta3Config` is an immutable builder mapping to the subset of `xd3_config` and the
flags bitfield this binding exposes:

```java
XDelta3Config config = XDelta3Config.builder()
    .windowSize(1 << 23)          // encoder window, bytes; 0 = default (8 MB). Max 64 MB.
    .sourceWindowSize(1 << 26)    // source window, bytes; 0 = default (64 MB)
    .compressionLevel(6)          // 1..9; 0 = default (3)
    .checksum(true)               // XD3_ADLER32 (enabled by default)
    .secondaryCompression(XDelta3Config.SecondaryCompression.DJW) // NONE | DJW | FGK
    .build();

byte[] delta = XDelta3.encode(source, target, config);
```

Notes:

- **Window size is capped at 64 MB** (`XDelta3Config.MAX_WINDOW_SIZE`,
  `XD3_HARDMAXWINSIZE`). The decoder enforces this hard guard as an anti-DoS measure — a
  delta encoded with a larger window is undecodable. The builder rejects larger values.
- **Secondary compression is compile-time gated** in upstream xdelta3. This build enables
  DJW (static Huffman) and FGK (adaptive Huffman) via `-DSECONDARY_DJW=1
  -DSECONDARY_FGK=1`. **LZMA is intentionally not built** (it requires liblzma) and is
  therefore absent from the `SecondaryCompression` enum.

### Error handling

Native failures throw `XDelta3Exception` (a subclass of `IOException`).
`errorCode()` returns the raw native code — either an `xd3_rvalues` code (a large negative
sentinel such as `XD3_INVALID_INPUT`) or a positive `errno` value (`ENOSPC`, `ENOMEM`)
surfaced by the in-memory API. The message is resolved on the C side.

## Project layout

```
jxdelta3/
├── build.gradle.kts               Root Java module (the binding + tests)
├── settings.gradle.kts            Includes the :xdelta3 native subproject
├── PLAN.md                        Full API design and roadmap
├── src/
│   ├── main/java/com/frazieje/jxdelta3/
│   │   ├── XDelta3.java            Public entry point (static encode/decode)
│   │   ├── XDelta3Config.java      Immutable configuration (builder)
│   │   ├── XDelta3Exception.java   Checked exception wrapping xd3 error codes
│   │   └── internal/NativeXDelta3.java   Native method declarations + loadLibrary
│   └── test/java/...              JUnit 5 round-trip and error-path tests
└── xdelta3/                       Native subproject (cpp-library plugin, built as C)
    ├── build.gradle.kts           Native compile config (defines, C11, JNI includes)
    ├── src/main/c/jxdelta3_jni.c  JNI wrapper; #includes the upstream xdelta3 amalgam
    └── external/xdelta/           git submodule: jmacd/xdelta (the C library)
```

## License

See [LICENSE](LICENSE). The bundled upstream xdelta3 sources are under their own license
(GPL / Apache — see the [`xdelta3/external/xdelta`](https://github.com/jmacd/xdelta)
submodule).
