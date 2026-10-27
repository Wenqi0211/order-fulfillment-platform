#!/usr/bin/env python3
"""Validate and merge completed cache experiments without rereading heavy request JSONL."""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import statistics
from api_client import ROOT


class EvidenceError(ValueError):
    pass


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError) as error:
        raise EvidenceError(f'Cannot read evidence {path}: {type(error).__name__}') from error


def inside(directory, relative):
    result = (directory / relative).resolve()
    if not result.is_relative_to(directory.resolve()):
        raise EvidenceError('Evidence path escapes its experiment directory')
    return result


def require(condition, message):
    if not condition:
        raise EvidenceError(message)


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def validate_summary(value, label):
    counts = ('offeredRequests', 'startedRequests', 'successfulRequests', 'failedRequests', 'droppedRequests')
    for name in counts:
        require(isinstance(value.get(name), int) and not isinstance(value[name], bool) and value[name] >= 0,
            f'{label}: invalid {name}')
    require(value['offeredRequests'] == value['startedRequests'] + value['droppedRequests'], f'{label}: offered count mismatch')
    require(value['startedRequests'] == value['successfulRequests'] + value['failedRequests'], f'{label}: started count mismatch')
    require(finite(value.get('elapsedSecondsIncludingDrain')) and value['elapsedSecondsIncludingDrain'] > 0,
        f'{label}: invalid duration')
    for name in ('meanMs', 'p50Ms', 'p95Ms', 'p99Ms'):
        if value['successfulRequests']:
            require(finite(value.get(name)) and value[name] >= 0, f'{label}: invalid {name}')
        else:
            require(value.get(name) is None, f'{label}: no success but latency present')
    if value['successfulRequests']:
        require(value['p50Ms'] <= value['p95Ms'] <= value['p99Ms'], f'{label}: percentile order mismatch')


def instant(value, label):
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
        require(result.tzinfo is not None, f'{label}: timezone missing')
        return result
    except (AttributeError, ValueError) as error:
        raise EvidenceError(f'{label}: invalid timestamp') from error


def metric_counts(case):
    values = case['metrics']['counterAndTimerDeltas']
    names = {
        'databaseSelectCalls': 'inventory_catalog_database_reads_total',
        'cacheGetHits': 'inventory_cache_hits_total',
        'cacheGetMisses': 'inventory_cache_misses_total',
        'cacheBypasses': 'inventory_cache_bypasses_total',
        'cacheErrors': 'inventory_cache_errors_total'}
    result = {}
    for name, key in names.items():
        require(key in values and finite(values[key]) and values[key] >= 0, f'{case["caseDirectory"]}: missing/invalid metric {key}')
        result[name] = values[key]
    sources = {}
    for name, value in values.items():
        if re.fullmatch(r'inventory_catalog_lookup_seconds_count(?:\{.*\})?', name):
            require(finite(value) and value >= 0, 'Invalid lookup count')
            match = re.search(r'(?:\{|,)\s*source="([^"]+)"', name)
            source = match.group(1) if match else 'unlabeled'
            sources[source] = sources.get(source, 0) + value
    require(sources, f'{case["caseDirectory"]}: catalog lookup instrumentation missing')
    result['httpLookupsBySource'] = sources
    result['httpLookups'] = sum(sources.values())
    result['redisSourceHttpFraction'] = sources.get('redis', 0) / result['httpLookups'] if result['httpLookups'] else None
    get_calls = result['cacheGetHits'] + result['cacheGetMisses']
    result['cacheGetCallHitFraction'] = result['cacheGetHits'] / get_calls if get_calls else None
    return result


