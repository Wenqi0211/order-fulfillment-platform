#!/usr/bin/env python3
"""Real local cache profiling: warm path, simultaneous expiry misses, Redis outage.
No artificial database delays. Stops/restarts only this project's local Redis.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
from uuid import uuid4
from api_client import Client,ROOT,api_key
import urllib.request


def docker(*args,input=None,capture=True):
    return subprocess.run([str(ROOT/'scripts/docker-local.sh'),*args],cwd=ROOT,input=input,text=True,
        check=True,stdout=subprocess.PIPE if capture else subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=90).stdout


def metrics():
    req=urllib.request.Request('http://127.0.0.1:8081/actuator/prometheus',headers={'X-API-Key':api_key()})
    with urllib.request.urlopen(req,timeout=5) as r:return r.read().decode()


def samples(raw):
    result={}
    for line in raw.splitlines():
        if line and not line.startswith('#'):
            try:k,v=line.rsplit(None,1);result[k]=float(v)
            except ValueError:pass
    return result


def delta(before,after):
    a,b=samples(before),samples(after)
    return {k:b[k]-a.get(k,0) for k in sorted(b) if k.startswith('inventory_') and ('_total' in k or '_count' in k or '_sum' in k)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label',required=True);p.add_argument('--seconds',type=int,default=15)
    p.add_argument('--rate',type=int,default=500);p.add_argument('--outage-rate',type=int,default=50)
    p.add_argument('--burst-concurrency',type=int,default=64);p.add_argument('--bursts',type=int,default=3)
    p.add_argument('--warmup-seconds',type=int,default=15)
    args=p.parse_args()
    if not 1<=args.seconds<=120 or not 1<=args.burst_concurrency<=128 or not 1<=args.bursts<=10: p.error('bounded diagnostic settings required')
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ');out=ROOT/'reports'/'cache-diagnostics'/(stamp+'-'+args.label)
    out.mkdir(parents=True)
    c=Client('http://127.0.0.1:8081');c.wait_ready()
    run=uuid4().hex[:10];skus=[];expected={}
    for i in range(100):
        sku=f'DIAG_{run}_{i}';item={'sku':sku,'name':f'Diagnostic item {i}','unitPriceMinor':1234+i,'currency':'USD','initialStock':10}
        c.request('POST','/v1/items',item,expected=(201,));skus.append(sku);expected[sku]={k:item[k] for k in ['name','unitPriceMinor','currency']}
    dataset=out/'dataset.json';dataset.write_text(json.dumps({'skus':skus,'sha256':hashlib.sha256(json.dumps(skus,separators=(',',':')).encode()).hexdigest(),'items':expected}))
    identity=docker('inspect','--format','{{json .Image}} {{json .HostConfig.Memory}} {{json .HostConfig.NanoCpus}}',docker('compose','ps','-q','inventory-service').strip())
    (out/'container-resources.txt').write_text(identity)
    def load(name,rate,seconds,preload):
        command=[sys.executable,'scripts/load_test.py','--dataset',str(dataset),'--mode','warm','--rate',str(rate),'--seconds',str(seconds),
            '--concurrency','64','--output-dir',str(out/name)]
        if not preload:command+=['--no-preload']
        subprocess.run(command,cwd=ROOT,check=True,stdout=subprocess.DEVNULL,timeout=180)
    load('warmup',args.rate,args.warmup_seconds,True)
    before=metrics();load('healthy',args.rate,args.seconds,False);after=metrics()
    (out/'healthy-metrics-before.txt').write_text(before);(out/'healthy-metrics-after.txt').write_text(after)
    healthy=delta(before,after);bursts=[]
    for i in range(args.bursts):
        sku=skus[0]
        docker('compose','exec','-T','redis','sh','-c','export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli DEL "$1"','sh','catalog:v1:'+sku)
        before=metrics();barrier=threading.Barrier(args.burst_concurrency)
        def issue(index):
            client=Client('http://127.0.0.1:8081');barrier.wait(timeout=20);start=time.monotonic()
            try:
                code,item=client.request('GET','/v1/items/'+sku)
                return {'index':index,'status':code,'correct':all(item.get(k)==v for k,v in expected[sku].items()),'elapsedMs':(time.monotonic()-start)*1000}
            except Exception as error:return {'index':index,'status':None,'correct':False,'error':type(error).__name__,'elapsedMs':(time.monotonic()-start)*1000}
        with ThreadPoolExecutor(max_workers=args.burst_concurrency) as pool:rows=list(pool.map(issue,range(args.burst_concurrency)))
        after=metrics();d=delta(before,after)
        (out/f'burst-{i+1}-raw.json').write_text(json.dumps(rows,indent=2));(out/f'burst-{i+1}-metrics-before.txt').write_text(before);(out/f'burst-{i+1}-metrics-after.txt').write_text(after)
        bursts.append({'repeat':i+1,'concurrency':args.burst_concurrency,'successful':sum(r['correct'] for r in rows),'metricDelta':d})
    try:
        docker('compose','stop','-t','3','redis')
        before=metrics();load('redis-outage',args.outage_rate,args.seconds,False);after=metrics()
        (out/'outage-metrics-before.txt').write_text(before);(out/'outage-metrics-after.txt').write_text(after)
        outage=delta(before,after)
    finally:
        docker('compose','start','redis')
    # Redis reconnects asynchronously; await an actual cache hit, not merely HTTP fallback success.
    deadline=time.monotonic()+45;recovered=False
    while time.monotonic()<deadline:
        before_hits=sum(v for k,v in samples(metrics()).items() if k.startswith('inventory_cache_hits_total'))
        c.request('GET','/v1/items/'+skus[0]);c.request('GET','/v1/items/'+skus[0])
        after_hits=sum(v for k,v in samples(metrics()).items() if k.startswith('inventory_cache_hits_total'))
        if after_hits>before_hits:recovered=True;break
        time.sleep(0.3)
    result={'timestamp':datetime.now(timezone.utc).isoformat(),'label':args.label,'arguments':vars(args),'dataset':str(dataset),'container':identity.strip(),
        'healthyDelta':healthy,'bursts':bursts,'outageDelta':outage,'redisRecoveredToActualHits':recovered,'scope':'Local Docker; no artificial delay; no production capacity claim'}
    (out/'summary.json').write_text(json.dumps(result,indent=2)+'\n')
    assert recovered,'Redis did not recover to actual cache hits'
    print('Cache diagnostics complete: '+str(out),flush=True)


if __name__=='__main__':main()
