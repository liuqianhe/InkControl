"""真实媒体会话期间通过产品页面切换两处歌词，避免 Instrumentation 打断媒体进程。"""
import argparse
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
from device_session import Session

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--session',required=True,type=Path);p.add_argument('--prefix',required=True);a=p.parse_args()
    m=json.loads(a.session.read_text(encoding='utf-8'));s=Session(m['serial'],m['output']);step=0
    def shot(name):
        (s.output/(a.prefix+'-'+name+'.png')).write_bytes(s.adb('exec-out','screencap','-p').stdout)
    def center(node):
        x,y,r,b=map(int,re.findall(r'\d+',node.get('bounds')))
        s.adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
    def toggle(text,group='锁屏'):
        # 界面三级：功能 → 分类页（开关以 content-desc 标注功能名）→ 详情；成功结果写入“操作结果”提示条，失败才弹窗。
        nonlocal step
        s.adb('shell','am','start','-n','com.inkcontrol/.ui.MainActivity');time.sleep(1)
        s.tap_text('功能',a.prefix+'-navigation-'+str(step));step+=1
        for i in range(30):
            name=a.prefix+'-search-'+str(step);step+=1;s.ui(name)
            tree=list(ET.parse(s.output/(name+'.xml')).iter('node'))
            nodes=[n for n in tree if n.get('class')=='android.widget.Switch' and n.get('content-desc')==text]
            if len(nodes)==1:center(nodes[0]);break
            entry=[n for n in tree if n.get('content-desc')=='分类 '+group]
            if entry:center(entry[0]);time.sleep(1);continue
            s.adb('shell','input','swipe','550','1100','550','700','700');time.sleep(.4)
        else:raise AssertionError('未找到功能开关：'+text)
        for i in range(12):
            time.sleep(2)
            name=a.prefix+'-result-'+str(step);step+=1;s.ui(name)
            tree=list(ET.parse(s.output/(name+'.xml')).iter('node'))
            if any(n.get('text')=='知道了' for n in tree):
                s.tap_text('知道了',name+'-dismiss');raise AssertionError('页面操作未完成：'+text)
            if any(n.get('content-desc')=='操作结果' and n.get('text','').startswith('✓ 已保存「'+text+'」') for n in tree):break
        else:raise AssertionError('页面操作结果超时：'+text)
        s.adb('shell','input','keyevent','3');time.sleep(2)
    def state(name):
        text=s.record(a.prefix+'-'+name,'cat /data/user_de/0/com.inkcontrol/files/configuration.txt; dumpsys media_session')
        assert 'InkControl actual media session fixture' in text,text
        return text.replace('\r','')
    try:
        for id,value in (('lock.statusbar','default'),('lock.lyric','default'),('lock.style','8')):
            r=s.probe(a.prefix+'-enable-'+id,'change',id=id,enabled='true',value=value);assert r['statuses'][id]=='已生效'
        s.adb('shell','am','force-stop','com.inkcontrol.test');s.adb('shell','am','start','-n','com.inkcontrol.test/.MediaActivity')
        s.adb('shell','input','keyevent','3');time.sleep(2);shot('playing-a');time.sleep(6);shot('playing-b')
        toggle('锁屏歌词');assert 'lock.lyric=0\n' in state('lock-off') and 'lock.statusbar=1\n' in state('statusbar-remains-on');shot('lock-off-statusbar-on')
        toggle('状态栏歌词');assert 'lock.statusbar=0\n' in state('both-off');shot('both-lyrics-off')
        toggle('锁屏歌词');assert 'lock.lyric=1\n' in state('lock-on-statusbar-off') and 'lock.statusbar=0\n' in state('statusbar-remains-off')
        shot('statusbar-off-lock-on');s.adb('shell','input','keyevent','223');time.sleep(8);s.adb('shell','input','keyevent','224');time.sleep(2);s.adb('shell','wm','dismiss-keyguard')
        s.record(a.prefix+'-events','logcat -d -s "EinkKeyguardController|einkkeyguard" | tail -n 45; logcat -d -b crash')
        print('页面逐项开关、真实会话连续性通过；截图需人工核阅，息屏光学仍单独验收',flush=True)
    finally:
        s.probe(a.prefix+'-all-off','disableAll')
        s.adb('shell','input','keyevent','224');s.adb('shell','wm','dismiss-keyguard');s.adb('shell','input','keyevent','3')
        shot('restored')

if __name__=='__main__':main()
