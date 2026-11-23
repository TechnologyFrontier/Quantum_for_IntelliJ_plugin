# 設計項目と実装・試験の対応

対象: QIDE-BD 基本設計書 0.3、QIDE-FD 機能設計書 0.2。
パスは `core/src/main/kotlin/com/technologyfrontier/qide/`（以下 `core/`）と
`src/main/kotlin/com/technologyfrontier/qide/plugin/`（以下 `plugin/`）からの相対。

## 機能設計書 1章 回路編集・検証・サンプル（IDE-01／04）

| 設計項目 | 実装 | 試験 |
|---|---|---|
| 回路を開く・補助的な指摘（最終判定はサービス） | `plugin/editor/CircuitEditorSupport.kt`（JSON スキーマ、構造インスペクション）、`src/main/resources/schema/circuit.schema.json` | `QuantumPluginPlatformTest` |
| 構造検証（版1、1〜64量子ビット、10,000命令、4 MiB、H・X・Z・RX・RZ・CX、終端測定、20量子ビットは実行時） | `core/domain/CircuitStructure.kt` | `CircuitStructureTest` |
| 検証：信頼済みのみ、編集中は保存確認・キャンセルで起動しない、成功／コードと説明 | `plugin/ui/QuantumActions.kt`（ValidateCircuitAction）、`plugin/ui/QuantumUi.kt` | GUI 評価 #2・#5・#6 |
| サンプルを追加：3種、プロジェクト配下へ複製、上書きしない、自動実行しない | `plugin/samples/AddSampleAction.kt`、`core/samples/Samples.kt`、`core/src/main/resources/samples/` | `CircuitStructureTest`（サンプルの妥当性・命名） |
| ベル回路サンプル | `core/src/main/resources/samples/bell.circuit.json`、`bell.md` | 同上 |

## 2章 実行構成とプロセス制御（IDE-02）

| 設計項目 | 実装 | 試験 |
|---|---|---|
| shots 1〜100,000（既定 1,024）、seed 省略または 0〜2⁶⁴−1 の正規十進文字列 | `core/domain/RunInputs.kt` | `RunInputsTest` |
| 回路パスの絶対化、一引数で渡す、シェルを介さない | `plugin/run/QuantumRunConfiguration.kt`、`core/adapter/QosctlArguments.kt`、`core/adapter/ProcessExecution.kt` | `RunInputsTest`、`QosctlCliClientTest` |
| 実行前確認（対象ファイル・信頼状態・CLI パス・入力値）、明示操作のみ | `QuantumRunConfiguration.getState`／`checkConfiguration` | GUI 評価 #2・#18 |
| プロジェクトごとに同時1件、BUSY を再試行しない | `core/application/RunCoordinator.kt` | `RunCoordinatorTest` |
| 停止：CLI 終了を要求、IDE で止めたことを示す、停止後の着信・部分出力は採用しない | `RunCoordinator.stop`、`ProcessExecutor`、`plugin/run/QuantumRunState.kt` | `RunCoordinatorTest`、`QosctlCliClientTest` |
| 遷移：待機→実行中→成功／失敗／停止、1回だけ確定、プロジェクト終了後は反映しない | `RunCoordinator`、`QuantumProjectService.dispose`、`QuantumUi.onUi` | `RunCoordinatorTest` |
| 任意コマンド入力欄を提供しない | 該当 UI なし | — |

## 3章 結果・再実行・エラー（IDE-03）

| 設計項目 | 実装 | 試験 |
|---|---|---|
| 終了値0かつ1行の有効な結果 JSON のみ表示、表と比率の棒、measured_qubits 順 | `core/adapter/QosctlResponses.kt`、`plugin/ui/ResultsToolWindow.kt` | `QosctlResponsesTest` |
| 受信検証（重複なし・辞書順・キー長・0/1・正整数・合計＝shots・seed 必須・各項目） | `QosctlResponses.parseRunResult` | `QosctlResponsesTest` |
| 再実行：結果の seed・shots を採用、回路変更は警告して確認、seed を浮動小数点に変換しない | `core/application/Rerun.kt`、`ResultsPanel.RerunAction` | `RunCoordinatorTest`、`StrictJsonTest` |
| 明示保存のみ、既定で永続化しない、共有の実行構成に seed を保存しない | `ResultsPanel.SaveResultAction`、`QuantumRunConfiguration.writeExternal` | `QuantumPluginPlatformTest.testSeedIsNotPersisted` |
| 3.1 終了値 2／3／4、code 優先、空出力・複数行・不正 JSON・出力上限超過は連携エラー | `QosctlResponses`、`core/domain/Results.kt` | `QosctlResponsesTest`、`QosctlCliClientTest` |

## 4章 状態・保守案内・設定（IDE-05／06）

| 設計項目 | 実装 | 試験 |
|---|---|---|
| 状態を確認：明示操作で status、ready／busy、失敗は区分表示 | `CheckStatusAction` | `QosctlResponsesTest`、`QosctlCliClientTest` |
| 保守案内：手順表示のみ（実行・資格情報取得・権限昇格・再起動をしない） | `plugin/ui/MaintenanceGuideDialog.kt` | GUI 評価 #14 |
| ローカル設定と共有設定の区別、CLI パスは導入側管理、プロジェクト設定で差し替えない | `plugin/settings/QosctlSettings.kt`（`RoamingType.DISABLED`、`-Dqide.qosctl.path`） | — |
| CLI がない場合もサンプル閲覧・編集は可能、外部実行は無効 | `QuantumUi.checkCanLaunch`、`QuantumProjectService` | — |

