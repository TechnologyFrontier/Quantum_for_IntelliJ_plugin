package com.technologyfrontier.qide.application

import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.Shots
import java.security.MessageDigest

/** 回路内容のハッシュ（再実行時の変更検出用。永続化しない）。 */
object CircuitDigest {
    fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * 結果からの再実行計画（機能設計書 3章）。
 * 結果の seed と shots を採用し、回路の内容が前回と異なる場合は [circuitChanged] を立てる（呼び出し側で警告・確認）。
 * 再現性は同一イメージ・サービスビルド・実行環境・回路に限る。
 */
data class RerunPlan(val request: RunRequest, val circuitChanged: Boolean) {
    companion object {
        fun from(succeeded: RunState.Succeeded, currentDigest: String?): RerunPlan {
            val result = succeeded.result
            val shots = requireNotNull(Shots.of(result.shots.toLong())) { "結果の shots が範囲外です" }
            val request = RunRequest(succeeded.attempt.request.circuit, shots, result.seed)
            val previous = succeeded.attempt.circuitDigest
            val changed = previous == null || currentDigest == null || previous != currentDigest
            return RerunPlan(request, changed)
        }
    }
}
