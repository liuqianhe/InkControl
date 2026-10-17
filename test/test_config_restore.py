"""离线执行实际 shell 函数；Android settings/proc/observer 用本地替身，不连接设备。"""
from __future__ import annotations

import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
MODULE = HERE.parent / "backend" / "lib"
BASH = os.environ.get("EINK_TEST_BASH")
if not BASH:
    # Windows 的 PATH 中 bash 可能是 WSL 启动器，明确优先使用本机 Git Bash。
    git_bash = Path("C:/Program Files/Git/bin/bash.exe")
    BASH = str(git_bash) if os.name == "nt" and git_bash.exists() else shutil.which("bash")


def posix(path: Path) -> str:
    text = str(path.resolve()).replace("\\", "/")
    if len(text) > 1 and text[1] == ":":
        text = "/" + text[0].lower() + text[2:]
    return text


def xml(rows: dict[str, str]) -> str:
    return ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<config>\n"
            + "".join(f'<cfg pkg="{pkg}" mode="{mode}" />\n' for pkg, mode in rows.items())
            + "</config>\n")


class ShellFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="config_monitor_", dir=HERE)
        self.root = Path(self.tmp.name)
        self.state = self.root / "state"
        self.cfg = self.root / "epd.xml"
        self.global_file = self.root / "global"
        self.cfg.write_text(xml({"com.a": "515", "com.b": "515", "com.other": "518"}), encoding="utf-8")
        self.global_file.write_text("1\n", encoding="utf-8")
        self.shell_source = f"""
set -e
STATE={shlex.quote(posix(self.state))}
CFG={shlex.quote(posix(self.cfg))}
CS_HOOK_DIR={shlex.quote(posix(self.root / 'boot'))}
CS_SERVICE_DIR={shlex.quote(posix(self.root / 'service'))}
CS_MODULE={shlex.quote(posix(self.root / 'live_module'))}
MODDIR={shlex.quote(posix(MODULE))}
CONFIG_SOURCE="$MODDIR/config_state.sh"
GLOBAL_FILE={shlex.quote(posix(self.global_file))}
settings() {{
  [ "${{FAIL_SETTINGS:-0}}" = 0 ] || return 1
  case "$1" in
    get) if [ -f "$GLOBAL_FILE" ]; then cat "$GLOBAL_FILE"; else echo null; fi ;;
    put) printf '%s\\n' "$4" > "$GLOBAL_FILE" ;;
    delete) rm -f "$GLOBAL_FILE" ;;
    *) return 1 ;;
  esac
}}
getprop() {{ [ "$1" != sys.boot_completed ] || echo "${{FAKE_BOOT:-0}}"; }}
pidof() {{ [ "$1" != system_server ] || printf '%s' "${{FAKE_SERVER:-}}"; }}
chcon() {{ :; }}
restorecon() {{ :; }}
. "$MODDIR/config_state.sh"
mkdir -p "$CS_MODULE"
"""

    def tearDown(self):
        self.tmp.cleanup()

    def run_shell(self, text: str, expected=0):
        result = subprocess.run([BASH, "--noprofile", "--norc", "-s"],
                                input=self.shell_source + text, text=True, encoding="utf-8",
                                capture_output=True, timeout=60)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result.stdout

    def ledger(self) -> str:
        return (self.state / "config_state/apps.tsv").read_text(encoding="utf-8")

