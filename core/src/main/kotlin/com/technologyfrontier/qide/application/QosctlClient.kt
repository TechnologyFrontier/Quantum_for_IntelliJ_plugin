package com.technologyfrontier.qide.application

import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Validated
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * application 側が依存する qosctl の論理契約（機能設計書 6.1・7章）。
 * qosctl-adapter が実装する。呼び出しはブロッキングで、バックグラウンドスレッドから行う。
 */
interface QosctlClient {
    fun validate(circuit: Path, cancellation: Cancellation): QosOutcome<Validated>
    fun run(request: RunRequest, cancellation: Cancellation): QosOutcome<RunResult>
    fun status(cancellation: Cancellation): QosOutcome<ServiceState>
}

/** 停止要求の伝達。停止要求後は、届いた出力を結果として採用しない。 */
class Cancellation {
    private val cancelled = AtomicBoolean(false)
    private val handlers = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            handlers.forEach { runCatching(it) }
        }
    }

    /** 停止時の処理を登録する。既に停止済みなら即座に実行する。戻り値で登録解除できる。 */
    fun onCancel(handler: () -> Unit): AutoCloseable {
        handlers += handler
        if (cancelled.get()) runCatching(handler)
        return AutoCloseable { handlers -= handler }
    }
}
