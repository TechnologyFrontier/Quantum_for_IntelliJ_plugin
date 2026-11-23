# GUI 評価報告（ローカル ZIP 導入・実 qosctl 結合）

実施日: 2026-11-23
対象: Quantum OS IDE プラグイン 0.1.0（`build/distributions/quantum-os-ide-plugin-0.1.0.zip`）

## 評価構成

| 項目 | 内容 |
|---|---|
| IDE | IntelliJ IDEA 2025.3.6.2（ビルド 253.33813.99、JBR 21.0.11）。ライセンスなし（無償範囲）で起動 |
| デスクトップ | Linux コンテナ上の Xvfb（1600×1000）＋ openbox。操作は xdotool によるマウス・キーボード入力 |
| 量子OS | TechnologyFrontier/QuantumLinuxOS 3a37900 のソースツリーから起動した `qosd`（開発用設定、利用者を許可）と、pip で導入した実 `qosctl` |
| CLI パス | 導入側管理の VM オプション `-Dqide.qosctl.path=/home/user/qos-venv/bin/qosctl` |
| ロケール | `LC_ALL=C.UTF-8` |
| 評価プロジェクト | `/home/user/gui-eval/projects/量子 回路 デモ`（空白・日本語を含むパス） |

**制約:** 量子OSイメージ（systemd のソケット起動、quantum グループ、読み取り専用ルート）上のデスクトップ評価構成ではない。
OS イメージ上での確認は別途必要（[../open-items.md](../open-items.md)）。

初回起動時の JetBrains User Agreement への同意は、利用者の承認を得て実施した。匿名統計の送信は「Don't Send」を選択した。

## 結果

| # | 確認項目 | 対応 | 結果 | 証跡 |
|---|---|---|---|---|
| 1 | 設定 → Plugins → Install Plugin from Disk で ZIP を導入 | T-IDE-05 | 合格（Downloaded に Quantum OS IDE 0.1.0 が表示。再起動不要で読み込み） | [01](01-zip-installed.png) |
| 2 | 未信頼プロジェクト（セーフモード）で「状態を確認」→ qosctl を起動しない | T-IDE-01 | 合格（警告通知。qosd のログ件数は変化なし） | [02](02-untrusted-blocked.png) |
| 3 | サンプル（ベル）の追加。自動実行しない | IDE-04 | 合格（bell.circuit.json／bell.md を追加して開く。qosd への要求なし） | [03](03-sample-added.png) |
| 4 | 同じサンプルの再追加で既存ファイルを上書きしない | T-IDE-01 | 合格（bell-2.* を作成。元ファイルの md5 は不変） | [04](04-sample-no-overwrite.png) |
| 5 | 信頼後「回路を検証」→ 有効 | IDE-01 | 合格（qosd: `op=validate result=OK`） | [05](05-validate-ok.png) |
| 6 | 編集中の回路で「回路を検証」→ 保存確認、Cancel で起動しない | IDE-01 | 合格（Cancel 時 qosd のログ件数は不変） | — |
| 7 | 不正な op の構造チェック（エディター） | IDE-01 | 合格（インスペクションとスキーマの両方が指摘） | [06](06-structure-check.png) |
| 8 | 不正な回路の検証 → サービスのエラー（終了値 2、CIRCUIT_INVALID） | 3.1 | 合格（message と案内を表示） | [07](07-validate-error.png) |
| 9 | 実行構成（Current File）でベル回路 1,024 ショット | T-IDE-02 | 合格（00=504、11=520、合計 1,024、キーは 00・11 のみ。seed 省略時は `--seed` なし） | [08](08-results-bell.png) |
| 10 | 結果ビュー（回数・比率・棒・measured_qubits・seed・backend_id・service_version） | IDE-03 | 合格 | [08](08-results-bell.png) |
| 11 | 再実行（結果の seed・shots を採用）→ 同じ観測回数 | T-IDE-03 | 合格（`--seed 14045576854515700417`、00=504／11=520 で一致） | [09](09-rerun-same-seed.png) |
| 12 | 結果 JSON の明示保存 | IDE-03 | 合格（qosctl の出力 1 行をそのまま保存。seed は文字列のまま） | — |
| 13 | 状態を確認（ready） | T-IDE-04 | 合格（「量子サービス: 待機中（ready）」） | — |
| 14 | 保守案内は表示のみ | T-IDE-04 | 合格（ボタンは OK のみ。qos-admin／qosctl のプロセスなし） | [16](16-maintenance-guide.png) |
| 15 | 他クライアントの計算中に実行 → BUSY（終了値 3）、自動再試行しない、直前の正常結果を保持 | T-IDE-02 | 合格（qosd の BUSY 記録は 1 件のみ） | [10](10-busy.png) |
| 16 | 実行中に IDE から停止 → CLI 終了、サービスは切断を検知して計算枠を解放、後着結果を採用しない | T-IDE-02 | 合格（qosd: `CLIENT_DISCONNECTED`、2 秒で ready。表は直前の結果のまま） | [11](11-stopped.png) |
| 17 | 設定画面：導入側管理の CLI パスは変更不可 | IDE-06 | 合格 | [12](12-settings-managed-path.png) |
| 18 | 実行構成の seed 検証（`01` を起動前に拒否） | T-IDE-03 | 合格 | [13](13-seed-validation.png) |
| 19 | 実行構成で seed `0` を明示 → `--seed 0` を渡す。設定画面の seed が実行まで保持される | T-IDE-06 | 合格（相対パスもプロジェクト基準で絶対化） | [14](14-explicit-seed-zero.png) |
| 20 | 共有の実行構成（Store as project file）に seed を保存しない | 3章 | 合格（`.idea/runConfigurations/bell_seed.xml` は circuitPath と shots のみ） | — |
| 21 | 結果・シードの自動保存なし | T-IDE-05 | 合格（IDE 終了後、シード値を含むファイルは明示保存した result.json のみ。IDE ログに回路・結果なし） | — |

## 評価で見つかり修正した不具合

| 不具合 | 修正 | 再確認 |
|---|---|---|
| 失敗時に結果ビュー上部の表示が右へずれる（BoxLayout の alignmentX 不一致） | 全部品を左寄せに統一 | [10](10-busy.png) |
| 実行時に Run ウィンドウが前面に出て、結果ビューが見えない | 実行の確定（成功・失敗）時に結果ビューを表示 | [15](15-results-auto-shown.png) |
| 結果ビューのアイコンが Run と同じで区別できない | 専用アイコン（棒グラフ）に変更 | [15](15-results-auto-shown.png) |
| 失敗通知のタイトルが長く途中で切れる。「状態を確認 を実行できません」の表記 | タイトルを「「操作」に失敗しました（code）」に短縮し、区分・詳細は本文へ。表記を「「操作」を実行できません」に統一 | [07](07-validate-error.png) |
| 保守案内の本文が HTML 既定のセリフ体 | IDE の UI フォントを使用 | [16](16-maintenance-guide.png) |

## 観察事項（未修正）

- 不正な回路では、プラグインの構造チェックと JSON スキーマの両方が同じ問題を指摘する（2 件表示）。最終判定はサービスで行うため実害はない。
- セーフモードで開いたプロジェクトは、信頼後もプロジェクトを開き直すまでエディター解析が動かない（IntelliJ 側の挙動）。
- 実行構成の seed は IDE 起動中のメモリにのみ保持するため、IDE 再起動後は空欄（省略）に戻る（設計どおり）。
- Xvfb・ウィンドウマネージャーなしの環境では Install from Disk が確定しない場合があった。ウィンドウマネージャー導入後は問題なし（評価環境の問題）。
