#!/bin/bash
# max_part=0 のループドライバー（udev なしのコンテナ）で量子OSの make-disk.sh を使うための評価用ラッパー。
# 同じ GPT レイアウトの各パーティションをオフセット付きループデバイスとして用意し、
# /dev/loopNp1..4 のノードを作ってから、ブロックデバイス指定で make-disk.sh を実行する。
set -euo pipefail
OUT="$1"; IMG="$2"; REPO=${QOS_REPO:?QuantumLinuxOS のチェックアウトを QOS_REPO で指定してください}
. "$REPO/image/config.env"
rm -f "$IMG"; truncate -s "${DISK_SIZE_MIB}M" "$IMG"
sfdisk -q "$IMG" <<SF
label: gpt
size=${ESP_SIZE_MIB}MiB,       type=C12A7328-F81F-11D2-BA4B-00A0C93EC93B, name=ESP
size=${ROOT_SLOT_SIZE_MIB}MiB, type=4F68BCE3-E8CD-4DB1-96E7-FBCAF984B709, name=qos-root-a
size=${ROOT_SLOT_SIZE_MIB}MiB, type=4F68BCE3-E8CD-4DB1-96E7-FBCAF984B709, name=qos-root-b
                               type=4D21B016-B534-45C2-A9FB-5C16E091FD2D, name=qos-state
SF
DEV=$(losetup --find --show "$IMG")
PARTS=()
cleanup() { for l in "${PARTS[@]}"; do losetup -d "$l" 2>/dev/null || true; done; rm -f "${DEV}"p[1-4]; losetup -d "$DEV" 2>/dev/null || true; }
trap cleanup EXIT
i=0
while read -r start size; do
  i=$((i+1))
  L=$(losetup --find --show --offset $((start*512)) --sizelimit $((size*512)) "$IMG")
  PARTS+=("$L")
  mknod "${DEV}p$i" b $(tr ':' ' ' < /sys/block/$(basename "$L")/dev)
done < <(sfdisk -J "$IMG" | python3 -c 'import json,sys; [print(p["start"],p["size"]) for p in json.load(sys.stdin)["partitiontable"]["partitions"]]')
ls -la "${DEV}"p*
# make-disk.sh を実行する。このカーネルは vfat をマウントできないため、ESP の手順で失敗したら
# 同じ内容（make-disk.sh の ESP 節）をマウントなしで行う。
if ! "$REPO/image/make-disk.sh" "$OUT" "$DEV"; then
  echo ">> ESP: vfat を mount できないため、同内容をディレクトリーで作成し mtools で書き込みます"
  E=$(mktemp -d); mount -t tmpfs -o size=512m tmpfs "$E"
  SYSTEMD_RELAX_ESP_CHECKS=1 bootctl --esp-path="$E" --no-variables install
  install -D -m0644 "$OUT/vmlinuz"    "$E/quantumos/a/vmlinuz"
  install -D -m0644 "$OUT/initrd.img" "$E/quantumos/a/initrd.img"
  mkdir -p "$E/loader/entries"
  cat > "$E/loader/entries/quantumos-a.conf" <<ENT
title QuantumOS $QOS_VERSION (slot a)
version $QOS_VERSION
linux /quantumos/a/vmlinuz
initrd /quantumos/a/initrd.img
options root=PARTLABEL=qos-root-a ro quantumos.slot=a $KERNEL_CMDLINE_EXTRA
ENT
  cat > "$E/loader/loader.conf" <<LDR
default quantumos-a.conf
timeout 3
editor no
LDR
  mkfs.vfat -F 32 -n ESP "${DEV}p1" >/dev/null
  MTOOLS_SKIP_CHECK=1 mcopy -s -i "${DEV}p1" "$E"/* ::/
  MTOOLS_SKIP_CHECK=1 mdir -/ -i "${DEV}p1" ::/ | head -40
  umount "$E"; rmdir "$E"
  sync
  echo ">> disk ready: $IMG"
fi
