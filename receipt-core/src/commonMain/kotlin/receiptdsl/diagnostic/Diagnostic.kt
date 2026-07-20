package receiptdsl.diagnostic

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

public enum class DiagnosticSeverity {
    Warning,
    Error,
}

public data class NodePath(
    val ticketIndex: Int? = null,
    val childIndexes: PersistentList<Int> = persistentListOf(),
)

public interface Diagnostic {
    public val code: String
    public val severity: DiagnosticSeverity
    public val message: String
    public val path: NodePath?
}

public interface CompilationIssue : Diagnostic

public data class UnsupportedGlyph(
    val codePoint: Int,
    val source: String,
    override val severity: DiagnosticSeverity,
    override val path: NodePath?,
    override val code: String = "UNSUPPORTED_GLYPH",
    override val message: String = "Code point U+${codePoint.toString(16).uppercase()} cannot be encoded",
) : CompilationIssue

public data class UnsupportedFeature(
    val feature: String,
    val profileId: String,
    override val severity: DiagnosticSeverity,
    override val path: NodePath?,
    override val code: String = "UNSUPPORTED_FEATURE",
    override val message: String = "$feature is unsupported by $profileId",
) : CompilationIssue

public data class InvalidLayout(
    val reason: String,
    override val path: NodePath?,
    override val code: String = "INVALID_LAYOUT",
    override val severity: DiagnosticSeverity = DiagnosticSeverity.Error,
    override val message: String = reason,
) : CompilationIssue

public data class ValueClamped(
    val property: String,
    val requested: Int,
    val actual: Int,
    override val path: NodePath?,
    override val code: String = "VALUE_CLAMPED",
    override val severity: DiagnosticSeverity = DiagnosticSeverity.Warning,
    override val message: String = "$property was clamped from $requested to $actual",
) : CompilationIssue

public data class CompilationResult<T : Any>(
    val value: T?,
    val diagnostics: PersistentList<Diagnostic>,
) {
    public val isSuccess: Boolean
        get() = value != null && diagnostics.none { it.severity == DiagnosticSeverity.Error }
}
