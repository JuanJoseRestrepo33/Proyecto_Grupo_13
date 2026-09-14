# EXP-03 | Infraestructura reproducible para comparar topologias.
#
#   terraform init
#   terraform apply -var-file=topologia-a.tfvars    # multi-zona, 1 region
#   terraform apply -var-file=topologia-b.tfvars    # + region de respaldo
#
# Costo estimado: ver Estimacion_Costos_GCP_Solventa.docx
#   Topologia A ~ USD 1.331/mes   Topologia B ~ USD 1.910/mes

terraform {
  required_version = ">= 1.6"
  required_providers {
    google = { source = "hashicorp/google", version = "~> 5.0" }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region_primaria
}

# ---------- Red ----------
resource "google_compute_network" "vpc" {
  name                    = "solventa-vpc"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subred_primaria" {
  name          = "solventa-subred-primaria"
  ip_cidr_range = "10.10.0.0/20"
  region        = var.region_primaria
  network       = google_compute_network.vpc.id
}

# ---------- Cluster primario (multi-zona) ----------
resource "google_container_cluster" "primario" {
  name     = "solventa-primario"
  location = var.region_primaria          # regional => nodos en varias zonas
  network    = google_compute_network.vpc.id
  subnetwork = google_compute_subnetwork.subred_primaria.id

  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = false
}

resource "google_container_node_pool" "primario_nodos" {
  name       = "pool-primario"
  cluster    = google_container_cluster.primario.id
  location   = var.region_primaria
  node_count = var.nodos_por_zona        # por zona

  node_config {
    machine_type = var.tipo_maquina
    disk_size_gb = 50
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }
  management { auto_repair = true, auto_upgrade = true }
}

# ---------- Base de datos ----------
resource "google_sql_database_instance" "principal" {
  name             = "solventa-db-principal"
  database_version = "POSTGRES_15"
  region           = var.region_primaria
  deletion_protection = false

  settings {
    tier              = var.tier_sql
    availability_type = "REGIONAL"       # HA multi-zona: sustenta RC-03
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true   # sustenta el RPO
      transaction_log_retention_days = 7
    }
    ip_configuration {
      ipv4_enabled    = false            # IP privada: evita el cargo de IP inactiva
      private_network = google_compute_network.vpc.id
    }
  }
}

# Replica en region de respaldo: solo en la topologia B
resource "google_sql_database_instance" "replica_respaldo" {
  count                = var.habilitar_region_respaldo ? 1 : 0
  name                 = "solventa-db-replica"
  database_version     = "POSTGRES_15"
  region               = var.region_respaldo
  master_instance_name = google_sql_database_instance.principal.name
  deletion_protection  = false

  replica_configuration { failover_target = false }
  settings {
    tier              = var.tier_sql
    availability_type = "ZONAL"
    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.vpc.id
    }
  }
}

# ---------- Cache ----------
resource "google_redis_instance" "cache_perfil" {
  name           = "solventa-cache-perfil"
  tier           = "STANDARD_HA"        # HA: sustenta RC-03
  memory_size_gb = var.cache_gb
  region         = var.region_primaria
  authorized_network = google_compute_network.vpc.id
  redis_version  = "REDIS_7_0"
}

# ---------- Canal de eventos (EXP-02) ----------
resource "google_pubsub_topic" "parametricos" { name = "siniestros-parametricos" }
resource "google_pubsub_topic" "dlq"          { name = "siniestros-dlq" }

resource "google_pubsub_subscription" "parametricos_sub" {
  name  = "siniestros-parametricos-sub"
  topic = google_pubsub_topic.parametricos.id

  ack_deadline_seconds = 60
  dead_letter_policy {
    dead_letter_topic     = google_pubsub_topic.dlq.id
    max_delivery_attempts = 5
  }
  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }
}

# ---------- Auditoria inmutable (EXP-04) ----------
resource "google_bigquery_dataset" "auditoria" {
  dataset_id = "solventa_auditoria"
  location   = var.region_primaria
}

resource "google_bigquery_table" "decisiones" {
  dataset_id = google_bigquery_dataset.auditoria.dataset_id
  table_id   = "decisiones"
  deletion_protection = true            # append-only: sustenta RC-08

  time_partitioning { type = "DAY", field = "ts" }
  schema = jsonencode([
    { name = "id_decision",       type = "STRING",    mode = "REQUIRED" },
    { name = "cliente",           type = "STRING",    mode = "REQUIRED" },
    { name = "version_regla",     type = "STRING",    mode = "REQUIRED" },
    { name = "variables_entrada", type = "JSON",      mode = "REQUIRED" },
    { name = "consentimiento_id", type = "STRING",    mode = "REQUIRED" },
    { name = "prima",             type = "NUMERIC",   mode = "REQUIRED" },
    { name = "ts",                type = "TIMESTAMP", mode = "REQUIRED" }
  ])
}

# ---------- Balanceo con verificacion de salud (EXP-03) ----------
resource "google_compute_health_check" "salud" {
  name                = "solventa-health"
  check_interval_sec  = var.intervalo_healthcheck  # variable del experimento
  timeout_sec         = 2
  healthy_threshold   = 2
  unhealthy_threshold = var.umbral_fallos          # variable del experimento
  http_health_check { port = 8080, request_path = "/health" }
}
