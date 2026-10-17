"""Export the versioned knowledge base as native GitHub Wiki pages and navigation."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
REPO = 'https://github.com/wzp100/InkControl'
WIKI = REPO+'/wiki/'


def page_name(path):
    stem = path.name[:-3]
    return {'README': 'Home', 'README.en': 'Home.en'}.get(stem, stem)


def export(destination):
    index = json.loads((ROOT/'docs/wiki/knowledge-index.json').read_text(encoding='utf-8'))
    sources = [ROOT/topic[language] for topic in index['topics'] for language in ('zh', 'en')]
    lookup = {source.resolve(): page_name(source) for source in sources}
    destination.mkdir(parents=True, exist_ok=False)
    for source in sources:
        def rewrite(target):
            parsed = urlsplit(target)
            if parsed.scheme or parsed.netloc or not parsed.path:
                return target
            resolved = (source.parent/unquote(parsed.path)).resolve()
            if resolved in lookup:
                return WIKI+lookup[resolved]+('#'+parsed.fragment if parsed.fragment else '')
            relative = resolved.relative_to(ROOT).as_posix()
            return REPO+'/blob/main/'+relative+('?raw=true' if resolved.suffix in ('.svg', '.png') else '')
        text = source.read_text(encoding='utf-8')
        text = re.sub(r'(\]\()([^)]+)(\))', lambda m: m[1]+rewrite(m[2])+m[3], text)
        text = re.sub(r'((?:href|src)=")([^"]+)(")', lambda m: m[1]+rewrite(m[2])+m[3], text)
        (destination/(page_name(source)+'.md')).write_text(text, encoding='utf-8', newline='\n')
    sidebar = '# 墨控 Wiki\n\n[项目源码]('+REPO+') · [Releases]('+REPO+'/releases)\n\n'
    for language, title in (('zh', '简体中文'), ('en', 'English')):
        sidebar += '## '+title+'\n\n'
        for topic in index['topics']:
            label = topic['title_zh' if language == 'zh' else 'title_en']
            sidebar += '- ['+label+']('+WIKI+page_name(Path(topic[language]))+')\n'
        sidebar += '\n'
    (destination/'_Sidebar.md').write_text(sidebar, encoding='utf-8', newline='\n')
    footer = 'Hisense A5 Pro 经典版 · 黑白屏 / Monochrome\n\n'
    footer += '内容维护于 [docs/wiki]('+REPO+'/tree/main/docs/wiki)；云端验证不等于实机验收。\n\n'
    footer += 'Maintained in the source repository; cloud validation does not certify device acceptance.\n'
    (destination/'_Footer.md').write_text(footer, encoding='utf-8', newline='\n')
    print('Native Wiki export:', len(sources), 'pages + sidebar + footer →', destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destination', required=True, type=Path)
    args = parser.parse_args()
    export(args.destination)


if __name__ == '__main__': main()
