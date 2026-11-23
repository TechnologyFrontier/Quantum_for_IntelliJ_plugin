package com.technologyfrontier.qide.plugin

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.technologyfrontier.qide.plugin.editor.CircuitStructureInspection
import com.technologyfrontier.qide.plugin.run.QuantumRunConfiguration
import com.technologyfrontier.qide.plugin.run.QuantumRunConfigurationType
import org.jdom.Element

/** IDE 上での結線の確認（ヘッドレス）。 */
class QuantumPluginPlatformTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // JSON スキーマはプラグイン JAR から読み込まれるため、試験環境での読み取りを許可する。
        val plugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("com.technologyfrontier.quantum-os-ide"),
        )!!
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, plugin.pluginPath.toString())
    }

    private fun newConfiguration(): QuantumRunConfiguration {
        val factory = ConfigurationTypeUtil.findConfigurationType(QuantumRunConfigurationType::class.java).configurationFactories.first()
        return factory.createTemplateConfiguration(project) as QuantumRunConfiguration
    }

    /** 共有の実行構成にシードを保存しない（機能設計書 3章）。 */
    fun testSeedIsNotPersisted() {
        val configuration = newConfiguration().apply {
            circuitPath = "/tmp/bell.circuit.json"
            shotsText = "2048"
            seedText = "18446744073709551615"
        }
        val element = Element("configuration")
        configuration.writeExternal(element)
        val xml = com.intellij.openapi.util.JDOMUtil.write(element)
        assertFalse(xml, xml.contains("18446744073709551615"))
        assertTrue(xml, xml.contains("2048"))

        val restored = newConfiguration()
        restored.readExternal(element)
        assertEquals("/tmp/bell.circuit.json", restored.circuitPath)
        assertEquals("2048", restored.shotsText)
        assertEquals("", restored.seedText)

        val copy = configuration.clone() as QuantumRunConfiguration
        assertEquals("18446744073709551615", copy.seedText)
    }

    fun testStructureInspectionReportsGateAfterMeasure() {
        myFixture.enableInspections(CircuitStructureInspection())
        myFixture.configureByText(
            "bad.circuit.json",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"measure","qubits":[0]},{"op":"x","qubits":[0]}]}""",
        )
        val messages = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WEAK_WARNING }.map { it.description }
        assertTrue(messages.toString(), messages.any { it?.contains("測定の後にゲート") == true })
    }

    fun testValidSampleHasNoStructureFindings() {
        myFixture.enableInspections(CircuitStructureInspection())
        val text = javaClass.getResourceAsStream("/samples/bell.circuit.json")!!.readBytes().toString(Charsets.UTF_8)
        myFixture.configureByText("bell.circuit.json", text)
        val messages = myFixture.doHighlighting().filter { it.inspectionToolId == "QuantumCircuitStructure" }
        assertEmpty(messages)
    }

    fun testCircuitSchemaIsBundled() {
        assertNotNull(javaClass.getResource("/schema/circuit.schema.json"))
    }
}
