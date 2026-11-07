terraform {
  required_version = ">= 1.11"

  # Partial config: the bucket name contains the account ID, so it's passed at init.
  # See "Remote state" in README.md for the bootstrap steps.
  backend "s3" {
    key          = "ledgerwatch/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "aws" {
  region = var.aws_region
}
