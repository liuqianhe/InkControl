"""真实 MediaSession、桌面触控和两处歌词的实机回归；会话无声，不访问个人曲库。"""
import argparse
import json
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
from device_session import Session


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--session',required=True,type=Path);p.add_argument('--prefix',required=True);a=p.parse_args()
    m=json.loads(a.session.read_text(encoding='utf-8'));s=Session(m['serial'],m['output']);index=0
    def change(id, enabled, value=None):
        nonlocal index
        kwargs=dict(id=id,enabled=str(enabled).lower())
        if value is not None:kwargs['value']=value
        r=s.probe(a.prefix+'-change-%02d'%index,'change',**kwargs);index+=1
        assert r['statuses'][id]==('已生效' if enabled else '关闭'),r['statuses'][id]
        print(id,enabled,flush=True)
    def fixture(name):return json.loads(s.record(a.prefix+'-'+name,'cat /data/user/0/com.inkcontrol.test/files/media-fixture.json'))
    def shot(name):
        (s.output/(a.prefix+'-'+name+'.png')).write_bytes(s.adb('exec-out','screencap','-p').stdout)
    def controls(theme,name):
        s.adb('shell','input','keyevent','3');time.sleep(1)
        s.ui(a.prefix+'-'+name)
        nodes=[n for n in ET.parse(s.output/(a.prefix+'-'+name+'.xml')).iter('node') if n.get('content-desc')=='纸间主题音乐小组件']
        assert len(nodes)==1,[(n.get('text'),n.get('content-desc')) for n in nodes]
        x,y,right,bottom=map(int,re.findall(r'\d+',nodes[0].get('bounds')))
        xs=(.69,.8,.92) if theme=='paper' else (.45,.65,.85);yy=.83 if theme=='paper' else .59
        return [(int(x+v*(right-x)),int(y+yy*(bottom-y))) for v in xs]
    def click(point,name,key):
        before=fixture(name+'-before');s.adb('shell','input','tap',*map(str,point));time.sleep(1)
        after=fixture(name+'-after');assert after[key]==before[key]+1,(before,after,key)
        return after
    try:
        s.adb('shell','input','keyevent','224');s.adb('shell','wm','dismiss-keyguard')
        s.adb('shell','am','start','-n','com.inkcontrol.test/.MediaActivity');time.sleep(2)
        change('desktop.theme',True,'glass');change('desktop.widget',True,'music')
        for theme in ('glass','paper','ink'):
            if theme!='glass':change('desktop.theme',True,theme)
            previous,toggle,next=controls(theme,theme)
            assert not click(toggle,theme+'-pause','pauses')['playing']
            assert click(toggle,theme+'-play','plays')['playing']
            click(next,theme+'-next','nexts');click(previous,theme+'-previous','previous')
            shot(theme+'-music')
        # 正常配置进程重建后，首个控制命令不能因旧序号被吞掉。
        s.adb('shell','am','force-stop','com.inkcontrol');s.adb('shell','am','start','-n','com.inkcontrol/.ui.MainActivity');time.sleep(3)
        previous,toggle,next=controls('ink','provider-recreated')
        assert not click(toggle,'after-recreate-pause','pauses')['playing']
        assert click(toggle,'after-recreate-play','plays')['playing']
        # 桌面进程独立重建，不能沿用上一 PID 的确认。
        old=s.root('pidof com.hmct.vision');s.adb('shell','am','force-stop','com.hmct.vision');s.adb('shell','input','keyevent','3');time.sleep(4)
        for i in range(3):s.adb('shell','input','swipe','120','800','630','800','300');time.sleep(.4)
        new=s.root('pidof com.hmct.vision');assert old!=new,(old,new)
        r=s.probe(a.prefix+'-launcher-new-pid');assert r['statuses']['desktop.theme']=='已生效'
        s.adb('shell','input','keyevent','3');time.sleep(2)
        actual=json.loads(s.record(a.prefix+'-cold-desktop','cat /data/user/0/com.hmct.vision/files/einkthemes-state.json'))
        assert actual['media_available'] and actual['weather_available'],actual
        previous,toggle,next=controls('ink','cold-controls')
        assert not click(toggle,'cold-pause','pauses')['playing']
        assert click(toggle,'cold-play','plays')['playing']
        change('lock.lyric',True);change('lock.statusbar',True);change('lock.style',True,'8')
        s.adb('shell','input','keyevent','3');time.sleep(2);shot('lyric-a');time.sleep(6);shot('lyric-b')
        change('lock.statusbar',False);s.adb('shell','input','keyevent','3');time.sleep(1);shot('statusbar-off-lock-lyric-on')
        change('lock.statusbar',True);change('lock.lyric',False);s.adb('shell','input','keyevent','3');time.sleep(1);shot('lock-lyric-off-statusbar-on')
        # 暂停后歌词停止，随后真实跨分钟休眠；不发送伪造 TIME_TICK 广播。
        previous,toggle,next=controls('ink','minute-controls')
        assert not click(toggle,'minute-pause','pauses')['playing'];shot('paused-statusbar-restored')
        s.adb('shell','input','keyevent','223')
        for i in range(7):time.sleep(10);print('实际休眠跨分钟',10*(i+1),flush=True)
        s.adb('shell','input','keyevent','224');time.sleep(2);s.adb('shell','wm','dismiss-keyguard')
        s.record(a.prefix+'-minute-events','logcat -d -s "EinkKeyguardController|einkkeyguard" | tail -n 55; logcat -d -b crash')
        print('真实媒体控制和进程重建已通过；歌词与分钟截图/事件待核阅',flush=True)
    finally:
        for id in ('lock.style','lock.lyric','lock.statusbar','desktop.widget','desktop.theme'):change(id,False)
        s.adb('shell','input','keyevent','224');s.adb('shell','wm','dismiss-keyguard');s.adb('shell','input','keyevent','3');time.sleep(1);shot('restored')
        assert s.record(a.prefix+'-stock-style','settings get global eink_keyguard_style')=='0'


if __name__=='__main__':main()
