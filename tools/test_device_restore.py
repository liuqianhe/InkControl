"""显式实机会话的旧套件回退、切回及最终核查。每个阶段单独调用，不创建新备份。"""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess
import shutil
import time
import xml.etree.ElementTree as ET
from device_session import Session

CTL='/data/adb/modules/inkcontrol_backend/bin/inkctl'
MODE='CLASSPATH=/data/local/tmp/inkcontrol-mode-probe-v2.dex app_process /system/bin EpdModeProbe '
OLD=('com.einkfix','com.einkmotion','com.einklyric','com.einkthemes')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--session',required=True,type=Path)
    p.add_argument('--backup',required=True,help='已经人工核实的原始应用备份路径；不创建或覆盖备份')
    p.add_argument('--stage',required=True,choices=('restore','check-old','switch-back','check-final'))
    a=p.parse_args();m=json.loads(a.session.read_text(encoding='utf-8'));s=Session(m['serial'],m['output'])
    BACKUP=a.backup
    if not BACKUP.startswith('/data/adb/inkcontrol/backups/') or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/_-' for c in BACKUP):
        raise ValueError('备份路径无效')
    prefix='restore-'+a.stage
    assert s.root('cat /data/adb/inkcontrol/latest_backup')==BACKUP,'原始备份指针变化，停止'
    assert s.root('test -f '+BACKUP+'/verified && echo verified')=='verified'
    def frameworks():
        text=s.record(prefix+'-framework','/data/adb/lspd/cli modules ls --json')
        data=json.loads(text)
        # Vector 返回的模块对象结构从真实 CLI 读取，不猜测已生效状态。
        def walk(value):
            if isinstance(value,dict):
                if 'PACKAGE' in value and 'STATUS' in value:yield value['PACKAGE'],value['STATUS']
                for child in value.values():yield from walk(child)
            elif isinstance(value,list):
                for child in value:yield from walk(child)
        return dict(walk(data))
    def modes():
        before=ET.fromstring((s.output/'49-app-modes-before.txt').read_text(encoding='utf-8'))
        current=s.record(prefix+'-epd','cat /data/system/epd_mode_cfg.xml')
        pairs=lambda root:{n.get('pkg'):n.get('mode') for n in root.iter('cfg')}
        assert pairs(before)==pairs(ET.fromstring(current)),'个人每应用模式与测试前不同'
        return len(pairs(before))
    def reboot():
        boot=s.root('cat /proc/sys/kernel/random/boot_id')
        s.adb('reboot');print('已完整重启，等待系统启动',flush=True)
        for i in range(60):
            time.sleep(5)
            try:
                if s.adb('shell','getprop','sys.boot_completed',timeout=8).stdout.strip()==b'1' and s.root('cat /proc/sys/kernel/random/boot_id')!=boot:
                    time.sleep(8);print('系统启动完成',flush=True);return
            except (RuntimeError,subprocess.TimeoutExpired):pass
            if i%6==5:print('仍在等待完整启动',flush=True)
        raise RuntimeError('完整启动超时，保留现场')
    if a.stage=='restore':
        s.record(prefix+'-remove-fixture',MODE+'remove');time.sleep(3);print('个人模式行完整保留：',modes(),flush=True)
        r=s.probe(prefix+'-all-off','disableAll');assert all(line.split('=',1)[1]=='0' for line in r['configuration'].splitlines() if line.split('=',1)[0] in r['statuses'])
        r=s.probe(prefix+'-execute','restore');assert all(line.split('=',1)[1]=='0' for line in r['configuration'].splitlines() if line.split('=',1)[0] in r['statuses'])
        assert s.root('test -f /data/adb/modules/inkcontrol_backend/disable && echo disabled')=='disabled'
        reboot()
    elif a.stage=='check-old':
        fw=frameworks();assert all(fw.get(pkg)=='enabled' for pkg in OLD) and fw.get('com.inkcontrol')=='disabled',fw
        text=s.record(prefix+'-state','cat '+BACKUP+'/modules.txt; for d in /data/adb/modules/*; do [ -f "$d/disable" ] && echo "$d disabled" || echo "$d enabled"; done; sha256sum /vendor/lib64/hw/hwcomposer.ums512.so /vendor/lib64/hw/libeinkgpu.so; settings get global eink_keyguard_style; settings get global dynamic_refresh_mode; logcat -d -b crash')
        assert 'adbdf9aeb1868e1fb9bf01394a8f67942adb9b3c97a93d6ec2d168f83e60a675' in text and '44142c6a3e20321550b33c694201f8c10e58a755e607608d4132a4c61eb34132' in text
        for line in s.root('cat '+BACKUP+'/modules.txt').splitlines():
            module,state=line.split();disabled=s.root('test -f /data/adb/modules/'+module+'/disable && echo yes || echo no')=='yes';assert disabled==(state=='disabled'),line
        for key in ('eink_motion','eink_keyguard_style','einklyric_first_mode','einklyric_update_mode','einklyric_lyric_mode','einklyric_statusbar','einklyric_delay_ms','dynamic_refresh_mode'):
            assert s.root('settings get global '+key)==s.root('cat '+BACKUP+'/'+key),key
        modes();print('旧模块、框架、原设置及原显示库均已恢复',flush=True)
    elif a.stage=='switch-back':
        r=s.probe(prefix+'-config');assert all(line.split('=',1)[1]=='0' for line in r['configuration'].splitlines() if line.split('=',1)[0] in r['statuses'])
        s.record(prefix+'-execute','sh '+CTL+' migrate && /data/adb/lspd/cli modules enable com.inkcontrol && rm -f /data/adb/modules/inkcontrol_backend/disable')
        fw=frameworks();assert all(fw.get(pkg)=='disabled' for pkg in OLD) and fw.get('com.inkcontrol')=='enabled',fw
        reboot()
    else:
        r=s.probe(prefix+'-config');assert len(r['statuses'])==44 and all(v=='关闭' for v in r['statuses'].values()),r['statuses']
        revision=int(dict(line.split('=',1) for line in r['configuration'].splitlines())['revision'])
        for role,process in (('system','system_server'),('systemui','com.android.systemui'),('launcher','com.hmct.vision')):
            report=r['confirmations'][role]
            assert str(report['pid'])==s.root('pidof '+process) and report['revision']==revision and report['version']=='0.1.0-rc7' and not report['error'],report
        fw=frameworks();assert all(fw.get(pkg)=='disabled' for pkg in OLD) and fw.get('com.inkcontrol')=='enabled',fw
        status=s.record(prefix+'-status','sh '+CTL+' status; echo FINAL_STYLE; settings get global eink_keyguard_style; echo FINAL_DYNAMIC; settings get global dynamic_refresh_mode; echo FINAL_CRASH; logcat -d -b crash')
        assert 'ready=1' in status and 'conflict=\n' in status.replace('\r','') and 'error=' not in status,status
        assert 'hwc=d1ba01bf135151580c41dc7de13dd1c80876094c8022f58fe9ceecd0c9fb0988' in status
        assert '"helper_loaded":false' in status and s.root('cat /data/adb/inkcontrol/pending')=='0'
        assert s.root('settings get global eink_keyguard_style')=='0' and s.root('settings get global dynamic_refresh_mode')=='0'
        assert 'com.inkcontrol.overlay.google' not in s.root('cmd overlay list --user 0')
        count=modes()
        # 只读核对桌面布局，不拷贝或恢复用户数据库。
        data=s.adb('exec-out','su','-c','cat /data/user_de/0/com.hmct.vision/databases/launcher.db').stdout
        path=s.output/(prefix+'-launcher.db');path.write_bytes(data)
        wal=s.adb('exec-out','su','-c','if [ -f /data/user_de/0/com.hmct.vision/databases/launcher.db-wal ]; then cat /data/user_de/0/com.hmct.vision/databases/launcher.db-wal; fi').stdout
        if wal:path.with_name(path.name+'-wal').write_bytes(wal)
        analysis=Path(__file__).resolve().parents[1]/'out'/('launcher-analysis-'+str(time.time_ns()))
        analysis.mkdir()
        current=analysis/'current.db';baseline=analysis/'baseline.db'
        shutil.copy2(path,current);shutil.copy2(s.output/'77-launcher-before.db',baseline)
        if wal:current.with_name(current.name+'-wal').write_bytes(wal)
        def rows(path):
            with sqlite3.connect(path) as db:return sorted(db.execute('SELECT * FROM favorites').fetchall(),key=repr)
        assert rows(current)==rows(baseline),'桌面布局发生变化，保留现场'
        print('44 项全关闭、原厂 HWC、个人模式和桌面布局均核实；原备份指针未变',flush=True)
        (s.output/(prefix+'-assessment.json')).write_text(json.dumps({'features_off':44,'stock_hwc':True,'epd_rows_preserved':count,'launcher_layout_preserved':True,'backup_preserved':True},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
