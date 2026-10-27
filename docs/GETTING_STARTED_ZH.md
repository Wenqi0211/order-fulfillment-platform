# 第一次打开、运行、完成下单

本项目提供后端 API。你可以在 IntelliJ 看 Java 代码，在终端运行逐步演示。浏览器访问服务根地址不会出现商城页面；健康检查地址会显示服务状态。

## 1. 在 IntelliJ 打开代码

从 GitHub 克隆后，选择 **File → Open**，打开克隆得到的整个 `order-fulfillment-platform` 目录。首次获取代码可在终端执行：

```bash
git clone https://github.com/Wenqi0211/order-fulfillment-platform.git
cd order-fulfillment-platform
```

这是私有仓库，需要使用有访问权限的 GitHub 账号完成 Git 认证。原工作区保存在以下路径；只有使用这台原电脑时才直接打开它：

```text
/Users/wenqi/Documents/Codex/2026-10-17/zhe/order-fulfillment-platform
```

如果提示选择项目，按 Maven 项目加载根目录的 `pom.xml`。等待 Maven 同步完成。左侧应该能看到 `common`、`inventory-service`、`payment-service`、`order-service` 四个模块。

项目 SDK 选择 **JDK 17**。其他电脑选择自己的 JDK 17 安装目录；原电脑已经安装的路径为：

```text
/Users/wenqi/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home
```

如果 JDK 列表没有它，在项目 SDK 选择处使用 Add JDK from Disk。若 IDEA 提示 Maven wrapper 未配置，在 Maven 设置的 Maven home path 选择 Bundled (Maven 3)，再 Reload All Maven Projects。先用 Docker 运行服务、用 IDEA 阅读代码；此时再运行 Application 类会占用同样的 8080/8081/8082 端口。

## 2. 启动所有服务

打开 Docker Desktop，确认 Engine running。打开 macOS Terminal，或者 IntelliJ 底部 Terminal，逐条执行。下面的 `cd` 从克隆目录的上一级开始；如果终端已在项目根目录，跳过这条 `cd`。使用原工作区时，也可直接进入上一节列出的原路径：

```bash
cd order-fulfillment-platform
python3 scripts/init_env.py
./scripts/docker-local.sh compose up -d --build --wait --wait-timeout 180
./scripts/docker-local.sh compose ps
```

初始化脚本不会覆盖已有密钥。原工作区的镜像此前已构建；首次克隆到其他电脑会需要下载并构建。看到三个应用、PostgreSQL、Kafka 和 Redis 均为 `healthy` 后继续。`kafka-init` 是初始化任务，显示 `Exited (0)` 正常。

浏览器打开：

```text
http://localhost:8080/actuator/health/readiness
```

看到 `{"status":"UP"}` 表示订单 API 已就绪。8081、8082 的相同路径分别对应库存和支付。

## 3. 按回车逐步下单

在同一个终端执行：

```bash
python3 scripts/walkthrough.py
```

它会显示真正发出的请求地址、JSON、HTTP 返回码和返回内容；每个阶段等你按 Enter 才继续。密钥自动从本地 `.env` 读取，不需要复制或显示。

| 步骤 | 你会看到什么 | 应验证的结果 |
| --- | --- | --- |
| 1. 建商品 | POST 到库存服务，再 GET 库存 | 单价 1999 美分，库存 20，预留 0 |
| 2. 下单两件 | POST 到订单服务，后台执行 RESERVE → PAYMENT → COMMIT | 总额 3998 美分，最终 CONFIRMED，库存 18，预留 0 |
| 3. 重复请求 | 同一 Idempotency-Key 重发；再修改数量 | 同一请求返回原订单；修改请求 409；库存仍是 18 |
| 4. 仓储更新 | 依次发送 PACKED、SHIPPED、DELIVERED | 每次先 202，再经真实 Kafka 消费，查询到对应状态 |
| 5. 拒付 | 新订单使用模拟拒付 token | CANCELLED；释放预留；库存仍是 18 |
| 6. 库存不足 | 新订单购买 100 件 | CANCELLED；库存仍是 18 |

首次下单返回 201 表示订单已保存，可能先显示 PENDING；脚本会等待后台工作流并查询确认最终状态。仓储 202 表示事件已持久保存，之后异步生效。支付是本地模拟，不会真实扣款。

