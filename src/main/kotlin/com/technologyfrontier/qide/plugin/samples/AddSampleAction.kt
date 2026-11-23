package com.technologyfrontier.qide.plugin.samples

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.technologyfrontier.qide.plugin.ui.QuantumUi
import com.technologyfrontier.qide.samples.Sample
import com.technologyfrontier.qide.samples.SampleFileNames
import com.technologyfrontier.qide.samples.SampleNaming
import java.io.IOException

/**
 * サンプルを追加（IDE-04）。回路 JSON と説明を、利用者が指定したプロジェクト配下のディレクトリーへ複製する。
 * 既存ファイルは上書きせず別名にする。外部コマンドの起動・自動実行はしない。
 */
class AddSampleAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val step = object : BaseListPopupStep<Sample>("追加するサンプル", Sample.entries.toList()) {
            override fun getTextFor(value: Sample): String = "${value.title} — ${value.summary}"
            override fun onChosen(selectedValue: Sample, finalChoice: Boolean): PopupStep<*>? =
                doFinalStep { chooseTargetAndCopy(project, selectedValue) }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }

    private fun chooseTargetAndCopy(project: Project, sample: Sample) {
        val projectDir = project.guessProjectDir() ?: run {
            QuantumUi.notify(project, "サンプルを追加できません", "プロジェクトのディレクトリーが見つかりません。", NotificationType.WARNING)
            return
        }
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("サンプルの追加先（プロジェクト配下）")
            .withRoots(projectDir)
        val target = FileChooser.chooseFile(descriptor, project, projectDir) ?: return
        if (!VfsUtilCore.isAncestor(projectDir, target, false)) {
            QuantumUi.notify(project, "サンプルを追加できません", "プロジェクト配下のディレクトリーを選択してください。", NotificationType.WARNING)
            return
        }
        val circuitText = readResource(sample.circuitResource)
        val guideText = readResource(sample.guideResource)
        if (circuitText == null || guideText == null) {
            QuantumUi.notify(project, "サンプルを追加できません", "同梱サンプルを読み込めませんでした。", NotificationType.ERROR)
            return
        }

        var created: Pair<VirtualFile, SampleFileNames>? = null
        try {
            WriteCommandAction.writeCommandAction(project).withName("サンプルを追加").run<IOException> {
                val names = SampleNaming.choose(sample) { target.findChild(it) != null }
                val circuit = target.createChildData(this, names.circuit)
                circuit.setBinaryContent(circuitText.toByteArray(Charsets.UTF_8))
                val guide = target.createChildData(this, names.guide)
                guide.setBinaryContent(guideText.toByteArray(Charsets.UTF_8))
                created = circuit to names
            }
        } catch (e: IOException) {
            QuantumUi.notify(project, "サンプルを追加できません", QuantumUi.escape(e.message ?: "書き込みに失敗しました"), NotificationType.ERROR)
            return
        }
        val (circuitFile, names) = created ?: return
        val renamed = names.circuit != "${sample.baseName}.circuit.json"
        QuantumUi.notify(
            project,
            "サンプル「${sample.title}」を追加しました",
            QuantumUi.escape("${names.circuit} と ${names.guide}") +
                (if (renamed) "<br/>同名のファイルがあったため、別名で追加しました（既存ファイルは変更していません）。" else "") +
                "<br/>実行は自動では行いません。検証・実行は明示的に操作してください。",
            NotificationType.INFORMATION,
        )
        FileEditorManager.getInstance(project).openFile(circuitFile, true)
    }

    private fun readResource(path: String): String? =
        AddSampleAction::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
}
