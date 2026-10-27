output "instance_id" {
  value = aws_instance.host.id
}

output "vpc_id" {
  value = aws_vpc.staging.id
}

output "private_ip" {
  value = aws_instance.host.private_ip
}

output "ssm_session_command" {
  value = "aws ssm start-session --region ${var.aws_region} --target ${aws_instance.host.id}"
}

output "kubernetes_port_forward_command" {
  value = "aws ssm start-session --region ${var.aws_region} --target ${aws_instance.host.id} --document-name AWS-StartPortForwardingSession --parameters '{\"portNumber\":[\"6443\"],\"localPortNumber\":[\"16443\"]}'"
}
