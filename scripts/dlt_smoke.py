#!/usr/bin/env python3
"""Prove a malformed warehouse record reaches the real Kafka dead-letter topic."""
from datetime import datetime,timezone
import json
import subprocess
import time
from uuid import uuid4
from api_client import Client,ROOT,save_report


def kafka(tool,*args,input=None,timeout=30):
    return subprocess.run([str(ROOT/'scripts/docker-local.sh'),'compose','exec','-T','kafka',
        '/opt/kafka/bin/'+tool,'--bootstrap-server','kafka:29092',*args],cwd=ROOT,input=input,
        capture_output=True,text=True,timeout=timeout)


def main():
    client=Client('http://127.0.0.1:8080');client.wait_ready()
    _,before=client.request('GET','/v1/operations/backlog')
    marker='dlt-smoke-'+uuid4().hex
    raw=json.dumps({'invalidEnvelope':marker},separators=(',',':'))
    produced=kafka('kafka-console-producer.sh','--topic','warehouse.status.v1',input=raw+'\n')
    assert produced.returncode==0,'Failed to produce local synthetic record'
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        _,state=client.request('GET','/v1/operations/backlog')
        if state['deadLetteredMessages']>before['deadLetteredMessages']:break
        time.sleep(0.2)
    assert state['deadLetteredMessages']>before['deadLetteredMessages'],'No durable dead-letter acknowledgement observed'
    consumed=kafka('kafka-console-consumer.sh','--topic','warehouse.status.v1.DLT',
        '--from-beginning','--timeout-ms','5000','--max-messages','100',timeout=20)
    assert raw in consumed.stdout,'Synthetic raw payload missing from actual DLT'
    path=save_report('dlt-smoke-'+uuid4().hex[:12]+'.json',{'timestamp':datetime.now(timezone.utc).isoformat(),
        'passed':True,'marker':marker,'sourceTopic':'warehouse.status.v1','deadLetterTopic':'warehouse.status.v1.DLT',
        'checks':['Malformed source record quarantined','DLT broker acknowledgement recorded durably','Original payload read back from actual DLT'],
        'paymentProvider':'simulated; no charge'})
    print(f'PASS: 3 actual Kafka DLT checks. Evidence: {path}')


if __name__=='__main__':main()
