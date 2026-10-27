#!/usr/bin/env python3
"""Controlled local Compose cache experiments with raw evidence and independent JVM warmup."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import subprocess
import sys
import time
import urllib.request
from api_client import Client, ROOT, api_key
from load_test import select_working_set, validate_dataset


def run(*command, env=None, capture=False):
    if command and command[0] == 'docker':
        command = (str(ROOT / 'scripts' / 'docker-local.sh'), *command[1:])
    return subprocess.run(command, cwd=ROOT, env=env, text=True, check=True,
        stdout=subprocess.PIPE if capture else None).stdout


def parse_service_env(values):
    result = {}
    for value in values:
        name, separator, setting = value.partition('=')
        if not separator or not re.fullmatch(r'INVENTORY_(IMAGE|CACHE_[A-Z0-9_]+)', name) or not setting:
            raise ValueError('service-env must be INVENTORY_IMAGE=value or INVENTORY_CACHE_...=value')
        result[name] = setting
    return result


def make_schedule(modes, rates, repeats, order, seed):
    cases = []
    for rate in rates:
        for repetition in range(1, repeats + 1):
            selected = list(modes)
            if order == 'alternating' and repetition % 2 == 0:
                selected.reverse()
            elif order == 'random':
                random.Random(seed + repetition).shuffle(selected)
            for mode in selected:
                cases.append({'mode': mode, 'rate': rate, 'repetition': repetition})
    return cases


def parse_prometheus(text):
    """Keep exact metric/label identities; no approximate string-to-number substitution."""
    result = {}
    for line in text.splitlines():
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*(?:\{.*\})?)\s+([^\s]+)(?:\s+\d+)?', line)
        if match:
            try:
                value = float(match.group(2))
            except ValueError:
                continue
            if math.isfinite(value):
                result[match.group(1)] = value
    return result


def metric_delta(before_text, after_text):
    before, after = parse_prometheus(before_text), parse_prometheus(after_text)
    # These are application counters and timer sum/count. Max and gauges are
    # instantaneous observations, so they stay in the full snapshots only.
    deltas = {name: value - before.get(name, 0.0) for name, value in after.items()
        if name.startswith('inventory_') and re.match(r'.*_(total|count|sum)(?:\{|$)', name)}
    if any(value < 0 for value in deltas.values()):
        raise ValueError('Application counter reset during case; latency comparison is invalid')
    timer_means = {}
    for name, value in deltas.items():
        if '_seconds_sum' in name:
            count = deltas.get(name.replace('_seconds_sum', '_seconds_count'), 0)
            if count:
                timer_means[name.replace('_seconds_sum', '_mean_ms')] = value * 1000 / count
    return {'counterAndTimerDeltas': deltas, 'timerMeanMilliseconds': timer_means,
        'note': 'Means derive from measured timer sum/count deltas; full metrics snapshots retain gauges/maxima.'}


def inspect_inventory():
    identity = run('docker', 'compose', 'ps', '-q', 'inventory-service', capture=True).strip()
    if not identity:
        raise RuntimeError('Start the local Compose stack before benchmarking')
    value = json.loads(run('docker', 'inspect', '--format', '{{json .}}', identity, capture=True))
    environment = dict(entry.split('=', 1) for entry in value['Config'].get('Env', []) if '=' in entry)
    safe_env = {name: setting for name, setting in environment.items()
        if re.fullmatch(r'INVENTORY_CACHE_[A-Z0-9_]+', name)}
    return {'containerId': value['Id'], 'imageId': value['Image'], 'imageTag': value['Config']['Image'],
        'memoryBytes': value['HostConfig']['Memory'], 'nanoCpus': value['HostConfig']['NanoCpus'],
        'cacheEnvironment': safe_env}


def configure_cache(enabled, service_env):
    run('docker', 'compose', 'up', '-d', '--no-deps', '--force-recreate', '--wait', 'inventory-service',
        env={**os.environ, **service_env, 'INVENTORY_CACHE_ENABLED': str(enabled).lower()})
    Client('http://127.0.0.1:8081').wait_ready()
    actual = inspect_inventory()
    if actual['cacheEnvironment'].get('INVENTORY_CACHE_ENABLED') != str(enabled).lower():
        raise RuntimeError('Compose did not apply the requested cache mode')
    for name, setting in service_env.items():
        observed = actual['imageTag'] if name == 'INVENTORY_IMAGE' else actual['cacheEnvironment'].get(name)
        if observed != setting:
            raise RuntimeError(f'Compose did not apply {name}; do not benchmark a mislabeled configuration')
    return actual


def clear_catalog_prefix(prefix):
    # Dynamic values stay argv arguments; no FLUSHALL or interpolated shell code.
    scan = run('docker', 'compose', 'exec', '-T', 'redis', 'sh', '-c',
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --scan --pattern "$1"',
        'sh', f'catalog:v1:{prefix}_*', capture=True)
    keys = [line for line in scan.splitlines() if line.startswith(f'catalog:v1:{prefix}_')]
    for index in range(0, len(keys), 500):
        run('docker', 'compose', 'exec', '-T', 'redis', 'sh', '-c',
            'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli UNLINK "$@"',
            'sh', *keys[index:index + 500], capture=True)


def scrape_metrics():
    request = urllib.request.Request('http://127.0.0.1:8081/actuator/prometheus', headers={'X-API-Key': api_key()})
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read().decode()


def wait_for_outbox_drain(seconds):
    deadline = time.monotonic() + seconds
    while True:
        pending = parse_prometheus(scrape_metrics()).get('fulfillment_outbox_pending')
        if pending is None or pending == 0:
            return {'observed': pending is not None, 'pending': pending}
        if time.monotonic() >= deadline:
            raise RuntimeError('Inventory seeding outbox did not drain; background seeding work would contaminate the benchmark')
        time.sleep(1)


def source_snapshot():
    files = [ROOT / 'Dockerfile', ROOT / 'compose.yaml', ROOT / 'pom.xml']
    for name in ('common', 'inventory-service'):
        files += [p for p in (ROOT / name / 'src').rglob('*') if p.is_file()]
        files.append(ROOT / name / 'pom.xml')
    files += [ROOT / 'scripts' / name for name in ('cache_benchmark.py', 'load_test.py', 'seed_data.py')]
    hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(files)}
    digest = hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    return {'sha256': digest, 'files': hashes}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--count', type=int, default=1000)
    parser.add_argument('--rate', type=float, default=50)
    parser.add_argument('--rates', help='Comma-separated fixed arrival rates; overrides --rate')
    parser.add_argument('--seconds', type=float, default=60)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--concurrency', type=int, default=32)
    parser.add_argument('--prefix', default='BENCH')
    parser.add_argument('--dataset', type=Path, help='Reuse a manifest instead of creating products')
    parser.add_argument('--modes', default='off,cold,warm')
    parser.add_argument('--case-order', choices=('alternating', 'random', 'fixed'), default='alternating')
    parser.add_argument('--warmup-seconds', type=float, default=15)
    parser.add_argument('--warmup-rate', type=float, help='Default is the measured case arrival rate')
    parser.add_argument('--distribution', choices=('uniform', 'zipf'), default='uniform')
    parser.add_argument('--zipf-alpha', type=float, default=1.2)
    parser.add_argument('--hotset', type=int, default=0)
    parser.add_argument('--seed', type=int, default=2027)
    parser.add_argument('--label', default='experiment')
    parser.add_argument('--service-env', action='append', default=[], metavar='NAME=value')
    parser.add_argument('--drain-timeout', type=int, default=180)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.prefix) or not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', args.label):
        parser.error('prefix/label must be 1..40 letters, digits, underscores or hyphens')
    modes = args.modes.split(',')
    if not modes or len(set(modes)) != len(modes) or not set(modes) <= {'off', 'cold', 'warm'}:
        parser.error('modes must be a unique comma-separated selection of off,cold,warm')
    try:
        rates = [float(value) for value in args.rates.split(',')] if args.rates else [args.rate]
        overrides = parse_service_env(args.service_env)
    except ValueError as error:
        parser.error(str(error))
    if not rates or not all(math.isfinite(rate) and 0 < rate <= 10000 for rate in rates):
        parser.error('rates must be finite and 0..10000')
    if not 1 <= args.repeats <= 10 or not 1 <= args.concurrency <= 512 or not 1 <= args.seconds <= 3600:
        parser.error('repeats 1..10, concurrency 1..512, seconds 1..3600 required')
    if not 0 <= args.warmup_seconds <= 300 or args.warmup_rate is not None and not 0 < args.warmup_rate <= 10000:
        parser.error('warmup-seconds 0..300 and warmup-rate 0..10000 required')
    if not all(1 <= math.floor(rate * args.seconds) <= 1000000 for rate in rates):
        parser.error('each case must offer 1..1000000 requests')
    if not 1 <= args.drain_timeout <= 3600:
        parser.error('drain-timeout must be 1..3600')
    if not math.isfinite(args.zipf_alpha) or not 0.1 <= args.zipf_alpha <= 3:
        parser.error('zipf alpha must be finite and 0.1..3')
    previous = inspect_inventory()
    original_env = {'INVENTORY_IMAGE': previous['imageTag'], **previous['cacheEnvironment']}
    original_enabled = original_env.pop('INVENTORY_CACHE_ENABLED', 'true').lower() == 'true'
    # Inspect the forced local wrapper context, rather than trusting a remote context variable.
    endpoint = os.environ.get('DOCKER_HOST') or json.loads(run('docker', 'context', 'inspect', '--format', '{{json .Endpoints.docker.Host}}', capture=True))
    if not endpoint.startswith('unix://'):
        raise SystemExit('This experiment only supports the local Compose Unix socket')
    experiment = ROOT / 'reports' / 'cache-ab' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '-' + args.label)
    experiment.mkdir(parents=True)
    dataset_path = experiment / 'dataset.json'
    schedule = make_schedule(modes, rates, args.repeats, args.case_order, args.seed)
    metadata = {'startedAt': datetime.now(timezone.utc).isoformat(), 'label': args.label,
        'arguments': {name: str(value) if isinstance(value, Path) else value for name, value in vars(args).items()},
        'schedule': schedule, 'dockerContextEndpoint': endpoint, 'originalContainer': previous,
        'sourceAtStart': source_snapshot(), 'productionCapacityClaim': False,
        'method': 'Per-case container restart, equal timed JVM warmup, cache reset after warmup, optional prefill, fixed-arrival measurement; no concurrent Docker exec sampling during measurement.',
        'completedCases': [], 'status': 'RUNNING'}
    write_json(experiment / 'experiment.json', metadata)
    try:
        configure_cache(False, overrides)
        if args.dataset:
            dataset = json.loads(args.dataset.read_text())
            validate_dataset(dataset)
            # Legacy manifests did not preserve expected fields. Read the authority
            # with caching disabled before timing, then preserve those expectations.
            client = Client('http://127.0.0.1:8081')
            expected = dict(dataset.get('items', {}))
            for sku in dataset['skus']:
                if sku not in expected:
                    _, item = client.request('GET', '/v1/items/' + sku)
                    expected[sku] = {field: item[field] for field in ('name', 'unitPriceMinor', 'currency')}
            dataset['items'] = expected
            write_json(dataset_path, dataset)
        else:
            run(sys.executable, 'scripts/seed_data.py', '--count', str(args.count), '--prefix', args.prefix, '--output', str(dataset_path))
            dataset = json.loads(dataset_path.read_text())
        working = select_working_set(dataset['skus'], args.hotset)
        # A reset must only target this experiment's exact common prefix.
        inferred = {sku.rsplit('_', 1)[0] for sku in dataset['skus']}
        if len(inferred) != 1 or not re.fullmatch(r'[A-Za-z0-9_-]{1,40}', next(iter(inferred))):
            raise ValueError('Dataset SKUs need a single PREFIX_number namespace for scoped cache resets')
        reset_prefix = next(iter(inferred))
        metadata['datasetSha256'] = dataset['sha256']
        metadata['expectedFieldsSha256'] = hashlib.sha256(json.dumps(dataset['items'],sort_keys=True,separators=(',', ':')).encode()).hexdigest()
        metadata['seedingOutboxDrain'] = wait_for_outbox_drain(args.drain_timeout)
        for index, case in enumerate(schedule, start=1):
            mode, rate = case['mode'], case['rate']
            case_path = experiment / f'{index:02d}-{mode}-r{rate:g}-rep{case["repetition"]}'
            case_path.mkdir()
            state = configure_cache(mode != 'off', overrides)
            command = [sys.executable, 'scripts/load_test.py', '--dataset', str(dataset_path), '--mode', mode,
                '--concurrency', str(args.concurrency), '--distribution', args.distribution, '--zipf-alpha', str(args.zipf_alpha),
                '--hotset', str(args.hotset), '--repeat', '1', '--no-preload']
            if args.warmup_seconds:
                run(*command, '--rate', str(args.warmup_rate or rate), '--seconds', str(args.warmup_seconds),
                    '--seed', str(args.seed + 1), '--output-dir', str(case_path / 'warmup'))
            if mode != 'off':
                clear_catalog_prefix(reset_prefix)
            prefill_started = time.monotonic()
            if mode == 'warm':
                client = Client('http://127.0.0.1:8081')
                for sku in working:
                    client.request('GET', '/v1/items/' + sku)
            prefill_seconds = time.monotonic() - prefill_started
            before_text = scrape_metrics()
            (case_path / 'metrics-before.txt').write_text(before_text)
            container = state['containerId']
            before_stats = run('docker', 'stats', '--no-stream', '--format', '{{json .}}', container, capture=True)
            (case_path / 'container-stats-before.jsonl').write_text(before_stats)
            run(*command, '--rate', str(rate), '--seconds', str(args.seconds), '--seed', str(args.seed),
                '--output-dir', str(case_path / 'measurement'))
            after_text = scrape_metrics()
            (case_path / 'metrics-after.txt').write_text(after_text)
            after_state = inspect_inventory()
            if state['containerId'] != after_state['containerId']:
                raise RuntimeError('Inventory restarted during measurement; case invalid')
            (case_path / 'container-stats-after.jsonl').write_text(run('docker', 'stats', '--no-stream', '--format', '{{json .}}', container, capture=True))
            deltas = metric_delta(before_text, after_text)
            write_json(case_path / 'metrics-delta.json', deltas)
            measured_path = next((case_path / 'measurement').glob('*.json'))
            measured = json.loads(measured_path.read_text())
            result = {**case, 'caseDirectory': str(case_path.relative_to(experiment)), 'container': state,
                'prefillSeconds': prefill_seconds, 'report': str(measured_path.relative_to(experiment)),
                'summary': measured['summary'], 'requestSequenceSha256': measured['requestSequenceSha256'],
                'metrics': deltas, 'statsNote': 'Before/after instantaneous snapshots, not interval-average CPU'}
            write_json(case_path / 'case.json', result)
            metadata['completedCases'].append(result)
            write_json(experiment / 'experiment.json', metadata)
        metadata['status'] = 'COMPLETE'
    except BaseException as error:
        metadata['status'] = 'FAILED'
        metadata['failureType'] = type(error).__name__
        raise
    finally:
        metadata['endedAt'] = datetime.now(timezone.utc).isoformat()
        metadata['sourceAtEnd'] = source_snapshot()
        metadata['sourceTreeChangedDuringExperiment'] = metadata['sourceAtStart']['sha256'] != metadata['sourceAtEnd']['sha256']
        write_json(experiment / 'experiment.json', metadata)
        try:
            configure_cache(original_enabled, original_env)
        except Exception:
            print('Failed to restore original inventory configuration; run compose up -d --wait after inspection.', file=sys.stderr)
            raise
    print('Experiment complete. Raw measurements, metrics and case metadata: ' + str(experiment), flush=True)


if __name__ == '__main__':
    main()