最终应显示“演示通过”，并给出此次 SKU、成功订单 ID 和 JSON 记录路径。每次运行创建独立的一组演示数据。Ctrl+C 可退出演示，已保存的订单可能继续由后台处理。

如要连续执行全部步骤：

```bash
python3 scripts/walkthrough.py --auto
```

## 4. 观察后台在做什么

另外打开一个终端，进入相同目录后：

```bash
./scripts/docker-local.sh compose logs -f order-service inventory-service payment-service
```

保留这个终端，再去演示终端按 Enter。日志包含请求关联 ID。Ctrl+C 结束日志跟踪，不会停止容器。

代码阅读顺序：

1. `order-service` 的 `OrderController`：HTTP 请求入口。
2. `OrderService`：校验、报价、幂等和初始订单事务。
3. `SagaProcessor`：预留 → 支付 → 提交，失败后如何重试或补偿。
4. `inventory-service` 的 `InventoryService`：排序锁、多 SKU 原子预留、提交与释放。
5. `payment-service` 的 `PaymentService`：先持久保存支付意图，处理未知结果与终态重放。
6. `common` 的 `Events`、`OutboxRelay`：业务与消息同事务保存、Kafka 确认后标记已发。
7. `WarehouseListener`、`WarehouseService`：消费、去重和防止仓储状态倒退。

## 5. 跑测试

先跑自动完整流程：

```bash
python3 scripts/smoke.py
```

看到 `PASS: 6 system checks` 表示流程通过。

再运行全部 Java 单元和真实 PostgreSQL 集成测试：

```bash
python3 scripts/java_integration.py clean
```

看到 `BUILD SUCCESS`。本版本此前通过 32 个单元测试、44 个数据库集成测试；报告保存在各模块 `target` 下。

分别执行更深入的验证：

```bash
python3 scripts/api_contract_smoke.py
python3 scripts/fault_smoke.py
python3 scripts/dlt_smoke.py
```

第二条会临时停止并恢复本项目的 Redis、支付服务和 Kafka。等它完成、服务恢复后再运行下一条，也不要同时做压测。DLT 检查会向本地 Kafka 写入一个专用无效消息，并从死信主题读回它。具体历史验证见 [LOCAL_RESULTS](LOCAL_RESULTS.md)。

## 6. 停止与下次启动

```bash
# 停止并保留数据库
./scripts/docker-local.sh compose down
# 下次重新启动
./scripts/docker-local.sh compose up -d --wait --wait-timeout 180
```

修改 Java 代码后，需要加 `--build` 重建镜像再启动。避免使用 `down -v`，它会删除本地数据库、Kafka 和 Redis 的持久化数据。

## 云部署和缓存下一步

云部署可以完成。现有 Terraform 创建 EC2 主机及网络/SSM，尚未替你配置可运行的数据库、Kafka、Redis 和发布环境。合理的顺序是：先评估账号和预算，实际在 EC2 跑通全部服务与同样的测试；再完成 Kubernetes 部署、真实 GitHub Actions 运行和失败回滚验证。创建配置文件与实际部署是不同的完成标准。云资源会收费，创建前需确认预算。可先查看 [AWS EC2 官方定价](https://aws.amazon.com/ec2/pricing/on-demand/)。

Redis 缓存已经实现，稳定提速还需要按实际瓶颈优化和复测。上次是 500 SKU、本机数据库、100 请求/秒、每轮 10 秒；商品查询接口的平均响应已约 1–2ms，这种环境未体现稳定延迟收益。这个解释是基于当前结果的推测，尚未经过瓶颈分析验证。

下一步应先分别预热两种模式的 JVM，交替开/关缓存顺序，延长每轮到 60–120 秒，测试更大的数据集和多档到达速率，同时观察缓存命中、数据库读取/CPU/连接池、HTTP p95/p99、错误和丢弃。找到实际瓶颈再优化。缓存仅用于商品展示，成交报价和库存预留继续以数据库为准。最终可能证明减少数据库读取、改善某档负载的延迟或容量；不能先承诺提升百分比。衡量时应包括应用网络和序列化开销，参见 [Redis 官方监控说明](https://redis.io/docs/latest/operate/rs/monitoring/observability/)。