class ConfigCase(ShellFixture):
    def test_global_restore_owned(self):
        self.run_shell("cs_capture_install\ncs_global_apply\ncs_global_restore\n")
        self.assertEqual(self.global_file.read_text().strip(), "1")

    def test_global_preserves_user_change_across_boot_and_restore(self):
        self.run_shell("cs_global_apply\nprintf '1\\n' > \"$GLOBAL_FILE\"\ncs_global_apply\ncs_global_restore\n")
        self.assertEqual(self.global_file.read_text().strip(), "1")
        self.assertIn("kept", (self.state / "config_state/dynamic.tsv").read_text())

    def test_global_missing_key_restores_by_delete(self):
        self.global_file.unlink()
        self.run_shell("cs_global_apply\ncs_global_restore\n")
        self.assertFalse(self.global_file.exists())

    def test_install_to_boot_user_change_respected(self):
        self.run_shell("cs_capture_install\nprintf '0\\n' > \"$GLOBAL_FILE\"\ncs_global_apply\n")
        self.assertFalse((self.state / "config_state/dynamic.tsv").exists())

    def test_restore_failure_retains_owned_for_retry(self):
        self.run_shell("cs_global_apply\nFAIL_SETTINGS=1\ncs_global_restore && exit 90\nFAIL_SETTINGS=0\n")
        self.assertIn("owned", (self.state / "config_state/dynamic.tsv").read_text())
        self.run_shell("cs_global_restore\n")
        self.assertEqual(self.global_file.read_text().strip(), "1")

    def test_batch_apply_and_restore(self):
        self.run_shell("cs_queue_batch 515 521\ncs_apps_boot apply\n")
        self.assertIn('pkg="com.a" mode="521"', self.cfg.read_text())
        self.assertIn("com.a 515 521 owned", self.ledger())
        self.run_shell("cs_apps_boot restore\ncs_apps_boot restore\n")
        self.assertEqual(self.cfg.read_text(), xml({"com.a": "515", "com.b": "515", "com.other": "518"}))

    def test_per_field_user_edit_new_row_and_deletion_preserved(self):
        self.run_shell("cs_queue_batch 515 521\ncs_apps_boot apply\n")
        self.cfg.write_text(xml({"com.a": "518", "com.other": "771", "com.new": "521"}), encoding="utf-8")
        before = self.cfg.read_bytes()
        self.run_shell("cs_apps_boot restore\n")
        self.assertEqual(self.cfg.read_bytes(), before)
        self.assertIn("com.a 515 521 kept", self.ledger())
        self.assertIn("com.b 515 521 kept", self.ledger())

    def test_partial_restore_touches_only_owned_fields(self):
        self.run_shell("cs_queue_batch 515 521\ncs_apps_boot apply\n")
        self.cfg.write_text(xml({"com.a": "518", "com.b": "521", "com.other": "771", "com.new": "521"}), encoding="utf-8")
        self.run_shell("cs_apps_boot restore\n")
        self.assertEqual(self.cfg.read_text(), xml({"com.a": "518", "com.b": "515", "com.other": "771", "com.new": "521"}))

    def test_user_change_between_queue_and_boot_is_not_overwritten(self):
        self.run_shell("cs_queue_batch 515 521\n")
        self.cfg.write_text(xml({"com.a": "518", "com.b": "515"}), encoding="utf-8")
        self.run_shell("cs_apps_boot apply\n")
        self.assertIn('pkg="com.a" mode="518"', self.cfg.read_text())
        self.assertIn("com.a 515 521 kept", self.ledger())

    def test_crash_after_xml_write_before_receipt_commit_retries(self):
        self.run_shell("cs_queue_batch 515 521\n")
        self.cfg.write_text(xml({"com.a": "521", "com.b": "521", "com.other": "518"}), encoding="utf-8")
        self.run_shell("cs_apps_boot apply\ncs_apps_boot restore\n")
        self.assertIn('pkg="com.a" mode="515"', self.cfg.read_text())

    def test_absent_before_deletes_only_module_added_field(self):
        self.run_shell("cs_record_app com.added absent 521 queued\ncs_apps_boot apply\ncs_apps_boot restore\n")
        self.assertNotIn("com.added", self.cfg.read_text())

    def test_unknown_xml_and_duplicate_packages_are_rejected(self):
        for invalid in (xml({"com.a": "515"}).replace("</config>", "<unknown/>\n</config>"),
                        xml({"com.a": "515"}).replace("</config>", '<cfg pkg="com.a" mode="521" />\n</config>')):
            self.cfg.write_text(invalid, encoding="utf-8")
            self.run_shell("cs_queue_batch 515 521 && exit 90\nexit 0\n")
            self.assertEqual(self.cfg.read_text(), invalid)

    def test_atomicfile_backup_and_running_server_block_xml_writes(self):
        self.run_shell("cs_queue_batch 515 521\n")
        original = self.cfg.read_bytes()
        self.run_shell("FAKE_SERVER=123\ncs_apps_boot apply && exit 90\nexit 0\n")
        self.assertEqual(self.cfg.read_bytes(), original)
        Path(str(self.cfg) + ".bak").write_bytes(original)
        self.run_shell("cs_apps_boot apply && exit 90\nexit 0\n")
        self.assertEqual(self.cfg.read_bytes(), original)

    def test_duplicate_record_cannot_replace_original_before(self):
        self.run_shell("cs_record_app com.a 515 521 owned\ncs_record_app com.a 518 521 owned && exit 90\nexit 0\n")
        self.assertEqual(self.ledger(), "com.a 515 521 owned\n")

    def test_disable_boot_hook_restores_when_module_service_wont_run(self):
        self.run_shell("cs_prepare_hooks\ncs_global_apply\ncs_queue_batch 515 521\ncs_apps_boot apply\n")
        # 执行生成的独立钩子，而不是只测试 cs_boot；模拟 Magisk disable 后模块脚本不执行。
        self.run_shell("touch \"$CS_MODULE/disable\"\nexport GLOBAL_FILE\nexport -f settings getprop pidof chcon restorecon\nbash \"$CS_HOOK_DIR/inkcontrol_backend_config_restore.sh\"\nFAKE_BOOT=1; export FAKE_BOOT\nbash \"$CS_SERVICE_DIR/inkcontrol_backend_config_restore.sh\"\n")
        self.assertEqual(self.global_file.read_text().strip(), "1")
        self.assertIn('pkg="com.a" mode="515"', self.cfg.read_text())

    def test_removed_module_hook_restores_and_keeps_audit(self):
        self.run_shell("cs_prepare_hooks\ncs_queue_batch 515 521\ncs_apps_boot apply\nrmdir \"$CS_MODULE\"\ncs_boot\n")
        self.assertIn("restored", self.ledger())
        self.assertTrue((self.state / "config_state/events.log").exists())

    def test_stop_runtime_defers_xml(self):
        self.run_shell("cs_global_apply\ncs_queue_batch 515 521\ncs_apps_boot apply\nFAKE_SERVER=123\ncs_request_restore\n")
        self.assertEqual(self.global_file.read_text().strip(), "1")
        self.assertIn('pkg="com.a" mode="521"', self.cfg.read_text())
        self.run_shell("cs_boot\n")
        self.assertIn('pkg="com.a" mode="515"', self.cfg.read_text())



if __name__ == "__main__":
    unittest.main(verbosity=2)
