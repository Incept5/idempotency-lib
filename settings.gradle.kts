rootProject.name = "idempotency-lib"
include("idempotency-core")
include("idempotency-quarkus")
include("test-quarkus-idempotency")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
