#!/usr/bin/env python3
"""Exercise request boundaries against the running Spring/Jackson HTTP stack."""
from datetime import datetime,timezone
from uuid import uuid4
import urllib.error
import urllib.request
from api_client import Client,api_key,save_report


def raw(path,body,expected,content_type='application/json',method='POST'):
    request=urllib.request.Request('http://127.0.0.1:8080'+path,data=body,method=method,
        headers={'X-API-Key':api_key(),'Content-Type':content_type,'Idempotency-Key':'boundary-'+uuid4().hex})
    try:
        with urllib.request.urlopen(request,timeout=10) as response:status=response.status
    except urllib.error.HTTPError as error:status=error.code
    assert status==expected,(path,status,expected)


def main():
    client=Client('http://127.0.0.1:8080');client.wait_ready()
    body={'items':[{'sku':'BOUNDARY','quantity':1}],'paymentToken':'tok_approved'}
    client.request('POST','/v1/orders',body,expected=(400,))
    raw('/v1/orders',b'{"items":[{"sku":"X","quantity":1.5}],"paymentToken":"tok_approved"}',400)
    raw('/v1/orders',b'{"items":[{"sku":"X","quantity":"1"}],"paymentToken":"tok_approved"}',400)
    raw('/v1/orders',b'{"items":[{"sku":"X","quantity":1}],"paymentToken":"tok_approved","price":1}',400)
    raw('/v1/orders',b'{"items":[{"sku":"X","quantity":1,"quantity":2}],"paymentToken":"tok_approved"}',400)
    raw('/v1/orders',b' '*65537,413)
    raw('/v1/orders',b'hello',415,'text/plain')
    raw('/v1/orders',b'{}',405,method='DELETE')
    raw('/v1/does-not-exist',None,404,method='GET')
    path=save_report('api-contract-'+uuid4().hex[:12]+'.json',{'timestamp':datetime.now(timezone.utc).isoformat(),'passed':True,'checks':9,
        'boundaries':['missing idempotency header','fractional integer','string integer','unknown property','duplicate JSON key','oversized body','unsupported content type','unsupported method','missing resource']})
    print(f'PASS: 9 HTTP boundary checks. Evidence: {path}')


if __name__=='__main__':main()
