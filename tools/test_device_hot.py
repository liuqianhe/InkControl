"""显式运行的实机逐项开关回执检查；每项都会关闭，行为检查另行记录。"""
from pathlib import Path
import argparse
import json
from device_session import Session

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--session',required=True,type=Path)
    a=ap.parse_args();m=json.loads(a.session.read_text(encoding='utf-8'));s=Session(m['serial'],m['output'])
    ids=['motion.dim','motion.scale','lock.lyric','lock.statusbar','lock.first','lock.update','lock.delay',
         'fix.configlock','fix.modes','fix.video','fix.permissions']
    results=[]
    for index,id in enumerate(ids):
        row={'id':id}
        try:
            r=s.probe('hot-%02d-on'%index,'change',id=id,enabled='true')
            row['on']=r['statuses'][id]
        finally:
            r=s.probe('hot-%02d-off'%index,'change',id=id,enabled='false')
            row['off']=r['statuses'][id]
        row['passed']=row['on']=='已生效' and row['off']=='关闭'
        results.append(row)
        (s.output/'hot-switch-summary.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(row,ensure_ascii=False),flush=True)
    if not all(row['passed'] for row in results): raise RuntimeError('部分运行回执未通过，详见记录')

if __name__=='__main__':main()
