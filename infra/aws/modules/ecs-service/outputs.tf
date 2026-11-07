output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "service_name" {
  value = aws_ecs_service.this.name
}

output "security_group_id" {
  value = aws_security_group.task.id
}

output "internal_url" {
  description = "Base URL other services in the VPC can use to reach this one"
  value       = "http://${var.service_name}.${var.service_discovery_namespace.name}:${var.container_port}"
}
