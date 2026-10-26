#!/usr/bin/env python3
"""Exercise non-debuggable RELEASE -> RELEASE on an empty emulator using real UI."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from release_signing import PACKAGE, build_tools, inspect, pinned_certificate

TITLE = "release-upgrade-fixture"
BODY = "preserved-note-content-123456"
WINDOW = "/sdcard/Download/pfm-release-window.xml"


def find_node(tree, text):
    # Compose can merge title/body into one accessibility node. Prefer exact labels.
    for partial in (False, True):
        for node in tree.iter("node"):
            values = (node.get("text", ""), node.get("content-desc", ""))
            if any(value == text or (partial and text in value) for value in values):
                bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
                if bounds:
                    left, top, right, bottom = map(int, bounds.groups())
                    if right > left and bottom > top:
                        return (left + right) // 2, (top + bottom) // 2
    return None


def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=90).decode().strip()


def wait_node(text, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            adb("shell", "uiautomator", "dump", "--compressed", WINDOW)
            tree = ET.fromstring(adb("shell", "cat", WINDOW))
            position = find_node(tree, text)
            if position:
                return position
        except (subprocess.CalledProcessError, ET.ParseError):
            pass
        time.sleep(1)
    raise RuntimeError("Release UI did not show expected fixture element: " + text)


def tap(text):
    x, y = wait_node(text)
    adb("shell", "input", "tap", str(x), str(y))


def uid():
    output = adb("shell", "pm", "list", "packages", "-U", PACKAGE)
    match = re.search(r"uid:(\d+)", output)
    if not match:
        raise RuntimeError("Cannot determine the release application's UID")
    return match[1]


def launch():
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-p", PACKAGE)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--previous", required=True)
    parser.add_argument("--next", required=True)
    parser.add_argument("--certificate-file", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    expected = pinned_certificate(args.certificate_file)
    tools = build_tools()
    previous = inspect(Path(args.previous), expected, tools)
    following = inspect(Path(args.next), expected, tools)
    if following["version_code"] <= previous["version_code"]:
        raise ValueError("Upgrade requires a strictly increasing versionCode")
    if not adb("get-serialno").startswith("emulator-") or adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This disposable-fixture test only runs on an emulator")
    installed = subprocess.run(["adb", "shell", "pm", "path", PACKAGE], capture_output=True, timeout=30)
    if installed.returncode not in (0, 1):
        raise RuntimeError("Cannot check emulator package state")
    if b"package:" in installed.stdout:
        raise RuntimeError("Emulator already has this app; refusing to replace or uninstall it")
    adb("shell", "mkdir", "-p", "/sdcard/Download")
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "input", "keyevent", "224")
    adb("shell", "wm", "dismiss-keyguard")
    adb("install", args.previous)
    adb("shell", "appops", "set", "--uid", PACKAGE, "MANAGE_EXTERNAL_STORAGE", "allow")
    launch()
    tap("Заметки")
    tap("Новая заметка")
    tap("Заголовок")
    adb("shell", "input", "text", TITLE)
    tap("Текст заметки")
    adb("shell", "input", "text", BODY)
    wait_node("Сохранено")
    tap("Назад")
    wait_node(TITLE)
    original_uid = uid()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("install", "-r", args.next)
    if uid() != original_uid:
        raise RuntimeError("Application UID changed during install-r")
    launch()
    tap("Заметки")
    tap(TITLE)
    wait_node(BODY)
    report = {"result": "PASS", "previous": previous, "next": following,
              "same_uid": True, "note_title_preserved": True, "note_body_preserved": True,
              "uninstall_performed": False, "data_clear_performed": False,
              "scope": "empty API-35 emulator; disposable note fixture"}
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
