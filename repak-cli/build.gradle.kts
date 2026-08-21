plugins {
    application
}

dependencies {
    implementation(project(":actions"))
    implementation(project(":oodle-loader"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
}

application {
    mainClass.set("com.github.jpabscale.zenpak4j.repak_cli.MainKt")
}

tasks.named<org.gradle.api.tasks.bundling.Jar>("jar") {
    dependsOn(project(":repak").tasks.named("jar"))
    dependsOn(project(":retoc").tasks.named("jar"))
    dependsOn(project(":oodle-loader").tasks.named("jar"))
    manifest {
        attributes["Main-Class"] = "com.github.jpabscale.zenpak4j.repak_cli.MainKt"
    }
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    }
}
