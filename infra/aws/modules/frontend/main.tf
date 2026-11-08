locals {
  name          = "${var.project}-frontend"
  s3_origin_id  = "s3-spa"
  alb_origin_id = "alb-api"
}

data "aws_caller_identity" "current" {}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header except Host (the ALB answers to its own hostname).
# With caching disabled, forwarding all viewer headers is also how Authorization
# reaches the origin: it can't be listed on its own in an origin request policy.
data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

# ---------------------------------------------------------------------------
# Bucket: private, readable only through this distribution (OAC)
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "this" {
  bucket        = "${local.name}-${data.aws_caller_identity.current.account_id}"
  force_destroy = true # contents are build output, rebuilt from source

  tags = {
    Name = local.name
  }
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_cloudfront_origin_access_control" "this" {
  name                              = local.name
  description                       = "CloudFront access to the ${local.name} bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_iam_policy_document" "bucket" {
  statement {
    sid       = "CloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.this.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }

  # Lets S3 answer 404 rather than 403 for a missing asset.
  statement {
    sid       = "CloudFrontList"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.this.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.bucket.json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

# ---------------------------------------------------------------------------
# Edge functions
# ---------------------------------------------------------------------------

# SPA fallback lives here rather than in custom_error_response: error responses
# apply to the whole distribution, so they would also replace the API's
# 401/403/404 problem+json bodies with index.html.
resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${local.name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  comment = "Serve index.html for client-side routes"
  publish = true
  code    = file("${path.module}/functions/spa-rewrite.js")
}

resource "aws_cloudfront_function" "api_strip_prefix" {
  name    = "${local.name}-api-strip-prefix"
  runtime = "cloudfront-js-2.0"
  comment = "Strip /api before forwarding to the ALB"
  publish = true
  code    = file("${path.module}/functions/api-strip-prefix.js")
}

# ---------------------------------------------------------------------------
# Distribution
# ---------------------------------------------------------------------------

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.project} SPA and API"
  default_root_object = "index.html"
  price_class         = var.price_class
  http_version        = "http2and3"

  origin {
    origin_id                = local.s3_origin_id
    domain_name              = aws_s3_bucket.this.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.this.id
  }

  # The ALB only has an HTTP listener, so the CloudFront -> ALB hop is plain HTTP.
  # Browsers still get HTTPS to the edge.
  origin {
    origin_id   = local.alb_origin_id
    domain_name = var.api_origin_domain

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "http-only"
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    target_origin_id       = local.s3_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = local.alb_origin_id
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods           = ["GET", "HEAD"]
    compress                 = true
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.api_strip_prefix.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # No custom domain yet, so the default *.cloudfront.net certificate.
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  tags = {
    Name = local.name
  }
}
