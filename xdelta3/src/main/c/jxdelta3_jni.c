/*
 * JNI wrapper around the xdelta3 library.
 *
 * Uses xdelta3's single-amalgam pattern: including xdelta3.c pulls in the whole implementation as
 * one translation unit. The upstream include directory is added to the compiler include path by
 * xdelta3/build.gradle.kts, and SECONDARY_DJW / SECONDARY_FGK are enabled there via -D defines.
 */

#include <jni.h>
#include <assert.h>   /* xdelta3.h uses static_assert() before including it itself (needs C11) */
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "xdelta3.h"
#include "xdelta3.c"

#define XD3_EXCEPTION_CLASS "com/frazieje/jxdelta3/XDelta3Exception"

/* Throw XDelta3Exception(code, message), resolving a non-null message:
 *   1. stream->msg (if provided and non-empty)
 *   2. xd3_strerror(code)  -- only for xd3_rvalues codes, else NULL
 *   3. strerror(code)      -- for positive errno values (ENOSPC/ENOMEM/...)
 *   4. a generic fallback  -- never pass NULL into NewStringUTF */
static void throw_xd3(JNIEnv *env, int code, const char *stream_msg) {
    const char *msg = (stream_msg != NULL && stream_msg[0] != '\0') ? stream_msg : NULL;
    if (msg == NULL) {
        msg = xd3_strerror(code);
    }
    if (msg == NULL && code > 0) {
        msg = strerror(code);
    }
    if (msg == NULL) {
        msg = "unknown xdelta3 error";
    }

    jclass cls = (*env)->FindClass(env, XD3_EXCEPTION_CLASS);
    if (cls == NULL) {
        return; /* NoClassDefFoundError is already pending */
    }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(ILjava/lang/String;)V");
    if (ctor == NULL) {
        (*env)->DeleteLocalRef(env, cls);
        return;
    }
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    jobject exc = (*env)->NewObject(env, cls, ctor, (jint) code, jmsg);
    if (exc != NULL) {
        (*env)->Throw(env, (jthrowable) exc);
        (*env)->DeleteLocalRef(env, exc);
    }
    if (jmsg != NULL) {
        (*env)->DeleteLocalRef(env, jmsg);
    }
    (*env)->DeleteLocalRef(env, cls);
}

/* ------------------------------------------------------------------ */
/* In-memory encode/decode (Bytes API)                                */
/* ------------------------------------------------------------------ */

/*
 * Unified in-memory code path built on the pre-configured stream tier so that XDelta3Config
 * (window sizes, flags) is honored. Grows the output buffer and retries on ENOSPC, which is the
 * expected outcome for decode (reconstructed size is not known a priori) and a safety net for
 * pathological encodes.
 */
