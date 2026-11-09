output "dns_name" {
  value = aws_lb.this.dns_name
}

output "security_group_id" {
  value = aws_security_group.this.id
}

output "target_group_arns" {
  description = "Target group ARN for each service, keyed by service name"
  value       = { for name, group in aws_lb_target_group.this : name => group.arn }

  # ECS refuses a target group that isn't attached to a load balancer yet.
  depends_on = [aws_lb_listener_rule.this]
}

output "arn_suffix" {
  description = "The LoadBalancer dimension of AWS/ApplicationELB metrics"
  value       = aws_lb.this.arn_suffix
}

output "target_group_arn_suffixes" {
  description = "The TargetGroup dimension of AWS/ApplicationELB metrics for each service, keyed by service name"
  value       = { for name, group in aws_lb_target_group.this : name => group.arn_suffix }
}
