package com.technologyfrontier.qide.plugin.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.technologyfrontier.qide.plugin.editor.CircuitFiles

/** エディターやプロジェクトビューの回路ファイル（*.circuit.json）から実行構成を作る。実行は明示操作のみ。 */
class QuantumRunConfigurationProducer : LazyRunConfigurationProducer<QuantumRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(QuantumRunConfigurationType::class.java).configurationFactories.first()

    override fun setupConfigurationFromContext(
        configuration: QuantumRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!CircuitFiles.isCircuitFile(file)) return false
        configuration.circuitPath = file.path
        configuration.setGeneratedName()
        return true
    }

    override fun isConfigurationFromContext(configuration: QuantumRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.location?.virtualFile ?: return false
        return CircuitFiles.isCircuitFile(file) && configuration.circuitPath == file.path
    }
}
