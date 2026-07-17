import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    kotlin("multiplatform") version "2.4.10"
}

group = "receipt.print"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    val receiptPrintXCFramework = XCFramework("ReceiptPrint")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target: KotlinNativeTarget ->
        target.binaries.framework {
            baseName = "ReceiptPrint"
            isStatic = true
            receiptPrintXCFramework.add(this)
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
