"""Validate UTF-8 display translations, placeholders and coverage without compiling locally.

英文维护已于 2026-10-24 停止：新增中文文案不再强制要求 en.tsv 翻译（仅提示），
已有翻译保留（App 英文界面仍可用），en.tsv 自身格式仍校验。
"""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ('ui/MainActivity.java', 'ui/Guide.java', 'ui/Charts.java', 'ui/MotionDemo.java',
           'core/FeatureCatalog.java', 'core/DisplayAlgorithms.java', 'core/FeatureService.java',
           'core/Configuration.java', 'core/Integrity.java', 'core/FeatureDefinition.java')

def main():
    def decode(text): return text.replace('\\n', '\n').replace('\\t', '\t').replace('\\\\', '\\')
    pairs = {}
    for row in (ROOT/'assets/i18n/en.tsv').read_text(encoding='utf-8').splitlines():
        if not row or row.startswith('#'): continue
        key, value = map(decode, row.split('\t'))
        if key in pairs or not key or not value or re.search('[\u3400-\u9fff]', value):
            raise ValueError('Invalid, untranslated or duplicate catalog entry: '+key)
        if re.findall(r'%[-.0-9]*[sdf]', key) != re.findall(r'%[-.0-9]*[sdf]', value):
            raise ValueError('Format placeholders differ: '+key)
        pairs[key] = value
    missing = []
    paths = [ROOT/'src/com/inkcontrol'/p for p in SOURCES]
    paths.extend((ROOT/'src/com/inkcontrol/platform').glob('*.java'))
    for path in paths:
        for match in re.finditer(r'"(?:[^"\\]|\\.)*"', path.read_text(encoding='utf-8')):
            text = json.loads(match.group())
            if re.search('[\u3400-\u9fff]', text) and text not in pairs and text not in ('中 / EN', '语言 / Language'):
                missing.append((path.relative_to(ROOT).as_posix(), text))
    # 英文停止维护（2026-10-24）：缺失翻译只提示，不再阻断构建
    if missing:
        print('NOTE: %d phrase(s) without English translation (English no longer maintained):' % len(missing))
        for path, text in missing:
            print('  -', path, repr(text))
    print('UTF-8 bilingual catalog validated:', len(pairs), 'phrases')

if __name__ == '__main__': main()
