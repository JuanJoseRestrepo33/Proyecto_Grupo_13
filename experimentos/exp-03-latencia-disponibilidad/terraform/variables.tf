variable "region_primaria" {
  description = "Region AWS candidata a primaria (mas cercana a Colombia)"
  type        = string
  default     = "us-east-1"
}

variable "region_respaldo" {
  description = "Region AWS candidata a respaldo, usada como referencia de comparacion"
  type        = string
  default     = "sa-east-1"
}

variable "instance_type" {
  description = "Tipo de instancia EC2 (elegible para free tier en la mayoria de cuentas)"
  type        = string
  default     = "t3.micro"
}

variable "puerto_app" {
  description = "Puerto donde escucha el stub del servicio de cotizacion"
  type        = number
  default     = 8000
}
