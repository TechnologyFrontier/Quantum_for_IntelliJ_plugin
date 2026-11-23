package com.technologyfrontier.qide.adapter

import com.technologyfrontier.qide.domain.CountEntry
import com.technologyfrontier.qide.domain.ErrorCategory
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.Seed
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Validated
import com.technologyfrontier.qide.json.JsonArray
import com.technologyfrontier.qide.json.JsonBoolean
import com.technologyfrontier.qide.json.JsonNumber
import com.technologyfrontier.qide.json.JsonObject
import com.technologyfrontier.qide.json.JsonParseException
import com.technologyfrontier.qide.json.JsonString
import com.technologyfrontier.qide.json.JsonValue
import com.technologyfrontier.qide.json.StrictJson

/**
 * qosctl の戻り値（終了値・標準出力・標準エラー）を検証し、domain の型へ変換する（機能設計書 8章）。
 *
 * - 成功は終了値 0 かつ標準出力 1 行の結果 JSON。IPC の包み（protocol_version, ok, result）は読まない。
 * - 終了値 2／3／4 は標準エラー 1 行のエラー JSON から code と message を取得する。
 * - それ以外・契約不一致は IDE 側の連携失敗とする。
 */
object QosctlResponses {

    private class ContractViolation(message: String) : Exception(message)

    fun validate(outcome: ProcessOutcome): QosOutcome<Validated> = interpret(outcome) { obj ->
        when (val valid = obj["valid"]) {
            JsonBoolean(true) -> Validated
            null -> violation("validate の結果に valid がありません")
            JsonBoolean(false) -> violation("validate の結果が valid:false でした（成功扱いしません）")
            else -> violation("validate の valid が真偽値ではありません: $valid")
        }
    }

    fun status(outcome: ProcessOutcome): QosOutcome<ServiceState> = interpret(outcome) { obj ->
        val state = (obj["state"] as? JsonString)?.value ?: violation("status の結果に state 文字列がありません")
        ServiceState.fromWire(state) ?: violation("status の state が未知の値です: $state")
    }

    fun run(outcome: ProcessOutcome, request: RunRequest): QosOutcome<RunResult> = interpret(outcome) { obj ->
        parseRunResult(obj, request, (outcome as ProcessOutcome.Completed).stdout.trimEnd('\n', '\r'))
    }

    private fun <T> interpret(outcome: ProcessOutcome, onSuccess: (JsonObject) -> T): QosOutcome<T> {
        return when (outcome) {
            ProcessOutcome.Cancelled -> QosOutcome.Cancelled
            is ProcessOutcome.LaunchFailed -> integration("qosctl を起動できません: ${outcome.message}")
            is ProcessOutcome.OutputLimitExceeded -> integration("${outcome.stream}が応答上限を超えたため、qosctl を停止しました")
            is ProcessOutcome.MalformedEncoding -> integration("${outcome.stream}が UTF-8 として不正です")
            is ProcessOutcome.TimedOut -> integration("監視期限（${outcome.timeout.seconds} 秒）を超えたため、qosctl を停止しました")
            is ProcessOutcome.Completed -> interpretCompleted(outcome, onSuccess)
        }
    }

    private fun <T> interpretCompleted(outcome: ProcessOutcome.Completed, onSuccess: (JsonObject) -> T): QosOutcome<T> {
        val exitCode = outcome.exitCode
        if (exitCode == 0) {
            return try {
                val obj = singleLineObject(outcome.stdout, "標準出力")
                rejectEnvelope(obj)
                QosOutcome.Success(onSuccess(obj))
            } catch (e: ContractViolation) {
                integration(e.message!!, exitCode)
            }
        }
        val category = ErrorCategory.fromExitCode(exitCode)
            ?: return integration("未知の終了値です", exitCode)
        return try {
            val obj = singleLineObject(outcome.stderr, "標準エラー")
            val code = (obj["code"] as? JsonString)?.value?.takeIf { it.isNotEmpty() }
                ?: violation("エラー JSON に code 文字列がありません")
            val message = (obj["message"] as? JsonString)?.value
                ?: violation("エラー JSON に message 文字列がありません")
            QosOutcome.Failure(QosFailure.ServiceError(category, code, message))
        } catch (e: ContractViolation) {
            integration(e.message!!, exitCode)
        }
    }

    private fun integration(reason: String, exitCode: Int? = null) =
        QosOutcome.Failure(QosFailure.IntegrationFailure(reason, exitCode))