def load_experiment(directory, variant):
    directory = directory.resolve()
    metadata = read_json(directory / 'experiment.json')
    require(metadata.get('status') == 'COMPLETE', f'{variant}: experiment is not COMPLETE')
    listed = metadata.get('completedCases', [])
    schedule = metadata.get('schedule', [])
    require(listed and len(listed) == len(schedule), f'{variant}: completed case count does not match full schedule')
    args = metadata['arguments']
    require(metadata.get('datasetSha256') and metadata.get('expectedFieldsSha256'), f'{variant}: dataset fingerprints missing')
    paths = [entry['caseDirectory'] for entry in listed]
    require(len(set(paths)) == len(paths), f'{variant}: duplicated case directory')
    actual_case_files = {str(p.parent.relative_to(directory)) for p in directory.glob('*/case.json')}
    require(actual_case_files == set(paths), f'{variant}: unlisted/missing case.json; selective results are not allowed')
    cases = []
    for index, recorded in enumerate(listed):
        selected = schedule[index]
        require(all(recorded.get(key) == selected.get(key) for key in ('mode', 'rate', 'repetition')), f'{variant}: case deviates from declared schedule')
        case = read_json(inside(directory, recorded['caseDirectory']) / 'case.json')
        require(case == recorded, f'{variant}: case.json and experiment.json differ')
        mode = case['mode']
        require(mode in ('off', 'cold', 'warm'), f'{variant}: unexpected mode')
        container = case['container']
        env = container['cacheEnvironment']
        require(env.get('INVENTORY_CACHE_ENABLED') == ('false' if mode == 'off' else 'true'), f'{variant}: mode flag does not match container')
        require('INVENTORY_CACHE_TTL_SECONDS' in env, f'{variant}: TTL not recorded')
        require(finite(container.get('memoryBytes')) and container['memoryBytes'] > 0 and finite(container.get('nanoCpus')) and container['nanoCpus'] > 0,
            f'{variant}: explicit CPU/memory limits missing')
        measurement_path = inside(directory, case['report'])
        measurement = read_json(measurement_path)
        validate_summary(measurement['summary'], str(measurement_path))
        require(measurement['summary'] == case['summary'], f'{variant}: case and measurement summaries differ')
        for key in ('rate', 'mode'):
            require(measurement[key] == case[key], f'{variant}: measurement {key} mismatch')
        for key, arg_key in (('scheduledSeconds', 'seconds'), ('concurrency', 'concurrency'), ('seed', 'seed'), ('distribution', 'distribution'), ('zipfAlpha', 'zipf_alpha')):
            require(measurement.get(key) == args.get(arg_key), f'{variant}: measurement {key} differs from declared arguments')
        require(measurement['datasetSha256'] == metadata['datasetSha256'], f'{variant}: dataset changed within experiment')
        require(case['requestSequenceSha256'] == measurement['requestSequenceSha256'], f'{variant}: sequence hash mismatch')
        require(measurement['summary']['offeredRequests'] == math.floor(case['rate'] * args['seconds']), f'{variant}: offered request count differs from planned rate/duration')
        started, ended = instant(measurement['startedAt'], 'measurement start'), instant(measurement['endedAt'], 'measurement end')
        require(started < ended, f'{variant}: invalid measurement interval')
        warmups = []
        for path in sorted((inside(directory, case['caseDirectory']) / 'warmup').glob('*.json')):
            value = read_json(path)
            validate_summary(value['summary'], str(path))
            require(instant(value['endedAt'], 'warmup end') <= started, f'{variant}: warmup overlaps measured interval')
            require(value['datasetSha256'] == metadata['datasetSha256'], f'{variant}: warmup dataset differs')
            warmups.append({'report': str(path), 'summary': value['summary'], 'startedAt': value['startedAt'], 'endedAt': value['endedAt']})
        require(bool(warmups) == bool(args['warmup_seconds']), f'{variant}: missing/unexpected warmup evidence')
        result = {'variant': variant, 'experimentLabel': metadata['label'], 'experimentDirectory': str(directory),
            'caseDirectory': case['caseDirectory'], 'mode': mode, 'rate': case['rate'], 'repetition': case['repetition'],
            'container': container, 'measurementReport': str(measurement_path), 'startedAt': measurement['startedAt'], 'endedAt': measurement['endedAt'],
            'summary': measurement['summary'], 'metrics': metric_counts(case), 'timerMeansMs': case['metrics'].get('timerMeanMilliseconds', {}),
            'requestSequenceSha256': measurement['requestSequenceSha256'], 'workingSetCount': measurement['workingSetCount'], 'warmupReports': warmups}
        cases.append(result)
    require(len({case['container']['imageId'] for case in cases}) == 1, f'{variant}: image changed within variant')
    return {'variant': variant, 'directory': str(directory), 'metadata': metadata, 'cases': cases}


