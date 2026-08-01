import groovy.json.JsonSlurper

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
    }
}

// rustls-platform-verifier (the TLS cert verifier used by the Rust core's
// reqwest) needs its Kotlin support classes in the APK. The AAR ships inside
// the rustls-platform-verifier-android crate as a local maven repository, so
// locate that crate through cargo and add its repo. See MOBILE.md.
fun rustlsPlatformVerifierMavenRepo(): String {
    val metadataJson = providers.exec {
        workingDir = File(rootDir, "../../")
        commandLine("cargo", "metadata", "--format-version", "1")
    }.standardOutput.asText.get()

    @Suppress("UNCHECKED_CAST")
    val metadata = JsonSlurper().parseText(metadataJson) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val packages = metadata["packages"] as List<Map<String, Any?>>
    val manifestPath = packages
        .first { it["name"] == "rustls-platform-verifier-android" }["manifest_path"] as String
    return File(File(manifestPath).parentFile, "maven").path
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri(rustlsPlatformVerifierMavenRepo()) }
    }
}

tasks.register("clean").configure {
    delete("build")
}
