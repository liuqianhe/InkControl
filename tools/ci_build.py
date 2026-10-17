"""GitHub-hosted build entry point. Private inputs and keys stay outside the checkout."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(*args): subprocess.run(list(map(str, args)), cwd=ROOT, check=True)
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--signing', choices=('unsigned','signed'), default='signed')
    args = parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS') != 'true': raise ValueError('Use GitHub Actions for builds')
    config = json.loads((ROOT/'ci/inputs.json').read_text(encoding='utf-8'))
    encrypted = ROOT/'ci/private-inputs.zip.enc'
    if digest(encrypted) != config['encrypted_sha256']: raise ValueError('Encrypted dependency digest mismatch')
    if not os.environ.get('INKCONTROL_INPUT_PASSWORD'): raise ValueError('Missing INKCONTROL_INPUT_PASSWORD Actions secret')
    with tempfile.TemporaryDirectory(prefix='inkcontrol-', dir=os.environ['RUNNER_TEMP']) as temp:
        work = Path(temp)
        work.chmod(0o700)
        archive = work/'inputs.zip'
        run('openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000', '-in', encrypted, '-out', archive, '-pass', 'env:INKCONTROL_INPUT_PASSWORD')
        inputs = work/'inputs'; inputs.mkdir()
        with zipfile.ZipFile(archive) as z:
            if sorted(z.namelist()) != sorted(config['files']): raise ValueError('Unexpected private dependency members')
            for name, expected in config['files'].items():
                data = z.read(name)
                if hashlib.sha256(data).hexdigest() != expected: raise ValueError('Private dependency digest mismatch: '+name)
                (inputs/name).write_bytes(data)
        name = 'cloud-'+os.environ['GITHUB_RUN_ID']+'-'+os.environ['GITHUB_RUN_ATTEMPT']+'-'+args.signing
        command = [sys.executable, 'tools/build.py', '--name', name, '--inputs', inputs]
        if args.signing == 'signed':
            required = ('INKCONTROL_KEYSTORE_BASE64','INKCONTROL_STORE_PASSWORD','INKCONTROL_KEY_PASSWORD','INKCONTROL_KEY_ALIAS')
            missing = [key for key in required if not os.environ.get(key)]
            if missing: raise ValueError('Missing signing secrets: '+', '.join(missing))
            key = work/'signing.jks'
            key.write_bytes(base64.b64decode(os.environ['INKCONTROL_KEYSTORE_BASE64'], validate=True)); key.chmod(0o600)
            command += ['--keystore', key, '--key-alias', os.environ['INKCONTROL_KEY_ALIAS']]
        else: command += ['--unsigned', '--certificate', ROOT/'ci/trusted-certificate.der']
        run(*command)
        out = ROOT/'out'/name
        if digest(out/'cert.der') != digest(ROOT/'ci/trusted-certificate.der'):
            raise ValueError('Signing certificate changed; refuse to publish an incompatible update')
        run(sys.executable, 'tools/verify_package.py', '--build', out)
        manifest_path = out/'build-manifest.json'
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        manifest['cloud'] = {key: os.environ.get(key, '') for key in ('GITHUB_REPOSITORY','GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT')}
        manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        with Path(os.environ['GITHUB_OUTPUT']).open('a',encoding='utf-8') as f: f.write('directory='+out.relative_to(ROOT).as_posix()+'\n')
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a',encoding='utf-8') as f:
            f.write('### InkControl / 墨控\n\nSigning / 签名: **'+args.signing+'**\n\n')
            f.write('Unsigned artifacts cannot be installed. 未签名候选不能安装。\n\n' if args.signing=='unsigned' else 'Signature verified; device acceptance pending. 签名已验证，实机验收待做。\n\n')
            f.write('Commit: `'+os.environ['GITHUB_SHA']+'`\n\n')
            for artifact, sha in manifest['artifacts'].items(): f.write('- `'+artifact+'` SHA256: `'+sha+'`\n')

if __name__ == '__main__': main()
