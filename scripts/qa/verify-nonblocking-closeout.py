"""Validate delivery evidence without executing product tests or changing historical files."""
import csv
import hashlib
import json
import pathlib
import re
import sys
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/test-reports/2026-11-05/nonblocking-closeout-r1'
R3 = ROOT / 'docs/test-reports/2026-11-05/d26-baseline-r3'
HISTORY = ROOT / 'docs/test-reports/2026-10-28'


def read(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ref(value):
    p = pathlib.Path(value)
    return p if p.is_absolute() else ROOT / p if value.startswith('docs/') else OUT / p


modules = list(csv.DictReader((HISTORY / 'MODULE-RESULTS.csv').open(encoding='utf-8-sig', newline='')))
ledger = read(OUT / 'OBSERVATION-STATUS.json')
approved = [r for r in ledger['records'] if r['status'] == 'APPROVED']
approvals = [(p, read(p)) for p in sorted((ROOT / 'docs/test-reports').rglob('REVIEW-APPROVAL.json'))]
result_approvals = [(p, v) for p, v in approvals if v.get('status') in ('APPROVED', 'RESULTS_APPROVED', 'APPROVED_REPORTED_SCOPE')]
results = []
details = {}


def check(case_id, action):
    try:
        value = action()
        results.append({'id': case_id, 'result': 'PASS'})
        if value is not None:
            details[case_id] = value
    except (AssertionError, KeyError, FileNotFoundError, ValueError) as error:
        results.append({'id': case_id, 'result': 'FAIL', 'error': str(error) or type(error).__name__})


def module_inventory():
    assert len(modules) == 19
    assert len({r['模块'] for r in modules}) == 19
    assert len({r['模块'][:2] for r in modules}) == 16
    assert all(pathlib.Path(r['来源']).is_file() for r in modules)
    return {'mainModules': 16, 'acceptanceScopes': 19, 'sourceReportsExist': 19}


def totals():
    expected = {'检查项次': 3090, '正例': 1643, '反例': 1447, '最终通过': 3031, '未关闭失败': 0, '观察': 47, '阻塞': 12}
    for name, value in expected.items():
        assert sum(int(r[name]) for r in modules) == value, name
    for r in modules:
        assert int(r['正例']) + int(r['反例']) == int(r['检查项次'])
        assert sum(int(r[k]) for k in ['最终通过', '未关闭失败', '观察', '阻塞']) == int(r['检查项次'])
    return expected


def historical_bugs():
    rows = list(csv.DictReader((HISTORY / 'BUG-FIX-REGISTER.csv').open(encoding='utf-8-sig', newline='')))
    bugs = [r for r in rows if r['类型'] == 'PRODUCT_BUG']
    assets = [r for r in rows if r['类型'] != 'PRODUCT_BUG']
    assert len(bugs) == 108 and len(assets) == 1
    assert all(r['最终状态'] == 'FIXED_VERIFIED' for r in rows)
    return {'historicalProductBugs': 108, 'historicalAssets': 1}


def observations():
    assert len(ledger['records']) == len({r['id'] for r in ledger['records']}) == 47
    assert len(approved) == 45
    remaining = [r for r in ledger['records'] if r['status'] != 'APPROVED']
    assert len(remaining) == 2 and all(r['decisionId'] == 'D26' and r['status'] == 'SKIPPED_BY_USER_SPEC_RETAINED' for r in remaining)
    return {'approved': 45, 'skippedSpecRetained': 2}


def approval_coverage():
    ids = set()
    for _, v in result_approvals:
        values = v.get('approvedObservations', [v['approvedObservation']] if 'approvedObservation' in v else [])
        ids.update(values)
    evidence = []
    for r in approved:
        if r['id'] not in ids:
            value = r.get('approvalRecord') or r.get('approvalEvidence')
            p = ref(value)
            assert read(p).get('status') == 'RESULTS_APPROVED', r['id']
            evidence.append(str(p.relative_to(ROOT)))
    return {'coveredObservationResults': len(approved), 'resultApprovalFiles': len(result_approvals), 'additionalExplicitSources': evidence}


def d16():
    batches = ['2026-11-02/d16-api-automation-fixes-r1', '2026-11-02/d16-mock-fixes-r1', '2026-11-02/d16-performance-fixes-r1', '2026-11-02/d16-web-fixes-r1', '2026-11-03/d16-mobile-fixes-r1', '2026-11-03/d16-quality-task-fixes-r1']
    for batch in batches:
        assert read(ROOT / 'docs/test-reports' / batch / 'REVIEW-APPROVAL.json')['status'] == 'APPROVED'
    return {'approvedModules': 6}


def d23():
    d = ROOT / 'docs/test-reports/2026-11-04/d23-json-policy-fixes-r2'
    a, c = read(d / 'REVIEW-APPROVAL.json'), read(d / 'CASE-RESULTS.json')
    assert a['status'] == 'RESULTS_APPROVED' and a['bugResultApproved']
    assert a['reportSha256'] == digest(d / 'FINAL-REPORT.md')
    assert a['caseResultsSha256'] == digest(d / 'CASE-RESULTS.json')
    assert c['total'] == c['passed'] == len(c['cases']) == 32 and c['failed'] == 0
    return {'formalCasesAlreadyExecuted': 32, 'bug': 'BUG-D23-001', 'status': 'FIXED_APPROVED'}


def frozen_hashes():
    checked, manifests = 0, []
    parents = {p.parent for p, _ in result_approvals}
    parents.update(ROOT / 'docs/test-reports/2026-11-04' / n for n in ['d26-baseline-r1', 'd26-baseline-r2'])
    for parent in sorted(parents):
        p = parent / 'ARTIFACT-HASHES.json'
        if not p.is_file():
            continue
        value = read(p)
        entries = value.get('files', value)
        count = 0
        for name, expected in entries.items():
            if isinstance(expected, dict):
                expected = expected.get('sha256')
            if isinstance(expected, str) and re.fullmatch('[a-f0-9]{64}', expected):
                assert digest(parent / name) == expected, str(parent / name)
                count += 1
        assert count > 0, str(p)
        checked += count
        manifests.append({'file': str(p.relative_to(ROOT)), 'entries': count})
    assert len(manifests) >= 8
    return {'frozenFilesMatched': checked, 'manifests': manifests}


def references():
    keys = ['executionEvidence', 'resultApprovalEvidence', 'approvalRecord', 'ruleApprovalRecord', 'priorResultApprovalEvidence', 'mysqlEvidence', 'approvalEvidence']
    refs = [r[k] for r in ledger['records'] for k in keys if isinstance(r.get(k), str) and r[k].endswith(('.json', '.md'))]
    refs += [r['evidence'] for r in ledger['additionalNewFindings']]
    assert all(ref(v).is_file() for v in refs)
    assert read(OUT / 'ASSET-ISSUES.json')['records'][0]['affectedReferences'] == 9
    return {'currentReferencesVerified': len(refs), 'fixedReferences': 9}


def stopped():
    s, c = read(R3 / 'STATUS.json'), read(R3 / 'CLEANUP.json')
    assert s['status'] == 'SKIPPED_BY_USER' and s['completedWindows'] == 0
    assert not list(R3.glob('attempt-*'))
    samples = (R3 / 'SCHEDULING-STANDBY.jsonl').read_text().strip().splitlines()
    assert len(samples) == 1183
    assert c['ownedProcessesRemaining'] == 0 and c['ownedPrivateDirectoryAbsent'] and c['supervisorAbsent']
    assert not pathlib.Path(read(R3 / 'RUN-OWNERSHIP.json')['ownedDirectory']).exists()
    return {'formalAttempts': 0, 'validWindows': 0, 'standbySamplesRetained': len(samples), 'cleanupVerified': True}


def environment():
    b, s = read(OUT / 'ENVIRONMENT-BLOCKERS.json'), read(OUT / 'SKIPPED-WORK.json')
    assert b['count'] == len(b['records']) == 12
    assert len({r['id'] for r in b['records']}) == 12
    assert s['environmentCases'] == 12 and s['d26Observations'] == 2 and all(not r['countsAsPassed'] for r in s['records'])
    assert (OUT / 'ENVIRONMENT-BLOCKERS.json').read_bytes() == (ROOT / 'docs/test-reports/2026-11-04/d26-baseline-r2/ENVIRONMENT-BLOCKERS.json').read_bytes()
    return {'environmentSkippedThisRound': 12, 'notConvertedToPass': True}


def risk():
    r = read(OUT / 'UNCONFIRMED-RISK.json')
    assert r['status'] == 'UNCONFIRMED_RETAINED' and not r['rootCauseConfirmed']
    assert (OUT / 'UNCONFIRMED-RISK.json').read_bytes() == (ROOT / 'docs/test-reports/2026-11-04/d26-baseline-r2/UNCONFIRMED-RISK.json').read_bytes()


def sources():
    baseline = read(R3 / 'SOURCE-BASELINE.json')
    for p, value in baseline.items():
        assert digest(ROOT / p) == (value if isinstance(value, str) else value['sha256']), p
    assert len(baseline) == 1430
    return {'existingSourceFilesUnchanged': 1430}


def current_progress():
    text = (ROOT / 'docs/test-reports/2026-10-30/PROGRESS-AND-REMAINING.md').read_text()
    assert '45项结果审核通过' in text and '没有另一个未执行的非阻塞功能模块' in text
    assert read(OUT / 'SUMMARY.json')['remainingExecutableUntestedModules'] == 0
    for date in ['2026-11-02', '2026-11-03']:
        line = next(x for x in (ROOT / f'docs/test-reports/{date}/README.md').read_text().splitlines() if x.startswith('**最新状态'))
        assert '2026-11-05' in line and 'D23及新增Bug结果均已审核' in line


def routes():
    pages = set(re.findall(r"@/pages/(\w+)\.vue", (ROOT / 'src/router/index.ts').read_text()))
    mapped = {r['page'] for r in read(OUT / 'ROUTE-SCOPE-MAP.json')['pages']}
    assert pages == mapped, str(pages ^ mapped)
    return {'routedPagesMapped': len(pages), 'newProductFunctionTests': 0}


def delivery():
    assert len(read(OUT / 'AUDIT-PLAN.json')['checks']) == 16
    assert read(OUT / 'SUMMARY.json')['newProductTests'] == 0
    text = (OUT / 'FINAL-REPORT.md').read_text()
    for label, target in re.findall(r'\[([^\]]+)\]\(([^)]+)\)', text):
        if not target.startswith(('http:', 'https:')):
            assert (OUT / target).resolve().is_file(), label
    assert '0/2' in text and 'UNCONFIRMED_RETAINED' in text


actions = [module_inventory, totals, historical_bugs, observations, approval_coverage, d16, d23, frozen_hashes, references, stopped, environment, risk, sources, current_progress, routes, delivery]
# The report links to this result artifact; register it before validating delivery links.
(OUT / 'AUDIT-RESULTS.json').write_text(json.dumps({'status': 'RUNNING', 'total': len(actions)}) + '\n')
for i, action in enumerate(actions, 1):
    check(f'AC{i:02}', action)
value = {'at': datetime.now(timezone.utc).isoformat(), 'scope': 'DOCUMENT_AND_EVIDENCE_AUDIT_ONLY', 'total': len(results), 'passed': sum(x['result'] == 'PASS' for x in results), 'failed': sum(x['result'] == 'FAIL' for x in results), 'newProductPositiveCases': 0, 'newProductNegativeCases': 0, 'results': results, 'details': details}
(OUT / 'AUDIT-RESULTS.json').write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: value[k] for k in ['scope', 'total', 'passed', 'failed']}))
if value['failed']:
    print(json.dumps([r for r in results if r['result'] == 'FAIL'], ensure_ascii=False))
sys.exit(1 if value['failed'] else 0)
