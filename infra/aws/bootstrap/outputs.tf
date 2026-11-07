output "state_bucket" {
  description = "Pass to `terraform init -backend-config` in infra/aws"
  value       = aws_s3_bucket.state.bucket
}
