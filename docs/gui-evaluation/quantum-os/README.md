# 量子OSイメージ上のデスクトップ評価構成での GUI 評価

実施日: 2026-11-23
対象: Quantum OS IDE プラグイン 0.1.0（`build/distributions/quantum-os-ide-plugin-0.1.0.zip`）

## 評価構成

| 項目 | 内容 |
|---|---|
| 量子OS | TechnologyFrontier/QuantumLinuxOS（`image/build-rootfs.sh`・`image/make-disk.sh`）で作成した Debian 13 (trixie) ベースの量子OS 0.1.0。読み取り専用ルート A/B、状態領域 `/var`、`/etc` オーバーレイ、`qosd.socket`（root:quantum 0660） |
| 標準構成 | `image/packages.txt` のまま。GUI・IDE なし（確認済み） |
| IDE 評価構成 | 標準構成とは別に作成した派生イメージ。パッケージ一覧に X・openbox・IDE 用ライブラリー・日本語フォントを追加（[packages-eval.txt](../../../scripts/quantum-os-eval/packages-eval.txt)）。IntelliJ IDEA 2025.3.6.2 とプラグイン ZIP は OS イメージに含めず、状態領域の `/var/opt` に配置 |
| 仮想マシン | qemu q35、UEFI（OVMF、Secure Boot 無効）、2 vCPU、8 GiB、virtio ディスク、**ネットワークなし**。ホストに仮想化支援がないため TCG（ソフトウェアエミュレーション） |
| 利用者 | `useradd -m -G quantum alice`（README の手順）。X は root、openbox と IDE は alice の権限で起動 |
| CLI パス | 導入側の指定なし。プラグインの既定値 `/usr/bin/qosctl`（OS の実パスと一致） |
| 評価プロジェクト | `/home/alice/量子 回路 デモ`（= `/var/home/alice/…`、空白・日本語を含むパス） |

初回起動時の JetBrains User Agreement（Version 2.0）には、利用者の承認（前回の GUI 評価時）に基づき同意した。匿名統計は送信しない設定にした。

## 量子OSの不具合（修正済み）

作成したイメージはそのままでは起動せず、initramfs の緊急シェルで停止した。
`image/initramfs/quantumos-state` の `/etc` オーバーレイのマウントで `-o` がデバイス名の後ろにあり、
busybox なしの initramfs で使われる klibc の mount が解釈できなかったため。
引数の順を直したイメージで評価し、修正は QuantumLinuxOS の main に反映した（ec96d6a、回帰試験付き）。

修正後、標準構成は無人で約 34 秒でログインまで起動し、`/` 読み取り専用、`/var` 状態領域、`/etc` オーバーレイ、
`qosd.socket` 有効、quantum グループ外の利用者は `ACCESS_DENIED`（終了値 3）であることを確認した。

## 結果

| # | 確認項目 | 結果 | 証跡 |
|---|---|---|---|
| 1 | 量子OS上の IDE に ZIP を Install from Disk で導入 | 合格（Downloaded に 0.1.0。オフラインのため Marketplace は読み込めない表示） | [01](01-zip-installed.png) |
| 2 | 「量子OS」メニュー | 合格 | [02](02-menu.png) |
| 3 | 状態を確認 → 既定パス `/usr/bin/qosctl` → `qosd.socket` のソケット起動 | 合格（通知「待機中（ready）」、journal に `op=status uid=1000 result=OK`） | [03](03-status-ready.png) |
| 4 | サンプル（ベル）の追加（状態領域上の日本語パス） | 合格 | [04](04-sample-added.png) |
| 5 | 実行構成でベル回路 1,024 ショット | 合格（journal に `op=run uid=1000 result=OK shots=1024`） | [05](05-run-console.png) |
| 6 | 結果ビュー | 合格（00=505、11=519、合計 1,024、完了時に自動表示） | [07](07-results-bell.png) |
| 7 | 再実行（同じ seed・shots）→ 同じ観測回数 | 合格 | [08](08-rerun-same-seed.png) |
| 8 | 別プロセスが計算中に実行 → BUSY（終了値 3）、再試行なし、直前の正常結果を保持 | 合格（journal の BUSY は 1 件） | [09](09-busy.png) |
| 9 | 実行中に Run ウィンドウの停止 → qosctl 終了、サービスが切断を検知し計算枠を解放 | 合格（journal `CLIENT_DISCONNECTED`、約 4 秒で ready） | [10](10-running.png)、[11](11-stopped.png) |
| 10 | 結果・シードの自動保存なし | 合格（`/var/home/alice`、`/var/log`、`/var/lib/quantumos` にシード値・結果なし） | — |
| 11 | オフライン動作 | 合格（VM はループバックのみ。プラグインの全機能が動作） | — |

前回のコンテナでの GUI 評価（[../README.md](../README.md)）で確認した OS 非依存の項目（未信頼プロジェクトでの起動抑止、上書きしないサンプル追加、構造チェック、seed の検証と非保存、保守案内など）は、エミュレーションの遅さのため量子OS上では再実施していない。

## 観察事項

1. **再起動なしで読み込んだ直後、結果ツールウィンドウが登録されないことがある。**
   新しいプロファイルで ZIP を導入し（動的ロード）、その後プロジェクトを開くと、「Quantum Results」がサイドバー・「その他」・View → Tool Windows のいずれにも出なかった。
   IDE を再起動すると現れ、以後は正常に動作した。前回のコンテナ評価では同じ手順で現れており、再現条件は未特定。
   対策: プラグインを再起動必須（`require-restart="true"`）にした（導入・更新時は IDE の再起動が必要）。
2. **「Plugin 'Quantum OS IDE' might be slowing things down」の表示。**
   （[06](06-slowdown-banner.png)）フリーズ記録を確認したところ、サンプル追加後にファイルを開く IDE 標準処理（`FileEditorManager.openFile`、81 秒）が、プラグインの操作に紐づけて計上されたもの。エミュレーション環境の遅さによる。
3. **新しいプロファイルでの1回目の Install from Disk は、ZIP をステージングするだけで読み込まれない**（2回目で読み込まれる）。コンテナ評価と同じ IDE 側の挙動。

## 評価環境に起因する事項（プラグイン・量子OSの不具合ではない）

- TCG（マルチスレッド）では JVM の JIT 生成コードが SIGSEGV で落ちた（`-cpu max` でも Haswell でも発生）。単一スレッド TCG（`-accel tcg,thread=single`）で安定した。
- 実行期限 120 秒を超える回路は量子サービスが `TIMEOUT` を返した（エミュレーションの遅さによる。サービスの仕様どおり）。
- コンテナに udev がなく、ループデバイスがパーティション分割に対応しない（`max_part=0`）ため、
  [make-disk-noscan.sh](../../../scripts/quantum-os-eval/make-disk-noscan.sh) でオフセット付きループデバイスを用意して `make-disk.sh` を実行した。
  vfat をマウントできないため、ESP は同じ内容をディレクトリーで作成し mtools で書き込んだ。
- 仮想マシンの操作は、シリアルコンソール経由の root シェルとゲスト内の xdotool、画面取得は QMP の screendump で行った（[vmctl.py](../../../scripts/quantum-os-eval/vmctl.py)）。
- 実機（KVM あり）での性能評価は未実施。