    private fun violation(message: String): Nothing = throw ContractViolation(message)

    /** 1 行の JSON オブジェクトであることを確認する。末尾の改行 1 つは許容する。 */
    private fun singleLineObject(text: String, label: String): JsonObject {
        val line = when {
            text.endsWith("\r\n") -> text.dropLast(2)
            text.endsWith("\n") -> text.dropLast(1)
            else -> text
        }
        if (line.isBlank()) violation("${label}が空です")
        if (line.contains('\n') || line.contains('\r')) violation("${label}が複数行です（1 行の JSON が必要です）")
        val value: JsonValue = try {
            StrictJson.parse(line)
        } catch (e: JsonParseException) {
            violation("${label}の JSON が不正です: ${e.message}")
        }
        return value as? JsonObject ?: violation("${label}が JSON オブジェクトではありません")
    }

    private fun rejectEnvelope(obj: JsonObject) {
        if ("protocol_version" in obj.keys || ("ok" in obj.keys && "result" in obj.keys)) {
            violation("IPC の包み形式（protocol_version／ok／result）を受信しました。CLI の結果オブジェクトが必要です")
        }
    }

    private fun parseRunResult(obj: JsonObject, request: RunRequest, raw: String): RunResult {
        val measured = (obj["measured_qubits"] as? JsonArray)?.items
            ?: violation("measured_qubits が配列ではありません")
        val measuredQubits = measured.map {
            (it as? JsonNumber)?.toLongExactOrNull()?.takeIf { v -> v in 0..Int.MAX_VALUE }?.toInt()
                ?: violation("measured_qubits の要素が非負整数ではありません")
        }
        if (measuredQubits.isEmpty()) violation("measured_qubits が空です")

        val shots = (obj["shots"] as? JsonNumber)?.toLongExactOrNull()
            ?: violation("shots が整数ではありません")
        if (shots != request.shots.value.toLong()) {
            violation("shots が要求値と一致しません（要求 ${request.shots.value}、応答 $shots）")
        }

        val seedText = (obj["seed"] as? JsonString)?.value
            ?: violation("seed が十進文字列ではありません")
        val seed = Seed.parse(seedText) ?: violation("seed が 0〜2⁶⁴−1 の正規な十進文字列ではありません")
        if (request.seed != null && request.seed != seed) {
            violation("seed が明示値と一致しません（要求 ${request.seed.decimal}、応答 ${seed.decimal}）")
        }

        val backendId = (obj["backend_id"] as? JsonString)?.value ?: violation("backend_id が文字列ではありません")
        val serviceVersion = (obj["service_version"] as? JsonString)?.value
            ?: violation("service_version が文字列ではありません")

        val countItems = (obj["counts"] as? JsonArray)?.items ?: violation("counts が配列ではありません")
        val counts = ArrayList<CountEntry>(countItems.size)
        var total = 0L
        var previous: String? = null
        for (item in countItems) {
            val pair = (item as? JsonArray)?.items
            if (pair == null || pair.size != 2) violation("counts の要素が [ビット列, 回数] の組ではありません")
            val bits = (pair[0] as? JsonString)?.value ?: violation("counts のビット列が文字列ではありません")
            if (bits.length != measuredQubits.size) {
                violation("counts のキー長 ${bits.length} が測定ビット数 ${measuredQubits.size} と一致しません")
            }
            if (!bits.all { it == '0' || it == '1' }) violation("counts のキーに 0／1 以外の文字があります: $bits")
            if (previous != null && bits <= previous) {
                violation(if (bits == previous) "counts のキーが重複しています: $bits" else "counts が辞書順に並んでいません")
            }
            val count = (pair[1] as? JsonNumber)?.toLongExactOrNull()?.takeIf { it > 0 }
                ?: violation("counts の回数が正整数ではありません")
            total = try {
                Math.addExact(total, count)
            } catch (e: ArithmeticException) {
                violation("counts の合計が大きすぎます")
            }
            counts += CountEntry(bits, count)
            previous = bits
        }
        if (total != shots) violation("counts の合計 $total が shots $shots と一致しません")

        return RunResult(
            counts = counts,
            shots = shots.toInt(),
            seed = seed,
            measuredQubits = measuredQubits,
            backendId = backendId,
            serviceVersion = serviceVersion,
            rawJson = raw,
        )
    }
}
