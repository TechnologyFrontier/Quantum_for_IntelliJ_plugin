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
 * 規則は QOS-IF 0.1 3章と量子OSの実装（終端の measure はちょうど1つ、rx・rz は有限の theta 必須、未知キー不可）に合わせる。
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

    val ROTATION_GATES = setOf("rx", "rz")
    val SINGLE_QUBIT_GATES = setOf("h", "x", "z") + ROTATION_GATES
    const val TWO_QUBIT_GATE = "cx"
    const val MEASURE = "measure"
    val ALL_OPS = SINGLE_QUBIT_GATES + TWO_QUBIT_GATE + MEASURE
    private val ROOT_KEYS = setOf("schema_version", "n_qubits", "operations")

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

        // QOS-IF 0.1 3章・量子OS実装（circuit.py）に合わせた規則。最終判定は量子サービス。
        root.keys.filter { it !in ROOT_KEYS }.forEach { error("/$it", "未知のキー \"$it\" です（schema_version・n_qubits・operations のみ）。") }

        val version = (root["schema_version"] as? JsonNumber)?.toLongExactOrNull()
        when {
            root["schema_version"] == null -> error("/schema_version", "schema_version がありません。")
            version == null -> error("/schema_version", "schema_version は整数である必要があります。")
            version != SCHEMA_VERSION -> error("/schema_version", "schema_version $version は未対応です（$SCHEMA_VERSION のみ対応、SCHEMA_UNSUPPORTED）。")
        }

        val nQubits = (root["n_qubits"] as? JsonNumber)?.toLongExactOrNull()
        when {
            root["n_qubits"] == null -> error("/n_qubits", "n_qubits がありません。")
            nQubits == null || nQubits !in MIN_QUBITS..MAX_QUBITS ->
                error("/n_qubits", "n_qubits は $MIN_QUBITS〜$MAX_QUBITS の整数である必要があります。")
            nQubits > RUN_QUBIT_LIMIT ->
                warn("/n_qubits", "実行時の上限は $RUN_QUBIT_LIMIT 量子ビットです。この回路は検証できても実行は拒否されます（BACKEND_LIMIT）。")
        }

        val ops = root["operations"]
        if (ops !is JsonArray) {
            error("/operations", if (ops == null) "operations がありません。" else "operations は配列である必要があります。")
            return findings
        }
        if (ops.items.isEmpty()) {
            error("/operations", "operations が空です。終端の measure が必要です。")
            return findings
        }
        if (ops.items.size > MAX_OPERATIONS) {
            error("/operations", "命令数が上限 ${"%,d".format(MAX_OPERATIONS)}（測定込み）を超えています（${ops.items.size}）。")
        }

        var measured = false
        val last = ops.items.size - 1
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
            val allowedKeys = if (op in ROTATION_GATES) setOf("op", "qubits", "theta") else setOf("op", "qubits")
            item.keys.filter { it !in allowedKeys }.forEach {
                error("$path/$it", if (it == "theta") "theta は rx・rz だけに指定できます。" else "未知のキー \"$it\" です。")
            }
            if (op == MEASURE) {
                if (measured) {
                    error(path, "measure は回路に1つだけ置けます。")
                } else if (index != last) {
                    error(path, "measure は最後の命令である必要があります（終端測定）。")
                }
                measured = true
            }
            if (op in ROTATION_GATES) {
                val theta = item["theta"]
                when {
                    theta == null -> error(path, "$op には角度 theta（ラジアン、有限の数値）が必要です。")
                    theta !is JsonNumber || theta.text.toDoubleOrNull()?.isFinite() != true ->
                        error("$path/theta", "theta は有限の数値（ラジアン）である必要があります。")
                }
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
            error("/operations", "終端の measure 命令がありません。")
        }
        return findings
    }
}
