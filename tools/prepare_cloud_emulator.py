"""Reclaim build-only SDK files before emulator acceptance on the same disposable runner."""
import os
from pathlib import Path
import shutil

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise ValueError('Disposable cloud runner only')
    temp=Path(os.environ['RUNNER_TEMP']).resolve()
    sdk=Path(os.environ['ANDROID_HOME']).resolve()
    ndk=Path(os.environ['ANDROID_NDK_HOME']).resolve()
    # Only this workflow's newly downloaded NDK is disposable. Never accept a host SDK path.
    if not sdk.is_relative_to(temp) or not ndk.is_relative_to(sdk/'ndk') or ndk==sdk/'ndk':
        raise ValueError('Refuse cleanup outside this run temporary SDK')
    if ndk.exists(): shutil.rmtree(ndk)
    print('Build-only NDK removed after verified APK upload. Free space MiB:',shutil.disk_usage(temp).free//(1024*1024))

if __name__=='__main__':main()
