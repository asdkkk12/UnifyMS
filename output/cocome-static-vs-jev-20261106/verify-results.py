"""Verify this fresh two-arm formal run, without executing search or API requests."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

OUT = Path(__file__).resolve().parent
read = lambda path: json.loads(Path(path).read_text())
sha = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()

manifest = read(OUT/'repeat-manifest.json')
assert manifest['status'] in ('success', 'transport-degraded')
assert manifest['arms'] == ['off', 'jev'] and manifest['seeds'] == [101]
assert manifest['offlineJevRequestsThisRun'] == 0 and not manifest['reuseFormalResults']
case = OUT/'experiment/CoCoME'
source = Path(manifest['sourceManifest']).parent/'CoCoME'
assert sha(case/'calibration.json') == manifest['calibrationSha256'] == sha(source/'calibration.json')
assert sha(case/'selection-jev.json') == sha(source/'selection-jev.json')
assert not (case/'online-rule').exists()
rows = {}
for arm in manifest['arms']:
    row = read(case/f'online-{arm}/run-jev-101.json')
    old = read(source/f'online-{arm}/run-jev-101.json')
    run = row['run']; control = run['onlineControl']; rows[arm] = row
    assert run['status'] == 'success' and run['evaluations'] == 1802000 and run['completedGenerations'] == 300
    assert run['config']['id'] == 'C3' and run['config']['seed'] == 101
    assert run['taskKey'] != old['run']['taskKey']
    audit = Path(control['auditDirectory'])
    assert OUT in audit.parents
    assert sha(audit/'audit-files.json') == control['auditIndexSha256']
    for file, digest in read(audit/'audit-files.json').items():
        assert sha(audit/file) == digest, file
    generation = [json.loads(line) for line in (audit/'generation-stats.jsonl').read_text().splitlines()]
    decisions = [json.loads(line) for line in (audit/'control-decisions.jsonl').read_text().splitlines()]
    assert [r['generation'] for r in generation] == list(range(1,301))
    assert [r['generation'] for r in decisions] == list(range(30,300,30))
    assert all(r['valid'] for r in generation)
    for decision in decisions:
        g=decision['generation']
        assert decision['effectiveGeneration'] == g+1
        assert generation[g]['probability'] == decision['probabilityAfter']
    if arm=='off':
        assert control['requestCount']==0 and all(r['probability']==0.5 for r in generation)
        assert all(r['action']=='KEEP' for r in decisions)
        assert run['population']==old['run']['population'] and row['hv']==old['hv']
    else:
        requests=[read(audit/d['requestFile']) for d in decisions]
        assert len(requests)==control['requestCount']==9
        assert sum(r['status']!='success' for r in requests)==control['fallbackCount']
    vis = OUT/f'reports/{arm}/visualizations/seed-101'
    diagnostics=read(vis/'cocome-diagnostics.json')
    assert diagnostics['counts']['uc']==10 and diagnostics['counts']['entity']==13
    assert diagnostics['counts']['services']==row['representative']['serviceCount']
    assert not any(d['severity']=='error' for d in diagnostics['diagnostics'])
    for name in ['cocome.req','cocome-usecases.svg','cocome-domain.svg']:
        ET.parse(vis/name)
    assert (vis/'cocome-merm.html').is_file()
assert rows['off']['run']['config']==rows['jev']['run']['config']
verification=dict(status='passed',arms=manifest['arms'],seeds=manifest['seeds'],freshTaskKeys=True,
    frozenEvidencePreserved=True,staticPopulationMatchesHistorical=True,evaluationsPerArm=1802000,
    jevRequestCount=rows['jev']['run']['onlineControl']['requestCount'],
    jevFallbackCount=rows['jev']['run']['onlineControl']['fallbackCount'],
    hv={a:r['hv'] for a,r in rows.items()},
    metrics={a:r['representative']['metrics'] for a,r in rows.items()},
    exportedModelCounts={'usecases':10,'entities':13})
(OUT/'verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2))
print(json.dumps(verification,ensure_ascii=False))
