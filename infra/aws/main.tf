data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ami" "ubuntu_arm64" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-arm64-server-*"]
  }
  filter {
    name   = "architecture"
    values = ["arm64"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_vpc" "staging" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "staging" {
  vpc_id = aws_vpc.staging.id
}

resource "aws_subnet" "staging" {
  vpc_id                  = aws_vpc.staging.id
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 1)
  availability_zone       = data.aws_availability_zones.available.names[0]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-ssm" }
}

resource "aws_route_table" "staging" {
  vpc_id = aws_vpc.staging.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.staging.id
  }
}

resource "aws_route_table_association" "staging" {
  subnet_id      = aws_subnet.staging.id
  route_table_id = aws_route_table.staging.id
}

resource "aws_security_group" "host" {
  name_prefix = "${var.name}-"
  description = "No ingress. Admin and port forwarding through authenticated SSM only."
  vpc_id      = aws_vpc.staging.id
  # A public address provides outbound package/SSM access without a NAT gateway.
  # No SSH, app, or Kubernetes port is opened by this security group.
  egress {
    description = "SSM, image registries and verified HTTPS package downloads"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  dynamic "egress" {
    for_each = length(var.data_service_cidrs) == 0 ? toset([]) : toset([5432, 6379, 6380, 9092, 9094])
    content {
      description = "Explicit private PostgreSQL/Kafka/Redis networks"
      from_port   = egress.value
      to_port     = egress.value
      protocol    = "tcp"
      cidr_blocks = var.data_service_cidrs
    }
  }
  tags = { Name = "${var.name}-no-ingress" }
}

resource "aws_iam_role" "host" {
  name_prefix = "${var.name}-ssm-"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ec2.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.host.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "host" {
  name_prefix = "${var.name}-"
  role        = aws_iam_role.host.name
}

resource "aws_instance" "host" {
  ami                         = data.aws_ami.ubuntu_arm64.id
  instance_type               = var.instance_type
  subnet_id                   = aws_subnet.staging.id
  associate_public_ip_address = true
  vpc_security_group_ids      = [aws_security_group.host.id]
  iam_instance_profile        = aws_iam_instance_profile.host.name
  monitoring                  = false
  user_data                   = <<-SCRIPT
    #!/usr/bin/env bash
    set -euo pipefail
    # Canonical AWS images ship the SSM agent; no application or cluster is auto-installed.
    systemctl enable --now snap.amazon-ssm-agent.amazon-ssm-agent.service
    install -d -m 0750 /opt/order-platform
    printf '%s\n' 'Single-host learning/staging only. Review docs/OPERATIONS.md before installing services.' > /opt/order-platform/README
  SCRIPT
  user_data_replace_on_change = true
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 40
    encrypted             = true
    delete_on_termination = true
  }
  tags       = { Name = var.name }
  depends_on = [aws_route_table_association.staging, aws_iam_role_policy_attachment.ssm]
}
