package com.technologyfrontier.qide.plugin.ui

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.plugin.settings.QosctlSettings

object QuantumUi {
    const val NOTIFICATION_GROUP = "Quantum OS IDE"
    const val TOOL_WINDOW_ID = "Quantum Results"

    fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .notify(project)
    }

    fun failureText(failure: QosFailure): String = buildString {
        append(failure.title)
        if (failure.detail.isNotBlank()) append("\n").append(failure.detail)
        append("\n").append(failure.guidance)
    }

    /** タイトルは短く（操作名と code）、区分・詳細・案内は本文に置く。 */
    fun notifyFailure(project: Project?, operation: String, failure: QosFailure) {
        val code = (failure as? QosFailure.ServiceError)?.code
        val title = if (code != null) "「$operation」に失敗しました（$code）" else "「$operation」に失敗しました"
        val body = listOf(failure.title, failure.detail, failure.guidance).filter { it.isNotBlank() }.joinToString("<br/>") { escape(it) }
        notify(project, title, body, NotificationType.ERROR)
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 信頼済みプロジェクトか。外部コマンドは信頼済みプロジェクトでのみ起動する。 */
    fun isTrusted(project: Project): Boolean = TrustedProjects.isProjectTrusted(project)

    /**
     * 外部コマンド起動前の共通確認（信頼状態・CLI パス）。問題があれば通知して false。
     */
    fun checkCanLaunch(project: Project, operation: String): Boolean {
        if (!isTrusted(project)) {
            notify(
                project,
                "「$operation」を実行できません",
                "このプロジェクトは信頼されていません。信頼済みプロジェクトでのみ qosctl を起動します。",
                NotificationType.WARNING,
            )
            return false
        }
        val problem = QosctlSettings.getInstance().resolve().problem
        if (problem != null) {
            notify(
                project,
                "「$operation」を実行できません",
                escape(problem) + "<br/>設定 → ツール → 量子OS で qosctl のパスを確認してください。サンプルの閲覧と編集は引き続き利用できます。",
                NotificationType.WARNING,
            )
            return false
        }
        return true
    }

    /**
     * 未保存の変更があれば保存の確認を行う。キャンセル時は false（起動しない）。
     */
    fun confirmSaveIfModified(project: Project, file: VirtualFile): Boolean {
        val fdm = FileDocumentManager.getInstance()
        val document = fdm.getCachedDocument(file) ?: return true
        if (!fdm.isDocumentUnsaved(document)) return true
        val answer = Messages.showOkCancelDialog(
            project,
            "${file.name} は編集中です。保存してから続行しますか？",
            "回路の保存",
            "保存して続行",
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.OK) return false
        fdm.saveDocument(document)
        return true
    }

    fun showResults(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    /** バックグラウンドから UI スレッドへ。プロジェクト終了後は反映しない。 */
    fun onUi(project: Project, action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.nonModal(), project.disposed)
    }
}
