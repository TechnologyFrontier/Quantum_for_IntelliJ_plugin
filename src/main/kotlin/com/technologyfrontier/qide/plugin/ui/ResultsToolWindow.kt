package com.technologyfrontier.qide.plugin.ui

import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.technologyfrontier.qide.application.CircuitDigest
import com.technologyfrontier.qide.application.RerunPlan
import com.technologyfrontier.qide.application.RunState
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.plugin.bootstrap.QuantumProjectService
import com.technologyfrontier.qide.plugin.run.QuantumRunConfiguration
import com.technologyfrontier.qide.plugin.run.QuantumRunConfigurationType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.io.IOException
import java.nio.file.Files
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class QuantumResultsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ResultsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        Disposer.register(content, panel)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * 結果ビュー（IDE-03）。観測回数の表と比率の棒、測定ビット順、シード等を表示し、再実行・停止・明示保存を提供する。
 * 表示する結果は直前の正常結果で、失敗・停止では消さない。結果とシードは既定ではメモリにのみ保持する。
 */
class ResultsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = QuantumProjectService.getInstance(project)
    private val statusLabel = JBLabel("待機中：実行構成「量子回路」から実行してください。")
    private val metaLabel = JBLabel(" ")
    private val errorArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.RED
        isVisible = false
    }
    private val model = CountsTableModel()
    private val table = JBTable(model).apply {
        setShowGrid(false)
        columnModel.getColumn(3).cellRenderer = RatioBarRenderer()
        columnModel.getColumn(0).preferredWidth = 120
        columnModel.getColumn(3).preferredWidth = 240
    }
    private var displayed: RunState.Succeeded? = null
    private val subscription: AutoCloseable

    init {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6)
            // BoxLayout は部品ごとの alignmentX を揃えないと位置がずれるため、すべて左寄せにする。
            listOf(statusLabel, metaLabel, errorArea).forEach {
                it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
                add(it)
            }
        }
        val body = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        setContent(body)
        val group = DefaultActionGroup(RerunAction(), StopAction(), SaveResultAction(), *listOfNotNull(
            ActionManager.getInstance().getAction("QuantumIde.CheckStatus"),
            ActionManager.getInstance().getAction("QuantumIde.MaintenanceGuide"),
        ).toTypedArray())
        val toolbar = ActionManager.getInstance().createActionToolbar("QuantumResults", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        subscription = service.coordinator.addListener { state -> QuantumUi.onUi(project) { render(state) } }
        render(service.coordinator.state)
    }

    private fun render(state: RunState) {
        service.coordinator.lastSuccess?.let { if (it != displayed) showResult(it) }
        errorArea.isVisible = false
        when (state) {
            RunState.Idle -> statusLabel.text = "待機中：実行構成「量子回路」から実行してください。"
            is RunState.Running -> statusLabel.text = "実行中：${state.attempt.request.circuit.fileName}（shots ${state.attempt.request.shots}）"
            is RunState.Succeeded -> statusLabel.text = "成功：${state.attempt.request.circuit.fileName}"
            is RunState.Failed -> {
                statusLabel.text = "失敗：${state.attempt.request.circuit.fileName}" +
                    (if (displayed != null) "（表は直前の正常結果）" else "")
                errorArea.text = QuantumUi.failureText(state.failure)
                errorArea.isVisible = true
            }
            is RunState.Stopped -> statusLabel.text =
                "停止：IDE で停止しました。サービス側の計算停止は確定していません。" +
                    (if (displayed != null) "（表は直前の正常結果）" else "")
        }
    }

    private fun showResult(succeeded: RunState.Succeeded) {
        displayed = succeeded
        val r = succeeded.result
        model.update(r)
        metaLabel.text = "<html>shots: ${r.shots}　seed: ${r.seed}　measured_qubits（ビット順）: ${r.measuredQubits.joinToString(", ", "[", "]")}<br/>" +
            "backend_id: ${QuantumUi.escape(r.backendId)}　service_version: ${QuantumUi.escape(r.serviceVersion)}　回路: ${QuantumUi.escape(succeeded.attempt.request.circuit.toString())}</html>"
    }

    override fun dispose() {
        subscription.close()
    }

    private inner class RerunAction : DumbAwareAction("再実行", "結果の seed と shots で再実行します", AllIcons.Actions.Rerun) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = displayed != null && !service.coordinator.isRunning
        }

        override fun actionPerformed(e: AnActionEvent) {
            val succeeded = displayed ?: return
            if (!QuantumUi.checkCanLaunch(project, "再実行")) return
            val circuit = succeeded.attempt.request.circuit
            val currentDigest = try {
                CircuitDigest.of(Files.readAllBytes(circuit))
            } catch (e: IOException) {
                null
            }
            val plan = RerunPlan.from(succeeded, currentDigest)
            if (plan.circuitChanged) {
                val answer = Messages.showOkCancelDialog(
                    project,
                    "回路の内容が前回の実行時から変更されています（または読み込めません）。\n" +
                        "同じ seed でも結果は再現しません。このまま再実行しますか？",
                    "回路が変更されています",
                    "再実行",
                    Messages.getCancelButton(),
                    Messages.getWarningIcon(),
                )
                if (answer != Messages.OK) return
            }
            val type = QuantumRunConfigurationType::class.java
            val factory = com.intellij.execution.configurations.ConfigurationTypeUtil.findConfigurationType(type).configurationFactories.first()
            val settings = RunManager.getInstance(project).createConfiguration("再実行: ${circuit.fileName}", factory)
            settings.isTemporary = true
            val configuration = settings.configuration as QuantumRunConfiguration
            configuration.circuitPath = circuit.toString()
            configuration.shotsText = plan.request.shots.toString()
            configuration.seedText = plan.request.seed?.decimal.orEmpty()
            val builder = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings) ?: return
            builder.buildAndExecute()
        }
    }

    private inner class StopAction : DumbAwareAction("停止", "実行中の回路を停止します（CLI の終了を要求）", AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.coordinator.isRunning
        }

        override fun actionPerformed(e: AnActionEvent) {
            service.coordinator.stop()
        }
    }

    private inner class SaveResultAction : DumbAwareAction("結果JSONを保存", "表示中の結果 JSON を指定の場所へ保存します", AllIcons.Actions.MenuSaveall) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = displayed != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val result = displayed?.result ?: return
            val descriptor = FileSaverDescriptor("結果JSONを保存", "保存先を選択してください（seed を含みます）", "json")
            val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save(null as java.nio.file.Path?, "result.json") ?: return
            try {
                Files.writeString(wrapper.file.toPath(), result.rawJson + "\n", Charsets.UTF_8)
                QuantumUi.notify(project, "結果JSONを保存しました", QuantumUi.escape(wrapper.file.path), NotificationType.INFORMATION)
            } catch (ex: IOException) {
                QuantumUi.notify(project, "結果JSONを保存できません", QuantumUi.escape(ex.message ?: ""), NotificationType.ERROR)
            }
        }
    }
}

private class CountsTableModel : AbstractTableModel() {
    private var result: RunResult? = null

    fun update(newResult: RunResult) {
        result = newResult
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = result?.counts?.size ?: 0
    override fun getColumnCount(): Int = 4
    override fun getColumnName(column: Int): String = when (column) {
        0 -> "ビット列"
        1 -> "回数"
        2 -> "比率"
        else -> "分布"
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = result!!
        val entry = r.counts[rowIndex]
        return when (columnIndex) {
            0 -> entry.bits
            1 -> entry.count
            2 -> "%.4f".format(r.ratio(entry))
            else -> r.ratio(entry)
        }
    }
}

/** 比率（回数÷shots）の棒表示。 */
private class RatioBarRenderer : DefaultTableCellRenderer() {
    private var ratio = 0.0

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
        ratio = (value as? Double) ?: 0.0
        return this
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val inset = JBUI.scale(3)
        val width = ((width - inset * 2) * ratio.coerceIn(0.0, 1.0)).toInt()
        g.color = JBColor(0x3D7EC6, 0x5A9BE0)
        g.fillRect(inset, inset, width, height - inset * 2)
    }
}
