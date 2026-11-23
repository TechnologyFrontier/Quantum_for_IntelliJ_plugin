package com.technologyfrontier.qide.plugin.editor

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.technologyfrontier.qide.domain.CircuitStructure

object CircuitFiles {
    const val SUFFIX = ".circuit.json"

    /** 回路ファイルの命名規約: *.circuit.json（スキーマ・構造チェック・実行構成の対象）。 */
    fun isCircuitFile(file: VirtualFile): Boolean = !file.isDirectory && file.name.endsWith(SUFFIX)

    /** 検証・実行の対象にできる JSON ファイルか（任意の .json を回路として扱える）。 */
    fun isJsonFile(file: VirtualFile): Boolean = !file.isDirectory && file.extension.equals("json", ignoreCase = true)
}

/** 回路 JSON のスキーマ（エディター上の補完・指摘。最終判定は量子サービス）。 */
class CircuitSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(CircuitSchemaProvider)

    private object CircuitSchemaProvider : JsonSchemaFileProvider {
        override fun isAvailable(file: VirtualFile): Boolean = CircuitFiles.isCircuitFile(file)
        override fun getName(): String = "量子OS 回路（schema_version 1）"
        override fun getSchemaFile(): VirtualFile? =
            JsonSchemaProviderFactory.getResourceFile(CircuitSchemaProviderFactory::class.java, "/schema/circuit.schema.json")
        override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
    }
}

/**
 * 回路の構造チェック（domain の CircuitStructure を利用）。補助的な指摘であり、
 * 有効性の最終判定は「回路を検証」（qosctl validate）で行う。
 */
class CircuitStructureInspection : LocalInspectionTool() {

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        if (file !is JsonFile) return null
        val virtualFile = file.virtualFile ?: return null
        if (!CircuitFiles.isCircuitFile(virtualFile)) return null
        val findings = CircuitStructure.check(file.text)
        if (findings.isEmpty()) return null
        return findings.map { finding ->
            val anchor = locate(file, finding.path) ?: file
            val type = when (finding.severity) {
                CircuitStructure.Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                CircuitStructure.Severity.WARNING -> ProblemHighlightType.WEAK_WARNING
            }
            manager.createProblemDescriptor(anchor, finding.message, isOnTheFly, emptyArray(), type)
        }.toTypedArray()
    }

    /** "/operations/3/qubits" のような位置を PSI 要素に対応付ける。見つからなければ最も近い親。 */
    private fun locate(file: JsonFile, path: String): PsiElement? {
        var current: JsonValue = file.topLevelValue ?: return null
        var best: PsiElement = current
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            val next: JsonValue? = when (val node = current) {
                is JsonObject -> node.findProperty(segment)?.let { prop ->
                    best = prop.nameElement
                    prop.value
                }
                is JsonArray -> segment.toIntOrNull()?.let { node.valueList.getOrNull(it) }
                else -> null
            }
            current = next ?: return best
            best = current
        }
        return best
    }
}
