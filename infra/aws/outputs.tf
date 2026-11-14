output "ecr_repository_urls" {
  value = {
    account-service     = module.account_service.ecr_repository_url
    transaction-service = module.transaction_service.ecr_repository_url
  }
}

output "alb_url" {
  description = "Public base URL for both services"
  value       = "http://${module.alb.dns_name}"
}

output "frontend_url" {
  description = "Public HTTPS URL for the SPA; the API is under /api"
  value       = "https://${module.frontend.domain_name}"
}

output "frontend_bucket" {
  description = "S3 bucket to upload frontend/dist to"
  value       = module.frontend.bucket_name
}

output "frontend_distribution_id" {
  description = "CloudFront distribution to invalidate after an upload"
  value       = module.frontend.distribution_id
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

output "dashboard_url" {
  description = "CloudWatch dashboard: 5xx rates, latency, outbox lag, transaction counts"
  value       = module.monitoring.dashboard_url
}

output "alarm_topic_arn" {
  description = "SNS topic every alarm notifies; subscribe more endpoints to it as needed"
  value       = module.monitoring.alarm_topic_arn
}
