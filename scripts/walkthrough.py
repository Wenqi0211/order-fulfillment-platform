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
    for name,client in [('订单',order),('库存',inventory),('支付',payment)]:
        client.wait_ready(seconds=15)
        print(name+'服务已就绪',flush=True)
    print('\n本演示只访问这台电脑的服务；支付为模拟，不会真实扣款。API 密钥从 .env 私下读取。',flush=True)
    run=uuid4().hex[:12];sku='LEARN_'+run;checks=[];ids=[]

    def pause(label,explanation):
        print('\n'+'='*64+'\n'+label+'\n'+explanation,flush=True)
        if not args.auto:input('按 Enter 执行这一步（Ctrl+C 可退出）：')

    def call(client,method,path,body=None,headers=None,expected=(200,)):
        print('\n→ '+method+' '+client.base_url+path,flush=True)
        if headers and 'Idempotency-Key' in headers:print('Idempotency-Key: '+headers['Idempotency-Key'],flush=True)
        if body is not None:show('请求 JSON',body)
        status,result=client.request(method,path,body,headers,expected)
        show('← HTTP '+str(status),result)
        return result

    def wait(order_id,status):
        print('\n等待后台工作流 / Kafka 将状态更新为 '+status+'…',flush=True)
        result=await_status(order,order_id,status,60)
        show('已查询确认',result)
        return result

    pause('第 1 步：新建商品，查看起始库存','商品单价 1999 美分（$19.99），起始库存 20。每次演示使用新的 SKU，不覆盖旧商品。')
    call(inventory,'POST','/v1/items',{'sku':sku,'name':'Learning Demo Item','unitPriceMinor':1999,'currency':'USD','initialStock':20},expected=(201,))
    before=call(inventory,'GET','/v1/stock/'+sku)
    assert before['onHand']==20 and before['reserved']==0
    checks.append('created item and verified initial stock')

    pause('第 2 步：提交购买两件的订单','订单服务先取得数据库报价，再保存订单。HTTP 201 表示已创建；后台依次预留库存、模拟支付和提交库存。')
    body={'items':[{'sku':sku,'quantity':2}],'paymentToken':'tok_approved'}
    headers={'Idempotency-Key':'learn-'+run}
    created=call(order,'POST','/v1/orders',body,headers,expected=(201,));ids.append(created['id'])
    confirmed=wait(created['id'],'CONFIRMED')
    assert confirmed['totalMinor']==3998
    print('\n总额应为 3998 美分（$39.98）；CONFIRMED 表示支付成功且库存已提交。',flush=True)
    print('\n下面重放相同支付请求以读取已保存的结果，不会再次执行提供方支付。',flush=True)
    paid=call(payment,'PUT','/internal/payments/'+created['id'],{'amountMinor':3998,'currency':'USD','paymentToken':'tok_approved'})
    assert paid['status']=='APPROVED'
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0 and stock['available']==18
    checks.append('confirmed order, authoritative total, idempotent simulated payment and stock18')

    pause('第 3 步：重复请求，验证幂等','相同请求、相同 Idempotency-Key 应返回同一个订单；改数量仍复用该 key，应收到 HTTP 409。')
    replay=call(order,'POST','/v1/orders',body,headers)
    assert replay['id']==created['id']
    call(order,'POST','/v1/orders',{**body,'items':[{'sku':sku,'quantity':3}]},headers,expected=(409,))
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0
    checks.append('same request replayed same order; changed payload409; no repeated stock consumption')

    pause('第 4 步：模拟仓库打包、发货、送达','每个仓储请求先返回 HTTP 202，事件保存到 outbox 后经 Kafka 消费，再查询到对应订单状态。')
    for sequence,status in enumerate(['PACKED','SHIPPED','DELIVERED'],start=1):
        call(order,'POST','/v1/warehouse/events',{'eventId':str(uuid4()),'orderId':created['id'],'sequence':sequence,'status':status},expected=(202,))
        wait(created['id'],status)
    checks.append('actual Kafka applied packed, shipped and delivered')

    pause('第 5 步：模拟支付拒绝，验证补偿','另建一个订单，使用 tok_declined。系统应释放预留库存并取消订单；可用库存仍是 18。')
    declined=call(order,'POST','/v1/orders',{'items':[{'sku':sku,'quantity':1}],'paymentToken':'tok_declined'},
        {'Idempotency-Key':'decline-'+run},expected=(201,));ids.append(declined['id'])
    wait(declined['id'],'CANCELLED')
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0 and stock['available']==18
    checks.append('declined payment cancelled and reservation released')

    pause('第 6 步：请求超过现有库存','再购买 100 件，超过当前可用的 18 件。工作流应取消订单，库存保持 18。')
    shortage=call(order,'POST','/v1/orders',{'items':[{'sku':sku,'quantity':100}],'paymentToken':'tok_approved'},
        {'Idempotency-Key':'shortage-'+run},expected=(201,));ids.append(shortage['id'])
    wait(shortage['id'],'CANCELLED')
    stock=call(inventory,'GET','/v1/stock/'+sku)
    assert stock['onHand']==18 and stock['reserved']==0
    checks.append('insufficient stock cancelled without consuming inventory')
    path=save_report('walkthrough-'+run+'.json',{'timestamp':datetime.now(timezone.utc).isoformat(),'passed':True,'sku':sku,'orderIds':ids,
        'checks':checks,'paymentProvider':'simulated; no real charges','transport':'actual HTTP, PostgreSQL and Kafka'})
    print('\n演示通过。此次 SKU：'+sku+'\n成功订单：'+created['id']+'\n结果记录：'+str(path),flush=True)
    print('订单和商品保留在本地数据库中；再次运行会创建一组新的演示数据。',flush=True)


if __name__=='__main__':
    try:main()
    except KeyboardInterrupt:
        print('\n演示已退出。已创建的订单可能继续由后台处理；不会清空数据库。',file=sys.stderr)
        raise SystemExit(130)
    except (AssertionError,OSError) as error:
        print('\n演示未通过：'+str(error)+'\n请先确认 Docker 已启动，再查看 compose ps 和应用日志。',file=sys.stderr)
        raise SystemExit(1)
