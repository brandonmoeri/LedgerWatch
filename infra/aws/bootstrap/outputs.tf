output "state_bucket" {
  description = "Pass to `terraform init -backend-config` in infra/aws"
  value       = aws_s3_bucket.state.bucket
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository variable in GitHub"
  value       = aws_iam_role.github_deploy.arn
}
