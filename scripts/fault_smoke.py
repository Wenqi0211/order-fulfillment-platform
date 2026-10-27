#!/usr/bin/env python3
"""Scoped local fault/recovery checks. Always restart the dependency stopped by this script."""
from datetime import datetime, timezone
from pathlib import Path
import subprocess
import time
from uuid import uuid4
from api_client import Client, ROOT, save_report
from smoke import await_status


def compose(*args):
    subprocess.run([str(ROOT / 'scripts/docker-local.sh'), 'compose', *args], cwd=ROOT,
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=90)


def main():
    inventory, order, payment = (Client(f'http://127.0.0.1:{p}') for p in (8081,8080,8082))
    for client in (inventory,order,payment): client.wait_ready()
    checks=[]; run=uuid4().hex[:12]; sku='FAULT_'+run
    inventory.request('POST','/v1/items',{'sku':sku,'name':'Fault recovery item','unitPriceMinor':1234,'currency':'USD','initialStock':10},expected=(201,))
    inventory.request('GET',f'/v1/items/{sku}')
    try:
        compose('stop','-t','3','redis')
        start=time.monotonic()
        _, catalog=inventory.request('GET',f'/v1/items/{sku}')
        assert catalog['unitPriceMinor']==1234
        checks.append({'check':'Redis outage falls back to authoritative database','elapsedMs':round((time.monotonic()-start)*1000,2)})
    finally:
        compose('start','redis')
    body={'items':[{'sku':sku,'quantity':2}],'paymentToken':'tok_approved'}
    headers={'Idempotency-Key':'fault-'+run}
    try:
        compose('stop','-t','3','payment-service')
        _,created=order.request('POST','/v1/orders',body,headers,expected=(201,))
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            _,current=order.request('GET',f'/v1/orders/{created["id"]}')
            if current['workflowStep']=='PAYMENT' and current['attempts']>=1:break
            time.sleep(0.2)
        assert current['status']=='PENDING' and current['workflowStep']=='PAYMENT' and current['attempts']>=1,current
        _,stock=inventory.request('GET',f'/v1/stock/{sku}')
        assert stock['reserved']==2 and stock['onHand']==10,stock
        _,replay=order.request('POST','/v1/orders',body,headers)
        assert replay['id']==created['id']
    finally:
        compose('start','payment-service')
    payment.wait_ready()
    await_status(order,created['id'],'CONFIRMED',60)
    _,stock=inventory.request('GET',f'/v1/stock/{sku}')
    assert stock['reserved']==0 and stock['onHand']==8,stock
    checks.append({'check':'Payment service outage retains durable PAYMENT step and reservation; recovery confirms exactly one order','orderId':created['id']})
    try:
        compose('stop','-t','5','kafka')
        event={'eventId':str(uuid4()),'orderId':created['id'],'sequence':1,'status':'SHIPPED'}
        order.request('POST','/v1/warehouse/events',event,expected=(202,))
        _,current=order.request('GET',f'/v1/orders/{created["id"]}')
        assert current['status']=='CONFIRMED',current
        _,backlog=order.request('GET','/v1/operations/backlog')
        assert backlog['outboxPending']>=1,backlog
    finally:
        compose('start','kafka')
    await_status(order,created['id'],'SHIPPED',90)
    checks.append({'check':'Kafka outage accepts warehouse event durably; outbox publishes and consumer applies after broker recovery','eventId':event['eventId']})
    path=save_report('fault-smoke-'+run+'.json',{'timestamp':datetime.now(timezone.utc).isoformat(),'passed':True,'checks':checks,'scope':'Only local Compose dependencies stopped/restarted; simulated payments'})
    print(f'PASS: {len(checks)} fault/recovery checks. Evidence: {path}')


if __name__=='__main__':main()
