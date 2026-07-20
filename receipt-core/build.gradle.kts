import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

kotlin {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {}

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val receiptCoreXCFramework = XCFramework("ReceiptCore")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target: KotlinNativeTarget ->
        target.binaries.framework {
            baseName = "ReceiptCore"
            isStatic = true
            receiptCoreXCFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.collections.immutable)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
