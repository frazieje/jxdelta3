plugins {
    java
}

group = "com.frazieje"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
    val xdelta3LibDir = project(":xdelta3").layout.buildDirectory.dir("lib/main/debug")
    jvmArgs = listOf(
        "-Djava.library.path=${xdelta3LibDir.get().asFile.absolutePath}",
        // Allows the FD API to reflect FileDescriptor.fd on JDK 9+ (tests only).
        "--add-opens", "java.base/java.io=ALL-UNNAMED"
    )
    dependsOn(":xdelta3:linkDebug")
}
