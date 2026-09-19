import org.gradle.api.tasks.testing.Test
plugins {
    `java-library`
}

tasks.withType<Test> {
    maxHeapSize = "2g"
    jvmArgs("-Xmx2g")
}

dependencies {
    api("com.fasterxml.jackson.core:jackson-databind:2.22.2")  // approved: Zen JSON node API
    api(project(":repak"))
    api(project(":oodle-loader"))
    api("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.github.luben:zstd-jni:1.5.7-3") // compress only
    implementation("com.qyntrax:unzstd:0.1.0") // pure-JVM decompress (works on win-arm64)
    implementation("org.lz4:lz4-java:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
