package com.technologyfrontier.qide.plugin.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import com.intellij.openapi.util.NotNullLazyValue
import com.technologyfrontier.qide.application.CircuitDigest
import com.technologyfrontier.qide.domain.InputCheck
import com.technologyfrontier.qide.domain.RunInputValidator
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.plugin.settings.QosctlSettings
import com.technologyfrontier.qide.plugin.ui.QuantumUi
import org.jdom.Element
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class QuantumRunConfigurationType : ConfigurationTypeBase(
    ID,
    "量子回路",
    "量子OS上で回路JSONを qosctl run で実行します",
    NotNullLazyValue.createValue { AllIcons.RunConfigurations.Application },
) {
    init {
        addFactory(QuantumRunConfigurationFactory(this))
    }

    companion object {
        const val ID = "QuantumCircuitRunConfiguration"
    }
}

class QuantumRunConfigurationFactory(type: QuantumRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = QuantumRunConfigurationType.ID
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        QuantumRunConfiguration(project, this, "量子回路")
}

/**
 * 量子回路の実行構成（IDE-02）。
 *
 * 永続化するのは回路パスと shots のみ。seed は保存しない（共有の実行構成にシードを保存しない、
 * 既定ではシードを永続化しない：機能設計書 3章）。seed はこの IDE セッション中のメモリにのみ保持する。
 */
class QuantumRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<Any>(project, factory, name) {

    var circuitPath: String = ""
    var shotsText: String = ""

    /** 永続化しない。 */
    @Transient
    var seedText: String = ""

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = QuantumRunSettingsEditor(project)

    override fun readExternal(element: Element) {
        super.readExternal(element)
        circuitPath = JDOMExternalizerUtil.readField(element, FIELD_CIRCUIT).orEmpty()
        shotsText = JDOMExternalizerUtil.readField(element, FIELD_SHOTS).orEmpty()
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, FIELD_CIRCUIT, circuitPath)
        JDOMExternalizerUtil.writeField(element, FIELD_SHOTS, shotsText)
        // seed は意図的に書き出さない。
    }

    override fun clone(): RunConfiguration {
        val copy = super.clone() as QuantumRunConfiguration
        copy.circuitPath = circuitPath
        copy.shotsText = shotsText
        copy.seedText = seedText
        return copy
    }

    override fun suggestedName(): String? = circuitPath.takeIf { it.isNotBlank() }?.let {
        runCatching { Path.of(it).fileName?.toString() }.getOrNull()
    }

    /** 入力値を検証して実行要求を作る。問題があれば例外。 */
    @Throws(RuntimeConfigurationError::class)
    fun buildRequest(): RunRequest {
        val circuit = try {
            Path.of(circuitPath.trim())
        } catch (e: InvalidPathException) {
            throw RuntimeConfigurationError("回路ファイルのパスが不正です。")
        }
        if (circuitPath.isBlank()) throw RuntimeConfigurationError("回路ファイルを指定してください。")
        // 回路パスは絶対化する。相対パスはプロジェクトの基準ディレクトリーから解決する。
        val base = project.basePath?.let { Path.of(it) }
        val absolute = when {
            circuit.isAbsolute -> circuit.normalize()
            base != null -> base.resolve(circuit).normalize()
            else -> throw RuntimeConfigurationError("回路ファイルは絶対パスで指定してください。")
        }
        if (!Files.isRegularFile(absolute)) throw RuntimeConfigurationError("回路ファイルが見つかりません: $absolute")
        val shots = when (val check = RunInputValidator.shots(shotsText)) {
            is InputCheck.Ok -> check.value
            is InputCheck.Invalid -> throw RuntimeConfigurationError(check.message)
        }
        val seed = when (val check = RunInputValidator.seed(seedText)) {
            is InputCheck.Ok -> check.value
            is InputCheck.Invalid -> throw RuntimeConfigurationError(check.message)
        }
        return RunRequest(absolute, shots, seed)
    }

    override fun checkConfiguration() {
        buildRequest()
        QosctlSettings.getInstance().resolve().problem?.let { throw RuntimeConfigurationError(it) }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (!QuantumUi.isTrusted(project)) {
            throw ExecutionException("このプロジェクトは信頼されていません。信頼済みプロジェクトでのみ qosctl を起動します。")
        }
        QosctlSettings.getInstance().resolve().problem?.let { throw ExecutionException(it) }
        val request = try {
            buildRequest()
        } catch (e: RuntimeConfigurationError) {
            throw ExecutionException(e.localizedMessage)
        }
        val digest = try {
            CircuitDigest.of(Files.readAllBytes(request.circuit))
        } catch (e: IOException) {
            throw ExecutionException("回路ファイルを読み込めません: ${e.message}")
        }
        return QuantumRunState(project, environment, request, digest)
    }

    private companion object {
        const val FIELD_CIRCUIT = "circuitPath"
        const val FIELD_SHOTS = "shots"
    }
}
