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
    useJUnitPlatform()
    // 空白・日本語を含むパス（T-IDE-06）を扱うため、試験は UTF-8 ロケールで実行する。
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
}
