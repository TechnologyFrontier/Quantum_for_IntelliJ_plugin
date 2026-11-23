package com.technologyfrontier.qide.domain

/** validate 成功（{"valid":true}）。 */
data object Validated

/** qosctl status の状態。busy は状態確認としては成功。 */
enum class ServiceState(val wire: String, val label: String) {
    READY("ready", "待機中（ready）"),
    BUSY("busy", "計算中（busy）"),
    ;

    companion object {
        fun fromWire(value: String): ServiceState? = entries.firstOrNull { it.wire == value }
    }
}

/** 観測されたビット列と回数。ビット順は measured_qubits の順。 */
data class CountEntry(val bits: String, val count: Long)

/**
 * 検証済みの run 結果。[rawJson] は qosctl が返した 1 行の結果 JSON（明示保存用）。
 */
data class RunResult(
    val counts: List<CountEntry>,
    val shots: Int,
    val seed: Seed,
    val measuredQubits: List<Int>,
    val backendId: String,
    val serviceVersion: String,
    val rawJson: String,
) {
    /** 比率は回数÷shots。 */
    fun ratio(entry: CountEntry): Double = entry.count.toDouble() / shots
}

/** 終了値 2／3／4 の区分（機能設計書 3.1・8章）。 */
enum class ErrorCategory(val exitCode: Int, val label: String) {
    INPUT(2, "入力・形式・版・実行上限のエラー"),
    ACCESS(3, "接続・権限・計算枠のエラー"),
    EXECUTION(4, "実行失敗・期限・通信のエラー"),
    ;

    companion object {
        fun fromExitCode(exitCode: Int): ErrorCategory? = entries.firstOrNull { it.exitCode == exitCode }
    }
}

sealed interface QosFailure {
    val title: String
    val detail: String
    val guidance: String

    /**
     * 量子サービス／CLI が返した契約どおりのエラー。code で分岐し、message は表示専用。
     */
    data class ServiceError(val category: ErrorCategory, val code: String, val message: String) : QosFailure {
        val isBusy: Boolean get() = code == BUSY_CODE
        override val title: String get() = "${category.label}（終了値 ${category.exitCode}、code: $code）"
        override val detail: String get() = message
        override val guidance: String
            get() = when (code) {
                BUSY_CODE -> "量子サービスは別の計算を実行中です（サービス全体の同時計算は1件）。計算完了を待ってから再度実行してください。IDEは自動で再試行しません。"
                "ACCESS_DENIED" -> "量子サービスの利用権限がありません。利用者が quantum グループに属しているかを管理者に確認してください（IDE から権限は変更しません）。"
                "SERVICE_UNAVAILABLE" -> "量子サービスに接続できません。量子サービス（qosd.socket）が起動しているかを確認してください。"
                "BACKEND_LIMIT" -> "実行上限（20 量子ビット）を超えています。量子ビット数を減らしてください。"
                "CIRCUIT_INVALID", "SCHEMA_UNSUPPORTED" -> "回路 JSON を修正してください（schema_version は 1、measure は最後に1つ、rx・rz には theta が必要）。"
                "RUN_OPTION_INVALID" -> "shots（1〜100,000）と seed（0〜18446744073709551615 の十進整数）を確認してください。"
                "FILE_UNREADABLE" -> "回路ファイルを読み取れません。ファイルの場所と読み取り権限を確認してください。"
                "TIMEOUT" -> "量子サービスの実行期限を超えました。回路の規模（量子ビット数・命令数）や shots を減らしてください。"
                "TRANSPORT_ERROR" -> "量子サービスとの通信が途中で切れたか、応答が不正でした。サービスの状態を確認して再実行してください。"
                else -> when (category) {
                    ErrorCategory.INPUT -> "回路ファイルの内容、shots・seed の値、量子ビット数などの実行上限を確認して修正してください。"
                    ErrorCategory.ACCESS -> "量子サービスが起動しているか、利用者が量子サービスの利用権限（quantum グループ等）を持つかを確認してください。"
                    ErrorCategory.EXECUTION -> "計算の失敗、実行期限の超過、または通信切断です。サービスの状態を確認し、必要なら再実行してください。"
                }
            }
    }

    /** 起動不能・未知の終了値・JSON 欠落や型違反・出力上限超過・監視期限超過など、IDE 側の連携失敗。 */
    data class IntegrationFailure(val reason: String, val exitCode: Int? = null) : QosFailure {
        override val title: String get() = "IDE側の連携エラー" + (exitCode?.let { "（終了値 $it）" } ?: "")
        override val detail: String get() = reason
        override val guidance: String
            get() = "qosctl の導入状態とCLIパスの設定、量子OSとプラグインの版の組み合わせを確認してください。直前の正常な結果は保持されています。"
    }

    companion object {
        const val BUSY_CODE = "BUSY"
    }
}

/** CLI 呼び出しの結果。停止（キャンセル）は失敗と区別する。 */
sealed interface QosOutcome<out T> {
    data class Success<T>(val value: T) : QosOutcome<T>
    data class Failure(val failure: QosFailure) : QosOutcome<Nothing>
    data object Cancelled : QosOutcome<Nothing>
}
