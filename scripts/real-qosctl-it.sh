#!/usr/bin/env bash
# 実際の量子OS CLI（qosctl）と量子サービス（qosd）を相手にした結合試験を実行する。
#
# 使い方:
#   scripts/real-qosctl-it.sh /path/to/qosctl /path/to/qosd
#
# 開発用の qosd を2つ（現在の利用者を許可するもの／許可しないもの）一時ディレクトリーで起動し、
# :core:realQosctlTest を実行して停止する。量子OS上で systemd の qosd を使う場合は、
# QIDE_QOSCTL と QOS_SOCKET を設定して ./gradlew :core:realQosctlTest を直接実行する。
set -euo pipefail

QOSCTL=${1:?qosctl の絶対パスを指定してください}
QOSD=${2:?qosd の絶対パスを指定してください}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$WORK"' EXIT

uid=$(id -u)
echo "{\"allowed_group\": null, \"allowed_uids\": [$uid], \"socket_group\": null}" > "$WORK/allow.json"
echo "{\"allowed_group\": null, \"allowed_uids\": [$((uid + 4242))], \"socket_group\": null}" > "$WORK/deny.json"

"$QOSD" --config "$WORK/allow.json" --socket "$WORK/quantum.sock" > "$WORK/qosd.log" 2>&1 &
"$QOSD" --config "$WORK/deny.json" --socket "$WORK/denied.sock" > "$WORK/qosd-denied.log" 2>&1 &
for _ in $(seq 50); do
  [[ -S "$WORK/quantum.sock" && -S "$WORK/denied.sock" ]] && break
  sleep 0.1
done

export QIDE_QOSCTL="$QOSCTL" QOS_SOCKET="$WORK/quantum.sock" QIDE_QOS_SOCKET_DENIED="$WORK/denied.sock"
export LC_ALL=C.UTF-8 LANG=C.UTF-8
status=0
"$ROOT/gradlew" -p "$ROOT" :core:realQosctlTest --console=plain || status=$?
echo "--- qosd log (tail) ---"
tail -n 30 "$WORK/qosd.log"
exit $status