## 6〜8章 モジュール構成・呼び出し契約・戻り値

| 設計項目 | 実装 | 試験 |
|---|---|---|
| ui → application → domain、QosctlClient 契約を adapter が実装 | `core/application/QosctlClient.kt`、`core/adapter/QosctlCliClient.kt` | `RunCoordinatorTest`（代替実装） |
| adapter が唯一の CLI 起動元 | `QosctlCliClient`（`ProcessLauncher` 経由。IDE では `GeneralCommandLine`） | — |
| 引数列 validate／run／status | `QosctlArguments` | `RunInputsTest`、`QosctlCliClientTest` |
| 標準入力不使用、stdout／stderr を合流させず UTF-8 で取得、終了と収集完了後に判定、上限・期限超過で停止 | `ProcessExecutor` | `QosctlCliClientTest` |
| IPC の包みを読まない | `QosctlResponses.rejectEnvelope` | `QosctlResponsesTest` |
| shots の一致、明示 seed の照合、message で分岐しない | `QosctlResponses` | `QosctlResponsesTest` |
| バックグラウンド処理と UI スレッド反映の分離 | `QosctlTask`、`RunCoordinator` + `QuantumUi.onUi` | — |

## 実 qosctl との結合試験（`core/src/test/.../integration/RealQosctlIntegrationTest.kt`）

`scripts/real-qosctl-it.sh <qosctl> <qosd>` で実行。2026-11-23 に量子OS 0.1.0（QuantumLinuxOS 3a37900）の
ソースツリーから起動した qosd と実 qosctl に対して 14 件すべて合格。

| 試験 | 確認内容 |
|---|---|
| status is ready | `{"state":"ready"}` |
| bell circuit from design document | 1,024 ショットのキーが 00・11 の範囲内、合計 1,024（T-IDE-02） |
| bit order follows measured_qubits | X(1)→measure(1,0) が "10"（QOS-IF の例） |
| path with spaces and Japanese | 空白・日本語パスの validate／run（T-IDE-06） |
| seed boundaries round trip and reproduce | 0・2⁵³＋1・2⁶⁴−1 の往復と同一条件での観測回数一致（T-IDE-03） |
| omitted seed then rerun with returned seed | シード省略→返却シードで再実行して一致 |
| 20 and 21 qubit boundary | 20 は実行可、21 は validate 可・run は BACKEND_LIMIT（終了値 2）（T-IDE-02） |
| invalid measure and unknown schema are rejected | CIRCUIT_INVALID／SCHEMA_UNSUPPORTED（T-IDE-01） |
| local structure check agrees with service validate | 17 通りで構造チェックとサービス判定が一致 |
| busy while running, stop releases the slot… | 計算中の BUSY（終了値 3）、IDE からの停止でサービスが切断を検知し計算枠を解放、停止後の結果を採用しない（T-IDE-02） |
| ide side timeout stops qosctl | IDE 側の監視期限超過で qosctl を停止し、計算枠も解放 |
| service unavailable／access denied | 終了値 3 の SERVICE_UNAVAILABLE と ACCESS_DENIED を区別（T-IDE-04） |
| cli rejects invalid seed text | CLI 側でも非正規シードを RUN_OPTION_INVALID で拒否 |

## GUI 評価

2026-11-23 に Xvfb 上の IntelliJ IDEA 2025.3.6.2 で ZIP 導入から実行・停止・BUSY・保存まで 21 項目を確認（[gui-evaluation/README.md](gui-evaluation/README.md)）。

## 5章 受入試験との対応

| 試験 | 自動化済みの範囲 | 実機（GUI 評価構成）で残る確認 |
|---|---|---|
| T-IDE-01 | サンプル妥当性・命名（上書きしない）、構造チェック、CLI による不正測定・未知版の拒否（実 qosctl） | —（GUI 評価で確認済み） |
| T-IDE-02 | 終了値 2/3/4、BUSY、停止・遅延結果の不採用（ダミー CLI・実 qosctl）、ベル回路、20／21 量子ビット境界（実 qosctl） | —（GUI 評価で確認済み） |
| T-IDE-03 | seed 0・2⁵³＋1・2⁶⁴−1 の保持、範囲外拒否、JSON 破損・合計不正の不採用、同一条件での観測回数一致（実 qosctl） | —（GUI 評価で確認済み） |
| T-IDE-04 | ready／busy、サービス停止（SERVICE_UNAVAILABLE）と権限不許可（ACCESS_DENIED）の区別（実 qosctl） | —（GUI 評価で確認済み） |
| T-IDE-05 | Plugin Verifier（IU-253.33813.99：Compatible、オフライン） | ZIP 導入・自動保存なしは GUI 評価で確認済み。量子OSイメージ上での確認は未実施 |
| T-IDE-06 | 空白・日本語パス、seed 省略／0 の引数（実プロセス） | — |
| T-IDE-07 | 3操作の成功、終了値 2/3/4、IPC 包み、破損 JSON、停止後の遅延応答（ダミー CLI）、実 qosctl との結合 | — |
