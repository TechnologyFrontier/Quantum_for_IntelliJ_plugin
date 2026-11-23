package com.technologyfrontier.qide.plugin.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.technologyfrontier.qide.domain.Shots
import javax.swing.JComponent

class QuantumRunSettingsEditor(project: Project) : SettingsEditor<QuantumRunConfiguration>() {

    private val circuitField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("回路JSONを選択"),
        )
    }
    private val shotsField = JBTextField().apply {
        emptyText.text = "既定 ${Shots.DEFAULT_VALUE}（${Shots.MIN}〜${"%,d".format(Shots.MAX)}）"
    }
    private val seedField = JBTextField().apply {
        emptyText.text = "空欄でシードを省略（0〜18446744073709551615）"
    }

    override fun resetEditorFrom(configuration: QuantumRunConfiguration) {
        circuitField.text = configuration.circuitPath
        shotsField.text = configuration.shotsText
        seedField.text = configuration.seedText
    }

    override fun applyEditorTo(configuration: QuantumRunConfiguration) {
        configuration.circuitPath = circuitField.text.trim()
        configuration.shotsText = shotsField.text.trim()
        configuration.seedText = seedField.text.trim()
    }

    override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("回路ファイル:", circuitField)
        .addLabeledComponent("shots:", shotsField)
        .addLabeledComponent("seed:", seedField)
        .addComponentToRightColumn(
            com.intellij.ui.components.JBLabel(
                "<html>seed はこの実行構成と一緒に保存されません（IDE の起動中のみ保持）。<br/>" +
                    "空欄ならシードを渡さず、0 なら --seed 0 を渡します。</html>",
            ).apply { componentStyle = com.intellij.util.ui.UIUtil.ComponentStyle.SMALL },
        )
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel
}
