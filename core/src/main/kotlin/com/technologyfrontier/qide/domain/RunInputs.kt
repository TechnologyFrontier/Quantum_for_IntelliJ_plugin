package com.technologyfrontier.qide.domain

import java.math.BigInteger
import java.nio.file.Path

/**
 * 64ビット符号なしシード。正規な ASCII 十進文字列（先頭ゼロなし、符号・空白なし）として保持し、
 * 浮動小数点や符号付き整数へ変換しない（機能設計書 2章・3章）。
 */
@JvmInline
value class Seed private constructor(val decimal: String) {
    override fun toString(): String = decimal

    companion object {
        val MAX: BigInteger = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        private val CANONICAL = Regex("0|[1-9][0-9]{0,19}")

        /** 正規な十進表現で 0〜2⁶⁴−1 の範囲なら Seed を返す。 */
        fun parse(text: String): Seed? {
            if (!CANONICAL.matches(text)) return null
            if (BigInteger(text) > MAX) return null
            return Seed(text)
        }
    }
}

/** ショット数 1〜100,000（既定 1,024）。 */
@JvmInline
value class Shots private constructor(val value: Int) {
    override fun toString(): String = value.toString()

    companion object {
        const val MIN = 1
        const val MAX = 100_000
        const val DEFAULT_VALUE = 1_024
        val DEFAULT = Shots(DEFAULT_VALUE)

        fun of(value: Long): Shots? = if (value in MIN..MAX) Shots(value.toInt()) else null

        /** 正規な十進文字列のみ受け付ける。 */
        fun parse(text: String): Shots? {
            if (!Regex("[1-9][0-9]{0,5}").matches(text)) return null
            return of(text.toLong())
        }
    }
}

/** run 操作の入力。回路パスは絶対パス。seed が null なら --seed を渡さない。 */
data class RunRequest(val circuit: Path, val shots: Shots, val seed: Seed?) {
    init {
        require(circuit.isAbsolute) { "回路パスは絶対パスである必要があります: $circuit" }
    }
}

/** 実行構成・画面で入力された値の検証結果。 */
sealed interface InputCheck<out T> {
    data class Ok<T>(val value: T) : InputCheck<T>
    data class Invalid(val message: String) : InputCheck<Nothing>
}

object RunInputValidator {
    fun shots(text: String): InputCheck<Shots> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return InputCheck.Ok(Shots.DEFAULT)
        return Shots.parse(trimmed)?.let { InputCheck.Ok(it) }
            ?: InputCheck.Invalid("shots は ${Shots.MIN}〜${"%,d".format(Shots.MAX)} の整数で指定してください。")
    }

    /** 空欄はシード省略（null）。前後の空白は入力ミスとして拒否せず除去する。 */
    fun seed(text: String): InputCheck<Seed?> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return InputCheck.Ok(null)
        return Seed.parse(trimmed)?.let { InputCheck.Ok(it) }
            ?: InputCheck.Invalid("seed は空欄、または 0〜18446744073709551615 の十進整数（先頭ゼロ・符号なし）で指定してください。")
    }
}
