plugins {
    `java-library`
}

dependencies {
    api(project(":repak"))
    api(project(":retoc"))
    api(project(":actions"))
    api(project(":oodle-loader"))
    api("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.github.luben:zstd-jni:1.5.7-3")
    implementation("org.lz4:lz4-java:1.8.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
