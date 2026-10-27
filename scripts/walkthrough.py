#!/usr/bin/env python3
"""Interactive local HTTP/Kafka order walkthrough; Enter advances one explained step."""
import argparse
from datetime import datetime, timezone
import json
import sys
from uuid import uuid4
from api_client import Client, save_report
from smoke import await_status


def show(title, value):
    print('\n'+title, flush=True)
    print(json.dumps(value,ensure_ascii=False,indent=2),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--auto',action='store_true',help='Run all steps without waiting for Enter')
    args=parser.parse_args()
    if not args.auto and not sys.stdin.isatty():
        parser.error('Interactive mode needs a terminal; use --auto for non-interactive verification.')
    order=Client('http://127.0.0.1:8080');inventory=Client('http://127.0.0.1:8081');payment=Client('http://127.0.0.1:8082')
    for name,client in [('Order',order),('Inventory',inventory),('Payment',payment)]:
        client.wait_ready(seconds=15)
        print(name+' service is ready',flush=True)
    print('\nThis demo only accesses services on this computer; payments are simulated and no real charges are made. The API key is read privately from .env.',flush=True)
    run=uuid4().hex[:12];sku='LEARN_'+run;checks=[];ids=[]

    def pause(label,explanation):
        print('\n'+'='*64+'\n'+label+'\n'+explanation,flush=True)
        if not args.auto:input('Press Enter to run this step (Ctrl+C to exit): ')

    def call(client,method,path,body=None,headers=None,expected=(200,)):
        print('\n→ '+method+' '+client.base_url+path,flush=True)
        if headers and 'Idempotency-Key' in headers:print('Idempotency-Key: '+headers['Idempotency-Key'],flush=True)
        if body is not None:show('Request JSON',body)
        status,result=client.request(method,path,body,headers,expected)
        show('← HTTP '+str(status),result)
        return result

    def wait(order_id,status):
        print('\nWaiting for the background workflow / Kafka to update the status to '+status+'…',flush=True)
        result=await_status(order,order_id,status,60)
        show('Confirmed by querying',result)
        return result

    pause('Step 1: Create an item and check its initial stock','The item costs 1999 cents ($19.99), with initial stock of 20. Each demo uses a new SKU and preserves existing items.')
    call(inventory,'POST','/v1/items',{'sku':sku,'name':'Learning Demo Item','unitPriceMinor':1999,'currency':'USD','initialStock':20},expected=(201,))
    before=call(inventory,'GET','/v1/stock/'+sku)
    assert before['onHand']==20 and before['reserved']==0
    checks.append('created item and verified initial stock')

    pause('Step 2: Submit an order for two items','The order service gets a quote from the database, then saves the order. HTTP 201 means it was created; the background workflow then reserves stock, simulates payment, and commits stock in sequence.')
    body={'items':[{'sku':sku,'quantity':2}],'paymentToken':'tok_approved'}
    headers={'Idempotency-Key':'learn-'+run}
    created=call(order,'POST','/v1/orders',body,headers,expected=(201,));ids.append(created['id'])
    confirmed=wait(created['id'],'CONFIRMED')
    assert confirmed['totalMinor']==3998
    print('\nThe total should be 3998 cents ($39.98); CONFIRMED means payment succeeded and stock was committed.',flush=True)
    print('\nNext, replay the same payment request to read the saved result without calling the payment provider again.',flush=True)
    paid=call(payment,'PUT','/internal/payments/'+created['id'],{'amountMinor':3998,'currency':'USD','paymentToken':'tok_approved'})
    assert paid['status']=='APPROVED'
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0 and stock['available']==18
    checks.append('confirmed order, authoritative total, idempotent simulated payment and stock18')

    pause('Step 3: Repeat the request to verify idempotency','The same request with the same Idempotency-Key should return the same order; changing the quantity while reusing that key should return HTTP 409.')
    replay=call(order,'POST','/v1/orders',body,headers)
    assert replay['id']==created['id']
    call(order,'POST','/v1/orders',{**body,'items':[{'sku':sku,'quantity':3}]},headers,expected=(409,))
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0
    checks.append('same request replayed same order; changed payload409; no repeated stock consumption')

    pause('Step 4: Simulate warehouse packing, shipping, and delivery','Each warehouse request first returns HTTP 202. The event is saved to the outbox and consumed through Kafka, after which a query returns the corresponding order status.')
    for sequence,status in enumerate(['PACKED','SHIPPED','DELIVERED'],start=1):
        call(order,'POST','/v1/warehouse/events',{'eventId':str(uuid4()),'orderId':created['id'],'sequence':sequence,'status':status},expected=(202,))
        wait(created['id'],status)
    checks.append('actual Kafka applied packed, shipped and delivered')

    pause('Step 5: Simulate a declined payment to verify compensation','Create another order using tok_declined. The system should release the stock reservation and cancel the order; available stock should remain 18.')
    declined=call(order,'POST','/v1/orders',{'items':[{'sku':sku,'quantity':1}],'paymentToken':'tok_declined'},
        {'Idempotency-Key':'decline-'+run},expected=(201,));ids.append(declined['id'])
    wait(declined['id'],'CANCELLED')
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0 and stock['available']==18
    checks.append('declined payment cancelled and reservation released')

    pause('Step 6: Request more than the available stock','Try to buy 100 items, exceeding the 18 currently available. The workflow should cancel the order and leave stock at 18.')
    shortage=call(order,'POST','/v1/orders',{'items':[{'sku':sku,'quantity':100}],'paymentToken':'tok_approved'},
        {'Idempotency-Key':'shortage-'+run},expected=(201,));ids.append(shortage['id'])
    wait(shortage['id'],'CANCELLED')
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0
    checks.append('insufficient stock cancelled without consuming inventory')
    path=save_report('walkthrough-'+run+'.json',{'timestamp':datetime.now(timezone.utc).isoformat(),'passed':True,'sku':sku,'orderIds':ids,
        'checks':checks,'paymentProvider':'simulated; no real charges','transport':'actual HTTP, PostgreSQL and Kafka'})
    print('\nDemo passed. SKU for this run: '+sku+'\nSuccessful order: '+created['id']+'\nResults: '+str(path),flush=True)
    print('Orders and items remain in the local database; running again creates a new set of demo data.',flush=True)


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:
        print('\nDemo exited. Orders already created may continue processing in the background; the database will not be cleared.',file=sys.stderr)
        raise SystemExit(130)
    except (AssertionError,OSError) as error:
        print('\nDemo failed: '+str(error)+'\nFirst confirm Docker is running, then check compose ps and the application logs.',file=sys.stderr)
        raise SystemExit(1)
