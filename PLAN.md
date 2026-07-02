# jxdelta3 API Plan

## Overview

jxdelta3 wraps the [xdelta3](https://github.com/jmacd/xdelta) C library with a JNI layer to expose binary delta (VCDIFF/RFC 3284) encode and decode functionality to Java and Android applications.

---

## xdelta3 C API Summary

xdelta3 exposes three tiers of API, each building on the same underlying state machine:

| Tier | C functions | Best for |
|---|---|---|
| One-shot / in-memory | `xd3_encode_memory` / `xd3_decode_memory` | Small-to-medium data, simplest to bind |
| Pre-configured stream | `xd3_encode_stream` / `xd3_decode_stream` | Medium data, slightly more control |
| State-machine streaming | `xd3_encode_input` / `xd3_decode_input` | Large files, block-by-block source via `XD3_GETSRCBLK` |

The state-machine tier is the foundation; the higher tiers are convenience wrappers over it.

### Key C types

| Type | Purpose |
|---|---|
| `xd3_stream` | Central encode/decode state object |
| `xd3_source` | Describes the reference (old) file for delta encoding |
| `xd3_config` | Configuration passed to `xd3_config_stream` |
| `xoff_t` | 64-bit file offset |
| `usize_t` | 64-bit window size |

### Key C status codes

| Code | Meaning |
|---|---|
| `XD3_INPUT` | Engine needs more input |
| `XD3_OUTPUT` | Engine has output ready to consume |
| `XD3_GETSRCBLK` | Engine needs a specific source block (async block fetch) |
| `XD3_GOTHEADER` | Decoder parsed VCDIFF header and first window header |
| `XD3_WINSTART` / `XD3_WINFINISH` | Window boundary notifications |
| `0` | Success |

### Key C configuration flags

| Flag | Purpose |
|---|---|
| `XD3_FLUSH` | Flush stream buffer (required before close) |
| `XD3_ADLER32` | Enable Adler-32 checksum in encoder |
| `XD3_ADLER32_NOVER` | Disable checksum verification in decoder |
| `XD3_NOCOMPRESS` | Source-match only, no target compression |
| `XD3_SEC_DJW` / `XD3_SEC_FGK` / `XD3_SEC_LZMA` | Secondary compression algorithms |
| `XD3_COMPLEVEL_1` … `XD3_COMPLEVEL_9` | Compression level (bits 20–23) |

### Key C defaults

| Constant | Default | Purpose |
|---|---|---|
| `XD3_DEFAULT_WINSIZE` | 8 MB (1<<23) | Encoder window size |
| `XD3_DEFAULT_SRCWINSZ` | 64 MB (1<<26) | Source window size |
| `XD3_HARDMAXWINSIZE` | 64 MB (1<<26) | Max decode window (malicious-input guard) |
| `XD3_DEFAULT_LEVEL` | 3 | Compression level |

---

## Java API Design

### Package structure

```
com.frazieje.jxdelta3/
├── XDelta3.java          -- public entry point: static factory + convenience methods
├── XDelta3Config.java    -- immutable configuration (builder pattern)
├── XDelta3Exception.java -- checked exception wrapping xd3 error codes and messages
└── internal/
    └── NativeXDelta3.java -- package-private native method declarations + System.loadLibrary
```

---

### `XDelta3Config`

Immutable value object mapping to `xd3_config` / `xd3_init_config` plus the flags bitfield.
Passed across the JNI boundary as discrete `int`/`long` parameters (no struct marshalling needed).

```java
XDelta3Config config = XDelta3Config.builder()
    .windowSize(1 << 23)        // 8 MB — default
    .sourceWindowSize(1 << 26)  // 64 MB — default
    .compressionLevel(3)        // 1–9, maps to XD3_COMPLEVEL_* bits
    .checksum(true)             // XD3_ADLER32
    .secondaryCompression(SecondaryCompression.NONE) // NONE | DJW | FGK | LZMA
    .build();
```

`SecondaryCompression` is a nested enum. **Secondary compression is compile-time gated in
upstream and off by default** (`SECONDARY_FGK=0`, `SECONDARY_DJW=0` in `xdelta3.c:279-284`;
`SECONDARY_LZMA=0` unless `HAVE_LZMA_H` is defined, `xdelta3.c:286-291`). Setting a runtime flag
such as `XD3_SEC_DJW` without the corresponding `-DSECONDARY_DJW=1` compiled in will fail.

Therefore the initial build enables the two self-contained compressors via the native build
(`-DSECONDARY_DJW=1 -DSECONDARY_FGK=1`, added to `xdelta3/build.gradle.kts` compilerArgs) and the
enum is scoped to only what is actually compiled:

```java
enum SecondaryCompression { NONE, DJW, FGK }   // LZMA excluded — see note
```

LZMA is deliberately excluded initially: it requires `<lzma.h>` and liblzma linked
(`xdelta3-lzma.h:23`), which is extra build/packaging work for Android ABIs. It can be added
later by vendoring/linking liblzma and defining `HAVE_LZMA_H`, at which point `LZMA` joins the enum.

**Validation:** the builder must clamp/reject `windowSize > XD3_HARDMAXWINSIZE` (64 MB). The decoder
enforces a hard 64 MB window guard (anti-DoS); a delta encoded with a larger window is undecodable.

### Native build defines

The initial `xdelta3/build.gradle.kts` currently passes no preprocessor defines. It must add:

| Define | Purpose |
|---|---|
| `-DSECONDARY_DJW=1` | Enable DJW static Huffman secondary compression |
| `-DSECONDARY_FGK=1` | Enable FGK adaptive Huffman secondary compression |
| `-DSIZEOF_SIZE_T=__SIZEOF_SIZE_T__` | Config macro xdelta3.h expects from autoconf |
| `-DSIZEOF_UNSIGNED_INT=__SIZEOF_INT__` | " |
| `-DSIZEOF_UNSIGNED_LONG=__SIZEOF_LONG__` | " |
| `-DSIZEOF_UNSIGNED_LONG_LONG=__SIZEOF_LONG_LONG__` | " |

(For MSVC use `/D SECONDARY_DJW=1` etc.) Do **not** define `HAVE_LZMA_H` until liblzma is vendored.

**Discovered during implementation** (not obvious from the header alone):
- `xdelta3.h` is normally configured by autoconf and hits `#error Bad configure script` without the
  four `SIZEOF_*` macros above. Deriving them from the compiler's `__SIZEOF_*__` builtins keeps them
  correct on every ABI automatically — important for 32-bit Android (`arm/x86`) where `long` and
  `size_t` are 4 bytes, so hardcoding LP64 values would be wrong.
- Must compile as **C11** (`-std=gnu11`): `xdelta3.h` uses `static_assert()` before it includes
  anything, so the wrapper must `#include <assert.h>` and the standard must be C11 for that macro to
  exist. `gnu11` (not `c11`) also keeps POSIX `lseek`/`read`/`write` declarations visible for the FD path.
- The upstream include dir is added to the compiler include path (`compileTask.includes.from(
  "external/xdelta/xdelta3")`) so the wrapper uses `#include "xdelta3.h"` / `#include "xdelta3.c"`
  rather than a brittle relative `../../../` path.

---

### `XDelta3Exception`

```java
public class XDelta3Exception extends IOException {
    public int errorCode();    // raw return code (xd3_rvalues, or a positive errno)
    public String xdMessage(); // resolved message (see C note below)
}
```

Thrown by all native operations on non-zero return codes.

**C-side message resolution.** `xd3_strerror` returns `NULL` for any code that is not an
`xd3_rvalues` (`xdelta3.c:537`, `default → return NULL`). The memory API's most likely failures
are `ENOSPC` (`xdelta3.c:3361`) and `ENOMEM` — **positive errno values, not xd3 codes** — for which
`xd3_strerror` yields `NULL`. Passing `NULL` into a JNI string call would crash. The helper must
guard for this:

```c
static void throw_xdelta3_exception(JNIEnv *env, int code, const char *stream_msg) {
    const char *msg = xd3_strerror(code);          // xd3_rvalues only, else NULL
    if (msg == NULL) msg = strerror(code);          // positive errno fallback (ENOSPC/ENOMEM/...)
    if (msg == NULL) msg = "unknown xdelta3 error";
    /* prefer stream->msg when available (from xd3_errstring), else msg */
    ...
}
```

---

### Layer 1 — Bytes API

The entire operation runs inside a single JNI call. Suitable for data that fits comfortably in
heap (typically up to tens of MB).

**API tier used — important.** `xd3_encode_memory` / `xd3_decode_memory` take a fixed 8-argument
signature with **no window-size / config parameter** (`xdelta3.h:1122-1130`); they accept only a
`flags` bitfield. They therefore cannot honor `XDelta3Config.windowSize` / `sourceWindowSize`.

To make `XDelta3Config` meaningful for in-memory operations, Layer 1 is built on the
**pre-configured stream tier** (`xd3_encode_stream` / `xd3_decode_stream`, `xdelta3.h:1146+`), which
operates on a caller-configured `xd3_stream`:
- When `config` is absent (or only flag-level options are set), the plain `xd3_encode_memory` /
  `xd3_decode_memory` path may be used as a fast path.
- When `config` carries window/source-window sizing, the C code builds an `xd3_config`
  (`xd3_init_config` + `winsize`/`sprevsz`/`flags`), calls `xd3_config_stream`, sets the source via
  `xd3_set_source_and_size`, then calls `xd3_encode_stream` / `xd3_decode_stream`, and finally
  `xd3_free_stream`.

**Public surface (`XDelta3.java`):**

```java
public static byte[] encode(byte[] source, byte[] target) throws XDelta3Exception;
public static byte[] encode(byte[] source, byte[] target, XDelta3Config config) throws XDelta3Exception;

public static byte[] decode(byte[] source, byte[] delta) throws XDelta3Exception;
public static byte[] decode(byte[] source, byte[] delta, XDelta3Config config) throws XDelta3Exception;
```

**JNI boundary (`NativeXDelta3.java`):**

Config is passed as discrete `long` params (widths match `usize_t`, which is 64-bit by default —
`XD3_USE_LARGESIZET=1`, `xdelta3.h:102-103,222`). Use `long`, not `int`, so window sizes are not
silently truncated. A value of `0` means "use xdelta3 default".

```java
static native byte[] encodeMemory(byte[] source, byte[] target,
                                   int flags, long winSize, long srcWinSize)
    throws XDelta3Exception;
static native byte[] decodeMemory(byte[] source, byte[] delta,
                                   int flags, long srcWinSize)
    throws XDelta3Exception;
```

**C implementation notes:**
- Pin arrays with `GetByteArrayElements` / `ReleaseByteArrayElements` (release in all exit paths).
- Output buffer upper bound for encode: `target_size + (target_size >> 1) + 1024`
  (a delta is normally far smaller, but the incompressible/no-match worst case exceeds target by
  VCDIFF framing overhead). `source_size` does not bound output and is not part of the formula.
  On `ENOSPC`, optionally retry once with a doubled buffer before failing.
- For decode, the output size is the reconstructed target; size the buffer from the caller's
  expectation or grow-on-`ENOSPC`, since the decoded length is not known a priori from the delta alone.
- On success, wrap result in a new `jbyteArray` via `NewByteArray` + `SetByteArrayRegion`.
- On non-zero return, call `throw_xdelta3_exception(env, ret, xd3_errstring(stream))` (or `NULL`
  for the fast path). See the exception helper for `NULL`/errno handling.

---

### Layer 2 — File Descriptor API

Drives the `xd3_encode_input` / `xd3_decode_input` state-machine loop entirely in C.
Takes open file descriptors so neither the source nor target needs to be loaded into JVM heap.
The C code handles `XD3_GETSRCBLK` by seeking the source fd.
This is the primary API for Android APK delta patching.

**Public surface (`XDelta3.java`):**

```java
public static void encode(FileDescriptor source, long sourceSize,
                          FileDescriptor target,
                          FileDescriptor deltaOut,
                          XDelta3Config config) throws XDelta3Exception, IOException;

public static void decode(FileDescriptor source, long sourceSize,
                          FileDescriptor delta,
                          FileDescriptor targetOut,
                          XDelta3Config config) throws XDelta3Exception, IOException;
```

`sourceSize` is required because `xd3_set_source_and_size` needs it to configure the source window,
and reliably obtaining file size from a raw fd across all Android versions is not guaranteed.

On Android, callers open files with `ParcelFileDescriptor` and pass `pfd.getFileDescriptor()`.

**JNI boundary (`NativeXDelta3.java`):**

Window sizes are `long` (matching 64-bit `usize_t`/`xoff_t`) so large-file configs are not truncated:

```java
static native void encodeFd(int sourceFd, long sourceSize, int targetFd, int deltaFd,
                             int flags, long winSize, long srcWinSize) throws XDelta3Exception;
static native void decodeFd(int sourceFd, long sourceSize, int deltaFd, int targetFd,
                             int flags) throws XDelta3Exception;
```

**C implementation notes:**
- Initialize `xd3_stream` with `xd3_config_stream` using provided flags and sizes.
- Initialize `xd3_source`: set `blksize` = `srcwinsize`, `curblkno = (xoff_t)-1`.
- Call `xd3_set_source_and_size`.
- Drive the encode/decode loop:
  - Read `winsize` bytes from target/delta fd → `xd3_avail_input`.
  - Call `xd3_encode_input` / `xd3_decode_input` in a loop.
  - `XD3_OUTPUT`: write `stream->next_out` / `stream->avail_out` to output fd, call `xd3_consume_output`.
  - `XD3_INPUT`: read next chunk, set `XD3_FLUSH` on last read.
  - `XD3_GETSRCBLK`: lseek source fd to `source.getblkno * source.blksize`, read block into buffer,
    set `source.curblk`, `source.onblk`, `source.curblkno`.
- Call `xd3_close_stream`, `xd3_free_stream`.

---

### Layer 3 — Streaming API (deferred)

Exposes the xdelta3 state machine to Java for cases where neither byte arrays nor file descriptors
are available (e.g., streaming from network, non-seekable sources). The C `xd3_stream` state lives
across multiple JNI calls, held as a `long nativeHandle` field on the Java object.

**Public surface (tentative):**

```java
public class XDelta3Encoder implements AutoCloseable {
    public static XDelta3Encoder create(XDelta3Config config);
    public void setSource(byte[] source);
    public int encode(byte[] input, int off, int len, byte[] output) throws XDelta3Exception;
    public void flush() throws XDelta3Exception;
    @Override public void close();
}

public class XDelta3Decoder implements AutoCloseable {
    public static XDelta3Decoder create(XDelta3Config config);
    public void setSource(byte[] source);
    public int decode(byte[] input, int off, int len, byte[] output) throws XDelta3Exception;
    @Override public void close();
}
```

The `nativeHandle` holds a heap-allocated `xd3_stream *` created in `create()` and freed in `close()`.

This layer is deferred until Layers 1 and 2 are validated.

---

## C-side structure

**Rename the wrapper file.** The current wrapper is `xdelta3/src/main/c/xdelta3.c`, which would
`#include "xdelta3.c"` (the upstream file of the *same name*). That is legal but fragile — it
confuses IDEs/tooling and breaks if the source set ever globs both directories. Rename the wrapper
to `xdelta3/src/main/c/jxdelta3_jni.c`. (The Gradle source set globs `**/*.c` under `src/main/c`,
so the rename needs no build change.)

The JNI wrapper file `jxdelta3_jni.c` should:

1. `#include` the upstream library using its single-amalgam pattern:
   ```c
   #include "../../external/xdelta/xdelta3/xdelta3.h"
   #include "../../external/xdelta/xdelta3/xdelta3.c"
   ```
   This is the upstream-documented usage (see `examples/`) and avoids adding separate compile
   inputs to the Gradle build — the wrapper `.c` file is the only compilation unit. Secondary
   compression is enabled via the build's `-DSECONDARY_DJW=1 -DSECONDARY_FGK=1` defines (see
   "Native build defines"), not by editing this file.

2. Define a shared error-throwing helper used by all JNI functions:
   ```c
   static void throw_xdelta3_exception(JNIEnv *env, int code, const char *msg);
   ```

3. Implement each `Java_com_frazieje_jxdelta3_internal_NativeXDelta3_*` function.

---

## Implementation order

| Phase | Work | Validates |
|---|---|---|
| 0 | Rename wrapper to `jxdelta3_jni.c`; add `-DSECONDARY_DJW=1 -DSECONDARY_FGK=1` to native build | Build wiring, secondary compressors compiled in |
| 1 | Layer 1 no-config overloads (`xd3_*_memory`) + `XDelta3Exception` (with NULL/errno-safe helper) | End-to-end JNI plumbing, encode/decode correctness |
| 2 | `XDelta3Config` (+ window validation) + config-carrying overloads on `xd3_encode_stream`/`decode_stream` | Config actually reaches the engine |
| 3 | Layer 2 file descriptor API | Large-file path, `XD3_GETSRCBLK` handling, Android use case |
| 4 | Layer 3 streaming API | Non-seekable / chunked source support |

Each phase adds JUnit tests with known delta/patch vectors before moving to the next.
