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

# Claude API key for insights-service. Terraform creates the empty secret only: the key
# itself is put in out of band (README "Deploying"), so it never lands in Terraform state.
# ECS can't start insights-service tasks until the secret has a value.
resource "aws_secretsmanager_secret" "anthropic_api_key" {
  name                    = "${var.project}/anthropic-api-key"
  recovery_window_in_days = 0

  tags = {
    Name = "${var.project}-anthropic-api-key"
  }
}

locals {
  # transaction-service's ledgerwatch.* meters (see its TransactionMetrics, OutboxBacklogMetrics).
  metrics_namespace = "LedgerWatch"

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

  # Fraud flags live in transaction-service, but under account-service's /accounts/*.
  path_overrides = {
    account-fraud-flags = {
      service  = "transaction-service"
      paths    = ["/accounts/*/flags"]
      priority = 50
    }
  }
}

# The SPA from S3, with /api/* proxied to the ALB, so the browser sees one origin
# (no CORS) exactly as with the Vite dev proxy.
module "frontend" {
  source = "./modules/frontend"

  project           = var.project
  api_origin_domain = module.alb.dns_name
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

  # transaction-service's outbox publisher applies balance adjustments here; insights-service's
  # tools read accounts with the end user's JWT.
  allowed_security_groups = {
    "Load balancer"                    = module.alb.security_group_id
    "transaction-service Fargate task" = module.transaction_service.security_group_id
    "insights-service Fargate task"    = module.insights_service.security_group_id
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

  metrics_namespace = local.metrics_namespace

  load_balancer = {
    target_group_arn = module.alb.target_group_arns["transaction-service"]
  }

  # insights-service's tools read transactions and fraud flags with the end user's JWT.
  allowed_security_groups = {
    "Load balancer"                 = module.alb.security_group_id
    "insights-service Fargate task" = module.insights_service.security_group_id
  }

  environment = {
    ACCOUNT_SERVICE_URL = module.account_service.internal_url
  }
}

# No load balancer route or database yet: it has no endpoints besides /actuator/health.
# It calls the Claude API (outbound over the task's public IP) and, from its tools,
# account-service and transaction-service over service discovery.
module "insights_service" {
  source = "./modules/ecs-service"

  project                     = var.project
  aws_region                  = var.aws_region
  cluster_id                  = aws_ecs_cluster.main.id
  vpc_id                      = module.network.vpc_id
  subnet_ids                  = module.network.public_subnet_ids
  service_discovery_namespace = aws_service_discovery_private_dns_namespace.main
  admin_cidr                  = var.admin_cidr

  service_name   = "insights-service"
  container_port = 8083

  secrets = {
    JWT_SECRET        = { arn = aws_secretsmanager_secret.jwt.arn }
    ANTHROPIC_API_KEY = { arn = aws_secretsmanager_secret.anthropic_api_key.arn }
  }

  environment = {
    ACCOUNT_SERVICE_URL     = module.account_service.internal_url
    TRANSACTION_SERVICE_URL = module.transaction_service.internal_url
  }
}

# Dashboard, plus alarms on each service's 5xx rate, ALB-generated 5xx and
# outbox lag, all notifying one SNS topic.
module "monitoring" {
  source = "./modules/monitoring"

  project                   = var.project
  aws_region                = var.aws_region
  load_balancer_arn_suffix  = module.alb.arn_suffix
  target_group_arn_suffixes = module.alb.target_group_arn_suffixes
  metrics_namespace         = local.metrics_namespace
  alarm_email               = var.alarm_email
}
