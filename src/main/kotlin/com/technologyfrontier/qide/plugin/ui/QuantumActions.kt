package com.technologyfrontier.qide.plugin.ui

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.technologyfrontier.qide.application.Cancellation
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.plugin.bootstrap.QuantumProjectService
import com.technologyfrontier.qide.plugin.editor.CircuitFiles
import java.nio.file.Path

/**
 * バックグラウンドで qosctl を呼び、進捗表示のキャンセルを CLI の停止要求に結び付ける。
 * 結果の画面反映は UI スレッドで、プロジェクト終了後は行わない。
 */
private abstract class QosctlTask<T>(project: Project, title: String) : Task.Backgroundable(project, title, true) {
    private var outcome: QosOutcome<T>? = null

    abstract fun call(service: QuantumProjectService, cancellation: Cancellation): QosOutcome<T>
    abstract fun done(project: Project, outcome: QosOutcome<T>)

    override fun run(indicator: ProgressIndicator) {
        val cancellation = Cancellation()
        val watcher = Thread {
            try {
                while (!cancellation.isCancelled) {
                    if (indicator.isCanceled) cancellation.cancel()
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; name = "qosctl-cancel-watch" }
        watcher.start()
        try {
            outcome = call(QuantumProjectService.getInstance(project), cancellation)
        } finally {
            watcher.interrupt()
        }
    }

    override fun onFinished() {
        val result = outcome ?: return
        if (!project.isDisposed) done(project, result)
    }
}

/** 回路を検証（IDE-01）。qosctl validate の結果を表示する。 */
class ValidateCircuitAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && CircuitFiles.isJsonFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!QuantumUi.checkCanLaunch(project, "回路を検証")) return
        if (!QuantumUi.confirmSaveIfModified(project, file)) return
        val path = file.fileSystem.getNioPath(file)?.toAbsolutePath() ?: run {
            QuantumUi.notify(project, "回路を検証できません", "ローカルファイルではありません。", NotificationType.WARNING)
            return
        }
        ValidateTask(project, path, file.name).queue()
    }

    private class ValidateTask(project: Project, private val path: Path, private val name: String) :
        QosctlTask<com.technologyfrontier.qide.domain.Validated>(project, "回路を検証中: $name") {
        override fun call(service: QuantumProjectService, cancellation: Cancellation) = service.validate(path, cancellation)

        override fun done(project: Project, outcome: QosOutcome<com.technologyfrontier.qide.domain.Validated>) {
            when (outcome) {
                is QosOutcome.Success ->
                    QuantumUi.notify(project, "回路は有効です", QuantumUi.escape(name), NotificationType.INFORMATION)
                is QosOutcome.Failure -> QuantumUi.notifyFailure(project, "回路を検証", outcome.failure)
                QosOutcome.Cancelled ->
                    QuantumUi.notify(project, "回路の検証を停止しました", QuantumUi.escape(name), NotificationType.INFORMATION)
            }
        }
    }
}

/** 状態を確認（IDE-05）。qosctl status で ready／busy を表示する。OS 全体や A/B スロットの健全性判定には使わない。 */
class CheckStatusAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!QuantumUi.checkCanLaunch(project, "状態を確認")) return
        StatusTask(project).queue()
    }

    private class StatusTask(project: Project) : QosctlTask<ServiceState>(project, "量子サービスの状態を確認中") {
        override fun call(service: QuantumProjectService, cancellation: Cancellation) = service.status(cancellation)

        override fun done(project: Project, outcome: QosOutcome<ServiceState>) {
            when (outcome) {
                is QosOutcome.Success -> QuantumUi.notify(
                    project,
                    "量子サービス: ${outcome.value.label}",
                    if (outcome.value == ServiceState.BUSY) "別の計算を実行中です。完了を待ってから実行してください。" else "回路を実行できます。",
                    NotificationType.INFORMATION,
                )
                is QosOutcome.Failure -> QuantumUi.notifyFailure(project, "状態を確認（${categoryOf(outcome.failure)}）", outcome.failure)
                QosOutcome.Cancelled -> Unit
            }
        }

        private fun categoryOf(failure: QosFailure): String = when (failure) {
            is QosFailure.ServiceError -> when (failure.category) {
                com.technologyfrontier.qide.domain.ErrorCategory.ACCESS -> "サービス接続／権限"
                com.technologyfrontier.qide.domain.ErrorCategory.INPUT -> "入力"
                com.technologyfrontier.qide.domain.ErrorCategory.EXECUTION -> "実行・通信"
            }
            is QosFailure.IntegrationFailure -> "IDE連携"
        }
    }
}

/** 保守案内（IDE-05）。手順を表示するだけで、コマンドの実行・資格情報の取得・権限昇格・再起動は行わない。 */
class MaintenanceGuideAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        MaintenanceGuideDialog(e.project).show()
    }
}
