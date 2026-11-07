module "network" {
  source = "./modules/network"

  project = var.project
}

module "rds" {
  source = "./modules/rds"

  project     = var.project
  vpc_id      = module.network.vpc_id
  subnet_ids  = module.network.public_subnet_ids
  db_name     = var.db_name
  db_username = var.db_username
  admin_cidr  = var.admin_cidr

  allowed_security_groups = {
    "account-service Fargate task"     = module.account_service.security_group_id
    "transaction-service Fargate task" = module.transaction_service.security_group_id
  }
}

resource "aws_ecs_cluster" "main" {
  name = "${var.project}-cluster"
}

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${var.project}.local"
  vpc  = module.network.vpc_id
}

# Shared HMAC key: account-service signs tokens with it and both services validate them.
resource "random_password" "jwt" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${var.project}/jwt"
  recovery_window_in_days = 0

  tags = {
    Name = "${var.project}-jwt"
  }
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt.result
}

locals {
  service_secrets = {
    DB_URL      = { arn = module.rds.secret_arn, key = "url" }
    DB_USERNAME = { arn = module.rds.secret_arn, key = "username" }
    DB_PASSWORD = { arn = module.rds.secret_arn, key = "password" }
    JWT_SECRET  = { arn = aws_secretsmanager_secret.jwt.arn }
  }
}

module "alb" {
  source = "./modules/alb"

  project    = var.project
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.public_subnet_ids

  services = {
    account-service = {
      port     = 8081
      paths    = ["/accounts", "/accounts/*", "/auth", "/auth/*"]
      priority = 100
    }
    transaction-service = {
      port     = 8082
      paths    = ["/transactions*"]
      priority = 200
    }
  }
}

module "account_service" {
  source = "./modules/ecs-service"

  project                     = var.project
  aws_region                  = var.aws_region
  cluster_id                  = aws_ecs_cluster.main.id
  vpc_id                      = module.network.vpc_id
  subnet_ids                  = module.network.public_subnet_ids
  service_discovery_namespace = aws_service_discovery_private_dns_namespace.main
  admin_cidr                  = var.admin_cidr

  service_name   = "account-service"
  container_port = 8081
  secrets        = local.service_secrets

  load_balancer = {
    target_group_arn = module.alb.target_group_arns["account-service"]
  }

  # transaction-service's outbox publisher applies balance adjustments here.
  allowed_security_groups = {
    "Load balancer"                    = module.alb.security_group_id
    "transaction-service Fargate task" = module.transaction_service.security_group_id
  }
}

module "transaction_service" {
  source = "./modules/ecs-service"

  project                     = var.project
  aws_region                  = var.aws_region
  cluster_id                  = aws_ecs_cluster.main.id
  vpc_id                      = module.network.vpc_id
  subnet_ids                  = module.network.public_subnet_ids
  service_discovery_namespace = aws_service_discovery_private_dns_namespace.main
  admin_cidr                  = var.admin_cidr

  service_name   = "transaction-service"
  container_port = 8082
  secrets        = local.service_secrets

  load_balancer = {
    target_group_arn = module.alb.target_group_arns["transaction-service"]
  }

  allowed_security_groups = {
    "Load balancer" = module.alb.security_group_id
  }

  environment = {
    ACCOUNT_SERVICE_URL = module.account_service.internal_url
  }
}
