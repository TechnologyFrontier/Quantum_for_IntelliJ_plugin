#!/usr/bin/env python3
"""Evaluation helper for the Quantum OS VM.

  vmctl.py sh 'command'        run a shell command as root on the serial console
  vmctl.py shot out.png        take a screenshot of the VM display (QMP screendump)
"""
import json
import os
import re
import socket
import sys
import time
import uuid

BASE = os.path.dirname(os.path.abspath(__file__))
SERIAL = os.path.join(BASE, "serial.sock")
QMP = os.path.join(BASE, "qmp.sock")
PASSWORD_FILE = os.environ.get("QOS_ROOT_PASSWORD_FILE", "")


def _recv_until(sock, patterns, timeout):
    buf = b""
    end = time.time() + timeout
    while time.time() < end:
        sock.settimeout(max(0.1, end - time.time()))
        try:
            chunk = sock.recv(65536)
        except socket.timeout:
            break
        if not chunk:
            break
        buf += chunk
        text = buf.decode("utf-8", "replace")
        for p in patterns:
            if re.search(p, text):
                return text
    return buf.decode("utf-8", "replace")


def sh(command, timeout=600):
    s = socket.socket(socket.AF_UNIX)
    s.connect(SERIAL)
    s.sendall(b"\r")
    text = _recv_until(s, [r"login: $", r"# $", r"Password: $"], 20)
    if re.search(r"login: $", text):
        s.sendall(b"root\r")
        _recv_until(s, [r"Password: $"], 20)
        text = "Password: "
    if re.search(r"Password: $", text):
        with open(PASSWORD_FILE) as f:
            s.sendall(f.read().strip().encode() + b"\r")
        _recv_until(s, [r"# $"], 60)
        s.sendall(b"stty -echo cols 250; export TERM=dumb PAGER=cat SYSTEMD_PAGER=cat\r")
        _recv_until(s, [r"# $"], 10)
    tag = uuid.uuid4().hex[:8]
    s.sendall(f"echo BEGIN{tag}; {command}; echo END{tag} rc=$?\r".encode())
    out = _recv_until(s, [rf"END{tag} rc=\d+"], timeout)
    s.close()
    m = re.search(rf"BEGIN{tag}\r?\n(.*)END{tag} rc=(\d+)", out, re.S)
    if not m:
        print(out)
        return 99
    print(m.group(1).replace("\r", ""), end="")
    return int(m.group(2))


def shot(path):
    s = socket.socket(socket.AF_UNIX)
    s.connect(QMP)
    f = s.makefile("rw")
    f.readline()
    for cmd in ({"execute": "qmp_capabilities"},
                {"execute": "screendump", "arguments": {"filename": os.path.abspath(path), "format": "png"}}):
        f.write(json.dumps(cmd) + "\n")
        f.flush()
        while True:
            line = json.loads(f.readline())
            if "return" in line or "error" in line:
                if "error" in line:
                    print(line)
                break
    s.close()


if __name__ == "__main__":
    if sys.argv[1] == "sh":
        sys.exit(sh(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 600))
    elif sys.argv[1] == "shot":
        shot(sys.argv[2])
