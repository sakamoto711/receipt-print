import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

tasks.withType<Test>().configureEach {
    systemProperty("receipt.fixtures.dir", rootProject.file("fixtures").absolutePath)
}

kotlin {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {}

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
        // Exercise the Flutter Android channel codec on the JVM without an Android
        // device: the codec only depends on this module's public DTOs.
        jvmTest {
            kotlin.srcDir("../packages/receipt_print/android/src/main/kotlin/dev/receiptprint/receipt_print/codec")
            kotlin.srcDir("../packages/receipt_print/android/src/test/kotlin")
            dependencies { implementation(kotlin("test-junit")) }
        }
        commonMain.dependencies {
            implementation(project(":receipt-core"))
            implementation(project(":receipt-command-escpos"))
            implementation(project(":receipt-command-star"))
            implementation(project(":receipt-receiptline"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
