"""Tiny synthetic fixtures for completed-study evidence guards; no HTTP/load/Docker."""
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from summarize_cache_study import EvidenceError, build_study, load_experiment, markdown


def put(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value))


def fixture(directory,variant,modes,offset):
    args={'seconds':10,'concurrency':4,'seed':2027,'distribution':'uniform','zipf_alpha':1.2,'hotset':0,'warmup_seconds':5,'warmup_rate':None,'repeats':3}
    metadata={'status':'COMPLETE','label':variant,'arguments':args,'datasetSha256':'same-dataset','expectedFieldsSha256':'same-fields','schedule':[],'completedCases':[]}
    for repeat in range(1,4):
        for mode in modes:
            index=len(metadata['schedule']);case_id=f'{index+1:02d}-{mode}-r1-rep{repeat}'
            start=datetime(2026,1,1,tzinfo=timezone.utc)+timedelta(seconds=offset+index*20)
            summary={'offeredRequests':10,'startedRequests':10,'successfulRequests':10,'failedRequests':0,'droppedRequests':0,
                'elapsedSecondsIncludingDrain':10.0,'meanMs':2 if mode=='off' else (1.5 if variant=='baseline' else 1.6),
                'p50Ms':0.5,'p95Ms':[1,5,100][repeat-1],'p99Ms':101}
            measurement={'summary':summary,'mode':mode,'rate':1,'scheduledSeconds':10,'concurrency':4,'seed':2027,
                'distribution':'uniform','zipfAlpha':1.2,'datasetSha256':'same-dataset','workingSetCount':2,'requestSequenceSha256':'same-sequence',
                'startedAt':start.isoformat(),'endedAt':(start+timedelta(seconds=10)).isoformat()}
            report=f'{case_id}/measurement/run.json';put(directory/report,measurement)
            warmup=deepcopy(measurement);warmup['summary'].update(offeredRequests=5,startedRequests=4,successfulRequests=4,droppedRequests=1,elapsedSecondsIncludingDrain=5.0)
            warmup.update(startedAt=(start-timedelta(seconds=5)).isoformat(),endedAt=start.isoformat())
            put(directory/case_id/'warmup/warm.json',warmup)
            database=10 if mode=='off' else (2 if variant=='baseline' else 1)
            counts={'inventory_catalog_database_reads_total':database,'inventory_cache_hits_total':0 if mode=='off' else 8,
                'inventory_cache_misses_total':0 if mode=='off' else 2,'inventory_cache_bypasses_total':10 if mode=='off' else 0,'inventory_cache_errors_total':0,
                'inventory_catalog_lookup_seconds_count{source="database"}':database,
                'inventory_catalog_lookup_seconds_count{source="redis"}':0 if mode=='off' else 8}
            if variant=='optimized':counts['inventory_catalog_lookup_seconds_count{source="coalesced"}']=1
            case={'caseDirectory':case_id,'mode':mode,'rate':1,'repetition':repeat,'summary':summary,'report':report,'requestSequenceSha256':'same-sequence',
                'container':{'imageId':'sha256:'+variant,'memoryBytes':1000,'nanoCpus':1000000000,'cacheEnvironment':{'INVENTORY_CACHE_ENABLED':'false' if mode=='off' else 'true','INVENTORY_CACHE_TTL_SECONDS':'30'}},
                'metrics':{'counterAndTimerDeltas':counts,'timerMeanMilliseconds':{}}}
            put(directory/case_id/'case.json',case)
            metadata['completedCases'].append(case)
            metadata['schedule'].append({key:case[key] for key in ('mode','rate','repetition')})
    put(directory/'experiment.json',metadata)


class CacheStudyEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp=TemporaryDirectory();self.root=Path(self.temp.name)
        fixture(self.root/'baseline','baseline',['off','warm'],10)
        fixture(self.root/'optimized','optimized',['warm'],200)
    def tearDown(self):self.temp.cleanup()
    def load(self):return build_study(load_experiment(self.root/'baseline','baseline'),load_experiment(self.root/'optimized','optimized'))
    def mutate_case(self,variant,index,mutate):
        directory=self.root/variant;metadata=json.loads((directory/'experiment.json').read_text());case=metadata['completedCases'][index]
        mutate(case);put(directory/case['caseDirectory']/'case.json',case);put(directory/'experiment.json',metadata)

    def test_all_cases_and_bad_warmups_remain_and_slowdown_is_not_hidden(self):
        value=self.load()
        self.assertEqual(9,len(value['allMeasuredCases']))
        self.assertEqual(9,value['warmup']['droppedRequests'])
        self.assertEqual(0,sum(case['summary']['droppedRequests'] for case in value['allMeasuredCases']))
        self.assertLess(value['comparisons'][0]['incrementalOptimization']['weightedMeanReductionPercent'],0)
        group=next(group for group in value['groups'] if group['variant']=='baseline' and group['mode']=='warm')
        self.assertEqual(5,group['medianOfRunP95Ms'])
        self.assertEqual([1,100],group['runP95MsRange'])
        self.assertNotIn('pooledP95Ms',group)
        self.assertIn('预热总计失败 0、丢弃 9',markdown(value))

    def test_measurement_failures_are_retained_and_disable_healthy_claim(self):
        directory=self.root/'baseline';metadata=json.loads((directory/'experiment.json').read_text())
        case=metadata['completedCases'][1]
        case['summary'].update(successfulRequests=9,failedRequests=1)
        put(directory/case['caseDirectory']/'case.json',case)
        report=directory/case['report'];measurement=json.loads(report.read_text());measurement['summary']=case['summary'];put(report,measurement)
        put(directory/'experiment.json',metadata)
        value=self.load()
        group=next(group for group in value['groups'] if group['variant']=='baseline' and group['mode']=='warm')
        self.assertEqual(1,group['failedRequests'])
        self.assertFalse(value['comparisons'][0]['incrementalOptimization']['eligibleForHealthyLatencyComparison'])

    def test_running_or_missing_scheduled_cases_are_rejected(self):
        path=self.root/'baseline/experiment.json';value=json.loads(path.read_text());value['status']='RUNNING';put(path,value)
        with self.assertRaisesRegex(EvidenceError,'not COMPLETE'):self.load()
        value['status']='COMPLETE';value['completedCases'].pop();put(path,value)
        with self.assertRaisesRegex(EvidenceError,'case count'):self.load()

    def test_unlisted_case_cannot_be_silently_ignored(self):
        put(self.root/'baseline/99-unlisted/case.json',{})
        with self.assertRaisesRegex(EvidenceError,'selective'):self.load()

    def test_mismatched_ttl_is_rejected(self):
        self.mutate_case('optimized',0,lambda case:case['container']['cacheEnvironment'].update(INVENTORY_CACHE_TTL_SECONDS='300'))
        with self.assertRaisesRegex(EvidenceError,'TTL differs'):self.load()

    def test_wrong_cache_mode_and_changed_image_are_rejected(self):
        self.mutate_case('baseline',0,lambda case:case['container']['cacheEnvironment'].update(INVENTORY_CACHE_ENABLED='true'))
        with self.assertRaisesRegex(EvidenceError,'mode flag'):self.load()
        self.mutate_case('baseline',0,lambda case:case['container']['cacheEnvironment'].update(INVENTORY_CACHE_ENABLED='false'))
        self.mutate_case('optimized',1,lambda case:case['container'].update(imageId='sha256:replacement'))
        with self.assertRaisesRegex(EvidenceError,'image changed'):self.load()

    def test_summary_cannot_disagree_with_recorded_measurement(self):
        self.mutate_case('baseline',0,lambda case:case['summary'].update(meanMs=0.001))
        with self.assertRaisesRegex(EvidenceError,'summaries differ'):self.load()

    def test_incorrect_sequence_across_variants_is_rejected(self):
        directory=self.root/'optimized';metadata=json.loads((directory/'experiment.json').read_text())
        for case in metadata['completedCases']:
            case['requestSequenceSha256']='different';put(directory/case['caseDirectory']/'case.json',case)
            report=directory/case['report'];measurement=json.loads(report.read_text());measurement['requestSequenceSha256']='different';put(report,measurement)
        put(directory/'experiment.json',metadata)
        with self.assertRaisesRegex(EvidenceError,'sequence'):self.load()


if __name__=='__main__':unittest.main()
