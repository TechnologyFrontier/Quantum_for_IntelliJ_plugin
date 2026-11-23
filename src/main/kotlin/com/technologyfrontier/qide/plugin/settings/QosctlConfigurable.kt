package com.technologyfrontier.qide.plugin.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/** 設定 → ツール → 量子OS。IDE 全体のローカル設定のみを扱う。 */
class QosctlConfigurable : BoundSearchableConfigurable("量子OS", "qide.settings") {

    private val settings = QosctlSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val state = settings.state
        val managedPath = settings.managedCliPath
        val managedWorkDir = settings.managedWorkingDirectory
        return panel {
            group("qosctl（量子OS CLI）") {
                row("qosctl の絶対パス:") {
                    if (managedPath != null) {
                        textField().applyToComponent { text = managedPath }.enabled(false).align(AlignX.FILL)
                            .comment("導入側が管理しています（VM オプション -D${QosctlSettings.MANAGED_PATH_PROPERTY}）。")
                    } else {
                        textFieldWithBrowseButton(
                            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("qosctl を選択"),
                        ).bindText({ state.cliPath.orEmpty() }, { state.cliPath = it.trim() })
                            .align(AlignX.FILL)
                            .comment("導入手順で指定された絶対パスを設定します。プロジェクト単位では変更できません。")
                    }
                }
                row("作業ディレクトリー:") {
                    if (managedWorkDir != null) {
                        textField().applyToComponent { text = managedWorkDir }.enabled(false).align(AlignX.FILL)
                            .comment("導入側が管理しています（VM オプション -D${QosctlSettings.MANAGED_WORKDIR_PROPERTY}）。")
                    } else {
                        textFieldWithBrowseButton(
                            FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("作業ディレクトリーを選択"),
                        ).bindText({ state.workingDirectory.orEmpty() }, { state.workingDirectory = it.trim() })
                            .align(AlignX.FILL)
                            .comment("空欄の場合はホームディレクトリーを使用します。")
                    }
                }
            }
            row {
                comment(
                    "qosctl が無い環境でも、サンプルの閲覧と回路の編集は利用できます（検証・実行・状態確認は無効）。" +
                        "結果とシードは既定では保存されません。",
                )
            }
        }
    }
}
