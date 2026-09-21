output "cluster_primario" {
  value = google_container_cluster.primario.name
}

output "cluster_respaldo" {
  value = var.habilitar_region_respaldo ? google_container_cluster.respaldo[0].name : "no desplegado"
}

output "zonas_con_pool" {
  value       = local.zonas
  description = "Grupos de nodos que la prueba de caos puede derribar"
}

output "db_ip_privada" {
  value = google_sql_database_instance.principal.private_ip_address
}

output "redis_host" {
  value = google_redis_instance.cache.host
}

output "registro_imagenes" {
  value = "${var.region_primaria}-docker.pkg.dev/${var.project_id}/solventa"
}

output "redis_host_respaldo" {
  value = var.habilitar_region_respaldo ? google_redis_instance.cache_respaldo[0].host : "no desplegado"
}

output "db_ip_replica" {
  value = var.habilitar_region_respaldo ? google_sql_database_instance.replica[0].private_ip_address : "no desplegado"
}
