# AWS 费用与 CI/CD 状态 · 2026-10-24

按当前项目默认的 **Oregon / us-west-2，Linux，按需付费，单台 t4g.large、40 GiB gp3、1 个公网 IPv4** 估算。金额为美元、税前，未扣新账号 credits。没有创建任何 AWS 资源。

## 大概要多少钱

| 项目 | 已核对的单价 | 连续运行 730 小时 |
| --- | --- | ---: |
| EC2 t4g.large，2 vCPU / 8 GiB | $0.0672 / 小时 | $49.06 |
| 40 GiB EBS gp3，默认基础性能 | $0.08 / GB·月 | $3.20 |
| 1 个公网 IPv4 | $0.005 / 小时 | $3.65 |
| 合计 | 按 730 小时折算 | **约 $55.91 / 月** |

EC2 与 EBS 单价从 AWS 官网使用的公开定价 JSON 中核对，文件发布时间为 `2026-10-23T22:13:55Z`：[EC2 Oregon/Linux 定价数据](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/US%20West%20%28Oregon%29/Linux/index.json)、[EBS 定价数据](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ebs.json)。公网地址单价见 [AWS VPC 官方定价](https://aws.amazon.com/vpc/pricing/)。

如果只部署、演示、测试 **20 小时后删除这组资源**，计算费约 $1.34，公网 IPv4 约 $0.10，磁盘按时间折算约 $0.09，基础合计 **约 $1.53**。如果仅停止主机、让 40 GiB 磁盘保留整月，按公网自动地址已释放计算，基础合计约 **$4.64**。磁盘只要还保留就继续计费，见 [EBS 计费说明](https://aws.amazon.com/ebs/pricing/)。

这些估算不含税、超额出网、快照、日志、额外 IOPS/吞吐量、收费支持计划和 CI runner 等费用。T4g 默认 Unlimited，长时间高 CPU 可能增加 CPU credit 费用；官方当前列出的额外费率为 $0.04/vCPU·小时，参见 [T4g 官方说明](https://aws.amazon.com/ec2/instance-types/t4/)。因此 $55.91 是所列基础资源估算，不是月账单上限。

这可以作为把三个应用、PostgreSQL、Kafka、Redis 同放在一台 EC2 上的学习/演示环境基础预算；能否承受目标流量还要在云端验证。当前 Terraform 只创建主机和网络/SSM，没有替你安装并运行整套依赖。当前 Kubernetes 模板使用外部数据库、Kafka、Redis；若选择 RDS、MSK、ElastiCache，需要单独增加费用估算，不能把 $55.91 当成整套托管集群的价格。

## AWS 账号本身要花钱吗

注册账号本身免费，Paid Plan 按服务用量收费。当前新客户可获得 $100 注册 credits，并通过指定活动再获得最多 $100；Free Plan 最长六个月，或 credits 先用尽时结束。是否符合资格、注册流程和可用资源以你的实际账号为准。近期 AWS 正逐步推出新的注册体验，大部分新客户不要求先提供支付方式，部分客户仍需身份验证。见 [当前 Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/) 和 [新注册流程说明](https://docs.aws.amazon.com/accounts/latest/reference/sign-in-new.html)。

**当前模板的 t4g.large 不在 EC2 文档列出的 Free Tier eligible 实例清单中。** 不应直接承诺这个配置免费；需看账号允许的规格、计划和 credits，必要时调整模板或使用付费计划。[EC2 Free Tier 规格说明](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)

## CI/CD 是什么，现在有没有做

- **CI，持续集成**：提交代码后自动编译、测试、检查配置，提前发现改动破坏了什么。
- **CD，持续交付/部署**：把检查通过的版本发布到测试或正式环境。当前设计由人触发发布，属于带人工发布控制的持续交付。

本地已写好：

| 配置 | 已实现的流程 |
| --- | --- |
| `.github/workflows/ci.yml` | PR/main 提交触发；Java/PostgreSQL 测试；部署脚本安全测试；YAML/Kustomize 检查；真实 Compose/Kafka 流程与故障检查；全部成功后在 main 构建并发布三个多架构镜像及其 digest |
| `.github/workflows/deploy.yml` | 手动选择 staging/prod 与已验证 commit；检查成功 CI、镜像 digest、目标集群/命名空间；等待三个服务发布并检查接口；失败时尝试恢复全部已记录的应用版本 |

**配置和本地相关测试已经完成，尚无 GitHub 托管工作流、镜像推送和云端发布成功运行的记录。** 真正启用还需要 GitHub 仓库、环境与权限、镜像仓库、可访问集群的部署 runner，以及实际执行流水线。生产环境审批规则也需要在 GitHub 环境设置中配置。
