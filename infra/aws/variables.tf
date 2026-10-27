variable "aws_region" {
  type    = string
  default = "us-west-2"
}

variable "name" {
  type    = string
  default = "order-platform-learning"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,35}$", var.name))
    error_message = "Use 3-36 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "instance_type" {
  description = "ARM64 host for a single-node learning/staging cluster. This is not an HA production design."
  type        = string
  default     = "t4g.large"
  validation {
    condition     = contains(["t4g.large", "t4g.xlarge", "m7g.large", "m7g.xlarge"], var.instance_type)
    error_message = "Select one of the documented ARM64 instance types."
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.72.0.0/16"
  validation {
    condition     = can(cidrsubnet(var.vpc_cidr, 8, 1)) && startswith(var.vpc_cidr, "10.") && try(tonumber(split("/", var.vpc_cidr)[1]), 0) >= 16 && try(tonumber(split("/", var.vpc_cidr)[1]), 33) <= 20
    error_message = "Supply a private 10.x.x.x VPC CIDR large enough for the staging subnet."
  }
}

variable "data_service_cidrs" {
  description = "Explicit private data-service network ranges reachable through separately configured VPC routing/peering. Empty denies outbound PostgreSQL/Kafka/Redis."
  type        = list(string)
  default     = []
  validation {
    condition = alltrue([for cidr in var.data_service_cidrs : can(cidrhost(cidr, 0)) && (
      (startswith(cidr, "10.") && try(tonumber(split("/", cidr)[1]), 0) >= 8) ||
      (startswith(cidr, "192.168.") && try(tonumber(split("/", cidr)[1]), 0) >= 16) ||
      (can(regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.", cidr)) && try(tonumber(split("/", cidr)[1]), 0) >= 12)
    )])
    error_message = "Use explicit RFC1918 IPv4 ranges; unrestricted data-service egress is prohibited."
  }
}
