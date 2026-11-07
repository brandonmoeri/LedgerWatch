output "ecr_repository_urls" {
  value = {
    account-service     = module.account_service.ecr_repository_url
    transaction-service = module.transaction_service.ecr_repository_url
  }
}

output "rds_endpoint" {
  value = module.rds.address
}

output "rds_port" {
  value = module.rds.port
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service_names" {
  value = {
    account-service     = module.account_service.service_name
    transaction-service = module.transaction_service.service_name
  }
}

output "db_secret_arn" {
  value = module.rds.secret_arn
}

output "db_password" {
  value     = module.rds.password
  sensitive = true
}