def aggregate(cases):
    totals = {key: sum(case['summary'][key] for case in cases) for key in
        ('offeredRequests', 'startedRequests', 'successfulRequests', 'failedRequests', 'droppedRequests')}
    success = totals['successfulRequests']
    valid = [case for case in cases if case['summary']['successfulRequests']]
    means = [case['summary']['meanMs'] for case in valid]
    p95s = [case['summary']['p95Ms'] for case in valid]
    p99s = [case['summary']['p99Ms'] for case in valid]
    elapsed = sum(case['summary']['elapsedSecondsIncludingDrain'] for case in cases)
    metrics = {key: sum(case['metrics'][key] for case in cases) for key in
        ('databaseSelectCalls', 'cacheGetHits', 'cacheGetMisses', 'cacheBypasses', 'cacheErrors', 'httpLookups')}
    sources = defaultdict(float)
    for case in cases:
        for source, count in case['metrics']['httpLookupsBySource'].items():sources[source] += count
    metrics['httpLookupsBySource'] = dict(sources)
    metrics['redisSourceHttpFraction'] = sources.get('redis', 0) / metrics['httpLookups'] if metrics['httpLookups'] else None
    get_calls = metrics['cacheGetHits'] + metrics['cacheGetMisses']
    metrics['cacheGetCallHitFraction'] = metrics['cacheGetHits'] / get_calls if get_calls else None
    return {'runCount': len(cases), **totals, 'healthyMeasuredWorkload': not (totals['failedRequests'] or totals['droppedRequests']),
        'weightedMeanSuccessfulMs': sum(case['summary']['meanMs'] * case['summary']['successfulRequests'] for case in valid) / success if success else None,
        'runMeanMsRange': [min(means), max(means)] if means else None,
        'runP95MsRange': [min(p95s), max(p95s)] if p95s else None,
        'medianOfRunP95Ms': statistics.median(p95s) if p95s else None,
        'runP99MsRange': [min(p99s), max(p99s)] if p99s else None,
        'successfulRequestsPerSecondAcrossRunDurations': success / elapsed,
        'metrics': metrics,
        'percentileNote': 'Run p95/p99 ranges and median of run p95 values only; no pooled percentile inferred from run percentiles.'}


def change(before, after):
    return (before - after) / before * 100 if before is not None and before > 0 and after is not None else None


def comparison(before, after, attribution):
    return {'from': before, 'to': after, 'attribution': attribution,
        'weightedMeanReductionPercent': change(before['weightedMeanSuccessfulMs'], after['weightedMeanSuccessfulMs']),
        'changeInMedianOfRunP95ReductionPercent': change(before['medianOfRunP95Ms'], after['medianOfRunP95Ms']),
        'databaseSelectCallReductionPercent': change(before['metrics']['databaseSelectCalls'], after['metrics']['databaseSelectCalls']),
        'eligibleForHealthyLatencyComparison': before['healthyMeasuredWorkload'] and after['healthyMeasuredWorkload'],
        'note': 'Positive reduction means lower; negative means higher. Successful-response means exclude failures/drops, which remain explicit. No statistical significance or production capacity is asserted.'}