static jbyteArray mem_code(JNIEnv *env, int is_encode,
                           jbyteArray jsrc, jbyteArray jin,
                           jint flags, jlong win_size, jlong src_win_size) {
    if (jin == NULL) {
        throw_xd3(env, EINVAL, "input array must not be null");
        return NULL;
    }

    jsize in_len = (*env)->GetArrayLength(env, jin);
    jsize src_len = (jsrc != NULL) ? (*env)->GetArrayLength(env, jsrc) : 0;

    jbyte *in = (*env)->GetByteArrayElements(env, jin, NULL);
    jbyte *src = (jsrc != NULL) ? (*env)->GetByteArrayElements(env, jsrc, NULL) : NULL;
    if (in == NULL || (jsrc != NULL && src == NULL)) {
        if (src != NULL) (*env)->ReleaseByteArrayElements(env, jsrc, src, JNI_ABORT);
        if (in != NULL) (*env)->ReleaseByteArrayElements(env, jin, in, JNI_ABORT);
        throw_xd3(env, ENOMEM, "failed to pin input arrays");
        return NULL;
    }

    /* Initial output allocation. Encode: delta is normally small but worst case exceeds target by
     * VCDIFF framing. Decode: reconstructed target can be much larger than the delta. */
    usize_t out_alloc = is_encode
            ? (usize_t) in_len + (usize_t)(in_len >> 1) + 1024
            : (usize_t) in_len * 4 + 4096;
    const usize_t OUT_ALLOC_CAP = (usize_t) 1 << 30; /* 1 GiB safety cap */

    uint8_t *out = (uint8_t *) malloc(out_alloc);
    int ret = ENOMEM;
    usize_t out_size = 0;
    char errmsg[256];
    errmsg[0] = '\0';

    while (out != NULL) {
        xd3_stream stream;
        xd3_config config;
        xd3_source source;
        memset(&stream, 0, sizeof(stream));
        memset(&source, 0, sizeof(source));
        xd3_init_config(&config, (uint32_t) flags);
        if (win_size > 0) {
            config.winsize = (usize_t) win_size;
        }

        ret = xd3_config_stream(&stream, &config);
        if (ret == 0 && src != NULL) {
            source.blksize = (usize_t) src_len;
            source.onblk = (usize_t) src_len;
            source.curblk = (const uint8_t *) src;
            source.curblkno = 0;
            if (src_win_size > 0) {
                source.max_winsize = (xoff_t) src_win_size;
            }
            ret = xd3_set_source_and_size(&stream, &source, (xoff_t) src_len);
        }

        if (ret == 0) {
            out_size = 0;
            if (is_encode) {
                ret = xd3_encode_stream(&stream, (const uint8_t *) in, (usize_t) in_len,
                                        out, &out_size, out_alloc);
            } else {
                ret = xd3_decode_stream(&stream, (const uint8_t *) in, (usize_t) in_len,
                                        out, &out_size, out_alloc);
            }
        }

        if (ret != 0 && stream.msg != NULL) {
            strncpy(errmsg, stream.msg, sizeof(errmsg) - 1);
            errmsg[sizeof(errmsg) - 1] = '\0';
        }

        xd3_free_stream(&stream);

        if (ret == ENOSPC && out_alloc < OUT_ALLOC_CAP) {
            usize_t next = out_alloc * 2;
            if (next > OUT_ALLOC_CAP) next = OUT_ALLOC_CAP;
            uint8_t *grown = (uint8_t *) realloc(out, next);
            if (grown == NULL) {
                ret = ENOMEM;
                break;
            }
            out = grown;
            out_alloc = next;
            errmsg[0] = '\0';
            continue;
        }
        break;
    }

    if (src != NULL) (*env)->ReleaseByteArrayElements(env, jsrc, src, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jin, in, JNI_ABORT);

    if (ret != 0) {
        free(out);
        throw_xd3(env, ret, errmsg[0] != '\0' ? errmsg : NULL);
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize) out_size);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) out_size, (const jbyte *) out);
    }
    free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_frazieje_jxdelta3_internal_NativeXDelta3_encodeMemory(
        JNIEnv *env, jclass cls, jbyteArray source, jbyteArray target,
        jint flags, jlong winSize, jlong srcWinSize) {
    (void) cls;
    return mem_code(env, 1, source, target, flags, winSize, srcWinSize);
}

JNIEXPORT jbyteArray JNICALL
Java_com_frazieje_jxdelta3_internal_NativeXDelta3_decodeMemory(
        JNIEnv *env, jclass cls, jbyteArray source, jbyteArray delta,
        jint flags, jlong srcWinSize) {
    (void) cls;
    return mem_code(env, 0, source, delta, flags, 0, srcWinSize);
}

/* ------------------------------------------------------------------ */
/* File-descriptor streaming encode/decode                            */
/* ------------------------------------------------------------------ */

/* Source read-block granularity for the getblk path. */
#define JXD3_SRC_BLKSIZE (1 << 16) /* 64 KiB */

static int write_all(int fd, const uint8_t *buf, usize_t len) {
    usize_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, buf + off, (size_t)(len - off));
        if (w < 0) {
            return -1;
        }
        off += (usize_t) w;
    }
    return 0;
}

/*
 * Drives the non-blocking xd3_{encode,decode}_input state machine over raw file descriptors,
 * modeled on upstream examples/encode_decode_test.c. The source (if any) is fetched block-by-block
 * via XD3_GETSRCBLK using pread-style lseek+read, so the whole source need not be buffered.
 */
