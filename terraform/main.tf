# ============================================================================
# Solventa | Infraestructura de los experimentos en Google Cloud
#
#   terraform init
#   terraform apply -var-file=topologia-a.tfvars   # multi-zona, una region
#   terraform apply -var-file=topologia-b.tfvars   # agrega region de respaldo
#   terraform destroy -var-file=topologia-b.tfvars # AL TERMINAR CADA CORRIDA
#
# Tamano por defecto: reducido para experimentos y para caber en la cuota de
# una cuenta nueva (~8 vCPU, 250 GB de SSD por region). Para replicar la
# estimacion de costos de produccion, subir tier_sql y nodos_por_zona.
# ============================================================================

terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.40"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region_primaria
}

data "google_project" "actual" {}

locals {
  zonas = [for z in var.sufijos_zona : "${var.region_primaria}-${z}"]
  apis = [
    "compute.googleapis.com",
    "container.googleapis.com",
    "sqladmin.googleapis.com",
    "redis.googleapis.com",
    "pubsub.googleapis.com",
    "servicenetworking.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
  ]
  pubsub_agent = "serviceAccount:service-${data.google_project.actual.number}@gcp-sa-pubsub.iam.gserviceaccount.com"
}

# ---------- APIs ----------
resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.key
  disable_on_destroy = false
}

# ---------- Red ----------
resource "google_compute_network" "vpc" {
  name                    = "solventa-vpc"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.apis]
}

resource "google_compute_subnetwork" "primaria" {
  name          = "solventa-primaria"
  region        = var.region_primaria
  network       = google_compute_network.vpc.id
  ip_cidr_range = "10.10.0.0/20"

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.20.0.0/16"
  }
  secondary_ip_range {
    range_name    = "servicios"
    ip_cidr_range = "10.30.0.0/20"
  }
}

resource "google_compute_subnetwork" "respaldo" {
  count         = var.habilitar_region_respaldo ? 1 : 0
  name          = "solventa-respaldo"
  region        = var.region_respaldo
  network       = google_compute_network.vpc.id
  ip_cidr_range = "10.40.0.0/20"

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.50.0.0/16"
  }
  secondary_ip_range {
    range_name    = "servicios"
    ip_cidr_range = "10.60.0.0/20"
  }
}

# Acceso privado a servicios: requerido para Cloud SQL con IP privada.
resource "google_compute_global_address" "servicios_privados" {
  name          = "solventa-servicios-privados"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "privada" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.servicios_privados.name]
}

# ---------- Kubernetes: cluster regional con un grupo de nodos POR ZONA ----------
# Un grupo por zona permite a la prueba de caos derribar exactamente una zona.
resource "google_container_cluster" "primario" {
  name                     = "solventa-primario"
  location                 = var.region_primaria
  node_locations           = local.zonas
  network                  = google_compute_network.vpc.id
  subnetwork               = google_compute_subnetwork.primaria.id
  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = false

  # El pool por defecto se crea (1 nodo por zona) antes de borrarse. Con el
  # disco por defecto (100 GB SSD) excede la cuota SSD_TOTAL_GB de 250 GB;
  # con disco estandar pequeno no la consume. node_locations limita las
  # zonas a las que tienen capacidad disponible.
  node_config {
    disk_size_gb = 20
    disk_type    = var.tipo_disco
  }

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "servicios"
  }
}

resource "google_container_node_pool" "por_zona" {
  for_each       = toset(local.zonas)
  name           = "pool-${each.key}"
  cluster        = google_container_cluster.primario.id
  location       = var.region_primaria
  node_locations = [each.key]
  node_count     = var.nodos_por_zona

  node_config {
    machine_type = var.tipo_maquina
    disk_size_gb = 30
    disk_type    = var.tipo_disco
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
    labels = {
      zona = each.key
    }
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }
}

resource "google_container_cluster" "respaldo" {
  count                    = var.habilitar_region_respaldo ? 1 : 0
  name                     = "solventa-respaldo"
  location                 = "${var.region_respaldo}-${var.sufijos_zona[0]}"
  network                  = google_compute_network.vpc.id
  subnetwork               = google_compute_subnetwork.respaldo[0].id
  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = false

  node_config {
    disk_size_gb = 20
    disk_type    = var.tipo_disco
  }

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "servicios"
  }
}

