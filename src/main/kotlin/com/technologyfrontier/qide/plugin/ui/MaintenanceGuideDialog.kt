package com.technologyfrontier.qide.plugin.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * 管理者向けの保守案内（機能設計書 4章）。表示専用で、IDE から qos-admin を起動しない。
 */
class MaintenanceGuideDialog(project: Project?) : DialogWrapper(project, false) {

    init {
        title = "量子OS 保守案内（管理者向け）"
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val pane = JEditorPane("text/html", GUIDE_HTML).apply {
            // HTML 既定のセリフ体ではなく、IDE の UI フォントで表示する。
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = com.intellij.util.ui.UIUtil.getLabelFont()
            isEditable = false
            caretPosition = 0
        }
        return JBScrollPane(pane).apply { preferredSize = JBUI.size(640, 480) }
    }

    companion object {
        val GUIDE_HTML = """
            <html><body>
            <h2>更新は管理者がローカル管理コンソールで行います</h2>
            <p>この IDE からは、更新コマンドの実行、資格情報の取得、権限昇格、再起動を行いません。
            以下の手順は、管理者が量子OSのローカル管理コンソールで実行するためのものです。</p>
            <h3>1. 更新状態の確認</h3>
            <pre>qos-admin update-status</pre>
            <h3>2. 更新バンドルの適用</h3>
            <pre>qos-admin update BUNDLE</pre>
            <p>BUNDLE には配布された更新バンドルのパスを指定します。</p>
            <h3>A/B 更新の仕組み</h3>
            <ul>
              <li>更新バンドルの署名とハッシュを検証します。</li>
              <li>稼働中のルートは書き換えず、非稼働側のルートへ書き込んでから再照合します。</li>
              <li>次回起動で健全性確認に合格した試行先だけを確定します。</li>
              <li>失敗や期限超過の場合は、次回起動で確定済みのルートへ戻します。利用者データは巻き戻しません。</li>
            </ul>
            <h3>IDE の「状態を確認」について</h3>
            <p>IDE の状態確認は <code>qosctl status</code> による量子サービスの ready／busy の確認です。
            OS 全体や A/B スロットの健全性判定には使いません。</p>
            </body></html>
        """.trimIndent()
    }
}
