# Open the Project, Run It, and Place Your First Order

This project provides backend APIs. Use IntelliJ to read the Java code and a terminal to run the step-by-step demonstration. Opening a service's root URL in a browser does not show a storefront; health-check URLs show service status.

## 1. Open the code in IntelliJ

After cloning from GitHub, choose **File → Open** and open the entire cloned `order-fulfillment-platform` directory. To obtain the code for the first time, run in a terminal:

```bash
git clone https://github.com/Wenqi0211/order-fulfillment-platform.git
cd order-fulfillment-platform
```

This is a private repository, so authenticate Git with a GitHub account that has access. The original workspace is at the following path; open it directly only when using the original computer:

```text
/Users/wenqi/Documents/Codex/2026-10-17/zhe/order-fulfillment-platform
```

If prompted to select a project, load the root `pom.xml` as a Maven project. Wait for Maven synchronization to finish. You should see four modules on the left: `common`, `inventory-service`, `payment-service`, and `order-service`.

Choose **JDK 17** as the project SDK. On another computer, select your own JDK 17 installation directory. The installation path on the original computer is:

```text
/Users/wenqi/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home
```

If it is missing from the JDK list, use Add JDK from Disk in the project SDK selector. If IDEA reports that the Maven wrapper is not configured, select Bundled (Maven 3) for Maven home path in Maven settings, then choose Reload All Maven Projects. Start the services with Docker and use IDEA to read the code; running Application classes at the same time would use the same 8080/8081/8082 ports.

## 2. Start all services

Open Docker Desktop and confirm Engine running. Open macOS Terminal or the Terminal at the bottom of IntelliJ, then run the commands one at a time. The `cd` below assumes you are in the parent of the cloned directory; skip this `cd` if the terminal is already at the project root. For the original workspace, you can also go directly to the original path listed above:

```bash
cd order-fulfillment-platform
python3 scripts/init_env.py
./scripts/docker-local.sh compose up -d --build --wait --wait-timeout 180
./scripts/docker-local.sh compose ps
```

The initialization script does not overwrite existing keys. Images have already been built in the original workspace; the first clone on another computer requires downloads and a build. Continue when the three applications, PostgreSQL, Kafka, and Redis are all `healthy`. `kafka-init` is an initialization task, so `Exited (0)` is normal.

Open in a browser:

```text
http://localhost:8080/actuator/health/readiness
```

`{"status":"UP"}` means the order API is ready. The same path on ports 8081 and 8082 checks inventory and payment, respectively.

## 3. Press Enter to place an order step by step

In the same terminal, run:

```bash
python3 scripts/walkthrough.py
```

It shows the actual request URL, JSON, HTTP status code, and response body. Each stage waits for you to press Enter before continuing. The key is read automatically from the local `.env`; you do not need to copy or display it.

| Step | What you will see | Expected result |
| --- | --- | --- |
| 1. Create a product | POST to inventory, then GET stock | Unit price 1999 cents, stock 20, reserved 0 |
| 2. Order two units | POST to orders; RESERVE → PAYMENT → COMMIT runs in the background | Total 3998 cents, final status CONFIRMED, stock 18, reserved 0 |
| 3. Repeat the request | Resend the same Idempotency-Key, then change the quantity | The same request returns the original order; the changed request returns 409; stock remains 18 |
| 4. Update warehouse status | Send PACKED, SHIPPED, and DELIVERED in sequence | Each returns 202 first; after actual Kafka consumption, queries show the corresponding status |
| 5. Decline payment | Use the simulated-decline token for a new order | CANCELLED; reservation released; stock remains 18 |
| 6. Insufficient stock | Order 100 units in a new order | CANCELLED; stock remains 18 |

An initial 201 response means the order has been saved and may initially show PENDING. The script waits for the background workflow and queries the final status. A warehouse 202 response means the event has been persisted and will take effect asynchronously. Payment is simulated locally and does not create a real charge.

At the end, it should display “Demo passed.” along with the SKU, successful order ID, and JSON record path for this run. Each run creates a separate set of demonstration data. Ctrl+C exits the demonstration; saved orders may continue processing in the background.

