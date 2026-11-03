#!/usr/bin/env python3
"""Select an explicit bounded host batch; never discover or run the full suite."""
import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPECIAL = {'browser-expression', 'browser-long-page'}

def selected(registry, single, batch):
    if not batch.strip():
        groups = [single]
    else:
        if len(batch) > 1024:
            raise ValueError('Selected host batch exceeds 1024 characters')
        parts = batch.split(',')
        if any(not part.strip() for part in parts):
            raise ValueError('Each batch group must be explicit and nonempty')
        groups = list(dict.fromkeys(part.strip() for part in parts))
        if not 1 <= len(groups) <= 16:
            raise ValueError('Select between one and sixteen named host groups')
        if SPECIAL.intersection(groups):
            raise ValueError('Browser Chromium fixtures require their individual workflow mode')
    if not groups or any(group not in registry for group in groups):
        raise ValueError('Unknown host group; no implicit all-tests expansion')
    return groups

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--single', required=True)
    parser.add_argument('--batch', default='')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    registry = json.loads((ROOT / 'tools/host-tests.json').read_text())
    groups = selected(registry, args.single, args.batch)
    Path(args.output).write_text(''.join(group + '\n' for group in groups))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
            output.write('groups=' + json.dumps(groups, separators=(',', ':')) + '\n')
    print(json.dumps({'selectedGroups': groups, 'implicitAll': False}))

if __name__ == '__main__':
    main()
