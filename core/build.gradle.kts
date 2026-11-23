// 純Kotlinモジュール: domain / application / qosctl-adapter / json
// IntelliJ Platform に依存しない（機能設計書 6章）。
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("real-qosctl")
    }
    // 空白・日本語を含むパス（T-IDE-06）を扱うため、試験は UTF-8 ロケールで実行する。
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
}

// 実際の qosctl／qosd を相手にする結合試験（scripts/real-qosctl-it.sh から実行）。
val realQosctlTest by tasks.registering(Test::class) {
    description = "Runs integration tests against a real qosctl and quantum service."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("real-qosctl")
    }
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
    listOf("QIDE_QOSCTL", "QOS_SOCKET", "QIDE_QOS_SOCKET_DENIED").forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}
