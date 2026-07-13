output "ecr_repository_url" {
  value = aws_ecr_repository.account_service.repository_url
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "rds_port" {
  value = aws_db_instance.main.port
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  value = aws_ecs_service.account_service.name
}

output "db_secret_arn" {
  value = aws_secretsmanager_secret.db.arn
}

output "db_password" {
  value     = random_password.db.result
  sensitive = true
}
