resource "aws_ecs_cluster" "main" {
  name = "${var.project}-cluster"
}

resource "aws_cloudwatch_log_group" "account_service" {
  name              = "/ecs/${var.project}-account-service"
  retention_in_days = 3
}

resource "aws_iam_role" "ecs_task_execution" {
  name = "${var.project}-ecs-task-execution"

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

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_task_execution_secrets" {
  name = "${var.project}-ecs-read-db-secret"
  role = aws_iam_role.ecs_task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = aws_secretsmanager_secret.db.arn
    }]
  })
}

resource "aws_ecs_task_definition" "account_service" {
  family                   = "${var.project}-account-service"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_task_execution.arn

  container_definitions = jsonencode([
    {
      name      = "account-service"
      image     = "${aws_ecr_repository.account_service.repository_url}:latest"
      essential = true
      portMappings = [{
        containerPort = 8081
        protocol      = "tcp"
      }]
      secrets = [
        { name = "DB_URL", valueFrom = "${aws_secretsmanager_secret.db.arn}:url::" },
        { name = "DB_USERNAME", valueFrom = "${aws_secretsmanager_secret.db.arn}:username::" },
        { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.db.arn}:password::" },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.account_service.name
          "awslogs-region"        = var.aws_region
          "awslogs-stream-prefix" = "account-service"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "account_service" {
  name            = "${var.project}-account-service"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.account_service.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.ecs_task.id]
    assign_public_ip = true
  }
}
