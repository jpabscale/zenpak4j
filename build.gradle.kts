import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}

// pinned SHAs (also in gradle/libs.versions.toml)
val repak_upstream_sha = "355b5f62f51959c7cc6dd5a51708646ef483065d"
val retoc_upstream_sha = "885a8dae740cb1ce1e41ff2e74f67f9f0c118237"

group = "com.github.jpabscale"
version = "0.1.0-SNAPSHOT"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "maven-publish")

    group = "com.github.jpabscale.zenpak4j"
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    // Use Java 25 toolchain for FFM (java.lang.foreign); Kotlin 2.3.0 supports Java 25
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }
    extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    // detekt configuration — allow snake_case for parity (see docs/mapping.md)
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.projectDir}/config/detekt.yml"))
        buildUponDefaultConfig = false
        parallel = true
    }
    dependencies {
        add("detektPlugins", "io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
    }

    // Publish to local maven for in-process use in automod
    extensions.configure<PublishingExtension> {
        publications {
            register<MavenPublication>("maven") {
                from(components["java"])
                groupId = project.group.toString()
                artifactId = project.name
                version = project.version.toString()
            }
        }
    }

    tasks.named("check") {
        dependsOn("detekt")
    }
    // Workaround for Java 25: detekt 1.23.8 bundles Kotlin 1.9 which cannot parse "25.0.4.1".
    // Fake the java.version system property during detekt execution.
    val original_java_version = System.getProperty("java.version")
    val original_spec_version = System.getProperty("java.specification.version")
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "21"
        doFirst {
            System.setProperty("java.version", "21.0.0")
            System.setProperty("java.specification.version", "21")
        }
        doLast {
            if (original_java_version != null) System.setProperty("java.version", original_java_version) else System.clearProperty("java.version")
            if (original_spec_version != null) System.setProperty("java.specification.version", original_spec_version) else System.clearProperty("java.specification.version")
        }
    }
}

// Fat jars runnable via `java -jar` (no extra classpath)
// repak-cli and retoc-cli already produce fat jars via Jar { from(zipTree(runtimeClasspath)) }
tasks.register<Copy>("repakJar") {
    group = "zenpak4j"
    description = "Builds repak fat jar runnable via java --enable-native-access=ALL-UNNAMED -jar build/libs/repak.jar"
    dependsOn(":repak-cli:jar")
    from(project(":repak-cli").tasks.named<org.gradle.api.tasks.bundling.Jar>("jar").get().archiveFile)
    into(layout.buildDirectory.dir("libs"))
    rename { "repak.jar" }
    // Ensure the jar is executable via `java -jar build/libs/repak.jar` (manifest Main-Class already set in repak-cli)
}
tasks.register<Copy>("retocJar") {
    group = "zenpak4j"
    description = "Builds retoc fat jar runnable via java --enable-native-access=ALL-UNNAMED -jar build/libs/retoc.jar"
    dependsOn(":retoc-cli:jar")
    from(project(":retoc-cli").tasks.named<org.gradle.api.tasks.bundling.Jar>("jar").get().archiveFile)
    into(layout.buildDirectory.dir("libs"))
    rename { "retoc.jar" }
}
tasks.register("fatJars") {
    group = "zenpak4j"
    description = "Builds both repak.jar and retoc.jar fat jars"
    dependsOn("repakJar", "retocJar")
}

// Aggregate check
tasks.register("downloadFixtures") {
    group = "zenpak4j"
    description = "Downloads repak/retoc test fixtures on demand (never committed) from pinned upstream SHAs"
    doLast {
        val log = java.nio.file.Files.createTempFile("download-fixtures", ".log")
        val proc = ProcessBuilder("$rootDir/scripts/download-fixtures.sh")
            .directory(rootDir)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            .redirectErrorStream(true)
            .start()
        val code = proc.waitFor()
        if (code != 0) {
            println(String(java.nio.file.Files.readAllBytes(log)))
            throw GradleException("downloadFixtures failed (exit $code, log: $log)")
        }
        println("Fixtures at $rootDir/build/fixtures")
    }
}
