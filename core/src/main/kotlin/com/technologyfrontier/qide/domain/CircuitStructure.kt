package com.technologyfrontier.qide.domain

import com.technologyfrontier.qide.json.JsonArray
import com.technologyfrontier.qide.json.JsonNumber
import com.technologyfrontier.qide.json.JsonObject
import com.technologyfrontier.qide.json.JsonParseException
import com.technologyfrontier.qide.json.JsonString
import com.technologyfrontier.qide.json.StrictJson

/**
 * 回路 JSON の構造上の指摘（機能設計書 1章「構造検証」）。
 *
 * これはエディター上の補助であり、最終判定は量子サービス（qosctl validate）が行う。
 * 指摘がなくても有効とは限らず、指摘があってもサービス側の判定を優先する。
 */
object CircuitStructure {
    const val SCHEMA_VERSION = 1L
    const val MIN_QUBITS = 1L
    const val MAX_QUBITS = 64L
    const val MAX_OPERATIONS = 10_000
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** 実行時に量子サービスが適用する量子ビット上限（構造上の上限とは別）。 */
    const val RUN_QUBIT_LIMIT = 20L

    val SINGLE_QUBIT_GATES = setOf("h", "x", "z", "rx", "rz")
    const val TWO_QUBIT_GATE = "cx"
    const val MEASURE = "measure"
    val ALL_OPS = SINGLE_QUBIT_GATES + TWO_QUBIT_GATE + MEASURE

    enum class Severity { ERROR, WARNING }

    /** [path] は JSON ポインター風の位置（例: /operations/3/qubits）。 */
    data class Finding(val severity: Severity, val path: String, val message: String)

    fun check(bytes: ByteArray): List<Finding> {
        if (bytes.size > MAX_FILE_BYTES) {
            return listOf(Finding(Severity.ERROR, "", "回路ファイルが上限 4 MiB を超えています（${bytes.size} バイト）。"))
        }
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            return listOf(Finding(Severity.ERROR, "", "回路ファイルは UTF-8 である必要があります。"))
        }
        return check(text)
    }

    fun check(text: String): List<Finding> {
        val root = try {
            StrictJson.parse(text)
        } catch (e: JsonParseException) {
            return listOf(Finding(Severity.ERROR, "", "JSON として解析できません: ${e.message}"))
        }
        val findings = mutableListOf<Finding>()
        fun error(path: String, msg: String) = findings.add(Finding(Severity.ERROR, path, msg))
        fun warn(path: String, msg: String) = findings.add(Finding(Severity.WARNING, path, msg))

        if (root !is JsonObject) {
            error("", "回路はJSONオブジェクトである必要があります。")
            return findings
        }

        val version = (root["schema_version"] as? JsonNumber)?.toLongExactOrNull()
        when {
            root["schema_version"] == null -> error("/schema_version", "schema_version がありません。")
            version != SCHEMA_VERSION -> error("/schema_version", "schema_version は $SCHEMA_VERSION のみ対応しています。")
        }

        val nQubits = (root["n_qubits"] as? JsonNumber)?.toLongExactOrNull()
        when {
            root["n_qubits"] == null -> error("/n_qubits", "n_qubits がありません。")
            nQubits == null || nQubits !in MIN_QUBITS..MAX_QUBITS ->
                error("/n_qubits", "n_qubits は $MIN_QUBITS〜$MAX_QUBITS の整数である必要があります。")
            nQubits > RUN_QUBIT_LIMIT ->
                warn("/n_qubits", "実行時の上限は $RUN_QUBIT_LIMIT 量子ビットです。この回路は検証できても実行は拒否されます。")
        }

        val ops = root["operations"]
        if (ops !is JsonArray) {
            error("/operations", "operations は配列である必要があります。")
            return findings
        }
        if (ops.items.size > MAX_OPERATIONS) {
            error("/operations", "命令数が上限 ${"%,d".format(MAX_OPERATIONS)}（測定込み）を超えています（${ops.items.size}）。")
        }

        var measured = false
        var reportedAfterMeasure = false
        ops.items.forEachIndexed { index, item ->
            val path = "/operations/$index"
            if (item !is JsonObject) {
                error(path, "命令はJSONオブジェクトである必要があります。")
                return@forEachIndexed
            }
            val op = (item["op"] as? JsonString)?.value
            if (op == null || op !in ALL_OPS) {
                error("$path/op", "op は ${ALL_OPS.joinToString("・")} のいずれかである必要があります。")
                return@forEachIndexed
            }
            if (op == MEASURE) {
                measured = true
            } else if (measured && !reportedAfterMeasure) {
                error(path, "測定の後にゲートがあります。測定は回路の終端に置いてください。")
                reportedAfterMeasure = true
            }
            val qubits = item["qubits"]
            if (qubits !is JsonArray) {
                error("$path/qubits", "qubits は量子ビット番号の配列である必要があります。")
                return@forEachIndexed
            }
            val indices = qubits.items.map { (it as? JsonNumber)?.toLongExactOrNull() }
            if (indices.any { it == null }) {
                error("$path/qubits", "量子ビット番号は整数である必要があります。")
                return@forEachIndexed
            }
            val ints = indices.filterNotNull()
            if (nQubits != null && ints.any { it < 0 || it >= nQubits }) {
                error("$path/qubits", "量子ビット番号は 0〜${nQubits - 1} の範囲である必要があります。")
            }
            val arityOk = when (op) {
                in SINGLE_QUBIT_GATES -> ints.size == 1
                TWO_QUBIT_GATE -> ints.size == 2
                else -> ints.isNotEmpty()
            }
            if (!arityOk) {
                val expected = when (op) {
                    in SINGLE_QUBIT_GATES -> "1個"
                    TWO_QUBIT_GATE -> "2個（制御・標的）"
                    else -> "1個以上"
                }
                error("$path/qubits", "$op の qubits は$expected 指定してください。")
            } else if (ints.toSet().size != ints.size) {
                error("$path/qubits", "同じ量子ビット番号が重複しています。")
            }
        }
        if (!measured) {
            warn("/operations", "測定（measure）命令がありません。")
        }
        return findings
    }
}