def build_study(baseline, optimized):
    base, opt = baseline['metadata'], optimized['metadata']
    require(base['datasetSha256'] == opt['datasetSha256'] and base['expectedFieldsSha256'] == opt['expectedFieldsSha256'], 'Variants use different dataset/expected fields')
    required_args = ('seconds', 'concurrency', 'seed', 'distribution', 'zipf_alpha', 'hotset', 'warmup_seconds', 'warmup_rate', 'repeats')
    for key in required_args:
        require(base['arguments'].get(key) == opt['arguments'].get(key), f'Variants differ in {key}')
    cases = baseline['cases'] + optimized['cases']
    resources = {(case['container']['memoryBytes'], case['container']['nanoCpus'], case['container']['cacheEnvironment']['INVENTORY_CACHE_TTL_SECONDS']) for case in cases}
    require(len(resources) == 1, 'CPU/memory/TTL differs across variants or cases')
    sequences = defaultdict(set)
    for case in cases:
        sequences[case['rate']].add((case['requestSequenceSha256'], case['workingSetCount']))
    require(all(len(values) == 1 for values in sequences.values()), 'Measured request sequence or working set differs across modes/variants')
    groups = defaultdict(list)
    for case in cases:groups[(case['variant'], case['mode'], case['rate'])].append(case)
    base_rates = {rate for variant, mode, rate in groups if variant == 'baseline' and mode == 'warm'}
    require(base_rates and base_rates == {rate for variant, mode, rate in groups if variant == 'baseline' and mode == 'off'}
        == {rate for variant, mode, rate in groups if variant == 'optimized' and mode == 'warm'}, 'Missing comparable baseline off/warm or optimized warm rate groups')
    comparisons = []
    for rate in sorted(base_rates):
        selected = [groups[('baseline', 'off', rate)], groups[('baseline', 'warm', rate)], groups[('optimized', 'warm', rate)]]
        expected_repetitions = set(range(1, base['arguments']['repeats'] + 1))
        require(all({case['repetition'] for case in group} == expected_repetitions and len(group) == len(expected_repetitions) for group in selected), 'Repetitions missing, duplicated or selectively included')
        off, warm, improved = [aggregate(group) for group in selected]
        comparisons.append({'rate': rate,
            'cacheBenefitWithinBaselineImage': comparison(off, warm, 'Same baseline image, cache disabled versus enabled; do not attribute this whole difference to the later optimization.'),
            'incrementalOptimization': comparison(warm, improved, 'Baseline cache-enabled image versus optimized cache-enabled image at matching recorded controls.')})
    intervals = sorted((instant(case['startedAt'], 'start'), instant(case['endedAt'], 'end')) for case in cases)
    require(all(intervals[i][1] <= intervals[i + 1][0] for i in range(len(intervals) - 1)), 'Measured intervals overlap; workloads were not isolated')
    warmups = [record for case in cases for record in case['warmupReports']]
    warmup_totals = {key: sum(record['summary'][key] for record in warmups) for key in
        ('offeredRequests', 'startedRequests', 'successfulRequests', 'failedRequests', 'droppedRequests')}
    memory, cpu, ttl = next(iter(resources))
    return {'generatedAt': datetime.now(timezone.utc).isoformat(), 'validation': 'PASSED',
        'baselineExperiment': baseline['directory'], 'optimizedExperiment': optimized['directory'],
        'datasetSha256': base['datasetSha256'], 'expectedFieldsSha256': base['expectedFieldsSha256'],
        'controls': {**{key: base['arguments'][key] for key in required_args}, 'memoryBytes': memory, 'nanoCpus': cpu, 'ttlSeconds': ttl},
        'imageIds': {'baseline': baseline['cases'][0]['container']['imageId'], 'optimized': optimized['cases'][0]['container']['imageId']},
        'allMeasuredCases': cases,
        'groups': [{'variant': variant, 'mode': mode, 'rate': rate, **aggregate(group)} for (variant, mode, rate), group in sorted(groups.items())],
        'comparisons': comparisons,
        'warmup': {'reportCount': len(warmups), **warmup_totals, 'excludedFromMeasuredLatencyByDesign': True,
            'note': 'Warmup was a separately declared phase. All warmup failures and drops are retained in each case and these totals; they are not a healthy cold-start claim.'},
        'limitations': ['All scheduled measured cases were included; warmup/prefill stay outside measured request statistics.',
            'Latency averages are weighted by successful correct responses; errors and drops are retained separately.',
            'Median of run p95 values is not a pooled p95 and does not prove statistical significance.',
            'These local results share a host for generator and services; they do not establish production capacity.',
            'Observed cache hits/misses count cache-get calls. A leader recheck may make more than one call per HTTP request; HTTP sources are reported separately.']}


def number(value):
    if value is None:return '—'
    return f'{value:.3f}' if isinstance(value, float) else str(value)


