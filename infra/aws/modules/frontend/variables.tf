variable "project" {
  description = "Short name used to prefix/tag resources"
  type        = string
}

variable "api_origin_domain" {
  description = "DNS name of the load balancer that serves /api/* (reached over HTTP on port 80)"
  type        = string
}

variable "price_class" {
  description = "CloudFront price class; PriceClass_100 serves from North America and Europe edges only"
  type        = string
  default     = "PriceClass_100"
}
