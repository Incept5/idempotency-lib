plugins {
    // Apply the java-library plugin for API and implementation separation.
    `java-library`
    // publish to nexus
    `maven-publish`
    // Apply the org.jetbrains.kotlin.jvm Plugin to add support for Kotlin.
    alias(libs.plugins.kotlin.jvm)
}

dependencies {

    api(libs.incept5.error.core)
    api(libs.incept5.scheduler.core)
    implementation(libs.incept5.json)

    implementation(platform(libs.jakarta.bom))
    api("jakarta.transaction:jakarta.transaction-api")

    // test dependencies
    // kotest
    testImplementation(enforcedPlatform(libs.kotest.bom))
    testImplementation("io.kotest:kotest-runner-junit5")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // For JitPack compatibility, we need to use the correct group ID format
            // JitPack expects: com.github.{username}.{repository}
            val publishGroupId = rootProject.properties["publishGroupId"]?.toString()
                ?: if (System.getenv("JITPACK") != null) {
                    // When building on JitPack
                    "com.github.incept5.idempotency-lib"
                } else {
                    // For local development
                    "com.github.incept5"
                }

            // Explicitly set the coordinates
            groupId = publishGroupId
            artifactId = "idempotency-core"
            version = project.version.toString()

            from(components["java"])

            // POM information
            pom {
                name.set("idempotency Core")
                description.set("Core functionality for idempotency in Rest Services")
                url.set("https://github.com/incept5/idempotency-lib")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("incept5")
                        name.set("Incept5")
                        email.set("info@incept5.com")
                    }
                }

                // Important for JitPack to resolve dependencies correctly
                scm {
                    connection.set("scm:git:github.com/incept5/idempotency-lib.git")
                    developerConnection.set("scm:git:ssh://github.com/incept5/idempotency-lib.git")
                    url.set("https://github.com/incept5/idempotency-lib/tree/main")
                }
            }
        }
    }
}

// For JitPack compatibility
tasks.register("install") {
    dependsOn(tasks.named("publishToMavenLocal"))
}

// Always publish to local Maven repository after build for local development
tasks.named("build") {
    finalizedBy(tasks.named("publishToMavenLocal"))
}
