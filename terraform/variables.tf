variable "project_id" {
  type        = string
  description = "ID del proyecto de Google Cloud"
}

variable "region_primaria" {
  type    = string
  default = "southamerica-east1"
}

variable "region_respaldo" {
  type    = string
  default = "us-east1"
}

variable "sufijos_zona" {
  type        = list(string)
  default     = ["a", "b", "c"]
  description = "Zonas de la region primaria (verificar las disponibles en la region)"
}

variable "tipo_maquina" {
  type    = string
  default = "e2-medium"
}

variable "nodos_por_zona" {
  type        = number
  default     = 1
  description = "1 por zona = 3 nodos (6 vCPU): cabe en la cuota de una cuenta nueva"
}

variable "nodos_respaldo" {
  type    = number
  default = 1
}

variable "tier_sql" {
  type        = string
  default     = "db-custom-2-7680"
  description = "Tamano de experimento. La estimacion de costos asumio db-custom-4-15360"
}

variable "cache_gb" {
  type    = number
  default = 1
}

variable "db_password_admin" {
  type      = string
  sensitive = true
}

variable "habilitar_region_respaldo" {
  type        = bool
  default     = false
  description = "false = Topologia A (multi-zona). true = Topologia B (multi-region)"
}
