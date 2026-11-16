"""在 Windows 使用 Android SDK 和 JDK 构建、测试并校验调试版安装包。"""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT / ".tools"
BUILD = ROOT / "build"
BUILD.mkdir(exist_ok=True)
JAVA = Path(os.environ["JAVA_HOME"]) / "bin"
SDK = Path(os.environ.get("ANDROID_JAR", TOOLS / "platform/android-11/android.jar"))
BIN = Path(os.environ.get("ANDROID_BUILD_TOOLS", TOOLS / "build/android-11"))
API = Path(os.environ.get("XPOSED_API", TOOLS / "xposed.jar"))


def execute(*args):
    command = [str(args[0])] + [os.path.relpath(arg, ROOT) if isinstance(arg, Path) and arg.is_relative_to(ROOT) else str(arg) for arg in args[1:]]
    subprocess.run(command, cwd=ROOT, check=True)


def java_tool(name):
    return JAVA / (name + ".exe")


with tempfile.TemporaryDirectory(dir=BUILD) as directory:
    work = Path(directory)
    classes = work / "classes"
    classes.mkdir()
    tests = work / "tests"
    tests.mkdir()
    javac = java_tool("javac")
    java = java_tool("java")
    execute(javac, "-encoding", "UTF-8", "-d", tests,
            ROOT/"src/local/shake/Motion.java", ROOT/"test/local/shake/MotionTest.java")
    execute(java, "-cp", tests, "local.shake.MotionTest")
    execute(javac, "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-classpath", os.pathsep.join(map(str, (SDK, API))), "-d", classes,
            *sorted((ROOT/"src").rglob("*.java")))
    compiled = work/"compiled.jar"
    execute(java_tool("jar"), "cf", compiled, "-C", classes, ".")
    execute(java, "-cp", BIN/"lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26",
            "--lib", SDK, "--classpath", API, "--output", work, compiled)
    unsigned = work/"unsigned.apk"
    execute(BIN/"aapt.exe", "package", "-f", "-M", ROOT/"AndroidManifest.xml",
            "-S", ROOT/"res", "-A", ROOT/"assets", "-I", SDK, "-F", unsigned)
    with zipfile.ZipFile(unsigned, "a") as archive:
        archive.write(work/"classes.dex", "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    aligned = work/"aligned.apk"
    execute(BIN/"zipalign.exe", "-f", "4", unsigned, aligned)
    # 本地测试签名仅用于在同一台开发电脑上反复生成可覆盖安装的版本。
    key = BUILD/"local-debug.keystore"
    if not key.exists():
        execute(java_tool("keytool"), "-genkeypair", "-keystore", key, "-alias", "localdebug",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-storepass", "android",
                "-keypass", "android", "-dname", "CN=Local debug")
    signer = BIN/"lib/apksigner.jar"
    signed = work/"signed.apk"
    execute(java, "-jar", signer, "sign", "--ks", key, "--ks-pass", "pass:android",
            "--key-pass", "pass:android", "--out", signed, aligned)
    execute(java, "-jar", signer, "verify", "--verbose", signed)
    execute(BIN/"zipalign.exe", "-c", "4", signed)
    execute(BIN/"aapt.exe", "dump", "badging", signed)
    with zipfile.ZipFile(signed) as archive:
        assert archive.read("assets/xposed_init").strip() == b"local.shake.WechatHook"
        assert {"AndroidManifest.xml","classes.dex","resources.arsc"} <= set(archive.namelist())
    output = BUILD/"wechat-shake-0.8.apk"
    signed.replace(output)
    print(output)
