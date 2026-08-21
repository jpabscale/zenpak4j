plugins {
    `java-library`
}

dependencies {
    api("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
