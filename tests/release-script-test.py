import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "release.sh"
FAKE_GH = r'''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
state = Path(os.environ['GH_TEST_STATE'])
with Path(os.environ['GH_TEST_LOG']).open('a') as log:
    log.write(json.dumps(args) + '\n')
failed = os.environ.get('GH_TEST_FAIL') == '1' and not state.exists()
if args[:2] == ['repo', 'view']:
    print('test/qiafan')
elif args[:2] == ['run', 'list']:
    print('' if os.environ.get('GH_TEST_NO_RUN') == '1' else '42')
elif args[:2] == ['run', 'watch']:
    sys.exit(1 if failed else 0)
elif args[:2] == ['run', 'rerun']:
    state.touch()
elif args[:2] == ['run', 'view']:
    field = args[args.index('--json') + 1]
    print((2 if state.exists() else 1) if field == 'attempt' else ('failure' if failed else 'success'))
elif args[:2] == ['release', 'view']:
    field = args[args.index('--json') + 1]
    print('https://github.com/test/qiafan/releases/tag/' + args[2] if field == 'url'
          else ('false' if os.environ.get('GH_TEST_MISSING_ASSET') == '1' else 'true'))
else:
    sys.exit('Unexpected gh command: ' + repr(args))
'''


class ReleaseScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qiafan-release-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.work = self.base / "work"
        self.work.mkdir()
        self.remote = self.base / "remote.git"
        self.git("init", "--bare", str(self.remote))
        self.git("init", "-b", "master")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "test@example.invalid")
        shutil.copy2(SCRIPT, self.work / "release.sh")
        (self.work / "VERSION").write_text("1.0.2\n")
        workflow = self.work / ".github/workflows/android-release.yml"
        workflow.parent.mkdir(parents=True)
        workflow.write_text("name: Android Release\n")
        self.git("add", ".")
        self.git("commit", "-m", "base")
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "origin", "master")
        self.bin = self.base / "bin"
        self.bin.mkdir()
        for name, content in (("gh", FAKE_GH), ("sleep", "#!/bin/sh\nexit 0\n")):
            path = self.bin / name
            path.write_text(content)
            path.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        GH_TEST_LOG=str(self.base / "gh.log"), GH_TEST_STATE=str(self.base / "rerun"),
                        GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.work, text=True, stderr=subprocess.DEVNULL,
                                       env=dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")).strip()

    def run_script(self, *args, **env):
        return subprocess.run(["bash", "release.sh", *args], cwd=self.work,
                              env=dict(self.env, **env), text=True, capture_output=True, timeout=20)

    def assert_ok(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def remote_tags(self):
        return self.git("ls-remote", "--tags", "origin")

    def test_dry_run_preserves_dirty_files_and_creates_no_tag(self):
        (self.work / "draft.txt").write_text("keep me")
        result = self.run_script("1.0.3", "--dry-run")
        self.assert_ok(result)
        self.assertIn("预演", result.stdout)
        self.assertEqual(self.remote_tags(), "")
        self.assertEqual((self.work / "VERSION").read_text(), "1.0.2\n")
        self.assertFalse((self.base / "gh.log").exists())

    def test_invalid_versions_cannot_change_git(self):
        for version in ("1.0.03", "0.0.0", "2001.0.0", "1.1000.0", "1.0.3-rc1", "1.0.1"):
            with self.subTest(version=version):
                self.assertNotEqual(self.run_script(version).returncode, 0)
                self.assertEqual(self.remote_tags(), "")
                self.assertEqual((self.work / "VERSION").read_text(), "1.0.2\n")

    def test_dirty_worktree_blocks_release(self):
        (self.work / "VERSION").write_text("1.0.4\n")
        self.assertNotEqual(self.run_script("1.0.4").returncode, 0)
        self.assertEqual(self.remote_tags(), "")
        self.assertEqual((self.work / "VERSION").read_text(), "1.0.4\n")

    def test_success_pushes_version_commit_and_exact_tag(self):
        self.git("tag", "-a", "unrelated", "-m", "keep local")
        self.git("config", "push.followTags", "true")
        result = self.run_script("v1.0.3")
        self.assert_ok(result)
        self.assertIn("发布完成", result.stdout)
        sha = self.git("rev-parse", "v1.0.3^{commit}")
        self.assertEqual(self.git("show", "v1.0.3:VERSION"), "1.0.3")
        self.assertIn(sha, self.remote_tags())
        self.assertNotIn("unrelated", self.remote_tags())
        self.assertIn(sha, self.git("ls-remote", "origin", "refs/heads/master"))
        calls = [json.loads(line) for line in (self.base / "gh.log").read_text().splitlines()]
        lookup = next(call for call in calls if call[:2] == ["run", "list"])
        self.assertEqual(lookup[lookup.index("--commit") + 1], sha)
        self.assertEqual(lookup[lookup.index("--branch") + 1], "v1.0.3")

    def test_existing_tag_requires_resume_and_never_moves(self):
        self.assert_ok(self.run_script("1.0.3"))
        before = self.remote_tags()
        self.assertNotEqual(self.run_script("1.0.3").returncode, 0)
        (self.work / "draft.txt").write_text("uncommitted later work")
        self.assert_ok(self.run_script("1.0.3", "--resume"))
        self.assertEqual(self.remote_tags(), before)

    def test_local_tag_can_resume_atomic_push(self):
        (self.work / "VERSION").write_text("1.0.3\n")
        self.git("add", "VERSION")
        self.git("commit", "-m", "release version")
        self.git("tag", "-a", "v1.0.3", "-m", "release")
        self.assert_ok(self.run_script("1.0.3", "--resume"))
        self.assertIn("refs/tags/v1.0.3", self.remote_tags())

    def test_failed_action_and_missing_assets_do_not_report_success(self):
        failed = self.run_script("1.0.3", GH_TEST_FAIL="1")
        self.assertNotEqual(failed.returncode, 0)
        self.assertNotIn("发布完成", failed.stdout)
        missing = self.run_script("1.0.3", "--resume", GH_TEST_MISSING_ASSET="1")
        self.assertNotEqual(missing.returncode, 0)
        self.assertNotIn("发布完成", missing.stdout)

    def test_retry_reuses_tag_and_reruns_failed_workflow(self):
        self.assertNotEqual(self.run_script("1.0.3", GH_TEST_FAIL="1").returncode, 0)
        before = self.remote_tags()
        self.assert_ok(self.run_script("1.0.3", "--retry", GH_TEST_FAIL="1"))
        self.assertEqual(self.remote_tags(), before)
        self.assertTrue((self.base / "rerun").exists())

    def test_missing_workflow_does_not_report_success(self):
        result = self.run_script("1.0.3", GH_TEST_NO_RUN="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("发布完成", result.stdout)
        self.assertIn("90 秒", result.stderr)


if __name__ == "__main__":
    unittest.main()
