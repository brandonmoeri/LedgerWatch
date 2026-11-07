output "address" {
  value = aws_db_instance.main.address
}

output "port" {
  value = aws_db_instance.main.port
}

output "secret_arn" {
  description = "Secrets Manager secret holding JSON keys url, username and password"
  value       = aws_secretsmanager_secret.db.arn
}

output "password" {
  value     = random_password.db.result
  sensitive = true
}
