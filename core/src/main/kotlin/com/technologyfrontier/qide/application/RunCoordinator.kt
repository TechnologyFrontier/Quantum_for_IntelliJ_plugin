package com.technologyfrontier.qide.application

import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** 1回の実行要求。[circuitDigest] は実行時点の回路内容のハッシュ（再実行時の変更検出用、メモリのみ）。 */
data class RunAttempt(val id: Long, val request: RunRequest, val circuitDigest: String?)

/** 実行の状態遷移: 待機 → 実行中 → 成功／失敗／停止（機能設計書 2章）。 */
sealed interface RunState {
    data object Idle : RunState
    data class Running(val attempt: RunAttempt) : RunState
    data class Succeeded(val attempt: RunAttempt, val result: RunResult) : RunState
    data class Failed(val attempt: RunAttempt, val failure: QosFailure) : RunState

    /** IDE で停止した。サービス側の計算停止は確定扱いしない。 */
    data class Stopped(val attempt: RunAttempt) : RunState
}

/**
 * プロジェクト単位の実行枠（同時1件）と、停止・確定の制御を行う。
 *
 * - 確定（成功／失敗／停止）は1回だけ行う。停止が先に確定した場合、後着の結果は採用しない。
 * - [dispose] 後（プロジェクト終了後）は状態を変えず、リスナーへも通知しない。
 * - サービスの BUSY は失敗として確定し、再試行しない。
 * - 直前の正常結果は [lastSuccess] に保持し、失敗・停止では消さない。
 *
 * リスナーはバックグラウンドスレッドから呼ばれ得るため、画面反映は呼び出し側で UI スレッドへ移す。
 */
class RunCoordinator(
    private val clientProvider: () -> QosctlClient,
    private val executor: Executor,
) {
    fun interface Listener {
        fun stateChanged(state: RunState)
    }

    sealed interface StartResult {
        data class Started(val attempt: RunAttempt) : StartResult
        data object AlreadyRunning : StartResult
        data object Disposed : StartResult
    }

    private class Active(val attempt: RunAttempt, val cancellation: Cancellation, val onFinished: (RunState) -> Unit)

    private val lock = Any()
    private val ids = AtomicLong()
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var current: RunState = RunState.Idle
    private var active: Active? = null
    private var disposed = false
    private var lastSuccessState: RunState.Succeeded? = null

    val state: RunState get() = synchronized(lock) { current }
    val lastSuccess: RunState.Succeeded? get() = synchronized(lock) { lastSuccessState }
    val isRunning: Boolean get() = synchronized(lock) { current is RunState.Running }

    fun addListener(listener: Listener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * 実行を開始する。[onFinished] はこの実行が確定した時に1回だけ呼ばれる（破棄時は呼ばれない）。
     */
    fun start(request: RunRequest, circuitDigest: String?, onFinished: (RunState) -> Unit = {}): StartResult {
        val entry: Active
        synchronized(lock) {
            if (disposed) return StartResult.Disposed
            if (active != null) return StartResult.AlreadyRunning
            val attempt = RunAttempt(ids.incrementAndGet(), request, circuitDigest)
            entry = Active(attempt, Cancellation(), onFinished)
            active = entry
            current = RunState.Running(attempt)
        }
        notifyListeners(RunState.Running(entry.attempt))
        try {
            executor.execute { execute(entry) }
        } catch (e: Exception) {
            finish(entry.attempt.id, RunState.Failed(entry.attempt, QosFailure.IntegrationFailure("実行を開始できませんでした: ${e.message ?: e.javaClass.name}")))
        }
        return StartResult.Started(entry.attempt)
    }

    private fun execute(entry: Active) {
        val outcome = try {
            clientProvider().run(entry.attempt.request, entry.cancellation)
        } catch (e: Exception) {
            QosOutcome.Failure(QosFailure.IntegrationFailure("実行中に予期しない例外が発生しました: ${e.message ?: e.javaClass.name}"))
        }
        val terminal = when (outcome) {
            is QosOutcome.Success -> RunState.Succeeded(entry.attempt, outcome.value)
            is QosOutcome.Failure -> RunState.Failed(entry.attempt, outcome.failure)
            QosOutcome.Cancelled -> RunState.Stopped(entry.attempt)
        }
        finish(entry.attempt.id, terminal)
    }

    /**
     * 停止を要求する。CLI へ終了を要求し、状態は直ちに「停止」で確定する。
     * 以後この実行の出力は採用しない。実行中でなければ false。
     */
    fun stop(): Boolean {
        val attempt = synchronized(lock) { active?.attempt } ?: return false
        return finish(attempt.id, RunState.Stopped(attempt))
    }

    /** プロジェクト終了時。実行中の子プロセスを停止し、以後は状態を反映しない。 */
    fun dispose() {
        val entry: Active?
        synchronized(lock) {
            if (disposed) return
            disposed = true
            entry = active
            active = null
        }
        listeners.clear()
        entry?.cancellation?.cancel()
    }

    /** 状態を1回だけ確定する。確定できた場合のみ true。 */
    private fun finish(attemptId: Long, terminal: RunState): Boolean {
        val entry: Active
        synchronized(lock) {
            if (disposed) return false
            val running = active ?: return false
            if (running.attempt.id != attemptId) return false
            entry = running
            active = null
            current = terminal
            if (terminal is RunState.Succeeded) lastSuccessState = terminal
        }
        if (terminal is RunState.Stopped) entry.cancellation.cancel()
        notifyListeners(terminal)
        runCatching { entry.onFinished(terminal) }
        return true
    }

    private fun notifyListeners(state: RunState) {
        listeners.forEach { runCatching { it.stateChanged(state) } }
    }
}
