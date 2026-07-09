variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Short name used to prefix/tag resources"
  type        = string
  default     = "ledgerwatch"
}

variable "db_name" {
  description = "Postgres database name"
  type        = string
  default     = "ledgerwatch"
}

variable "db_username" {
  description = "Postgres master username"
  type        = string
  default     = "ledger"
}

variable "admin_cidr" {
  description = "CIDR allowed to reach RDS on 5432 directly (your current IP, for one-time schema migration). Leave empty to disable."
  type        = string
  default     = ""
}
