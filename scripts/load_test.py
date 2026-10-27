#!/usr/bin/env python3
"""Fixed-arrival-rate catalog benchmark; preserve every observation, including drops."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import http.client
import json
import math
from pathlib import Path
import platform
import random
import statistics
import threading
import time
from urllib.parse import quote, urlparse
from api_client import api_key, Client, ROOT


def percentile(values, percent):
    """Nearest-rank percentile, consistently used for all benchmark cases."""
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * percent / 100) - 1)]


def select_working_set(skus, hotset=0):
    if hotset < 0 or hotset > len(skus):
        raise ValueError('hotset must be 0 (whole dataset) or no larger than the dataset')
    return list(skus[:hotset] if hotset else skus)


def generate_sequence(skus, offered, seed, distribution='uniform', zipf_alpha=1.2):
    randomizer = random.Random(seed)
    if distribution == 'uniform':
        return [randomizer.choice(skus) for _ in range(offered)]
    if distribution != 'zipf' or not math.isfinite(zipf_alpha) or not 0.1 <= zipf_alpha <= 3:
        raise ValueError('zipf alpha must be finite and 0.1..3')
    weights = [(index + 1) ** -zipf_alpha for index in range(len(skus))]
    return randomizer.choices(skus, weights=weights, k=offered)


def summarize(rows, elapsed, offered):
    completed = [row for row in rows if row.get('startedAtSeconds') is not None]
    passed = [row for row in completed if row.get('status') == 200 and row.get('correct')]
    latencies = [row['elapsedMs'] for row in passed]
    scheduled_latencies = [row['elapsedMs'] + row.get('schedulerDelayMs', 0) for row in passed]
    delays = [row.get('schedulerDelayMs', 0) for row in completed]
    return {'offeredRequests': offered, 'startedRequests': len(completed), 'successfulRequests': len(passed),
        'droppedRequests': offered - len(completed), 'failedRequests': len(completed) - len(passed),
        'elapsedSecondsIncludingDrain': elapsed, 'successfulRequestsPerSecond': len(passed) / elapsed,
        'meanMs': statistics.fmean(latencies) if latencies else None,
        'p50Ms': percentile(latencies, 50), 'p95Ms': percentile(latencies, 95), 'p99Ms': percentile(latencies, 99),
        'meanScheduledLatencyMs': statistics.fmean(scheduled_latencies) if scheduled_latencies else None,
        'p95ScheduledLatencyMs': percentile(scheduled_latencies, 95),
        'p95SchedulerDelayMs': percentile(delays, 95),
        'percentileMethod': 'nearest rank; latency is successful, correct HTTP 200 requests only; drops separately counted'}


def validate_dataset(dataset):
    skus = dataset['skus']
    checksum = hashlib.sha256(json.dumps(skus, separators=(',', ':')).encode()).hexdigest()
    if checksum != dataset['sha256'] or not skus or len(set(skus)) != len(skus):
        raise ValueError('dataset integrity check failed')
    if not all(isinstance(sku, str) and sku and len(sku) <= 64 for sku in skus):
        raise ValueError('dataset has invalid SKUs')
    return skus, checksum


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8081')
    parser.add_argument('--dataset', type=Path, default=ROOT / 'reports' / 'benchmark-dataset.json')
    parser.add_argument('--mode', choices=('off', 'cold', 'warm'), required=True,
        help='Label the actual server configuration; this script does not change it')
    parser.add_argument('--rate', type=float, default=50)
    parser.add_argument('--seconds', type=float, default=30)
    parser.add_argument('--concurrency', type=int, default=32)
    parser.add_argument('--seed', type=int, default=2027)
    parser.add_argument('--repeat', type=int, default=1)
    parser.add_argument('--distribution', choices=('uniform', 'zipf'), default='uniform')
    parser.add_argument('--zipf-alpha', type=float, default=1.2)
    parser.add_argument('--hotset', type=int, default=0, help='Only request the first N dataset SKUs; 0 uses all')
    parser.add_argument('--no-preload', action='store_true', help='Do not prefill warm mode; experiment runner manages state')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'reports' / 'load')
    args = parser.parse_args()
    if not 0 < args.rate <= 10000 or not 0 < args.seconds <= 3600 or not 1 <= args.concurrency <= 512:
        parser.error('rate 0..10000, duration 0..3600 seconds, concurrency 1..512 required')
    if not math.isfinite(args.zipf_alpha) or not 0.1 <= args.zipf_alpha <= 3:
        parser.error('zipf alpha must be finite and 0.1..3')
    if not 1 <= args.repeat <= 100:
        parser.error('repeat must be 1..100')
    offered = math.floor(args.rate * args.seconds)
    if not 1 <= offered <= 1000000:
        parser.error('choose a rate/duration offering 1..1000000 requests per repetition')
    dataset = json.loads(args.dataset.read_text())
    try:
        all_skus, checksum = validate_dataset(dataset)
        skus = select_working_set(all_skus, args.hotset)
    except ValueError as error:
        parser.error(str(error))
    target = urlparse(args.url)
    if target.scheme not in ('http', 'https') or not target.hostname or target.query or target.fragment or target.username:
        parser.error('url must be an http(s) API base URL')
    key = api_key()
    Client(args.url, key).wait_ready()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    sequence = generate_sequence(skus, offered, args.seed, args.distribution, args.zipf_alpha)
    for repetition in range(1, args.repeat + 1):
        if args.mode == 'warm' and not args.no_preload:
            client = Client(args.url, key)
            for sku in skus:
                client.request('GET', '/v1/items/' + quote(sku, safe=''))
        slots = threading.BoundedSemaphore(args.concurrency)
        thread_state = threading.local()
        observations = [None] * offered
        started_utc = datetime.now(timezone.utc).isoformat()
        started = time.monotonic()

        def issue(index, sku):
            begin = time.monotonic()
            row = {'index': index, 'sku': sku, 'scheduledAtSeconds': index / args.rate,
                'startedAtSeconds': begin - started, 'schedulerDelayMs': (begin - started - index / args.rate) * 1000}
            try:
                if not hasattr(thread_state, 'connection'):
                    cls = http.client.HTTPSConnection if target.scheme == 'https' else http.client.HTTPConnection
                    thread_state.connection = cls(target.hostname, target.port, timeout=5)
                connection = thread_state.connection
                path = target.path.rstrip('/') + '/v1/items/' + quote(sku, safe='')
                connection.request('GET', path, headers={'X-API-Key': key, 'Accept': 'application/json'})
                response = connection.getresponse()
                raw = response.read(65537)
                row['status'] = response.status
                if len(raw) > 65536:
                    raise ValueError('response body exceeds bound')
                body = json.loads(raw)
                expected = dataset.get('items', {}).get(sku)
                row['correct'] = isinstance(body, dict) and body.get('sku') == sku and body.get('currency') == 'USD'
                if expected and row['correct']:
                    row['correct'] = all(body.get(field) == expected[field] for field in ('name', 'unitPriceMinor', 'currency'))
            except (OSError, ValueError, http.client.HTTPException, AttributeError) as error:
                row.update(status=row.get('status'), correct=False, error=type(error).__name__)
                if hasattr(thread_state, 'connection'):
                    thread_state.connection.close()
                    del thread_state.connection
            finally:
                row['elapsedMs'] = (time.monotonic() - begin) * 1000
                observations[index] = row
                slots.release()

        with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
            for index, sku in enumerate(sequence):
                due = started + index / args.rate
                delay = due - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                if slots.acquire(blocking=False):
                    executor.submit(issue, index, sku)
                else:
                    observations[index] = {'index': index, 'sku': sku, 'scheduledAtSeconds': index / args.rate,
                        'startedAtSeconds': None, 'error': 'inflight_limit_drop'}
        remaining = started + args.seconds - time.monotonic()
        if remaining > 0:
            time.sleep(remaining)
        elapsed = time.monotonic() - started
        ended_utc = datetime.now(timezone.utc).isoformat()
        summary = summarize(observations, elapsed, offered)
        run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        prefix = args.output_dir / f'{run_id}-{args.mode}-{repetition}'
        with prefix.with_suffix('.jsonl').open('w') as stream:
            for row in observations:
                stream.write(json.dumps(row, separators=(',', ':')) + '\n')
        report = {'timestamp': run_id, 'startedAt': started_utc, 'endedAt': ended_utc,
            'mode': args.mode, 'url': args.url, 'rate': args.rate,
            'scheduledSeconds': args.seconds, 'concurrency': args.concurrency, 'seed': args.seed,
            'repetition': repetition, 'datasetSha256': checksum,
            'workingSetCount': len(skus), 'distribution': args.distribution, 'zipfAlpha': args.zipf_alpha,
            'preloadPerformed': args.mode == 'warm' and not args.no_preload,
            'requestSequenceSha256': hashlib.sha256(json.dumps(sequence).encode()).hexdigest(),
            'machine': {'system': platform.system(), 'machine': platform.machine(), 'python': platform.python_version()},
            'summary': summary,
            'limitations': ['Mode is a label: verify actual server cache setting separately.',
                'Client and service may share host resources; this is not a production capacity result.',
                'Latency excludes dropped requests; compare failures/drops and scheduler delay alongside latency.']}
        prefix.with_suffix('.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'report': str(prefix.with_suffix('.json')), **summary}), flush=True)
        if summary['failedRequests'] or summary['droppedRequests']:
            print('This workload contains errors/drops; successful latency alone is not a healthy capacity result.', flush=True)


if __name__ == '__main__':
    main()
