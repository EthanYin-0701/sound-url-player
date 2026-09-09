import com.github.javaparser.printer.concretesyntaxmodel.CsmElement.token
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.changelog")
    id("org.jetbrains.intellij.platform")
}

// jvm-default=no-compatibility：避免 Kotlin 为实现类生成「接口默认方法转发桩」。
// 否则 ToolWindowFactory 等 Kotlin 接口(方法均为 JVM default)的未覆写默认方法
// 会以字节码 override/invoke 形式被 pluginVerifier 报 deprecated/experimental 警告
// （2026-09-08 已实测：all 与 no-compatibility 均使桩方法归零、告警清零；
//   no-compatibility 为 Kotlin 2.3 新选项名且无弃用警告，见 AGENTS.md §构建）。
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-jvm-default=no-compatibility")
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation(libs.junit)
    implementation(libs.kotlinx.serialization.json)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.5")
        testFramework(TestFrameworkType.Platform)
        // Add plugin dependencies for compilation here, for example:
        // bundledPlugin("com.intellij.java")
    }
}

tasks {
    publishPlugin {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}
