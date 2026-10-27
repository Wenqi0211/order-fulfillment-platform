# 精简验证证据

此目录保存本地 `reports/` 的精简副本：小型结果 JSON、实验计划与条件、源代码 SHA-256 摘要、合成数据集和缓存实验汇总。下级目录保持原 `reports/` 中的相对层级；复制时逐字节核对，没有修改测量值、时间戳、镜像 ID 或历史路径元数据。

完整原始 JSONL、运行日志、Maven XML、指标快照、突发逐请求原始 JSON 和 baseline 源码仍在原电脑本地 `reports/`，没有上传。JSON 中保留的本机绝对路径和以 `reports/` 开头的字符串是历史运行元数据，并不是新克隆中的可点击文件链接。若需重新验证，请运行仓库中的测试和实验脚本；新结果会写入被 Git 忽略的本地 `reports/`。

商品、订单、支付和负载数据均为本地合成测试数据。模拟支付不产生真实扣款；这些报告不代表真实业务用户、生产容量或已完成云部署。精选文件已检查常见密钥格式及凭据字段，未包含 `.env`、数据库转储、私钥、云端凭据或 kubeconfig。

## 入口

- [初次本地验证汇总](verification-summary.json) 与 [对应源码摘要](source-hashes.json)。
- [缓存优化后验证汇总](cache-optimization/verification.json)。
- [全部缓存测量轮次与预热汇总](cache-study-final/summary.md) 及 [机器可读版本](cache-study-final/summary.json)。
- [复制文件清单与 SHA-256](manifest.json)。

`manifest.json` 记录原相对路径、仓库副本、字节数和复制文件的 SHA-256；它只说明副本身份，不把历史报告变成当前工作区的新测试结果。
