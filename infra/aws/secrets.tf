resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.project}/account-service/db"
  recovery_window_in_days = 0

  tags = {
    Name = "${var.project}-account-service-db"
  }
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = aws_db_instance.main.username
    password = random_password.db.result
    url      = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}"
  })
}
