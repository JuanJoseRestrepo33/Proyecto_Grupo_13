output "endpoint_primaria" {
  value = "http://${aws_instance.primaria.public_ip}:${var.puerto_app}"
}

output "endpoint_respaldo" {
  value = "http://${aws_instance.respaldo.public_ip}:${var.puerto_app}"
}
