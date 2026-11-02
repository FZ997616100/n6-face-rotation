"""Build the legacy Xposed module with JDK 17 and an Android SDK.

The Xposed API 82 jar is downloaded from its official repository if absent.
Signing keys and generated files remain under work/ and outputs/ respectively.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

MODULE = Path(__file__).resolve().parent
ROOT = MODULE.parent
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
XPOSED_URL = "https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar"
XPOSED_SHA256 = "f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25"
EXE = ".exe" if os.name == "nt" else ""


def run(*args):
    arguments = []
    for arg in args:
        if isinstance(arg, Path):
            try:
                arg = arg.relative_to(ROOT)
            except ValueError:
                pass
        arguments.append(str(arg))
    result = subprocess.run(arguments, cwd=ROOT, capture_output=True,
                            text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(Path(str(args[0])).name + " failed:\n" + result.stdout + result.stderr)
    return result.stdout + result.stderr


def sdk_directory(explicit):
    if explicit:
        candidates = [Path(explicit).expanduser()]
    else:
        candidates = [Path(value).expanduser() for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME")
                      if (value := os.environ.get(name))]
        candidates.extend([Path.home() / "AppData/Local/Android/Sdk",
                           Path.home() / "Android/Sdk", Path.home() / "Library/Android/sdk"])
        if os.name == "nt":
            candidates.append(Path("C:/Android/Sdk"))
    for candidate in candidates:
        if candidate.is_dir():
            return candidate.resolve()
    raise RuntimeError("Android SDK not found; use --sdk or ANDROID_SDK_ROOT.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", help="Android SDK directory (or ANDROID_SDK_ROOT/ANDROID_HOME)")
    parser.add_argument("--build-tools", default="36.0.0", help="SDK build-tools version")
    parser.add_argument("--platform", default="36", help="SDK platform API level")
    parser.add_argument("--xposed-api", help="Official API 82 jar (or XPOSED_API_JAR)")
    args = parser.parse_args()
    sdk = sdk_directory(args.sdk)
    tools = sdk / "build-tools" / args.build_tools
    android = sdk / "platforms" / ("android-" + args.platform) / "android.jar"
    xposed_arg = args.xposed_api or os.environ.get("XPOSED_API_JAR")
    xposed = Path(xposed_arg).expanduser().resolve() if xposed_arg else ROOT / "work/module-deps/xposed-api-82.jar"
    if not xposed.is_file():
        if xposed_arg:
            raise RuntimeError("The supplied Xposed API jar does not exist.")
        xposed.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(XPOSED_URL, timeout=60) as response:
            content = response.read()
        if hashlib.sha256(content).hexdigest() != XPOSED_SHA256:
            raise RuntimeError("Downloaded Xposed API 82 checksum does not match.")
        xposed.write_bytes(content)
    if hashlib.sha256(xposed.read_bytes()).hexdigest() != XPOSED_SHA256:
        raise RuntimeError("Xposed API jar must match the official API 82 SHA-256.")
    java_home = os.environ.get("JAVA_HOME")
    javac_path = str(Path(java_home) / "bin" / ("javac" + EXE)) if java_home else shutil.which("javac")
    if not javac_path:
        raise RuntimeError("JDK 17 not found; set JAVA_HOME or add javac to PATH.")
    javac = Path(javac_path).resolve()
    java = javac.parent / ("java" + EXE)
    keytool = javac.parent / ("keytool" + EXE)
    for required in (javac, java, keytool, android, tools / ("aapt2" + EXE),
                     tools / ("zipalign" + EXE), tools / "lib/d8.jar", tools / "lib/apksigner.jar"):
        if not required.is_file():
            raise RuntimeError("Missing build dependency: " + required.name + "; check JDK/SDK arguments.")
    source_manifest = ET.parse(MODULE / "AndroidManifest.xml").getroot()
    package = source_manifest.attrib["package"]
    version = source_manifest.attrib[ANDROID_NS + "versionName"]
    version_code = int(source_manifest.attrib[ANDROID_NS + "versionCode"])
    min_api = source_manifest.find("uses-sdk").attrib[ANDROID_NS + "minSdkVersion"]
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    work = ROOT / "work" / ("module-build-" + stamp)
    classes, dex = work / "classes", work / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    outputs = ROOT / "outputs"
    outputs.mkdir(exist_ok=True)
    sources = sorted((MODULE / "src").rglob("*.java"))
    run(javac, "-encoding", "UTF-8", "-source", "8", "-target", "8", "-classpath",
        os.pathsep.join((str(android), str(xposed))), "-d", classes, *sources)
    class_jar = work / "module-classes.jar"
    with zipfile.ZipFile(class_jar, "w", zipfile.ZIP_DEFLATED) as archive:
        for item in sorted(classes.rglob("*.class")):
            archive.write(item, item.relative_to(classes).as_posix())
    run(java, "-cp", tools / "lib/d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", min_api,
        "--lib", android, "--classpath", xposed, "--output", dex, class_jar)
    compiled = work / "resources.zip"
    run(tools / ("aapt2" + EXE), "compile", "--dir", MODULE / "res", "-o", compiled)
    unsigned = work / "unsigned.apk"
    run(tools / ("aapt2" + EXE), "link", "-o", unsigned, "--manifest", MODULE / "AndroidManifest.xml",
        "-I", android, "-A", MODULE / "assets", compiled)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    aligned = work / "aligned.apk"
    run(tools / ("zipalign" + EXE), "-f", "-p", "4", unsigned, aligned)
    signing = ROOT / "work/module-signing"
    signing.mkdir(exist_ok=True)
    key = signing / "n6-face-rotation-debug.jks"
    if not key.exists():
        run(keytool, "-genkeypair", "-noprompt", "-keystore", key, "-storepass", "android", "-keypass", "android",
            "-alias", "n6-face-rotation", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=N6 Face Rotation Task Debug")
    apk = outputs / ("n6-face-rotation-v" + version + ".apk")
    signer = [java, "-jar", tools / "lib/apksigner.jar"]
    run(*signer, "sign", "--ks", key, "--ks-key-alias", "n6-face-rotation", "--ks-pass", "pass:android",
        "--key-pass", "pass:android", "--out", apk, aligned)
    signature = run(*signer, "verify", "--verbose", "--print-certs", apk)
    (work / "signature-verification.txt").write_text(signature, encoding="utf-8")
    run(tools / ("zipalign" + EXE), "-c", "-p", "4", apk)
    badging = run(tools / ("aapt2" + EXE), "dump", "badging", apk)
    resources = run(tools / ("aapt2" + EXE), "dump", "resources", apk)
    manifest = run(tools / ("aapt2" + EXE), "dump", "xmltree", apk, "--file", "AndroidManifest.xml")
    (work / "manifest-verification.txt").write_text(badging + "\n" + manifest + "\n" + resources, encoding="utf-8")
    with zipfile.ZipFile(apk) as archive:
        dex_files = [name for name in archive.namelist() if name.endswith(".dex")]
        assert dex_files == ["classes.dex"], dex_files
        assert archive.read("assets/xposed_init").decode().strip() == "com.waf.n6facerotation.MainHook"
        assert archive.read("assets/xposed_scope").decode().splitlines() == ["system", "com.aiunit.aon"]
    assert package in badging and ("versionCode='" + str(version_code) + "'") in badging
    assert ("versionName='" + version + "'") in badging
    assert "xposedscope" in manifest and "xposedminversion" in manifest
    assert '"com.aiunit.aon"' in resources and '"android"' in resources
    scope = ET.parse(MODULE / "res/values/arrays.xml").getroot().find("string-array[@name='xposed_scope']")
    assert [item.text for item in scope.findall("item")] == ["android", "com.aiunit.aon"]
    report = {
        "apk": apk.relative_to(ROOT).as_posix(), "package": package, "version": version, "versionCode": version_code,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "bytes": apk.stat().st_size,
        "runtimeScope": ["system", "com.aiunit.aon"], "recommendedScope": ["android", "com.aiunit.aon"],
        "xposedApi": 82, "xposedApiSha256": XPOSED_SHA256, "dexFiles": dex_files,
        "signatureVerified": True, "zipAlignmentVerified": True, "xposedInitVerified": True,
        "workDirectory": work.relative_to(ROOT).as_posix(), "deviceVerified": False,
    }
    (outputs / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
