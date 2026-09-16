plugins {
    `java-library`
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation(project(":oodle-loader"))
    implementation("com.github.luben:zstd-jni:1.5.7-3") // compress only
    implementation("com.qyntrax:unzstd:0.1.0") // pure-JVM decompress (works on win-arm64)
    implementation("org.lz4:lz4-java:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
