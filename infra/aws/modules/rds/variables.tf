variable "project" {
  description = "Short name used to prefix/tag resources"
  type        = string
}

variable "vpc_id" {
  description = "VPC to create the database security group in"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the DB subnet group"
  type        = list(string)
}

variable "db_name" {
  description = "Postgres database name"
  type        = string
}

variable "db_username" {
  description = "Postgres master username"
  type        = string
}

# A map, not a set of IDs: the IDs are unknown until apply, and for_each needs
# keys it can read at plan time.
variable "allowed_security_groups" {
  description = "Security groups allowed to reach Postgres, as { description = security group ID }"
  type        = map(string)
  default     = {}
}

variable "admin_cidr" {
  description = "CIDR allowed to reach Postgres directly. Leave empty to disable."
  type        = string
  default     = ""
}
