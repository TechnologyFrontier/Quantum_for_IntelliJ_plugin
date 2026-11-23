package com.technologyfrontier.qide.plugin.bootstrap

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.technologyfrontier.qide.adapter.ProcessLauncher
import com.technologyfrontier.qide.adapter.QosctlCliClient
import com.technologyfrontier.qide.application.Cancellation
import com.technologyfrontier.qide.application.QosctlClient
import com.technologyfrontier.qide.application.RunCoordinator
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Validated
import com.technologyfrontier.qide.plugin.settings.QosctlSettings
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * IntelliJ Platform の GeneralCommandLine で子プロセスを起動する。
 * シェルを介さず、現在の利用者の権限で起動する。環境変数は IDE の親環境を引き継ぐ。
 */
class IntelliJProcessLauncher : ProcessLauncher {
    override fun launch(executable: Path, arguments: List<String>, workingDirectory: Path?): Process =
        GeneralCommandLine(listOf(executable.toString()) + arguments)
            .withCharset(Charsets.UTF_8)
            .withRedirectErrorStream(false)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .apply { workingDirectory?.let { withWorkingDirectory(it) } }
            .createProcess()
}

/**
 * プロジェクト単位の結線（settings／bootstrap）。application の RunCoordinator に
 * qosctl-adapter の実装を渡す。プロジェクトを閉じると実行中の子プロセスを停止する。
 */
@Service(Service.Level.PROJECT)
class QuantumProjectService(@Suppress("unused") private val project: Project) : Disposable {

    private val launcher = IntelliJProcessLauncher()
    private val oneShotCancellations = CopyOnWriteArrayList<Cancellation>()

    /** 外部実行が可能な場合のみクライアントを返す。 */
    private fun clientOrFailure(): Pair<QosctlClient?, QosFailure?> {
        val resolved = QosctlSettings.getInstance().resolve()
        if (resolved.problem != null || resolved.executable == null) {
            return null to QosFailure.IntegrationFailure(resolved.problem ?: "qosctl のパスが不正です。")
        }
        return QosctlCliClient(resolved.executable, resolved.workingDirectory, launcher) to null
    }

    private val unavailableClient = object : QosctlClient {
        private fun failure(): QosOutcome.Failure =
            QosOutcome.Failure(clientOrFailure().second ?: QosFailure.IntegrationFailure("qosctl を利用できません。"))
        override fun validate(circuit: Path, cancellation: Cancellation): QosOutcome<Validated> = failure()
        override fun run(request: RunRequest, cancellation: Cancellation): QosOutcome<RunResult> = failure()
        override fun status(cancellation: Cancellation): QosOutcome<ServiceState> = failure()
    }

    private fun client(): QosctlClient = clientOrFailure().first ?: unavailableClient

    val coordinator = RunCoordinator({ client() }, AppExecutorUtil.getAppExecutorService())

    /** validate を同期実行する（バックグラウンドタスクから呼ぶ）。 */
    fun validate(circuit: Path, cancellation: Cancellation): QosOutcome<Validated> =
        tracked(cancellation) { client().validate(circuit, it) }

    /** status を同期実行する（バックグラウンドタスクから呼ぶ）。 */
    fun status(cancellation: Cancellation): QosOutcome<ServiceState> =
        tracked(cancellation) { client().status(it) }

    private fun <T> tracked(cancellation: Cancellation, block: (Cancellation) -> QosOutcome<T>): QosOutcome<T> {
        oneShotCancellations += cancellation
        try {
            return block(cancellation)
        } finally {
            oneShotCancellations -= cancellation
        }
    }

    override fun dispose() {
        coordinator.dispose()
        oneShotCancellations.forEach { it.cancel() }
    }

    companion object {
        fun getInstance(project: Project): QuantumProjectService = project.service()
    }
}
