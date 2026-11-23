# 実装上の仮定と結合前に確定する事項

設計書（QIDE-BD 0.3／QIDE-FD 0.2）で「結合前に確定」とされた値、および参照文書
（量子OS インターフェース仕様書 QOS-IF 0.1）が手元にないため仮定した事項です。
確定後に該当箇所を更新してください。

## 仮値（結合前に確定）

| 項目 | 仮値 | 場所 |
|---|---|---|
| 標準出力の応答上限 | 16 MiB（20量子ビット・100,000 shots の最大出力に余裕を持たせた値） | `core/.../adapter/ProcessExecution.kt` `ExecutionLimits` |
| 標準エラーの応答上限 | 64 KiB | 同上 |
| 監視期限 validate／status／run | 各 180 秒（量子サービスの実行期限 120 秒＋qosctl の応答待ち余裕 30 秒＋IDE 側余裕 30 秒） | 同上 |
| 停止要求後の強制終了までの猶予 | 3 秒（SIGTERM → SIGKILL） | 同上 |
| qosctl の既定パス | `/usr/bin/qosctl`（導入側が `-Dqide.qosctl.path` で固定可能） | `plugin/settings/QosctlSettings.kt` |
| 作業ディレクトリー | 利用者のホーム（導入側が `-Dqide.qosctl.workdir` で固定可能） | 同上 |
| 対象 IDE ビルド | IntelliJ IDEA 2025.3.6.2（253.33813.99）、互換範囲 253.* | `gradle.properties` |

## QOS-IF 0.1 との整合（量子OSリポジトリ TechnologyFrontier/QuantumLinuxOS で確認済み）

- **rx／rz の角度**: `theta`（有限のラジアン値、rx・rz では必須、他の命令には不可）。スキーマと構造チェックに反映。
- **終端測定**: `measure` はちょうど1つで最後の命令。`operations` は空不可。未知キーは不可。構造チェックに反映し、
  17 通りの回路で構造チェックとサービスの validate の判定が一致することを結合試験で確認。
- **エラー code**: QOS-IF 4章の code（ACCESS_DENIED・SERVICE_UNAVAILABLE・BACKEND_LIMIT・TIMEOUT・TRANSPORT_ERROR 等）と、
  量子OS実装の暫定 code（USAGE_INVALID・FILE_UNREADABLE）ごとに案内を表示する（message の文言では分岐しない）。
- **結果 JSON の未知の項目**: 将来の拡張に備えて無視します（IPC の包みを示す `protocol_version`、`ok`＋`result` は拒否）。
- **measured_qubits の重複**: サービス側で重複を拒否するため、IDE では検査していません。

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

## 実 qosctl との結合（実施済み）

`scripts/real-qosctl-it.sh` で、量子OSリポジトリのソースツリーから起動した `qosd`（許可用・拒否用の2つ）と
pip で導入した実 `qosctl` を相手に `:core:realQosctlTest`（14 件）を実行し、すべて合格した。
量子OSイメージ（systemd のソケット起動、quantum グループ）上ではなく、開発コンテナ上での確認である。

## GUI 評価（実施済み）

Xvfb 上の IntelliJ IDEA 2025.3.6.2 に ZIP を GUI から導入し、実 qosctl／qosd と結合して 21 項目を確認した。
評価中に見つかった表示上の不具合 5 件は修正し、再確認済み。詳細と証跡は [gui-evaluation/README.md](gui-evaluation/README.md)。

## 量子OSイメージ上の評価（実施済み）

QuantumLinuxOS で作成した量子OSイメージ（UEFI・qemu、オフライン）上のデスクトップ評価構成で、ZIP 導入から実行・BUSY・停止までを確認した。
詳細は [gui-evaluation/quantum-os/README.md](gui-evaluation/quantum-os/README.md)。評価中に見つけた量子OS側の initramfs の不具合は QuantumLinuxOS に修正済み。

## 未解決

- 再起動なしで読み込んだ直後に結果ツールウィンドウが登録されないことがある（再起動で解消、再現条件は未特定）。
  導入手順で IDE の再起動を案内するか、`require-restart="true"` にするかを決める。

## 未実施

- 仮想化支援（KVM）のある実機・仮想機での性能評価。
- 対象 IDE ビルドの最終固定（現在は 2025.3.6.2 で確認）。
