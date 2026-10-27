# Order Fulfillment Platform

第一次使用：请按 [中文逐步操作指南](docs/GETTING_STARTED_ZH.md) 打开项目，并运行 `python3 scripts/walkthrough.py` 查看每一步真实请求与结果。

Java 17 / Spring Boot 3.5 的订单履约项目。订单、库存、支付分别运行，分别拥有 PostgreSQL 数据库；Kafka 承担异步仓储状态更新，Redis 仅缓存商品展示信息。

这个版本着重实现可测试的事务边界、并发控制和故障恢复。支付提供方是明确标注的本地模拟器，代码没有接入真实扣款，也没有在 AWS 生产环境运行。测试结果见 [本次实测结果](docs/LOCAL_RESULTS.md)，不要把本机测试结果表述成真实生产流量。

最新补充：[缓存定位、优化及九轮真实压测](docs/CACHE_OPTIMIZATION.md)（正常延迟未提速，改进集中在冷并发和 Redis 故障回退）；[AWS 费用估算和 CI/CD 实际状态](docs/AWS_COST_ESTIMATE.md)。

## 本机启动

需要启动 Docker Desktop。首次构建会下载 Java/Maven 和数据库镜像，后续使用缓存。私有仓库需先使用有权限的 GitHub 账号完成 Git 认证；已克隆仓库时直接进入项目目录。

```bash
git clone https://github.com/Wenqi0211/order-fulfillment-platform.git
cd order-fulfillment-platform
python3 scripts/init_env.py
./scripts/docker-local.sh compose up -d --build --wait --wait-timeout 180
python3 scripts/smoke.py
```

`init_env.py` 只在缺失时生成本地凭据，文件不会提交。Docker 包装脚本使用项目自己的匿名镜像下载配置，避免修改 macOS 的全局凭据设置。

本机端口：订单 8080、库存 8081、支付 8082、PostgreSQL 5432、Redis 6379、Kafka 9092；均仅绑定本机回环地址。全部业务接口和指标接口需要 `.env` 中的 `APP_API_KEY`，通过 `X-API-Key` 请求头传递。健康检查不需要密钥。

```bash
# 查看运行状态与订单日志
./scripts/docker-local.sh compose ps
./scripts/docker-local.sh compose logs --tail=100 order-service
# 停止服务，保留数据库数据
./scripts/docker-local.sh compose down
```

不要使用 `down -v`，除非确定要清空本项目的全部本地数据库、Kafka 和 Redis 数据。

## 核心行为

- **订单幂等**：相同 `Idempotency-Key` 和相同请求返回同一个订单；修改请求返回 409。报价由库存数据库生成，客户端不能传入成交价格。
- **库存一致性**：对多个 SKU 按固定顺序加锁，整单预留或整单回滚；数据库约束防止负库存和超额预留。预留、提交、释放都有持久化状态与审计。
- **支付重试**：先独立提交不可变的 PENDING 支付意图，再使用稳定订单 ID 调用模拟提供方。结果未知时保留 PENDING；不同金额或 token 的重试返回 409。
- **持久化 Saga**：`RESERVE → PAYMENT → COMMIT → DONE`。只有明确拒付才进入库存补偿；超时保留当前步骤重试。达到次数上限后进入 `MANUAL_REVIEW`，操作员可继续同一步骤。
- **事务 outbox**：业务记录和待发消息一起提交；Kafka 确认后才标记发送完成。存在发送后、数据库提交前的重复窗口，因此消费者必须幂等。
- **仓储事件**：通过 Kafka 异步更新，按事件 ID 去重、按序号防止状态倒退。无法处理的消息有限重试后进入 DLT，确认 DLT 已发送才提交原消息偏移量；支持保留原事件 ID 重放。
- **Redis 边界**：展示缓存可短暂过期；报价和库存变更始终访问 PostgreSQL。同进程、同 SKU 的未命中加载有界合并；Redis 故障时短暂旁路，单探针确认恢复，商品查询回源数据库。

## 代码导览

| 目录 | 重点 |
| --- | --- |
| `common/` | API 契约、输入边界、密钥校验、请求关联 ID、事务 outbox、指标 |
| `inventory-service/` | 商品、权威报价、库存预留/提交/释放、Redis 回源 |
| `payment-service/` | 持久化支付意图、提供方接口、明确标识的模拟支付 |
| `order-service/` | 下单、持久化工作流、仓储消费、DLT 与恢复接口 |
| `infra/kubernetes/` | staging/prod 配置、探针、资源限制、网络策略、滚动部署 |
| `infra/aws/` | 单台学习/预发布 EC2、VPC、SSM 的 Terraform；不代表生产高可用架构 |
| `.github/workflows/` | 测试、镜像构建、按 digest 发布和受环境保护的部署/回滚 |
| `scripts/` | 完整流程验证、故障恢复验证、负载实验、部署工具 |

## 验证和负载实验

运行 Java 单元测试需要 JDK 17 和 Python 3。`./mvnw` 会在项目 `.tooling` 内下载并校验 Maven，不修改全局 Maven 安装或 `~/.m2`。

```bash
./mvnw test
# Docker 内数据库的真实 PostgreSQL 集成测试，凭据从 .env 私下读取
python3 scripts/java_integration.py
# 完整 HTTP + Kafka 流程
python3 scripts/smoke.py
# 临时停止并恢复本项目 Redis、支付服务和 Kafka
python3 scripts/fault_smoke.py
# 实际 Kafka 死信消息发送与读回
python3 scripts/dlt_smoke.py
# 相同数据、请求序列和资源限制下进行缓存开/关实验，保存原始数据
python3 scripts/cache_benchmark.py --count 1000 --rate 50 --seconds 30 --repeats 3
```

集成测试覆盖库存争抢、多 SKU 原子性、并发幂等、支付未知结果、崩溃后恢复、状态单调性、消费者事务回滚和 outbox 发送后的重复窗口。故障脚本在 `finally` 中恢复它停止的服务；如果手动中断，可用 `compose up -d --wait` 恢复。

压测会保存每次请求、失败、丢弃、延迟分位数和容器资源限制。未重复验证的比例、吞吐量、可用率不能写进简历；CI/CD 配置存在也不等于已经完成云端部署。

## 部署与边界

见 [架构说明](docs/ARCHITECTURE.md)、[操作手册](docs/OPERATIONS.md)、[简历逐项对应](docs/RESUME_TRACEABILITY.md) 和 [验证记录](docs/VERIFICATION.md)。

该 API 面向内部/B2B 单一服务身份，不具备消费者账号、多租户授权和真实支付安全认证。生产接入还需要真实提供方的幂等/对账、密钥管理与轮换、TLS、基础设施容量规划、数据保留与备份恢复演练、值班告警，以及针对实际流量的验证。公共云部署会产生费用，本项目没有自动执行 Terraform apply 或创建云资源。

技术实现无法证明过去发生过团队管理、Scrum、生产部署或业务成果；相关简历经历必须按实际发生的事情填写。
