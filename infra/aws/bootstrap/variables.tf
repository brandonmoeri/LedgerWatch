variable "aws_region" {
  description = "AWS region for the state bucket. Must match the region in the backend block in ../versions.tf"
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Short name used to prefix the state bucket"
  type        = string
  default     = "ledgerwatch"
}

variable "noncurrent_version_retention_days" {
  description = "Days to keep superseded state versions, which are the recovery path for a bad apply"
  type        = number
  default     = 90
}
