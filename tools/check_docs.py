#!/usr/bin/env python3
"""Validate Apex Companion project-documentation overlay without needing KMP Wizard binaries."""
from pathlib import Path
import re
import sys

ROOT=Path(__file__).resolve().parents[1]
REQUIRED=[
  'AGENTS.md','SUBAGENT.md','README.md','LICENSE','CHANGELOG.md',
  'docs/ARCHITECTURE.md','docs/REQUIREMENTS.md','docs/PRODUCT.md',
  'docs/INDEX.md','docs/ROADMAP.md','docs/PLANS.md',
  'docs/SUBAGENT.md','docs/ADR.md','docs/MODEL_ROUTING.md',
  'docs/SKILLS_INDEX.md','docs/KMP_SETUP.md','docs/IOS_WIDGETS.md',
  'docs/API_INTEGRATION.md','docs/WIDGETS.md','docs/RANKED_TRACKING.md',
  'docs/DATA_MODEL.md','docs/TEST_STRATEGY.md',
  'docs/specs/v0-0-offline-vertical-slice.md',
  'docs/adr/0001-kmp-mobile-platforms.md',
  'plans/active/2026-11-02-v0-0-offline-vertical-slice.md',
]
LINK=re.compile(r'(?<!!)\[[^\]]*\]\(([^)]+)\)')
ISSUES=[]
for rel in REQUIRED:
  if not (ROOT/rel).is_file():ISSUES.append(f'missing required file: {rel}')

for f in sorted(ROOT.rglob('*.md')):
  src=f.read_text(encoding='utf-8')
  rel=f.relative_to(ROOT)
  if not src.strip():ISSUES.append(f'empty Markdown: {rel}')
  if '[TODO' in src or '<TODO' in src:ISSUES.append(f'unfinished placeholder in {rel}')
  if re.search(r'\bvard\b|io[.]bonkowski[.]vard',src,re.IGNORECASE):ISSUES.append(f'old project name found: {rel}')
  for link in LINK.findall(src):
    path=link.strip().split('#',1)[0]
    if not path or path.startswith(('https://','http://','mailto:','data:')):continue
    from urllib.parse import unquote
    target=(f.parent/unquote(path))
    if not target.exists():ISSUES.append(f'broken relative link in {rel}: {link}')

for obsolete in ['docs/PACK_TRACKER.md','docs/adr/0001-native-android.md','docs/adr/0002-event-ledger.md','.agents/skills/android-test-matrix']:
  if (ROOT/obsolete).exists():ISSUES.append(f'obsolete scope/architecture path still exists: {obsolete}')

skills=sorted((ROOT/'.agents/skills').glob('*/SKILL.md'))
if len(skills)<12:ISSUES.append(f'expected 12+ local skills, found {len(skills)}')
for f in skills:
  src=f.read_text(encoding='utf-8'); parts=src.split('---',2)
  if len(parts)<3 or not src.startswith('---\n'):
    ISSUES.append(f'missing YAML frontmatter in {f.relative_to(ROOT)}');continue
  front=parts[1]
  match=re.search(r'^name:\s*(\S+)',front,flags=re.MULTILINE)
  if not match or match.group(1)!=f.parent.name:ISSUES.append(f'skill folder/name mismatch: {f.relative_to(ROOT)}')
  if not re.search(r'^description:\s*.{20,}',front,flags=re.MULTILINE):
    ISSUES.append(f'missing skill description: {f.relative_to(ROOT)}')
  if not (f.parent/'agents/openai.yaml').is_file():ISSUES.append(f'missing metadata: {f.relative_to(ROOT)}')

if ISSUES:
  for issue in ISSUES:print('ERROR:',issue)
  sys.exit(1)
print(f'PASS: {len(list(ROOT.rglob("*.md")))} Markdown docs, {len(skills)} agent skills, required files and links; no retired paths.')
