import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

allprojects {
    group = "receipt-kmp"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "build"
                    url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
                }
            }
        }
    }
}

val cp932GeneratorClasses = layout.buildDirectory.dir("cp932-generator/classes")
val regeneratedCp932Table = layout.buildDirectory.file("cp932-generator/Cp932MappingTable.kt")

val compileCp932TableGenerator by tasks.registering(JavaCompile::class) {
    source(layout.projectDirectory.file("tools/GenerateCp932Table.java"))
    destinationDirectory.set(cp932GeneratorClasses)
    classpath = files()
    sourceCompatibility = "17"
    targetCompatibility = "17"
    options.release.set(17)
}

val generateCp932BootstrapTable by tasks.registering(JavaExec::class) {
    dependsOn(compileCp932TableGenerator)
    classpath = files(cp932GeneratorClasses)
    mainClass.set("GenerateCp932Table")
    args("--jdk-bootstrap", regeneratedCp932Table.get().asFile.absolutePath)
    inputs.file(layout.projectDirectory.file("tools/GenerateCp932Table.java"))
    outputs.file(regeneratedCp932Table)
}

tasks.register<JavaExec>("verifyCp932Table") {
    group = "verification"
    description = "Regenerates the JDK-bootstrap CP932 table and verifies an exact source match."
    dependsOn(compileCp932TableGenerator)
    val committedTable = layout.projectDirectory.file(
        "receipt-core/src/commonMain/kotlin/receiptdsl/encoding/Cp932MappingTable.kt",
    )
    classpath = files(cp932GeneratorClasses)
    mainClass.set("GenerateCp932Table")
    args("--verify-jdk-bootstrap", committedTable.asFile.absolutePath)
    inputs.file(layout.projectDirectory.file("tools/GenerateCp932Table.java"))
    inputs.file(committedTable)
}

tasks.register("coreCheck") {
    group = "verification"
    description = "Runs receipt-core ABI, JVM, and iOS Simulator checks."
    dependsOn(
        "verifyCp932Table",
        ":receipt-core:checkKotlinAbi",
        ":receipt-core:jvmTest",
        ":receipt-core:iosSimulatorArm64Test",
    )
}

tasks.register("publishAllToBuildRepository") {
    group = "publishing"
    description = "Publishes every KMP module to build/maven-repository without touching ~/.m2."
    dependsOn(":receipt-core:publishAllPublicationsToBuildRepository")
}