def markdown(study):
    out = ['# 缓存实验汇总', '', '仅合并状态为 COMPLETE 的两组实验。数据集、预期响应、请求序列、CPU/内存、TTL、时长、预热和重复次数已核对。所有预声明测量轮次均保留。', '',
        '## 每轮测量', '', '| 版本 | 模式 | 轮次 | RPS | 成功 | 失败 | 丢弃 | 平均 ms | p95 ms | p99 ms |', '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for case in study['allMeasuredCases']:
        s = case['summary']
        out.append('| ' + ' | '.join(number(v) for v in [case['variant'],case['mode'],case['repetition'],case['rate'],s['successfulRequests'],s['failedRequests'],s['droppedRequests'],s['meanMs'],s['p95Ms'],s['p99Ms']]) + ' |')
    out += ['', '平均值与分位数仅统计返回正确内容的成功响应。失败和丢弃同时列出，不能用成功延迟掩盖不健康负载。', '', '## 数据库与缓存计数', '',
        '| 版本 | 模式 | 轮次 | 数据库 SELECT 调用 | cache.get 命中 | cache.get 未命中 | HTTP 来源：Redis | HTTP 来源：数据库 | HTTP 来源：合并等待 |',
        '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
    for case in study['allMeasuredCases']:
        m = case['metrics'];sources = m['httpLookupsBySource']
        out.append('| ' + ' | '.join(number(v) for v in [case['variant'],case['mode'],case['repetition'],m['databaseSelectCalls'],m['cacheGetHits'],m['cacheGetMisses'],sources.get('redis',0),sources.get('database',0),sources.get('coalesced',0)]) + ' |')
    out += ['', '命中/未命中计数针对缓存读取调用；优化版本可能为请求进行再次检查，因此不能将该比例直接当成 HTTP 命中率。', '', '## 汇总与两种比较', '',
        '| 版本 | 模式 | RPS | 加权成功平均 ms | 各轮 p95 范围 ms | 各轮 p95 的中位数 ms | 测量失败/丢弃 |',
        '| --- | --- | ---: | ---: | --- | ---: | --- |']
    for group in study['groups']:
        interval=group['runP95MsRange'];span='—' if interval is None else f'{interval[0]:.3f}–{interval[1]:.3f}'
        out.append(f"| {group['variant']} | {group['mode']} | {number(group['rate'])} | {number(group['weightedMeanSuccessfulMs'])} | {span} | {number(group['medianOfRunP95Ms'])} | {group['failedRequests']}/{group['droppedRequests']} |")
    for entry in study['comparisons']:
        out += ['', f"在 {number(entry['rate'])} RPS："]
        for key,label in [('cacheBenefitWithinBaselineImage','同一 baseline 镜像，关闭缓存 → 开启缓存'),('incrementalOptimization','已有缓存 baseline → 本次优化缓存')]:
            c=entry[key]
            out.append(f"- {label}：成功响应加权平均相对减少 {number(c['weightedMeanReductionPercent'])}%；数据库 SELECT 调用相对减少 {number(c['databaseSelectCallReductionPercent'])}%；两组测量均无失败/丢弃：{c['eligibleForHealthyLatencyComparison']}。")
        out += ['', '正数表示减少，负数表示增加；这是观察到的变化，不表示统计显著性。新增优化的成果只看第二项，不能把缓存本身的全部收益算作新增优化。']
    out += ['', '没有把各轮 p95 平均后冒充总体 p95；表中给出逐轮范围与“各轮 p95 的中位数”。', '', '## 预热结果单列保留', '',
        '| 版本 | 模式 | 轮次 | 成功 | 失败 | 丢弃 |', '| --- | --- | ---: | ---: | ---: | ---: |']
    for case in study['allMeasuredCases']:
        for record in case['warmupReports']:
            s=record['summary'];out.append(f"| {case['variant']} | {case['mode']} | {case['repetition']} | {s['successfulRequests']} | {s['failedRequests']} | {s['droppedRequests']} |")
    w=study['warmup']
    out += ['', f"预热总计失败 {w['failedRequests']}、丢弃 {w['droppedRequests']}。这些属于预先区分的预热阶段，未计入正式测量延迟，但已完整保留，不能声称冷启动也没有问题。", '',
        '本机客户端与服务共享机器资源，结果不代表生产容量。原始文件路径、每轮指标、镜像 ID、控制变量和全部预热报告见同目录 JSON 汇总。', '']
    return '\n'.join(out)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline-dir',type=Path,required=True)
    parser.add_argument('--optimized-dir',type=Path,required=True)
    output=parser.add_mutually_exclusive_group()
    output.add_argument('--output-dir',type=Path)
    output.add_argument('--output',type=Path,help='JSON path; Markdown uses the same stem')
    args=parser.parse_args()
    try:
        study=build_study(load_experiment(args.baseline_dir,'baseline'),load_experiment(args.optimized_dir,'optimized'))
    except EvidenceError as error:
        parser.exit(1,'Evidence validation failed: '+str(error)+'\n')
    target=args.output or (args.output_dir or ROOT/'reports'/'cache-study-summary')/'summary.json'
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(study,indent=2)+'\n')
    target.with_suffix('.md').write_text(markdown(study))
    print('Validated complete study: '+str(target))


if __name__=='__main__':main()
