#!/usr/bin/env python3
"""Read-only release verification; safe for the configured production environment."""
import argparse
import urllib.request
from api_client import Client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--order-url', required=True)
    parser.add_argument('--inventory-url', required=True)
    parser.add_argument('--payment-url', required=True)
    args = parser.parse_args()
    clients = [Client(url) for url in (args.order_url, args.inventory_url, args.payment_url)]
    for client in clients:
        client.wait_ready(60)
        request = urllib.request.Request(
            client.base_url + '/actuator/prometheus', headers={'X-API-Key': client.key})
        with urllib.request.urlopen(request, timeout=5) as response:
            content_type = response.headers.get('Content-Type', '')
            assert response.status == 200 and any(value in content_type
                for value in ('text/plain', 'application/openmetrics-text')), content_type
    clients[0].request('GET', '/v1/orders?limit=1')
    print('PASS: all service readiness and authenticated metrics; read-only order listing')


if __name__ == '__main__':
    main()