To run all steps without pausing:

```bash
python3 scripts/walkthrough.py --auto
```

## 4. Observe background processing

Open another terminal and enter the same directory, then run:

```bash
./scripts/docker-local.sh compose logs -f order-service inventory-service payment-service
```

Keep this terminal open, then press Enter in the demonstration terminal. Logs include request correlation IDs. Ctrl+C ends log streaming without stopping the containers.

Suggested code-reading order:

1. `OrderController` in `order-service`: HTTP request entry point.
2. `OrderService`: validation, quotes, idempotency, and the initial order transaction.
3. `SagaProcessor`: reserve → pay → commit, including retries and compensation after failure.
4. `InventoryService` in `inventory-service`: ordered locks, atomic multi-SKU reservation, commit, and release.
5. `PaymentService` in `payment-service`: persists the payment intent first, handles unknown outcomes, and replays terminal results.
6. `Events` and `OutboxRelay` in `common`: save business data and messages in the same transaction, then mark messages sent after Kafka acknowledges them.
7. `WarehouseListener` and `WarehouseService`: consume events, deduplicate, and prevent warehouse status regression.

## 5. Run tests

First, run the automated end-to-end flow:

```bash
python3 scripts/smoke.py
```

`PASS: 6 system checks` indicates that the flow passed.

Then run all Java unit tests and integration tests against real PostgreSQL:

```bash
python3 scripts/java_integration.py clean
```

Look for `BUILD SUCCESS`. This version previously passed 32 unit tests and 44 database integration tests; reports are saved under each module's `target` directory.

Run the deeper checks separately:

```bash
python3 scripts/api_contract_smoke.py
python3 scripts/fault_smoke.py
python3 scripts/dlt_smoke.py
```

The second command temporarily stops and restores this project's Redis, payment service, and Kafka. Wait for it to finish and for services to recover before running the next command; do not run load tests at the same time. The DLT check writes a dedicated invalid message to local Kafka and reads it back from the dead-letter topic. See [LOCAL_RESULTS](LOCAL_RESULTS.md) for the historical verification details.

## 6. Stop and restart later

```bash
# Stop services and retain the databases
./scripts/docker-local.sh compose down
# Start again next time
./scripts/docker-local.sh compose up -d --wait --wait-timeout 180
```

After changing Java code, add `--build` to rebuild the images before starting. Avoid `down -v`, which deletes persistent local database, Kafka, and Redis data.

## Next steps for cloud deployment and caching

Cloud deployment is achievable. The existing Terraform creates an EC2 instance and network/SSM resources; it does not yet configure runnable databases, Kafka, Redis, or the release environment. A reasonable sequence is to assess the account and budget, run all services and the same tests on EC2, then complete Kubernetes deployment, actual GitHub Actions runs, and failure rollback validation. Creating configuration files and completing an actual deployment are different completion criteria. Cloud resources incur charges, so confirm the budget before creating them. Start with [official AWS EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/).

Redis caching is implemented; consistent speed improvements still require optimization and retesting against actual bottlenecks. The previous test used 500 SKUs, a local database, 100 requests/second, and 10 seconds per round. The product-query endpoint already averaged approximately 1–2ms, and this environment did not show consistent latency benefits. This explanation is an inference from the current results and has not yet been validated through bottleneck analysis.

Next, warm up the JVM separately in both modes, alternate the cache on/off order, extend each round to 60–120 seconds, and test larger datasets and multiple arrival rates. Monitor cache hits, database reads/CPU/connection pools, HTTP p95/p99, errors, and dropped requests. Identify the actual bottleneck before optimizing. The cache serves only product display; purchase quotes and inventory reservations continue to use the database as the authority. The eventual result may demonstrate fewer database reads or better latency or capacity at a particular load, but no improvement percentage can be promised in advance. Include application networking and serialization overhead in measurements; see the [official Redis monitoring documentation](https://redis.io/docs/latest/operate/rs/monitoring/observability/).
