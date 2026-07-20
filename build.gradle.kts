import groovy.json.JsonSlurper
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

tasks.register("validateCommandFixtures") {
    group = "verification"
    description = "Validates the printer command fixture schema."
    val commandFixtures = layout.projectDirectory.dir("fixtures/commands")
    inputs.dir(commandFixtures)

    doLast {
        fun requiredString(map: Map<*, *>, key: String, location: String): String =
            (map[key] as? String)?.takeIf { it.isNotBlank() }
                ?: error("$location.$key must be a non-blank string")

        val fixtureFiles = commandFixtures.asFile
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "json" }
            .toList()
        check(fixtureFiles.isNotEmpty()) { "Command fixtures must not be empty" }

        val fixtureIds = mutableSetOf<String>()
        fixtureFiles.forEach { fixtureFile ->
            val fixture = JsonSlurper().parse(fixtureFile) as? Map<*, *>
                ?: error("Fixture ${fixtureFile.path} root must be an object")
            val location = "Fixture ${fixtureFile.path}"
            val schemaVersion = fixture["schemaVersion"] as? Number
                ?: error("$location.schemaVersion must be a number")
            check(schemaVersion.toDouble() == 1.0) { "$location.schemaVersion must be 1" }
            val fixtureId = requiredString(fixture, "fixtureId", location)
            check(fixtureIds.add(fixtureId)) { "Fixture id must be unique: $fixtureId" }

            val printer = fixture["printer"] as? Map<*, *>
                ?: error("$location.printer must be an object")
            requiredString(printer, "profileId", "$location.printer")
            val columnsPerLine = printer["columnsPerLine"] as? Number
                ?: error("$location.printer.columnsPerLine must be a positive integer")
            check(columnsPerLine.toInt() > 0 && columnsPerLine.toDouble() == columnsPerLine.toInt().toDouble()) {
                "$location.printer.columnsPerLine must be a positive integer"
            }

            val commands = fixture["commands"] as? List<*>
                ?: error("$location.commands must be an array")
            check(commands.isNotEmpty()) { "$location.commands must not be empty" }
            val operations = mutableSetOf<String>()
            commands.forEachIndexed { index, rawCommand ->
                val command = rawCommand as? Map<*, *>
                    ?: error("$location.commands[$index] must be an object")
                val commandLocation = "$location.commands[$index]"
                val operation = requiredString(command, "operation", commandLocation)
                check(operations.add(operation)) { "$location operation must be unique: $operation" }
                val supported = if (command.containsKey("supported")) {
                    command["supported"] as? Boolean
                        ?: error("$commandLocation.supported must be a boolean")
                } else {
                    true
                }
                val bytesHex = command["bytesHex"] as? String
                check(!supported || bytesHex != null) {
                    "$commandLocation.bytesHex is required for supported commands"
                }
                if (!supported) {
                    requiredString(command, "diagnosticCode", commandLocation)
                }
                bytesHex?.let { value ->
                    check(value.length % 2 == 0 && value.matches(Regex("[0-9a-f]*"))) {
                        "$commandLocation.bytesHex must be even-length lowercase hex"
                    }
                }
                val repeatByteHex = command["repeatByteHex"]
                val repeatCount = command["repeatCount"]
                check((repeatByteHex == null) == (repeatCount == null)) {
                    "$commandLocation repeatByteHex and repeatCount must be specified together"
                }
                if (repeatByteHex != null && repeatCount != null) {
                    check(repeatByteHex is String && repeatByteHex.matches(Regex("[0-9a-f]{2}"))) {
                        "$commandLocation.repeatByteHex must be one lowercase hex byte"
                    }
                    val count = repeatCount as? Number
                        ?: error("$commandLocation.repeatCount must be a positive integer")
                    check(count.toInt() > 0 && count.toDouble() == count.toInt().toDouble()) {
                        "$commandLocation.repeatCount must be a positive integer"
                    }
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
    description = "Runs core ABI, encoding fixture, JVM, and iOS Simulator checks."
    dependsOn(
        "validateCommandFixtures",
        "verifyCp932Table",
        ":receipt-core:checkKotlinAbi",
        ":receipt-command-escpos:checkKotlinAbi",
        ":receipt-command-star:checkKotlinAbi",
        ":receipt-core:jvmTest",
        ":receipt-core:iosSimulatorArm64Test",
        ":receipt-command-escpos:jvmTest",
        ":receipt-command-escpos:iosSimulatorArm64Test",
        ":receipt-command-star:jvmTest",
        ":receipt-command-star:iosSimulatorArm64Test",
    )
}

tasks.register("publishAllToBuildRepository") {
    group = "publishing"
    description = "Publishes every KMP module to build/maven-repository without touching ~/.m2."
    dependsOn(
        ":receipt-core:publishAllPublicationsToBuildRepository",
        ":receipt-command-escpos:publishAllPublicationsToBuildRepository",
        ":receipt-command-star:publishAllPublicationsToBuildRepository",
    )
}
