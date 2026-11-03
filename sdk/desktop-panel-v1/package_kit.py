"""Build a source-clean, standalone development kit from the verified release AAR."""
from argparse import ArgumentParser
from hashlib import sha256
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

SDK = Path(__file__).resolve().parent
ROOT = SDK.parents[1]
SAMPLE = ROOT / "examples" / "android-desktop-panel"
AAR_NAME = "headswap-desktop-profile-sdk-v1-dev.aar"


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("zip", type=Path)
    args = parser.parse_args()
    files = {
        "README.md": (SDK / "KIT-README.md").read_bytes(),
        "LICENSE": (ROOT / "LICENSE").read_bytes(),
        "libs/" + AAR_NAME: args.aar.read_bytes(),
        "gradle.properties": ("desktopPanelSdkAar=../libs/" + AAR_NAME + "\n").encode(),
    }
    for name in ("gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"):
        files[name] = (ROOT / "android" / name).read_bytes()
    for name in ("settings.gradle.kts", "app/build.gradle.kts"):
        files[name] = (SAMPLE / name).read_bytes()
    for p in sorted((SAMPLE / "app/src").rglob("*")):
        if p.is_file():
            files[p.relative_to(SAMPLE).as_posix()] = p.read_bytes()
    for p in sorted((SDK / "src/main/aidl").rglob("*.aidl")):
        files["api/" + p.relative_to(SDK / "src/main/aidl").as_posix()] = p.read_bytes()
    files["SHA256SUMS.txt"] = "".join(f"{sha256(data).hexdigest()}  {name}\n" for name, data in sorted(files.items())).encode()
    args.zip.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.zip, "w") as archive:
        for name, data in sorted(files.items()):
            entry = ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = (0o100755 if name == "gradlew" else 0o100644) << 16
            archive.writestr(entry, data, compress_type=ZIP_DEFLATED)


if __name__ == "__main__":
    main()
