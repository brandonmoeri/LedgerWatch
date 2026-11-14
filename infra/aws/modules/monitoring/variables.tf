variable "project" {
  description = "Short name used to prefix/tag resources"
  type        = string
}

variable "aws_region" {
  description = "Region the dashboard's widgets read metrics from"
  type        = string
}

variable "load_balancer_arn_suffix" {
  description = "ALB ARN suffix (app/<name>/<id>), the LoadBalancer dimension of AWS/ApplicationELB metrics"
  type        = string
}

variable "target_group_arn_suffixes" {
  description = "Target group ARN suffix (targetgroup/<name>/<id>) for each service, keyed by service name"
  type        = map(string)
}

variable "metrics_namespace" {
  description = "CloudWatch namespace transaction-service pushes its ledgerwatch.* meters to"
  type        = string
}

variable "metrics_application" {
  description = "Value of the application dimension on those meters (spring.application.name)"
  type        = string
  default     = "transaction-service"
}

variable "alarm_email" {
  description = "Email address subscribed to the alarm topic. AWS sends a confirmation link first; nothing is delivered until it's clicked. Leave empty for no subscription."
  type        = string
  default     = ""
}

variable "http_5xx_rate_threshold" {
  description = "Per-service alarm threshold: percent of a service's requests answered 5xx over 5 minutes"
  type        = number
  default     = 5
}

variable "http_5xx_min_requests" {
  description = "Requests a service must receive in the 5 minutes before its 5xx rate can alarm, so 1 error in 2 requests doesn't count as a 50% error rate"
  type        = number
  default     = 10
}

variable "alb_5xx_count_threshold" {
  description = "ALB-generated 5xx responses (502/503/504: no healthy target, connection refused, timeout) in 5 minutes that trigger an alarm"
  type        = number
  default     = 5
}

variable "outbox_lag_threshold_seconds" {
  description = "Age of the oldest undelivered outbox event, in seconds, that triggers an alarm once sustained for 3 minutes. The publisher's backoff tops out at 5 minutes, so lag passes that only when deliveries keep failing."
  type        = number
  default     = 300
}
