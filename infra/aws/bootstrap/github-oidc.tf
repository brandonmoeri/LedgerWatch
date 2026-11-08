# Lets GitHub Actions assume an IAM role with short-lived credentials from
# GitHub's OIDC token, so no AWS access keys are stored in the repo.
#
# Lives here rather than in infra/aws because CI needs it before it can run
# anything, and `terraform destroy` of the app stack must not remove it.

locals {
  account_id  = data.aws_caller_identity.current.account_id
  github_host = "token.actions.githubusercontent.com"

  # Only pushes to these branches may assume the role. Pull requests (sub
  # "repo:<repo>:pull_request"), other branches and tags are rejected.
  github_subjects = [
    for branch in var.github_deploy_branches : "repo:${var.github_repository}:ref:refs/heads/${branch}"
  ]
}

# One per account per URL. AWS validates GitHub's certificate against its own
# trusted CA store, so no thumbprint is needed.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://${local.github_host}"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${local.github_host}:aud"
      values   = ["sts.amazonaws.com"]
    }

    # StringEquals, not StringLike: a wildcard here is the usual way these
    # trust policies end up accepting any repo or branch.
    condition {
      test     = "StringEquals"
      variable = "${local.github_host}:sub"
      values   = local.github_subjects
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name                 = "${var.project}-github-deploy"
  description          = "Assumed by GitHub Actions in ${var.github_repository} via OIDC"
  assume_role_policy   = data.aws_iam_policy_document.github_trust.json
  max_session_duration = 3600
}

# What the manual steps under "Deploying" in README.md do, minus Terraform:
# push images to ECR, roll the ECS services, publish the SPA. Scoped by the
# resource names infra/aws creates.
data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = ["arn:aws:ecr:${var.aws_region}:${local.account_id}:repository/${var.project}/*"]
  }

  statement {
    sid       = "EcsRollServices"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = ["arn:aws:ecs:${var.aws_region}:${local.account_id}:service/${var.project}-cluster/${var.project}-*"]
  }

  statement {
    sid       = "FrontendList"
    actions   = ["s3:ListBucket"]
    resources = ["arn:aws:s3:::${var.project}-frontend-${local.account_id}"]
  }

  statement {
    sid       = "FrontendWrite"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["arn:aws:s3:::${var.project}-frontend-${local.account_id}/*"]
  }

  statement {
    sid       = "FrontendInvalidate"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = ["arn:aws:cloudfront::${local.account_id}:distribution/*"]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
