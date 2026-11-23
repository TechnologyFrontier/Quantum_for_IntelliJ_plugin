package com.technologyfrontier.qide.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * IDE 全体のローカル設定（利用者ごと、共有・ローミングしない）。機能設計書 4章「互換性と設定」。
 *
 * CLI パスは導入側が管理する。導入時に IDE の VM オプションで
 * `-Dqide.qosctl.path=/絶対/パス/qosctl` を指定した場合はそれを優先し、画面からは変更できない。
 * プロジェクト設定では差し替えない（プロジェクト単位の設定項目を持たない）。
 */
@Service(Service.Level.APP)
@State(name = "QuantumOsIdeSettings", storages = [Storage(value = "quantumOsIde.xml", roamingType = RoamingType.DISABLED)])
class QosctlSettings : SimplePersistentStateComponent<QosctlSettings.LocalState>(LocalState()) {

    class LocalState : BaseState() {
        var cliPath by string(DEFAULT_CLI_PATH)
        var workingDirectory by string("")
    }

    /** 解決済みの CLI 設定。[problem] が null なら外部実行可能。 */
    data class ResolvedCli(
        val executable: Path?,
        val workingDirectory: Path?,
        val managedByInstaller: Boolean,
        val problem: String?,
    )

    val managedCliPath: String? get() = System.getProperty(MANAGED_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
    val managedWorkingDirectory: String? get() = System.getProperty(MANAGED_WORKDIR_PROPERTY)?.takeIf { it.isNotBlank() }

    fun resolve(): ResolvedCli {
        val managed = managedCliPath
        val rawPath = managed ?: state.cliPath.orEmpty().trim()
        val rawWorkDir = managedWorkingDirectory ?: state.workingDirectory.orEmpty().trim()
        val workDir = rawWorkDir.takeIf { it.isNotEmpty() }?.let(::toPathOrNull)
            ?: Path.of(System.getProperty("user.home"))
        val executable = toPathOrNull(rawPath)
        val problem = when {
            rawPath.isEmpty() -> "qosctl のパスが設定されていません。"
            executable == null || !executable.isAbsolute -> "qosctl のパスは絶対パスで指定してください: $rawPath"
            !Files.isRegularFile(executable) -> "qosctl が見つかりません: $rawPath"
            !Files.isExecutable(executable) -> "qosctl を実行できません（実行権限がありません）: $rawPath"
            !workDir.isAbsolute || !Files.isDirectory(workDir) -> "作業ディレクトリーが存在しません: $workDir"
            else -> null
        }
        return ResolvedCli(executable?.takeIf { it.isAbsolute }, workDir, managed != null, problem)
    }

    private fun toPathOrNull(text: String): Path? = try {
        Path.of(text)
    } catch (e: InvalidPathException) {
        null
    }

    companion object {
        const val DEFAULT_CLI_PATH = "/usr/bin/qosctl"
        const val MANAGED_PATH_PROPERTY = "qide.qosctl.path"
        const val MANAGED_WORKDIR_PROPERTY = "qide.qosctl.workdir"

        fun getInstance(): QosctlSettings = service()
    }
}
