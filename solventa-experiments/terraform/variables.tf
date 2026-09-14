variable "project_id"       { type = string }
variable "region_primaria"  { type = string, default = "southamerica-east1" }
variable "region_respaldo"  { type = string, default = "us-east1" }
variable "tipo_maquina"     { type = string, default = "e2-medium" }
variable "nodos_por_zona"   { type = number, default = 2 }
variable "tier_sql"         { type = string, default = "db-custom-4-15360" }
variable "cache_gb"         { type = number, default = 5 }

variable "habilitar_region_respaldo" {
  type        = bool
  default     = false
  description = "false = Topologia A (multi-zona). true = Topologia B (multi-region)."
}

# Parametros bajo experimentacion en EXP-03.
# El prototipo de la semana 6 mostro que 1-2 s con umbral 3 detecta en 2-4 s.
variable "intervalo_healthcheck" { type = number, default = 2 }
variable "umbral_fallos"         { type = number, default = 3 }
