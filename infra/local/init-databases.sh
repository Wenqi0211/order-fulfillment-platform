#!/usr/bin/env bash
set -euo pipefail
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=inventory_password="$INVENTORY_DB_PASSWORD" \
  --set=payment_password="$PAYMENT_DB_PASSWORD" \
  --set=order_password="$ORDER_DB_PASSWORD" <<'SQL'
CREATE ROLE inventory_app LOGIN PASSWORD :'inventory_password';
CREATE ROLE payment_app LOGIN PASSWORD :'payment_password';
CREATE ROLE order_app LOGIN PASSWORD :'order_password';
CREATE DATABASE inventory OWNER inventory_app;
CREATE DATABASE payment OWNER payment_app;
CREATE DATABASE orders OWNER order_app;
REVOKE ALL ON DATABASE inventory FROM PUBLIC;
REVOKE ALL ON DATABASE payment FROM PUBLIC;
REVOKE ALL ON DATABASE orders FROM PUBLIC;
SQL
