package com.technologyfrontier.qide.adapter

import com.technologyfrontier.qide.domain.RunRequest
import java.nio.file.Path

/**
 * qosctl の引数列（実行ファイル名の後に渡す個別の引数）。機能設計書 7章。
 * シェルを介さないため、空白や日本語を含むパスも1引数として渡し、引用符を付けない。
 */
object QosctlArguments {
    fun validate(circuit: Path): List<String> {
        require(circuit.isAbsolute) { "回路パスは絶対パスである必要があります" }
        return listOf("validate", circuit.toString())
    }

    fun run(request: RunRequest): List<String> = buildList {
        add("run")
        add(request.circuit.toString())
        add("--shots")
        add(request.shots.value.toString())
        request.seed?.let {
            add("--seed")
            add(it.decimal)
        }
    }

    fun status(): List<String> = listOf("status")
}
