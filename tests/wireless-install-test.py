#!/usr/bin/env python3
"""Run wireless-install checks against temporary adb and Gradle substitutes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "install-wireless.sh"
FAKE_ADB = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
p=Path(os.environ['WIRELESS_TEST_STATE'])
s=json.loads(p.read_text()); args=sys.argv[1:]; s['calls'].append(args)
if args == ['devices']:
 print('List of devices attached')
 for serial,status in s['devices'].items(): print(serial+'\t'+status)
elif args == ['mdns','services']:
 print('List of discovered mdns services')
 for row in s.get('services',[]): print(row)
elif args and args[0]=='pair':
 s['code_received']=sys.stdin.readline().strip()=='123456'
 if s.get('pair_fail'):
  print('pairing failed'); p.write_text(json.dumps(s)); sys.exit(1)
 print('Successfully paired [guid=adb-phone]')
elif args and args[0]=='connect':
 if not s.get('connect_fail'): s['devices'][args[1]]='device';print('connected')
 else: print('failed to connect')
elif args and args[0]=='-s':
 if args[2]=='get-state': print(s['devices'].get(args[1],'offline'))
 elif args[2:4]==['install','-r']:print('Success')
 else: raise SystemExit('unexpected adb target command')
elif args not in [['kill-server'],['start-server']]: raise SystemExit('unexpected adb command')
p.write_text(json.dumps(s))
'''
FAKE_GRADLE = r'''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
p=Path(os.environ['WIRELESS_TEST_STATE']); s=json.loads(p.read_text())
s['gradle']=sys.argv[1:];p.write_text(json.dumps(s))
if s.get('build_fail'):sys.exit(1)
apk=Path('androidApp/build/outputs/apk/debug/androidApp-debug.apk');apk.parent.mkdir(parents=True,exist_ok=True);apk.write_bytes(b'fixture')
'''

class WirelessInstallTest(unittest.TestCase):
    def run_script(self, args=(), devices=None, **state):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            shutil.copyfile(SCRIPT,root/'install-wireless.sh')
            (root/'fixture.apk').write_bytes(b'fixture')
            for name,source in [('adb',FAKE_ADB),('gradlew',FAKE_GRADLE),('sleep','#!/bin/sh\nexit 0\n')]:
                p=root/name;p.write_text(source);p.chmod(0o755)
            data=root/'state.json'
            data.write_text(json.dumps({'devices':devices or {},'calls':[],**state}))
            env={**os.environ,'ADB':str(root/'adb'),'WIRELESS_TEST_STATE':str(data),'PATH':str(root)+os.pathsep+os.environ['PATH']}
            result=subprocess.run(['bash',str(root/'install-wireless.sh'),*args],input='123456\n',text=True,capture_output=True,env=env,timeout=10,cwd=root)
            return result,json.loads(data.read_text())

    @staticmethod
    def installs(state):
        return [call for call in state['calls'] if len(call)>3 and call[0]=='-s' and call[2]=='install']

    def test_builds_and_installs_only_on_the_wireless_phone(self):
        result,state=self.run_script(['--','--offline'],devices={'usb-device':'device','adb-phone._adb-tls-connect._tcp':'device'})
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual([':androidApp:assembleDebug','--no-daemon','--offline'],state['gradle'])
        self.assertEqual('adb-phone._adb-tls-connect._tcp',self.installs(state)[0][1])
        self.assertNotIn(['kill-server'],state['calls'])

    def test_pairs_with_stdin_code_and_discovers_only_that_phone(self):
        result,state=self.run_script(['--pair','192.168.1.2:30000','--apk','fixture.apk'],services=[
            'adb-other _adb-tls-connect._tcp 192.168.1.3:40000',
            'adb-phone _adb-tls-connect._tcp 192.168.1.2:40000'])
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertTrue(state['code_received'])
        self.assertIn(['pair','192.168.1.2:30000'],state['calls'])
        self.assertEqual('192.168.1.2:40000',self.installs(state)[0][1])
        self.assertNotIn('gradle',state)
        self.assertNotIn('123456',result.stdout+result.stderr+json.dumps(state['calls']))

    def test_connect_exit_zero_still_requires_online_state(self):
        result,state=self.run_script(['--connect','192.168.1.2:40000','--apk','fixture.apk'],connect_fail=True)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual([],self.installs(state))
        self.assertNotIn('gradle',state)

    def test_multiple_online_phones_do_not_trigger_install(self):
        result,state=self.run_script(devices={'192.168.1.2:40000':'device','192.168.1.3:40000':'device'})
        self.assertNotEqual(0,result.returncode)
        self.assertEqual([],self.installs(state))
        self.assertNotIn('gradle',state)

    def test_explicit_serial_selects_one_of_multiple_phones(self):
        result,state=self.run_script(['--device','192.168.1.3:40000','--apk','fixture.apk'],devices={'192.168.1.2:40000':'device','192.168.1.3:40000':'device'})
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('192.168.1.3:40000',self.installs(state)[0][1])

    def test_failed_pair_does_not_restart_or_install(self):
        result,state=self.run_script(['--pair','192.168.1.2:30000'],pair_fail=True)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual([],self.installs(state))
        self.assertNotIn(['kill-server'],state['calls'])

    def test_restart_is_explicit(self):
        result,state=self.run_script(['--restart-adb','--connect','192.168.1.2:40000','--apk','fixture.apk'])
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual([['kill-server'],['start-server']],state['calls'][:2])

    def test_no_device_or_invalid_address_never_builds_or_installs(self):
        for args in [[],['--connect','192.168.1.2:70000']]:
            result,state=self.run_script(args)
            self.assertNotEqual(0,result.returncode)
            self.assertEqual([],self.installs(state))
            self.assertNotIn('gradle',state)

    def test_failed_build_never_installs_an_old_apk(self):
        result,state=self.run_script(devices={'192.168.1.2:40000':'device'},build_fail=True)
        self.assertNotEqual(0,result.returncode)
        self.assertEqual([],self.installs(state))

if __name__=='__main__': unittest.main()
