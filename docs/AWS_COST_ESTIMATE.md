# AWS Costs and CI/CD Status · 2026-10-24

Estimate based on the project's current defaults: **Oregon / us-west-2, Linux, on-demand pricing, one t4g.large instance, 40 GiB gp3, and 1 public IPv4 address**. Amounts are in US dollars, before tax, without deducting new-account credits. No AWS resources have been created.

## Approximate costs

| Item | Verified unit price | 730 hours of continuous operation |
| --- | --- | ---: |
| EC2 t4g.large, 2 vCPU / 8 GiB | $0.0672 / hour | $49.06 |
| 40 GiB EBS gp3, default baseline performance | $0.08 / GB-month | $3.20 |
| 1 public IPv4 address | $0.005 / hour | $3.65 |
| Total | Based on 730 hours | **Approximately $55.91 / month** |

EC2 and EBS unit prices were checked against the public pricing JSON used by the AWS website, published at `2026-10-23T22:13:55Z`: [EC2 Oregon/Linux pricing data](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ec2-ondemand-without-sec-sel/US%20West%20%28Oregon%29/Linux/index.json), [EBS pricing data](https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/ec2/USD/current/ebs.json). See [official AWS VPC pricing](https://aws.amazon.com/vpc/pricing/) for the public-address unit price.

If you deploy, demonstrate, and test for **20 hours, then delete these resources**, compute costs approximately $1.34, public IPv4 approximately $0.10, and time-prorated storage approximately $0.09, for a base total of **approximately $1.53**. If you only stop the instance and retain the 40 GiB disk for the whole month, assuming the automatically assigned public address is released, the base total is approximately **$4.64**. Storage continues to incur charges while retained; see [EBS billing details](https://aws.amazon.com/ebs/pricing/).

These estimates exclude tax, excess outbound data transfer, snapshots, logs, additional IOPS/throughput, paid support plans, CI runners, and other charges. T4g uses Unlimited mode by default, so sustained high CPU usage may incur CPU credit charges. The additional rate currently listed by AWS is $0.04/vCPU-hour; see the [official T4g description](https://aws.amazon.com/ec2/instance-types/t4/). Therefore, $55.91 is an estimate for the listed base resources, not a monthly bill cap.

This can serve as a base budget for a learning/demo environment with all three applications, PostgreSQL, Kafka, and Redis on one EC2 instance. Whether it can handle the target traffic still requires cloud validation. The current Terraform creates only the instance and network/SSM resources; it does not install and run the full dependency stack. The current Kubernetes templates use external databases, Kafka, and Redis. If you choose RDS, MSK, and ElastiCache, estimate their additional costs separately; $55.91 is not the price of a complete managed cluster.

## Does the AWS account itself cost money?

Account registration itself is free; the Paid Plan charges for service usage. New customers can currently receive $100 in signup credits and earn up to another $100 through specified activities. The Free Plan lasts up to six months, or ends earlier when credits run out. Eligibility, the signup process, and available resources depend on your actual account. AWS is gradually rolling out a new signup experience: most new customers do not need to provide a payment method upfront, while some still require identity verification. See the [current Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/) and [new signup process](https://docs.aws.amazon.com/accounts/latest/reference/sign-in-new.html).

**The current template's t4g.large is not in the list of Free Tier eligible instances in the EC2 documentation.** Do not promise that this configuration is free. Check the instance types, plan, and credits available to the account, then adjust the template or use a paid plan if necessary. [EC2 Free Tier instance details](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)

## What is CI/CD, and what has been implemented?

- **CI, continuous integration**: Automatically builds, tests, and checks configuration after code is submitted, detecting broken behavior early.
- **CD, continuous delivery/deployment**: Releases a version that has passed checks to a test or production environment. The current design uses manually triggered releases, making it continuous delivery with human release control.

Implemented locally:

| Configuration | Implemented workflow |
| --- | --- |
| `.github/workflows/ci.yml` | Triggered by PR/main submissions; Java/PostgreSQL tests; deployment-script safety tests; YAML/Kustomize checks; real Compose/Kafka flows and failure checks; after all checks pass, builds and publishes three multi-architecture images and their digests on main |
| `.github/workflows/deploy.yml` | Manually select staging/prod and a verified commit; checks successful CI, image digests, and the target cluster/namespace; waits for all three service deployments and checks endpoints; attempts to restore all recorded application versions on failure |

**As of this 2026-10-24 record, configuration and related local tests were complete, but there was no record of a successful GitHub-hosted workflow run, image push, or cloud release.** Enabling them still requires a GitHub repository, environments and permissions, an image registry, a deployment runner with cluster access, and actual pipeline execution. Production approval rules also need to be configured in GitHub environment settings.
