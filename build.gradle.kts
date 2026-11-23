import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IntelliJ のインターフェースのデフォルトメソッドを橋渡しで再定義しない（非推奨／実験的 API の誤検出を避ける）。
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.modules.json")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        // Marketplace へ接続せず、対象 IDE に同梱のプラグインだけで依存を解決する（オフライン検証）。
        freeArgs = listOf("-offline")
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, providers.gradleProperty("platformVersion"))
        }
    }
    // 外部サービス（Marketplace）への自動公開・署名は行わない。配布はローカルZIP導入（基本設計書 3章）。
    buildSearchableOptions = false
}

tasks.test {
    // 空白・日本語を含むパスを扱うため、試験は UTF-8 ロケールで実行する。
    environment("LC_ALL", "C.UTF-8")
    environment("LANG", "C.UTF-8")
}
