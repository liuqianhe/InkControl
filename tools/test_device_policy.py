"""真实 Binder 权限开关检查；只操作固定验收包的每应用模式。"""
import argparse
import json
import time
from pathlib import Path
from device_session import Session


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--session',required=True,type=Path);p.add_argument('--prefix',required=True);a=p.parse_args()
    m=json.loads(a.session.read_text(encoding='utf-8'));s=Session(m['serial'],m['output'])
    probe='CLASSPATH=/data/local/tmp/inkcontrol-mode-probe-v2.dex app_process /system/bin EpdModeProbe '
    def untrusted(name):
        # shell APK 由平台签名，按策略本来就允许；使用独立普通 APK 的真实 Binder UID。
        s.adb('shell','am','force-stop','com.inkcontrol.test')
        s.adb('shell','am','start','-n','com.inkcontrol.test/.PolicyActivity');time.sleep(2)
        return json.loads(s.record(a.prefix+'-'+name,'cat /data/user/0/com.inkcontrol.test/files/policy-fixture.json'))
    original=s.record(a.prefix+'-before',probe+'get')
    assert 'fixture_mode=3' in original,original
    try:
        r=s.probe(a.prefix+'-on','change',id='fix.permissions',enabled='true');assert r['statuses']['fix.permissions']=='已生效'
        result=untrusted('app-on')
        assert not result['success'] and 'EinkFix: addAppMode denied' in result['error'],result
        assert 'fixture_mode=3' in s.record(a.prefix+'-denied-unchanged',probe+'get')
        r=s.probe(a.prefix+'-off','change',id='fix.permissions',enabled='false');assert r['statuses']['fix.permissions']=='关闭'
        result=untrusted('app-off')
        assert result['success'] and result['mode']==9,result
        print('真实 Binder：开启拒绝未授权写入，关闭后恢复原厂允许行为',flush=True)
    finally:
        s.probe(a.prefix+'-final-off','change',id='fix.permissions',enabled='false')
        s.record(a.prefix+'-restore',probe+'515');time.sleep(3)
        assert 'fixture_mode=3' in s.record(a.prefix+'-final',probe+'get')


if __name__=='__main__':main()
