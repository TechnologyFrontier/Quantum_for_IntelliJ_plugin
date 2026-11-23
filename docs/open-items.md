# 実装上の仮定と結合前に確定する事項

設計書（QIDE-BD 0.3／QIDE-FD 0.2）で「結合前に確定」とされた値、および参照文書
（量子OS インターフェース仕様書 QOS-IF 0.1）が手元にないため仮定した事項です。
確定後に該当箇所を更新してください。

## 仮値（結合前に確定）

| 項目 | 仮値 | 場所 |
|---|---|---|
| 標準出力の応答上限 | 16 MiB（20量子ビット・100,000 shots の最大出力に余裕を持たせた値） | `core/.../adapter/ProcessExecution.kt` `ExecutionLimits` |
| 標準エラーの応答上限 | 64 KiB | 同上 |
| 監視期限 validate／status／run | 60 秒／30 秒／10 分 | 同上 |
| 停止要求後の強制終了までの猶予 | 3 秒（SIGTERM → SIGKILL） | 同上 |
| qosctl の既定パス | `/usr/bin/qosctl`（導入側が `-Dqide.qosctl.path` で固定可能） | `plugin/settings/QosctlSettings.kt` |
| 作業ディレクトリー | 利用者のホーム（導入側が `-Dqide.qosctl.workdir` で固定可能） | 同上 |
| 対象 IDE ビルド | IntelliJ IDEA 2025.3.6.2（253.33813.99）、互換範囲 253.* | `gradle.properties` |

## QOS-IF 0.1 に依存する仮定

- **rx／rz の角度パラメーター名**: 設計書に記載がないため、スキーマと構造チェックでは角度を検査していません。
  QOS-IF に従って `circuit.schema.json` と `CircuitStructure` に追加してください。
- **終端測定の解釈**: 「measure の後にゲートを置かない」として構造チェックしています。同一量子ビットの複数回測定は検査していません。
- **エラー code の一覧**: 分岐に使っているのは `BUSY` のみです。権限不許可・サービス停止などの code が確定したら、
  `QosFailure.ServiceError.guidance` に案内を追加してください（message の文言では分岐しません）。
- **結果 JSON の未知の項目**: 将来の拡張に備えて無視します（IPC の包みを示す `protocol_version`、`ok`＋`result` は拒否）。
- **measured_qubits の重複**: 検査していません。

## 実装方式の判断

- **子プロセス起動**: 設計案どおり IDE では `GeneralCommandLine` で起動します。プロセス制御（出力収集・上限・期限・停止）は
  IntelliJ 非依存の `ProcessExecutor` に置き、`ProcessLauncher` を差し替えてダミー CLI で試験しています。
- **seed の保持**: 実行構成の seed は XML に書き出さず、IDE 起動中のメモリにのみ保持します。
- **回路ファイルの識別**: スキーマ・構造チェック・右クリックからの実行構成作成は `*.circuit.json` を対象とします。
  「回路を検証」は任意の `.json` に対して使えます。
- **JSON 解析**: 重複キーの拒否と 64 ビット整数の精度保持のため、自前の厳格なパーサーを使います（外部依存なし）。

## 環境上の前提

- IDE は UTF-8 ロケールで起動する必要があります。POSIX ロケールの JVM では日本語を含むファイルパスを扱えません
  （開発コンテナで実際に発生したため、試験は `LC_ALL=C.UTF-8` で実行しています）。量子OSのデスクトップ評価構成で確認してください。
- Plugin Verifier は `-offline` で実行しています（Marketplace に接続しない）。

## 未実施（GUI 評価構成が必要）

- 量子OS上のデスクトップ評価構成での実機確認（ローカル ZIP 導入、実 qosctl との結合、オフライン動作）。
- 実行構成・結果ツールウィンドウ・保守案内ダイアログの操作確認（このリポジトリの試験はヘッドレス）。
