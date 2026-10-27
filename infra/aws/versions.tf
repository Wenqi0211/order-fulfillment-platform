terraform {
  required_version = ">= 1.6, < 2.0"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = var.aws_region
  default_tags {
    tags = {
      Project     = "order-fulfillment-platform"
      ManagedBy   = "terraform"
      Environment = "learning-staging"
    }
  }
}
