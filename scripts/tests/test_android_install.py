"""Exercise the real CLI and compatibility entry point against an isolated fake SDK."""
import os
import errno
import pty
import select
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[2]
FAKE_ADB = r'''
import os, sys, time
from pathlib import Path
args = sys.argv[1:]
phones = [] if os.environ.get("TEST_NO_PHONES") else ["phoneA", "phoneB"]
if args[0] == "devices":
    print("List of devices attached")
    for serial in phones:
        print(serial + "\tdevice" + (" product:test model:test" if "-l" in args else ""))
    print("emulator-5554\tdevice" + (" product:test" if "-l" in args else ""))
    print("offline-phone\toffline")
    sys.exit(0)
serial, command = args[1], args[2:]
if command[0] == "get-state":
    print("device" if serial in phones else "offline")
    sys.exit(0)
if command[:2] == ["shell", "getprop"]:
    prop = command[2]
    if prop == "ro.kernel.qemu": print("0")
    if prop == "ro.build.characteristics": print("tablet")
    if prop == "ro.product.device" and os.environ.get("TEST_NO_CODE") != serial:
        print({"phoneA": "dada", "phoneB": "elish"}.get(serial, "emulator"))
    if prop == "ro.product.model": print("MODEL-" + serial)
    sys.exit(0)
if command[:3] != ["install", "-r", "-t"] or not Path(command[3]).is_file():
    sys.exit(99)
events = Path(os.environ["TEST_EVENTS"])
def record(kind):
    fd = os.open(events, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
    os.write(fd, (kind + " " + serial + "\n").encode())
    os.close(fd)
record("start")
# A sequential implementation cannot cross this barrier and fails deterministically.
deadline = time.monotonic() + 2
expected = int(os.environ["TEST_EXPECTED"])
while len([line for line in events.read_text().splitlines() if line.startswith("start ")]) < expected:
    if time.monotonic() > deadline:
        print("installation workers were not concurrent")
        record("end")
        sys.exit(42)
    time.sleep(.01)
time.sleep(.02 if serial == os.environ.get("TEST_FAIL") else .15)
record("end")
if serial == os.environ.get("TEST_FAIL"):
    print("Failure [INSTALL_FAILED_USER_RESTRICTED]")
    sys.exit(int(os.environ.get("TEST_FAIL_RC", "1")))
print("Performing Streamed Install\nSuccess")
'''
FAKE_GRADLE = r'''
import os, sys
from pathlib import Path
with Path("build-count").open("a") as target: target.write("build\n")
if os.environ.get("TEST_BUILD_FAIL"):
    print("build failed", file=sys.stderr)
    sys.exit(1)
output = Path("app/build/outputs/apk/debug/kujira-test.apk")
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(b"test APK")
'''


class AndroidInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        for name in ("android", "install-debug.sh"):
            shutil.copy2(PROJECT / "scripts" / name, self.root / "scripts" / name)
        # JDK selection is unrelated to this test; the real build still uses jdk-guard.sh.
        (self.root / "scripts/jdk-guard.sh").write_text("require_gradle_jdk() { return 0; }\n")
        sdk = self.root / "sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        self.executable(sdk / "platform-tools/adb", FAKE_ADB)
        self.executable(self.root / "gradlew", FAKE_GRADLE)
        self.env = dict(os.environ, ANDROID_HOME=str(sdk), TEST_EVENTS=str(self.root / "events"), TEST_EXPECTED="2")

    def executable(self, path, body):
        path.write_text(f"#!{sys.executable}\n" + body)
        path.chmod(0o755)

    def events(self):
        path = self.root / "events"
        return path.read_text().splitlines() if path.exists() else []

    def run_cli(self, *args, wrapper=False, **environment):
        command = ["bash", str(self.root / "scripts" / ("install-debug.sh" if wrapper else "android")), *args]
        proc = subprocess.Popen(command, env=dict(self.env, **environment), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        output = []
        try:
            for line in proc.stdout:
                output.append(line)
                if "[ERROR] install" in line:
                    self.assertEqual(int(environment.get("TEST_EXPECTED", "2")), sum(e.startswith("end ") for e in self.events()),
                                     "device errors must only be reported after every worker finishes")
            proc.wait(timeout=10)
        finally:
            proc.stdout.close()
            if proc.poll() is None:
                proc.kill()
                proc.wait()
        return proc.returncode, "".join(output)

    def assert_target_list_only_during_detection(self, output, *names):
        detection, installation = output.split("正在构建", 1)
        listed_targets = [line for line in output.splitlines() if line.startswith("  ") and " — " in line]
        self.assertEqual(len(names), len(listed_targets), output)
        for name in names:
            self.assertTrue(any(line.endswith(f" — {name}") for line in listed_targets), output)
        for line in listed_targets:
            self.assertIn(line, detection)
            self.assertNotIn(line, installation)
        self.assertNotIn("设备 #", output)

    def test_detection_displays_codenames(self):
        code, output = self.run_cli("devices")
        self.assertEqual(0, code)
        self.assertIn("dada (MODEL-phoneA)", output)
        self.assertIn("elish (MODEL-phoneB)", output)
        self.assertIn("[offline]", output)

    def test_wrapper_builds_once_then_installs_all_phones_concurrently(self):
        code, output = self.run_cli(wrapper=True)
        self.assertEqual(0, code, output)
        self.assertEqual(["build"], (self.root / "build-count").read_text().splitlines())
        self.assertEqual({"start phoneA", "start phoneB"}, set(self.events()[:2]))
        self.assertIn("已经安装到 dada", output)
        self.assertIn("已经安装到 elish", output)
        self.assertEqual(4, len(self.events()))
        self.assert_target_list_only_during_detection(output, "dada", "elish")

    def test_terminal_keeps_progress_while_installations_run_concurrently(self):
        master, slave = pty.openpty()
        proc = subprocess.Popen(["bash", str(self.root / "scripts/install-debug.sh")],
                                env=self.env, stdin=subprocess.DEVNULL, stdout=slave, stderr=slave)
        os.close(slave)
        output = bytearray()
        try:
            while True:
                readable, _, _ = select.select([master], [], [], 10)
                self.assertTrue(readable, "terminal installation did not finish")
                try:
                    data = os.read(master, 65536)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not data:
                    break
                output.extend(data)
            proc.wait(timeout=5)
        finally:
            os.close(master)
            if proc.poll() is None:
                proc.kill()
                proc.wait()
        self.assertEqual(0, proc.returncode, output.decode())
        self.assertIn("正在并发安装", output.decode())
        self.assertIn("已经安装到 dada", output.decode())
        self.assertIn("已经安装到 elish", output.decode())
        self.assert_target_list_only_during_detection(output.decode(), "dada", "elish")
        self.assertEqual({"start phoneA", "start phoneB"}, set(self.events()[:2]))

    def test_failure_does_not_prevent_other_devices_and_logs_remain_separate(self):
        code, output = self.run_cli("install-debug", TEST_FAIL="phoneA")
        self.assertEqual(1, code, output)
        self.assertIn("已经安装到 elish", output)
        self.assertIn("install failed on dada", output)
        self.assertIn("1/2 devices failed", output)
        self.assert_target_list_only_during_detection(output, "dada", "elish")
        log = (self.root / "build/install-debug-install.log").read_text()
        self.assertIn("=== dada (phoneA) ===", log)
        self.assertIn("=== elish (phoneB) ===", log)
        self.assertIn("INSTALL_FAILED_USER_RESTRICTED", log)
        self.assertIn("Success", log)

    def test_zero_exit_with_vendor_failure_output_is_still_a_failure(self):
        code, output = self.run_cli("install-debug", TEST_FAIL="phoneB", TEST_FAIL_RC="0")
        self.assertEqual(1, code, output)
        self.assertIn("已经安装到 dada", output)
        self.assertIn("install failed on elish", output)

    def test_explicit_serial_only_installs_that_device(self):
        code, output = self.run_cli("install-debug", "phoneB", TEST_EXPECTED="1")
        self.assertEqual(0, code, output)
        self.assertEqual(["start phoneB", "end phoneB"], self.events())
        self.assert_target_list_only_during_detection(output, "elish")
        self.assertNotIn("dada", output)
        self.assertIn("已经安装到 elish", output)

    def test_model_is_used_if_codename_is_unavailable(self):
        code, output = self.run_cli("devices", TEST_NO_CODE="phoneA")
        self.assertEqual(0, code, output)
        self.assertIn("MODEL-phoneA", output)

    def test_install_detection_uses_model_fallback_without_repeating_the_list(self):
        code, output = self.run_cli("install-debug", "phoneA", TEST_EXPECTED="1", TEST_NO_CODE="phoneA")
        self.assertEqual(0, code, output)
        self.assert_target_list_only_during_detection(output, "MODEL-phoneA")
        self.assertIn("已经安装到 MODEL-phoneA", output)

    def test_build_failure_never_starts_installs(self):
        code, output = self.run_cli("install-debug", TEST_BUILD_FAIL="1")
        self.assertEqual(1, code, output)
        self.assertEqual([], self.events())
        self.assertIn("build failed", output)
        self.assertEqual(["build"], (self.root / "build-count").read_text().splitlines())

    def test_no_phones_never_builds_or_installs_emulator(self):
        code, output = self.run_cli("install-debug", TEST_NO_PHONES="1")
        self.assertEqual(1, code, output)
        self.assertFalse((self.root / "build-count").exists())
        self.assertEqual([], self.events())


if __name__ == "__main__":
    unittest.main()
