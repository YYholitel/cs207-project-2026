"""Build a phone testing ZIP from the already verified debug APK and source files."""
from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parents[1]
delivery = root / "deliverables"
delivery.mkdir(exist_ok=True)
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
apk_name = "EGO1-Puzzle-0.2-debug.apk"
entries = {apk_name: apk.read_bytes(), "手机安装与测试.md": (root / "docs/phone-test.md").read_bytes()}
files = [root / name for name in (".gitignore", "README.md", "settings.gradle", "gradlew", "gradlew.bat", "app/build.gradle")]
for directory in ("app/src", "gradle", "tools", "docs"):
    files.extend(path for path in (root / directory).rglob("*") if path.is_file() and "__pycache__" not in path.parts)
for path in files:
    entries["android-source/" + path.relative_to(root).as_posix()] = path.read_bytes()
for path in (root / "app/build/outputs/preview").glob("*.png"):
    entries["screenshots/" + path.name] = path.read_bytes()
checksums = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(entries.items()))
entries["SHA256SUMS.txt"] = checksums.encode("utf-8")
target = delivery / "EGO1-Puzzle-Android-0.2.zip"
with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for name, data in entries.items():
        archive.writestr(name, data)
with zipfile.ZipFile(target) as archive:
    assert archive.testzip() is None
    for name, data in entries.items():
        assert archive.read(name) == data, name
(delivery / apk_name).write_bytes(entries[apk_name])
print(f"Verified package: {target.name}, {target.stat().st_size} bytes, {len(entries)} files")
