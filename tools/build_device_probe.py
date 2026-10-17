"""仅构建同证书的临时 Instrumentation APK，不连接设备。"""
import argparse
import os
from pathlib import Path
from build import ROOT, run

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build',required=True,type=Path)
    p.add_argument('--jdk',required=True,type=Path)
    p.add_argument('--sdk',required=True,type=Path)
    p.add_argument('--keystore',required=True,type=Path)
    p.add_argument('--name',default='device-probe')
    a=p.parse_args()
    if Path(a.name).name!=a.name: raise ValueError('非法构建名称')
    out=a.build/a.name;out.mkdir(exist_ok=False)
    jdk=a.jdk/'bin';bt=a.sdk/'build-tools/35.0.0';android=a.sdk/'platforms/android-33/android.jar'
    classes=out/'classes';classes.mkdir();dex=out/'dex';dex.mkdir()
    run(jdk/'javac.exe','-encoding','UTF-8','--release','8','-cp',str(android)+os.pathsep+str(a.build/'classes.jar'),'-d',classes,*sorted((ROOT/'test/android').glob('*.java')))
    run(jdk/'jar.exe','cf',out/'classes.jar','-C',classes,'.')
    run(jdk/'java.exe','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','29','--lib',android,'--classpath',a.build/'classes.jar','--output',dex,out/'classes.jar')
    run(bt/'aapt2.exe','link','-I',android,'--manifest',ROOT/'test/android/AndroidManifest.xml','-o',out/'unsigned.apk')
    import zipfile
    with zipfile.ZipFile(out/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:z.write(dex/'classes.dex','classes.dex')
    run(bt/'zipalign.exe','-f','4',out/'unsigned.apk',out/'aligned.apk')
    run(jdk/'java.exe','-jar',bt/'lib/apksigner.jar','sign','--ks',a.keystore,'--ks-key-alias','inkcontrol','--ks-pass','env:INKCONTROL_STORE_PASSWORD','--key-pass','env:INKCONTROL_KEY_PASSWORD','--out',out/'InkControl-device-probe.apk',out/'aligned.apk')
    print(out/'InkControl-device-probe.apk')

if __name__=='__main__':main()