resource "google_container_node_pool" "respaldo" {
  count      = var.habilitar_region_respaldo ? 1 : 0
  name       = "pool-respaldo"
  cluster    = google_container_cluster.respaldo[0].id
  location   = "${var.region_respaldo}-${var.sufijos_zona[0]}"
  node_count = var.nodos_respaldo

  node_config {
    machine_type = var.tipo_maquina
    disk_size_gb = 30
    disk_type    = var.tipo_disco
    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }
}

# ---------- Base de datos ----------
resource "google_sql_database_instance" "principal" {
  name                = "solventa-db-principal"
  database_version    = "POSTGRES_15"
  region              = var.region_primaria
  deletion_protection = false
  depends_on          = [google_service_networking_connection.privada]

  settings {
    tier              = var.tier_sql
    availability_type = "REGIONAL"   # HA entre zonas: sustenta RC-03

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
    }

    ip_configuration {
      ipv4_enabled    = false        # IP privada: sin cargo por IP inactiva
      private_network = google_compute_network.vpc.id
    }
  }
}

resource "google_sql_database" "solventa" {
  name     = "solventa"
  instance = google_sql_database_instance.principal.name
}

resource "google_sql_user" "admin" {
  name     = "solventa_admin"
  instance = google_sql_database_instance.principal.name
  password = var.db_password_admin
}

resource "google_sql_database_instance" "replica" {
  count                = var.habilitar_region_respaldo ? 1 : 0
  name                 = "solventa-db-replica"
  database_version     = "POSTGRES_15"
  region               = var.region_respaldo
  master_instance_name = google_sql_database_instance.principal.name
  deletion_protection  = false

  replica_configuration {
    failover_target = false
  }

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
resource "google_redis_instance" "cache" {
  name               = "solventa-cache"
  tier               = "STANDARD_HA"
  memory_size_gb     = var.cache_gb
  region             = var.region_primaria
  authorized_network = google_compute_network.vpc.id
  redis_version      = "REDIS_7_0"
  depends_on         = [google_project_service.apis]
}

# Cache en la region de respaldo. Sin ella, tras una caida de region la
# cotizacion no tiene cache y el registro de revocaciones se pierde
# (ver "Hallazgo de diseno" en el README).
resource "google_redis_instance" "cache_respaldo" {
  count              = var.habilitar_region_respaldo ? 1 : 0
  name               = "solventa-cache-respaldo"
  tier               = "BASIC"
  memory_size_gb     = var.cache_gb
  region             = var.region_respaldo
  authorized_network = google_compute_network.vpc.id
  redis_version      = "REDIS_7_0"
  depends_on         = [google_project_service.apis]
}

# ---------- Pub/Sub ----------
resource "google_pubsub_topic" "parametricos" {
  name       = "siniestros-parametricos"
  depends_on = [google_project_service.apis]
}

resource "google_pubsub_topic" "dlq" {
  name       = "siniestros-dlq"
  depends_on = [google_project_service.apis]
}

resource "google_pubsub_topic" "revocacion" {
  name       = "consentimiento-revocado"
  depends_on = [google_project_service.apis]
}

resource "google_pubsub_subscription" "parametricos" {
  name                 = "siniestros-parametricos-sub"
  topic                = google_pubsub_topic.parametricos.id
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

resource "google_pubsub_subscription" "revocacion_perfilamiento" {
  name                 = "consentimiento-revocado-perfilamiento"
  topic                = google_pubsub_topic.revocacion.id
  ack_deadline_seconds = 30
}

# La cola de mensajes fallidos solo funciona si el agente de Pub/Sub puede
# publicar en el topico DLQ y confirmar en la suscripcion de origen.
resource "google_pubsub_topic_iam_member" "dlq_publicador" {
  topic  = google_pubsub_topic.dlq.id
  role   = "roles/pubsub.publisher"
  member = local.pubsub_agent
}

resource "google_pubsub_subscription_iam_member" "dlq_suscriptor" {
  subscription = google_pubsub_subscription.parametricos.id
  role         = "roles/pubsub.subscriber"
  member       = local.pubsub_agent
}

# ---------- Registro de imagenes ----------
resource "google_artifact_registry_repository" "solventa" {
  location      = var.region_primaria
  repository_id = "solventa"
  format        = "DOCKER"
  depends_on    = [google_project_service.apis]
}