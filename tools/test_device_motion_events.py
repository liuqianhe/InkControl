"""显式授权的实际窗口/锁屏事件检查；旧 APK 仅作为测试窗口，不启用旧模块。"""
import argparse
import json
import time
from pathlib import Path
from device_session import Session


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--session',required=True,type=Path);p.add_argument('--prefix',required=True);a=p.parse_args()
    m=json.loads(a.session.read_text(encoding='utf-8'));s=Session(m['serial'],m['output']);index=0
    ids=('app_open','app_close','in_app_forward','in_app_back','dialog','gc_clear','lock','unlock')
    def change(id, enabled, value=None):
        nonlocal index
        kwargs={'id':'motion.'+id,'enabled':str(enabled).lower()}
        if value is not None: kwargs['value']=value
        r=s.probe(a.prefix+'-%02d'%index,'change',**kwargs);index+=1
        assert r['statuses']['motion.'+id]==('已生效' if enabled else '关闭'),r['statuses']
        print(id,enabled,r['statuses']['motion.'+id],flush=True)
    try:
        change('master',True)
        for id in ids:change(id,True,'ink' if id=='app_open' else 'scan' if id=='dialog' else None)
        s.adb('shell','input','keyevent','224');s.adb('shell','wm','dismiss-keyguard')
        s.root('am start -n com.einkmotion/.DemoActivity --ei page 1');time.sleep(2)
        s.tap_text('下一页 →',a.prefix+'-forward');time.sleep(1)
        s.tap_text('← 返回',a.prefix+'-back');time.sleep(1)
        s.tap_text('测试对话框',a.prefix+'-dialog');time.sleep(1)
        (s.output/(a.prefix+'-dialog.png')).write_bytes(s.adb('exec-out','screencap','-p').stdout)
        s.tap_text('关闭',a.prefix+'-close-dialog');time.sleep(1)
        s.adb('shell','input','keyevent','3');time.sleep(1)
        s.adb('shell','input','keyevent','223');time.sleep(3)
        s.adb('shell','input','keyevent','224');time.sleep(3)
        s.adb('shell','wm','dismiss-keyguard')
        text=s.record(a.prefix+'-events','getprop debug.eink.motion.req; getprop debug.eink.motion.gc; logcat -d -s EinkGPU | tail -n 180')
        assert 'MOTION wave=' in text or 'LOCK_MOTION wave=' in text,text
    finally:
        change('master',False)
        for id in ids:change(id,False)
        text=s.record(a.prefix+'-restored','printf "GC="; getprop debug.eink.motion.gc; printf "REQ="; getprop debug.eink.motion.req; logcat -d -b crash')
        assert 'gcsweep' not in text,text
        s.adb('shell','input','keyevent','224');s.adb('shell','wm','dismiss-keyguard');s.adb('shell','input','keyevent','3')


if __name__=='__main__':main()
