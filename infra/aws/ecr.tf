resource "aws_ecr_repository" "account_service" {
  name                 = "${var.project}/account-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true

  tags = {
    Name = "${var.project}-account-service"
  }
}
