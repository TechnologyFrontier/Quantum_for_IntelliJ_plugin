package com.technologyfrontier.qide.plugin.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.project.Project
import com.technologyfrontier.qide.application.RunCoordinator
import com.technologyfrontier.qide.application.RunState
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.plugin.bootstrap.QuantumProjectService
import com.technologyfrontier.qide.plugin.ui.QuantumUi
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

class QuantumRunState(
    private val project: Project,
    private val environment: ExecutionEnvironment,
    private val request: RunRequest,
    private val circuitDigest: String,
) : RunProfileState {

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val handler = QuantumRunProcessHandler(project, request, circuitDigest)
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        QuantumUi.showResults(project)
        return DefaultExecutionResult(console, handler)
    }
}

/**
 * 実行コンソールと停止ボタンを RunCoordinator に結び付けるプロセスハンドラー。
 * qosctl の子プロセスは qosctl-adapter が管理し、このハンドラーは状態を表示・中継する。
 */
class QuantumRunProcessHandler(
    private val project: Project,
    private val request: RunRequest,
    private val circuitDigest: String,
) : ProcessHandler() {

    private val coordinator: RunCoordinator get() = QuantumProjectService.getInstance(project).coordinator
    private val attemptId = AtomicLong(-1)

    override fun startNotify() {
        super.startNotify()
        print("qosctl run ${request.circuit}  --shots ${request.shots}" + (request.seed?.let { "  --seed $it" } ?: "") + "\n")
        when (val started = coordinator.start(request, circuitDigest, ::finished)) {
            is RunCoordinator.StartResult.Started -> attemptId.set(started.attempt.id)
            RunCoordinator.StartResult.AlreadyRunning -> {
                print("このプロジェクトでは別の回路を実行中です（プロジェクトごとに同時1件）。\n", ProcessOutputTypes.STDERR)
                notifyProcessTerminated(1)
            }
            RunCoordinator.StartResult.Disposed -> notifyProcessTerminated(1)
        }
    }

    private fun finished(state: RunState) {
        // 実行開始時は Run ウィンドウが前面に来るため、確定時に結果ウィンドウを表示する。
        if (state is RunState.Succeeded || state is RunState.Failed) {
            QuantumUi.onUi(project) { QuantumUi.showResults(project) }
        }
        when (state) {
            is RunState.Succeeded -> {
                val r = state.result
                print("成功: shots=${r.shots} seed=${r.seed} 観測キー数=${r.counts.size}（結果は Quantum Results に表示）\n")
                notifyProcessTerminated(0)
            }
            is RunState.Failed -> {
                print(QuantumUi.failureText(state.failure) + "\n", ProcessOutputTypes.STDERR)
                notifyProcessTerminated(1)
            }
            is RunState.Stopped -> {
                print("IDE で停止しました。サービス側の計算停止は確定していません。停止後に届いた出力は採用しません。\n", ProcessOutputTypes.SYSTEM)
                notifyProcessTerminated(130)
            }
            else -> Unit
        }
    }

    private fun print(text: String, type: com.intellij.openapi.util.Key<*> = ProcessOutputTypes.SYSTEM) {
        notifyTextAvailable(text, type)
    }

    private fun stopIfMine() {
        val current = coordinator.state
        if (current is RunState.Running && current.attempt.id == attemptId.get()) {
            coordinator.stop()
        }
    }

    override fun destroyProcessImpl() = stopIfMine()

    override fun detachProcessImpl() {
        stopIfMine()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}

/** 量子回路の実行構成専用のランナー（実行のみ。デバッグ等は対象外）。 */
class QuantumProgramRunner : GenericProgramRunner<RunnerSettings>() {
    override fun getRunnerId(): String = "QuantumCircuitRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultRunExecutor.EXECUTOR_ID && profile is QuantumRunConfiguration

    override fun doExecute(state: com.intellij.execution.configurations.RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }
}
