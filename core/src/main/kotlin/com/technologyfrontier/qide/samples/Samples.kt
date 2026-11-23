package com.technologyfrontier.qide.samples

/** 同梱サンプル（IDE-04）。資源はプラグイン配布物の /samples 配下。 */
enum class Sample(val baseName: String, val title: String, val summary: String) {
    BIT_FLIP("bit-flip", "ビット反転", "X ゲートで |0⟩ を |1⟩ に反転して測定"),
    SUPERPOSITION("superposition", "重ね合わせ", "H ゲートで重ね合わせを作って測定"),
    BELL("bell", "ベル", "H と CX でベル状態を作り 2 量子ビットを測定"),
    ;

    val circuitResource: String get() = "/samples/$baseName.circuit.json"
    val guideResource: String get() = "/samples/$baseName.md"
}

/** 取り込み先のファイル名。回路と説明は同じ番号で揃える。 */
data class SampleFileNames(val circuit: String, val guide: String)

object SampleNaming {
    /**
     * 既存ファイルを上書きしない名前を選ぶ。[exists] が true を返す名前は使わない。
     * 例: bell.circuit.json が既にあれば bell-2.circuit.json と bell-2.md。
     */
    fun choose(sample: Sample, exists: (String) -> Boolean): SampleFileNames {
        var index = 1
        while (true) {
            val stem = if (index == 1) sample.baseName else "${sample.baseName}-$index"
            val names = SampleFileNames("$stem.circuit.json", "$stem.md")
            if (!exists(names.circuit) && !exists(names.guide)) return names
            index++
            check(index < 10_000) { "空いているファイル名が見つかりません" }
        }
    }
}
