locals {
  name = "${var.project}-${var.service_name}"

  # ECS resolves "<arn>:<json-key>::" to one key of a JSON secret, and a bare
  # ARN to the whole secret string.
  container_secrets = [
    for name, secret in var.secrets : {
      name      = name
      valueFrom = secret.key != null ? "${secret.arn}:${secret.key}::" : secret.arn
    }
  ]
}

resource "aws_ecr_repository" "this" {
  name                 = "${var.project}/${var.service_name}"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  tags = {
    Name = local.name
  }
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/ecs/${local.name}"
  retention_in_days = 3
}

resource "aws_iam_role" "execution" {
  name = "${local.name}-execution"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "read_secrets" {
  count = length(var.secrets) > 0 ? 1 : 0
  name  = "${local.name}-read-secrets"
  role  = aws_iam_role.execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = distinct([for secret in values(var.secrets) : secret.arn])
    }]
  })
}

resource "aws_security_group" "task" {
  name        = "${local.name}-sg"
  description = "${var.service_name} Fargate task"
  vpc_id      = var.vpc_id

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.name}-sg"
  }
}

resource "aws_security_group_rule" "from_security_group" {
  for_each = var.allowed_security_groups

  type                     = "ingress"
  from_port                = var.container_port
  to_port                  = var.container_port
  protocol                 = "tcp"
  security_group_id        = aws_security_group.task.id
  source_security_group_id = each.value
  description              = each.key
}

resource "aws_security_group_rule" "from_admin" {
  count             = var.admin_cidr != "" ? 1 : 0
  type              = "ingress"
  from_port         = var.container_port
  to_port           = var.container_port
  protocol          = "tcp"
  security_group_id = aws_security_group.task.id
  cidr_blocks       = [var.admin_cidr]
  description       = "Temporary: direct verification of ${var.service_name} from admin machine"
}

# Task IPs change on every deployment, so services find each other through a
# private DNS record: <service_name>.<namespace>.
resource "aws_service_discovery_service" "this" {
  name          = var.service_name
  force_destroy = true

  dns_config {
    namespace_id   = var.service_discovery_namespace.id
    routing_policy = "MULTIVALUE"

    dns_records {
      type = "A"
      ttl  = 10
    }
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

resource "aws_ecs_task_definition" "this" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([
    {
      name      = var.service_name
      image     = "${aws_ecr_repository.this.repository_url}:${var.image_tag}"
      essential = true
      portMappings = [{
        containerPort = var.container_port
        protocol      = "tcp"
      }]
      environment = [for name, value in var.environment : { name = name, value = value }]
      secrets     = local.container_secrets
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = var.service_name
        }
      }
    }
  ])
}

resource "aws_ecs_service" "this" {
  name            = local.name
  cluster         = var.cluster_id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.this.arn
  }
}
