variable "project" {
  description = "Short name used to prefix/tag resources"
  type        = string
}

variable "service_name" {
  description = "Service name, used for the container, ECR repository and DNS record"
  type        = string
}

variable "aws_region" {
  description = "AWS region, for the CloudWatch log driver"
  type        = string
}

variable "cluster_id" {
  description = "ECS cluster to run the service in"
  type        = string
}

variable "vpc_id" {
  description = "VPC to create the task security group in"
  type        = string
}

variable "subnet_ids" {
  description = "Subnets to run tasks in. Tasks get public IPs, so these must be public."
  type        = list(string)
}

variable "service_discovery_namespace" {
  description = "Cloud Map private DNS namespace to register the service in"
  type = object({
    id   = string
    name = string
  })
}

variable "container_port" {
  description = "Port the container listens on"
  type        = number
}

variable "image_tag" {
  description = "Image tag to deploy from the service's ECR repository"
  type        = string
  default     = "latest"
}

variable "cpu" {
  description = "Fargate task CPU units"
  type        = number
  default     = 512
}

variable "memory" {
  description = "Fargate task memory in MiB"
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "Number of tasks to run"
  type        = number
  default     = 1
}

variable "environment" {
  description = "Plain environment variables, as { NAME = value }"
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variables read from Secrets Manager, as { NAME = { arn, key } }. Omit key to use the whole secret string."
  type = map(object({
    arn = string
    key = optional(string)
  }))
  default = {}
}

# A map, not a set of IDs: the IDs are unknown until apply, and for_each needs
# keys it can read at plan time.
variable "allowed_security_groups" {
  description = "Security groups allowed to reach container_port, as { description = security group ID }"
  type        = map(string)
  default     = {}
}

variable "load_balancer" {
  description = "Target group to register tasks with, and how long ECS ignores its health checks while a new task starts. Leave null to run without a load balancer."
  type = object({
    target_group_arn                  = string
    health_check_grace_period_seconds = optional(number, 180)
  })
  default = null
}

variable "admin_cidr" {
  description = "CIDR allowed to reach container_port directly. Leave empty to disable."
  type        = string
  default     = ""
}
