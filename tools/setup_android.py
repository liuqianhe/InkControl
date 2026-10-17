"""Install hash-pinned official Android command-line tools on a GitHub runner."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

URL = 'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip'
SHA256 = '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583'

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise ValueError('Cloud setup only')
    temp = Path(os.environ['RUNNER_TEMP'])
    archive = temp/'android-commandline-tools.zip'
    with urllib.request.urlopen(URL,timeout=120) as response, archive.open('wb') as out: shutil.copyfileobj(response,out)
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=SHA256: raise ValueError('Android command-line tools digest mismatch')
    sdk=temp/'android-sdk'
    unpack=temp/'android-commandline-unpack'
    with zipfile.ZipFile(archive) as z: z.extractall(unpack)
    tools=sdk/'cmdline-tools/latest'
    shutil.copytree(unpack/'cmdline-tools',tools)
    for path in (tools/'bin').iterdir(): path.chmod(0o755)
    env=dict(os.environ,ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk))
    result=subprocess.run([str(tools/'bin/sdkmanager'),'--sdk_root='+str(sdk),'--licenses'],input='y\n'*100,text=True,capture_output=True,env=env)
    if result.returncode: raise RuntimeError('Android license setup failed: '+result.stderr[-2000:])
    with Path(os.environ['GITHUB_ENV']).open('a',encoding='utf-8') as f:
        f.write('ANDROID_HOME='+str(sdk)+'\nANDROID_SDK_ROOT='+str(sdk)+'\n')
    with Path(os.environ['GITHUB_PATH']).open('a',encoding='utf-8') as f:
        for path in (tools/'bin',sdk/'platform-tools',sdk/'emulator'): f.write(str(path)+'\n')
    print('Official Android command-line tools installed; SHA256 verified')

if __name__=='__main__': main()
