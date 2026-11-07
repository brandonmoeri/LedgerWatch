# Creates the bucket that holds infra/aws's remote state, so this config cannot
# use that bucket itself: its own state stays local (gitignored).
terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}
