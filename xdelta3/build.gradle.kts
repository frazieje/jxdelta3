import org.gradle.internal.jvm.Jvm
import org.gradle.language.cpp.CppBinary
import org.gradle.nativeplatform.toolchain.GccCompatibleToolChain
import org.gradle.nativeplatform.toolchain.VisualCpp

/*
 * There is currently no "C library" plugin, so this build uses the "C++ library" plugin and then reconfigures it
 * to build C instead.
 */
plugins {
    `cpp-library`
}

group = "org.example"
version = "1.0-SNAPSHOT"

library {
    binaries.configureEach(CppBinary::class.java) {
        val binary = this
        val compileTask = binary.compileTask.get()
        val javaHome = Jvm.current().javaHome

        compileTask.includes.from("$javaHome/include")

        // Upstream xdelta3 amalgam headers/sources (submodule), pulled in via #include "xdelta3.c".
        compileTask.includes.from("external/xdelta/xdelta3")

        val osFamily = binary.targetPlatform.targetMachine.operatingSystemFamily
        when {
            osFamily.isMacOs -> compileTask.includes.from("$javaHome/include/darwin")
            osFamily.isLinux -> compileTask.includes.from("$javaHome/include/linux")
            osFamily.isWindows -> compileTask.includes.from("$javaHome/include/win32")
        }

        // Only the JNI wrapper is a compilation unit; it #includes the upstream xdelta3.c amalgam.
        compileTask.source.from(fileTree("src/main/c") { include("**/*.c") })

        when (val toolChain = binary.toolChain) {
            is VisualCpp -> compileTask.compilerArgs.addAll(
                // MSVC (x64, LLP64): supply the SIZEOF_* config macros xdelta3.h expects from autoconf.
                listOf(
                    "/TC",
                    "/D", "SECONDARY_DJW=1", "/D", "SECONDARY_FGK=1",
                    "/D", "SIZEOF_SIZE_T=8", "/D", "SIZEOF_UNSIGNED_INT=4",
                    "/D", "SIZEOF_UNSIGNED_LONG=4", "/D", "SIZEOF_UNSIGNED_LONG_LONG=8"
                )
            )
            is GccCompatibleToolChain -> compileTask.compilerArgs.addAll(
                // - C11 (gnu11) so xdelta3.h's static_assert() and POSIX decls (lseek/read/write) work.
                // - Enable DJW/FGK secondary compressors (off by default upstream); LZMA is NOT
                //   enabled (would need liblzma).
                // - Supply the SIZEOF_* config macros xdelta3.h expects from autoconf, derived from
                //   the compiler's own builtins so the values are correct on every ABI (incl. 32-bit
                //   Android where long/size_t are 4 bytes).
                listOf(
                    "-x", "c", "-std=gnu11",
                    "-DSECONDARY_DJW=1", "-DSECONDARY_FGK=1",
                    "-DSIZEOF_SIZE_T=__SIZEOF_SIZE_T__",
                    "-DSIZEOF_UNSIGNED_INT=__SIZEOF_INT__",
                    "-DSIZEOF_UNSIGNED_LONG=__SIZEOF_LONG__",
                    "-DSIZEOF_UNSIGNED_LONG_LONG=__SIZEOF_LONG_LONG__"
                )
            )
            else -> Unit
        }
    }
}
