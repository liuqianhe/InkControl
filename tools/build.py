"""独立离线构建：核心测试 → native → Magisk ZIP → APK → 完整性验证。绝不操作手机。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.1.0-rc14'
INPUTS = {
    'hwcomposer.stock.so': 'd1ba01bf135151580c41dc7de13dd1c80876094c8022f58fe9ceecd0c9fb0988',
    'hwcomposer.motion.so': 'adbdf9aeb1868e1fb9bf01394a8f67942adb9b3c97a93d6ec2d168f83e60a675',
}

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def run(*args, cwd=ROOT):
    env = dict(os.environ, JAVA_TOOL_OPTIONS='-Duser.language=en -Dfile.encoding=UTF-8', PYTHONUTF8='1', PYTHONIOENCODING='utf-8')
    # aapt2 在 Windows 上不接受含中文的绝对路径，项目路径使用相对路径。
    argv = [str(a.relative_to(cwd)) if isinstance(a, Path) and a.is_relative_to(cwd) else str(a) for a in args]
    subprocess.run(argv, check=True, cwd=cwd, env=env)

def zip_tree(directory, target):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(directory.rglob('*')):
            if not path.is_file(): continue
            name = path.relative_to(directory).as_posix()
            info = zipfile.ZipInfo(name, (2026, 10, 15, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o100755 if name.endswith('.sh') or name in ('bin/inkctl', 'bin/eink_status', 'META-INF/com/google/android/update-binary') else 0o100644) << 16
            archive.writestr(info, path.read_bytes())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='candidate-1')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--jdk', default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--ndk', default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--inputs', type=Path, default=ROOT/'inputs')
    parser.add_argument('--keystore', type=Path, default=os.environ.get('INKCONTROL_KEYSTORE'))
    parser.add_argument('--key-alias', default=os.environ.get('INKCONTROL_KEY_ALIAS', 'inkcontrol'))
    parser.add_argument('--test-only', action='store_true')
    parser.add_argument('--unsigned', action='store_true', help='生成不可安装的待签名候选，不读取签名密码')
    parser.add_argument('--certificate', type=Path, help='待签名构建使用的现有 DER 公钥证书，不是私钥')
    args = parser.parse_args()
    if Path(args.name).name != args.name or args.name in ('.', '..'): raise ValueError('invalid build name')
    if not args.jdk: raise ValueError('请设置 JAVA_HOME 或 --jdk')
    jdk = Path(args.jdk)/'bin'
    exe = '.exe' if os.name == 'nt' else ''
    java, javac = jdk/('java'+exe), jdk/('javac'+exe)
    out = ROOT/'out'/args.name
    out.mkdir(parents=True, exist_ok=False)
    host = out/'host'; host.mkdir()
    run(javac, '-encoding', 'UTF-8', '--release', '8', '-d', host, *sorted((ROOT/'src/com/inkcontrol/core').glob('*.java')), ROOT/'test/CoreTest.java', ROOT/'test/CatalogExport.java')
    run(java, '-cp', host, 'CoreTest')
    module = out/'module'; shutil.copytree(ROOT/'backend', module)
    # 控制器以字面版本判断后端是否匹配，须与构建版本一致。
    assert "= '"+VERSION+"' ]" in (module/'lib/common.sh').read_text(encoding='utf-8'), 'common.sh 版本与构建版本不一致'
    run(java, '-cp', host, 'CatalogExport', module/'lib')
    if args.test_only: return
    if not args.sdk or not args.ndk: raise ValueError('需要 --sdk、--ndk')
    if args.unsigned:
        if not args.certificate or not args.certificate.is_file(): raise ValueError('--unsigned 需要 --certificate 指定现有签名的 DER 公钥证书')
    elif not args.keystore: raise ValueError('需要 --keystore（密码从 INKCONTROL_STORE_PASSWORD / INKCONTROL_KEY_PASSWORD 环境变量读取）')
    for name, expected in INPUTS.items():
        if sha(args.inputs/name) != expected: raise ValueError('输入版本不匹配：'+name)
    sdk, ndk = Path(args.sdk), Path(args.ndk)
    android = sdk/'platforms/android-33/android.jar'; bt = sdk/'build-tools/35.0.0'
    prebuilt = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    clang = ndk/'toolchains/llvm/prebuilt'/prebuilt/'bin'/('clang++'+exe)
    payload = module/'payload'; payload.mkdir()
    run(clang, '--target=aarch64-linux-android29', '-std=c++17', '-O2', '-fPIC', '-shared', '-static-libstdc++', '-Wall', '-Wextra', '-Werror', ROOT/'native/service_gpu.cpp', '-lEGL', '-lGLESv3', '-lnativewindow', '-llog', '-ldl', '-Wl,-soname,libeinkgpu.so', '-o', payload/'libeinkgpu.so')
    run(clang, '--target=aarch64-linux-android29', '-std=c++17', '-O2', '-static-libstdc++', ROOT/'native/service_observer.cpp', '-o', module/'bin/eink_status')
    motion = (args.inputs/'hwcomposer.motion.so').read_bytes()
    stock = (args.inputs/'hwcomposer.stock.so').read_bytes()
    default = bytearray(motion); default[0x92bcc:0x92bd4] = stock[0x92bcc:0x92bd4]
    (payload/'hwcomposer.default.so').write_bytes(default)
    (payload/'hwcomposer.idle10.so').write_bytes(motion)
    run(bt/('aapt2'+exe), 'compile', '--dir', ROOT/'overlay/res', '-o', out/'overlay-res.zip')
    run(bt/('aapt2'+exe), 'link', '-I', android, '--manifest', ROOT/'overlay/AndroidManifest.xml', '-o', out/'overlay-unsigned.apk', out/'overlay-res.zip')
    run(bt/('zipalign'+exe), '-f', '4', out/'overlay-unsigned.apk', out/'overlay-aligned.apk')
    overlay = payload/'GmsSettingsIconFix.apk'
    if args.unsigned:
        shutil.copy2(out/'overlay-aligned.apk', overlay)
    else:
        run(java, '-jar', bt/'lib/apksigner.jar', 'sign', '--ks', args.keystore, '--ks-key-alias', args.key_alias, '--ks-pass', 'env:INKCONTROL_STORE_PASSWORD', '--key-pass', 'env:INKCONTROL_KEY_PASSWORD', '--out', overlay, out/'overlay-aligned.apk')
        run(java, '-jar', bt/'lib/apksigner.jar', 'verify', '--verbose', overlay)
    (module/'module.prop').write_text('id=inkcontrol_backend\nname=墨控显示后端\nversion='+VERSION+'\nversionCode=14\nauthor=InkControl contributors\ndescription=墨控统一后端；增强默认关闭，通过App逐项开启。\n', encoding='utf-8', newline='\n')
    (module/'checksums.sha256').write_text(''.join(sha(p)+'  '+p.relative_to(module).as_posix()+'\n' for p in sorted(module.rglob('*')) if p.is_file() and p.name != 'customize.sh' and 'META-INF' not in p.parts), encoding='utf-8', newline='\n')
    assert b'\r' not in (module/'module.prop').read_bytes() + (module/'checksums.sha256').read_bytes()
    suffix = '-unsigned' if args.unsigned else ''
    backend = out/('inkcontrol_backend-v'+VERSION+suffix+'.zip'); zip_tree(module, backend)
    cert = out/'cert.der'
    if args.unsigned: shutil.copy2(args.certificate, cert)
    else: run(jdk/('keytool'+exe), '-exportcert', '-keystore', args.keystore, '-alias', args.key_alias, '-storepass:env', 'INKCONTROL_STORE_PASSWORD', '-file', cert)
    generated = out/'generated/com/inkcontrol/platform/BuildInfo.java'; generated.parent.mkdir(parents=True)
    generated.write_text('package com.inkcontrol.platform;\npublic final class BuildInfo {\n public static final String VERSION="'+VERSION+'", CERT_SHA256="'+sha(cert)+'", BACKEND_SHA256="'+sha(backend)+'";\n public static final boolean DEBUG=false;\n private BuildInfo() {}\n}\n', encoding='utf-8')
    for name in ('stubs', 'classes', 'dex'): (out/name).mkdir()
    run(javac, '-encoding', 'UTF-8', '--release', '8', '-cp', android, '-d', out/'stubs', *sorted((ROOT/'stubs').rglob('*.java')))
    run(javac, '-encoding', 'UTF-8', '--release', '8', '-cp', str(android)+os.pathsep+str(out/'stubs'), '-d', out/'classes', *sorted((ROOT/'src').rglob('*.java')), generated)
    classes = out/'classes.jar'
    with zipfile.ZipFile(classes, 'w') as archive:
        for path in sorted((out/'classes').rglob('*.class')): archive.write(path, path.relative_to(out/'classes').as_posix())
    run(java, '-cp', bt/'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api', '29', '--lib', android, '--classpath', out/'stubs', '--output', out/'dex', classes)
    assets = out/'assets'; shutil.copytree(ROOT/'assets', assets); shutil.copy2(backend, assets/'inkcontrol_backend.zip')
    run(bt/('aapt2'+exe), 'compile', '--dir', ROOT/'res', '-o', out/'res.zip')
    run(bt/('aapt2'+exe), 'link', '-I', android, '--manifest', ROOT/'AndroidManifest.xml', '-o', out/'unsigned.apk', out/'res.zip')
    with zipfile.ZipFile(out/'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as archive:
        # Windows aapt2 的嵌套 assets 头使用反斜线，统一用 Python 写标准 POSIX 名称。
        for asset in sorted(assets.rglob('*')):
            if asset.is_file(): archive.write(asset, 'assets/'+asset.relative_to(assets).as_posix())
        for dex in sorted((out/'dex').glob('*.dex')): archive.write(dex, dex.name)
    # 统一 ZIP 头部与编码，避免 aapt2/Python 组合 ZIP 的 header mismatch。
    with zipfile.ZipFile(out/'unsigned.apk') as source, zipfile.ZipFile(out/'normalized.apk', 'w', zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist(): target.writestr(info.filename, source.read(info.filename))
    run(bt/('zipalign'+exe), '-f', '4', out/'normalized.apk', out/'aligned.apk')
    apk = out/('InkControl-v'+VERSION+suffix+'.apk')
    if args.unsigned: shutil.copy2(out/'aligned.apk', apk)
    else:
        run(java, '-jar', bt/'lib/apksigner.jar', 'sign', '--ks', args.keystore, '--ks-key-alias', args.key_alias, '--ks-pass', 'env:INKCONTROL_STORE_PASSWORD', '--key-pass', 'env:INKCONTROL_KEY_PASSWORD', '--out', apk, out/'aligned.apk')
        run(java, '-jar', bt/'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', apk)
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert hashlib.sha256(archive.read('assets/inkcontrol_backend.zip')).hexdigest() == sha(backend)
        assert archive.read('assets/xposed_init').decode().strip() == 'com.inkcontrol.platform.HookDispatcher'
        assert 'classes.dex' in archive.namelist()
    with zipfile.ZipFile(backend) as archive: assert archive.testzip() is None
    manifest = {'version': VERSION, 'signing': 'unsigned' if args.unsigned else 'verified', 'verification': 'offline-only; device acceptance pending', 'artifacts': {p.name: sha(p) for p in (apk, backend)}, 'inputs': {name: sha(args.inputs/name) for name in INPUTS}, 'sources': {p.relative_to(ROOT).as_posix(): sha(p) for directory in ('src', 'native', 'backend', 'assets', 'res', 'overlay', 'stubs', 'tools', 'test') for p in sorted((ROOT/directory).rglob('*')) if p.is_file() and '__pycache__' not in str(p) and 'config_monitor_' not in str(p)}}
    manifest['sources']['AndroidManifest.xml'] = sha(ROOT/'AndroidManifest.xml')
    for directory in ('.github', 'ci'):
        for path in sorted((ROOT/directory).rglob('*')):
            if path.is_file(): manifest['sources'][path.relative_to(ROOT).as_posix()] = sha(path)
    (out/'build-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    (out/'SHA256SUMS').write_text(''.join(sha(p)+'  '+p.name+'\n' for p in (apk, backend)), encoding='utf-8')
    print(apk)

if __name__ == '__main__': main()
