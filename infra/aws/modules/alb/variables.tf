variable "project" {
  description = "Short name used to prefix/tag resources"
  type        = string
}

variable "vpc_id" {
  description = "VPC to create the load balancer and target groups in"
  type        = string
}

variable "subnet_ids" {
  description = "Public subnets for the load balancer, in at least two AZs"
  type        = list(string)
}

variable "services" {
  description = "Services to route to, keyed by name. paths takes at most 5 patterns, and * also matches /."
  type = map(object({
    port              = number
    paths             = list(string)
    priority          = number
    health_check_path = optional(string, "/actuator/health")
  }))
}
