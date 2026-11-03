"""Test the installed HeadSwap and androidTest APKs on an explicitly selected disposable emulator."""
import argparse
import os
import re
import secrets
import shlex
import subprocess
import sys

PACKAGE = "com.headswap.desk"
COMPONENT = "com.headswap.desk.test/com.headswap.desk.qa.CompatibilityProbe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--reconnect", action="store_true",
                        help="Test IME routing on the same virtual display before and after an authenticated reconnect")
    parser.add_argument("--density", action="store_true", help="Verify that creating and resizing virtual displays preserves the primary DPI")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Select a disposable Android emulator")

    def shell(command, timeout=45):
        return subprocess.check_output([args.adb, "-s", args.serial, "shell", command],
                                       text=True, timeout=timeout).strip()

    if shell("getprop ro.kernel.qemu") != "1" or shell("getprop ro.hardware") not in ("ranchu", "goldfish"):
        parser.error("The selected device is not an Android SDK emulator")
    api = int(shell("getprop ro.build.version.sdk"))
    if args.density:
        result = shell(shlex.join(["am", "instrument", "--no-hidden-api-checks", "-w", "-e",
                                   "probe", "density", COMPONENT]), timeout=60)
        print(result)
        return 0 if "INSTRUMENTATION_CODE: -1" in result and "primaryUnchanged=true" in result else 1
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard")
    options = ["-e", "probe", "panel-reconnect" if args.reconnect else "panel"]
    if api < 33:
        parser.error("HeadSwap requires Android 13 or later")
    # This target instrumentation replaces the normal HeadSwap process. Give that new
    # process its own helper session, using the same installed APK and UID as the real CLI.
    package_path = shell("pm path " + PACKAGE).splitlines()[0]
    if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_/.=+~-]+/base\.apk", package_path):
        parser.error("Unexpected installed APK path")
    uid = int(shell(f"run-as {PACKAGE} id -u"))
    token = secrets.token_hex(16)
    apk = package_path.removeprefix("package:")
    helper_mode = "ime" if args.reconnect else "ime-and-panel"
    shell(f"CLASSPATH={shlex.quote(apk)} /system/bin/setsid /system/bin/nohup /system/bin/app_process / "
          f"{PACKAGE}.dex.ShellPanelHelper {uid} {token} {helper_mode} </dev/null >/dev/null 2>&1 &")
    options.extend(["-e", "panel_token", token])
    result = shell(shlex.join(["am", "instrument", "--no-hidden-api-checks", "-w", *options, COMPONENT]), timeout=80)
    print(result)
    expected = ("imeHiddenBefore=true", "imeHiddenAfter=true") if args.reconnect else (
        "blanked=1", "restored=2")
    return 0 if "INSTRUMENTATION_CODE: -1" in result and all(item in result for item in expected) else 1


if __name__ == "__main__":
    sys.exit(main())
