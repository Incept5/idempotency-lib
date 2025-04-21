plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.quarkus)
}

dependencies {

    implementation(project(":idempotency-quarkus"))
    api(project(":idempotency-core"))
    implementation(libs.incept5.error.core)

    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    runtimeOnly("io.quarkus:quarkus-arc")
    runtimeOnly("io.quarkus:quarkus-config-yaml")
    runtimeOnly("io.quarkus:quarkus-flyway")
    runtimeOnly("io.quarkus:quarkus-jdbc-postgresql")
    runtimeOnly(libs.quarkus.rest.jackson)

    api("jakarta.inject:jakarta.inject-api")
    api("jakarta.ws.rs:jakarta.ws.rs-api")
    testImplementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation("com.squareup.okhttp3:okhttp")
    testImplementation("org.eclipse.microprofile.config:microprofile-config-api")
    testImplementation("org.hamcrest:hamcrest")
    testImplementation("org.junit.jupiter:junit-jupiter-api")

    testImplementation("io.quarkus:quarkus-junit5")
    testRuntimeOnly("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation(libs.incept5.http)
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}
