# 量子OS 開発IDE（IntelliJ IDEA プラグイン）

量子回路 JSON を IntelliJ IDEA で編集・検証し、同一量子OS上の `qosctl` で実行して測定結果を確認するためのプラグインです。

設計の根拠:

- QIDE-BD 基本設計書 0.3（構成・配布・運用）
- QIDE-FD 機能設計書 0.2（操作・入出力・受入試験、モジュール構成、qosctl 呼び出し契約）

設計項目と実装・試験の対応は [docs/traceability.md](docs/traceability.md)、
実装上の仮定と結合前に確定が必要な事項は [docs/open-items.md](docs/open-items.md) を参照してください。

## 機能

| 機能ID | 内容 | 入口 |
|---|---|---|
| IDE-01 | 回路の編集補助（JSON スキーマ・構造チェック）と `qosctl validate` | `*.circuit.json` を開く／右クリック → 量子OS → 回路を検証 |
| IDE-02 | 実行構成「量子回路」から `qosctl run`、停止 | 実行構成、`*.circuit.json` の右クリック → 実行 |
| IDE-03 | 結果ビュー（観測回数・比率・ビット順・シード）、再実行、結果 JSON の明示保存 | ツールウィンドウ **Quantum Results** |
| IDE-04 | サンプル（ビット反転・重ね合わせ・ベル）の取り込み | ツール → 量子OS → サンプルを追加… |
| IDE-05 | `qosctl status`（ready／busy）、管理者向け保守案内（表示のみ） | ツール → 量子OS → 状態を確認／保守案内 |
| IDE-06 | IDE 全体のローカル設定（qosctl の絶対パス等） | 設定 → ツール → 量子OS |

安全上の方針（基本設計書 4章）:

- 外部コマンドは **信頼済みプロジェクト** で利用者が **明示操作** した場合のみ起動します。
- 管理された絶対パスの `qosctl` を、シェルを介さず個別の引数で起動します。ソケットへの直接接続や `qos-admin` の起動はしません。
- 結果とシードは既定ではメモリにのみ保持し、プロジェクト・IDE ログへの自動保存や外部送信は行いません。実行構成にシードを保存しません。

## 構成

単一のプラグイン ZIP として配布します。ソースは論理モジュールに分かれています（機能設計書 6章）。

```
core/                      純 Kotlin（IntelliJ 非依存）。単体試験・ダミー CLI 契約試験の対象
  domain/                  入力条件（Shots, Seed）、結果・失敗の型、回路の構造チェック
  application/             QosctlClient 契約、RunCoordinator（実行枠・停止・確定）、再実行計画
  adapter/                 qosctl-adapter：引数構築、子プロセス制御、応答の検証と型変換（唯一の CLI 起動元）
  json/                    厳格な JSON パーサー（重複キー拒否、数値は字句のまま保持）
  samples/ + resources/samples   同梱サンプル
src/main/kotlin/.../plugin/ IntelliJ 連携
  ui/                      アクション、結果ツールウィンドウ、保守案内
  run/                     実行構成（Execution API）、ランナー、プロセスハンドラー
  editor/                  回路 JSON スキーマ提供、構造チェックのインスペクション
  samples/                 サンプル追加アクション
  settings/, bootstrap/    ローカル設定、プロジェクトサービスでの結線、GeneralCommandLine による起動
```

依存方向は `ui → application → domain`。`application` は `QosctlClient` 契約に依存し、`adapter` が実装します。

## ビルド

前提: JDK 21。IntelliJ Platform SDK の取得に JetBrains のリポジトリへの接続が必要です。

```bash
./gradlew buildPlugin
```

成果物: `build/distributions/quantum-os-ide-plugin-<版>.zip`

試験:

```bash
./gradlew test
```

```bash
./gradlew verifyPlugin
```

対象 IDE は `gradle.properties` の `platformVersion`（初期候補: IntelliJ IDEA 2025.3.6.2、ビルド 253）です。
互換範囲（`pluginSinceBuild`／`pluginUntilBuild`）は Plugin Verifier と実機で確認したビルドに限ります。

## 導入（ローカル ZIP）

1. IntelliJ IDEA の 設定 → プラグイン → ⚙ → ディスクからプラグインをインストール… で ZIP を選びます。
2. 導入側が `qosctl` のパスを管理する場合は、IDE の VM オプション（ヘルプ → カスタム VM オプションの編集）に次を追加します。設定画面からは変更できなくなります。

   ```
   -Dqide.qosctl.path=/usr/bin/qosctl
   ```

   指定しない場合は 設定 → ツール → 量子OS で絶対パスを設定します（既定 `/usr/bin/qosctl`）。
3. IDE は UTF-8 ロケールで起動してください（空白・日本語を含む回路パスを扱うため）。

`qosctl` が無い環境でも、サンプルの閲覧と回路の編集は利用できます（検証・実行・状態確認は無効）。

## 使い方

1. ツール → 量子OS → **サンプルを追加…** で「ベル」を選び、プロジェクト配下の追加先を選びます（既存ファイルは上書きせず別名で追加）。
2. `bell.circuit.json` を右クリック → 量子OS → **回路を検証**。
3. 同じファイルを右クリック → **実行**。実行構成で shots（1〜100,000、既定 1,024）と seed（空欄＝省略、0〜2⁶⁴−1）を指定できます。
4. **Quantum Results** で結果を確認。**再実行** は結果の seed と shots を使い、回路が変更されていれば警告します。**結果JSONを保存** で保存先を選んで保存します。