static void fd_code(JNIEnv *env, int is_encode,
                    jint src_fd, jlong src_size,
                    jint in_fd, jint out_fd,
                    jint flags, jlong win_size, jlong src_win_size) {
    (void) src_size; /* reserved: source size is discovered via short reads in the getblk path */

    usize_t bufsize = (win_size > 0) ? (usize_t) win_size : XD3_DEFAULT_WINSIZE;
    if (bufsize < XD3_ALLOCSIZE) {
        bufsize = XD3_ALLOCSIZE;
    }

    xd3_stream stream;
    xd3_config config;
    xd3_source source;
    memset(&stream, 0, sizeof(stream));
    memset(&source, 0, sizeof(source));
    xd3_init_config(&config, (uint32_t) flags);
    config.winsize = bufsize;

    int ret = xd3_config_stream(&stream, &config);
    if (ret != 0) {
        throw_xd3(env, ret, stream.msg);
        xd3_free_stream(&stream);
        return;
    }

    uint8_t *srcbuf = NULL;
    uint8_t *inbuf = NULL;
    char errmsg[256];
    errmsg[0] = '\0';

    if (src_fd >= 0) {
        srcbuf = (uint8_t *) malloc(JXD3_SRC_BLKSIZE);
        if (srcbuf == NULL) {
            ret = ENOMEM;
            goto cleanup;
        }
        source.blksize = JXD3_SRC_BLKSIZE;
        source.curblk = srcbuf;
        source.max_winsize = (src_win_size > 0) ? (xoff_t) src_win_size : XD3_DEFAULT_SRCWINSZ;
        if (lseek(src_fd, 0, SEEK_SET) == (off_t) -1) {
            ret = errno;
            goto cleanup;
        }
        ssize_t r = read(src_fd, srcbuf, JXD3_SRC_BLKSIZE);
        if (r < 0) {
            ret = errno;
            goto cleanup;
        }
        source.onblk = (usize_t) r;
        source.curblkno = 0;
        ret = xd3_set_source(&stream, &source);
        if (ret != 0) {
            goto capture_and_cleanup;
        }
    }

    inbuf = (uint8_t *) malloc(bufsize);
    if (inbuf == NULL) {
        ret = ENOMEM;
        goto cleanup;
    }

    if (lseek(in_fd, 0, SEEK_SET) == (off_t) -1) {
        ret = errno;
        goto cleanup;
    }

    ssize_t nread;
    do {
        nread = read(in_fd, inbuf, bufsize);
        if (nread < 0) {
            ret = errno;
            goto cleanup;
        }
        if ((usize_t) nread < bufsize) {
            xd3_set_flags(&stream, XD3_FLUSH | stream.flags);
        }
        xd3_avail_input(&stream, inbuf, (usize_t) nread);

    process:
        ret = is_encode ? xd3_encode_input(&stream) : xd3_decode_input(&stream);
        switch (ret) {
            case XD3_INPUT:
                continue;
            case XD3_OUTPUT:
                if (write_all(out_fd, stream.next_out, stream.avail_out) != 0) {
                    ret = errno ? errno : EIO;
                    goto cleanup;
                }
                xd3_consume_output(&stream);
                goto process;
            case XD3_GETSRCBLK: {
                off_t pos = (off_t)(source.blksize * source.getblkno);
                if (lseek(src_fd, pos, SEEK_SET) == (off_t) -1) {
                    ret = errno;
                    goto cleanup;
                }
                ssize_t r = read(src_fd, srcbuf, source.blksize);
                if (r < 0) {
                    ret = errno;
                    goto cleanup;
                }
                source.onblk = (usize_t) r;
                source.curblkno = source.getblkno;
                goto process;
            }
            case XD3_GOTHEADER:
            case XD3_WINSTART:
            case XD3_WINFINISH:
                goto process;
            default:
                goto capture_and_cleanup; /* genuine error */
        }
    } while ((usize_t) nread == bufsize);

    ret = xd3_close_stream(&stream);

capture_and_cleanup:
    if (ret != 0 && stream.msg != NULL) {
        strncpy(errmsg, stream.msg, sizeof(errmsg) - 1);
        errmsg[sizeof(errmsg) - 1] = '\0';
    }

cleanup:
    xd3_free_stream(&stream);
    free(inbuf);
    free(srcbuf);
    if (ret != 0) {
        throw_xd3(env, ret, errmsg[0] != '\0' ? errmsg : NULL);
    }
}

JNIEXPORT void JNICALL
Java_com_frazieje_jxdelta3_internal_NativeXDelta3_encodeFd(
        JNIEnv *env, jclass cls, jint sourceFd, jlong sourceSize, jint targetFd, jint deltaFd,
        jint flags, jlong winSize, jlong srcWinSize) {
    (void) cls;
    /* encode: input = target, output = delta */
    fd_code(env, 1, sourceFd, sourceSize, targetFd, deltaFd, flags, winSize, srcWinSize);
}

JNIEXPORT void JNICALL
Java_com_frazieje_jxdelta3_internal_NativeXDelta3_decodeFd(
        JNIEnv *env, jclass cls, jint sourceFd, jlong sourceSize, jint deltaFd, jint targetFd,
        jint flags) {
    (void) cls;
    /* decode: input = delta, output = target */
    fd_code(env, 0, sourceFd, sourceSize, deltaFd, targetFd, flags, 0, 0);
}
