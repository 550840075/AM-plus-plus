#!/usr/bin/env python3
"""Validate packaged profile data and frozen compatibility snapshots without a device."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
DIRECTORY = ROOT / 'host-applemusic/src/main/resources/host-profiles'

def main():
    index = json.loads((DIRECTORY/'index.json').read_text(encoding='utf-8'))
    assert index['schemaVersion'] == 1
    tuples = set()
    target_count = 0
    for filename in index['profiles']:
        path = (DIRECTORY/filename).resolve()
        assert path.parent == DIRECTORY.resolve()
        profile = json.loads(path.read_text(encoding='utf-8'))
        key = (profile['packageName'],profile['versionName'],profile['versionCode'])
        assert key not in tuples, f'duplicate tuple: {key}'
        tuples.add(key)
        assert filename == f"{key[1]}-{key[2]}.json"
        assert profile['schemaVersion'] == 1
        assert profile['family'] in ('legacy-activity', 'fragment-content')
        assert profile['verification']['version_code'] == str(key[2])
        if not profile['productionEnabled']:
            assert not any(profile['capabilities'].values())
        for point,targets in profile['hookTargets'].items():
            for target in targets:
                target_count += 1
                assert target['className'] and target['contractId'] == point
                assert target['resolutionPolicy'] in ('legacy-reviewed-candidates','exact-required','reviewed-fallback')
                if target['parameterTypeNames'] is not None and target['parameterCount'] is not None:
                    assert len(target['parameterTypeNames']) == target['parameterCount']
        baseline_path = ROOT/'host-applemusic/src/test/resources/baseline'/filename
        if baseline_path.is_file():
            baseline = json.loads(baseline_path.read_text(encoding='utf-8'))
            assert profile['indexed'] == baseline['indexed'], f'indexed baseline drift: {filename}'
            for point,targets in baseline['hookTargets'].items():
                current = profile['hookTargets'][point]
                assert len(current) == len(targets), f'candidate count drift: {filename}/{point}'
                for old,new in zip(targets,current):
                    assert all(new[key] == value for key,value in old.items()), f'target/order drift: {filename}/{point}'
    print(f'PASS: {len(tuples)} exact profiles, {target_count} targets; frozen indexed/HLE data and candidate order preserved')

if __name__ == '__main__':
    main()
