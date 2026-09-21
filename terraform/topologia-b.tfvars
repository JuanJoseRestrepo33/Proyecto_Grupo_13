# Topologia B: multi-zona + region de respaldo
project_id                = "si"
region_primaria           = "southamerica-east1"
region_respaldo           = "southamerica-west1"
habilitar_region_respaldo = true

# southamerica-east1-b reporto ZONE_RESOURCE_POOL_EXHAUSTED durante el
# despliegue; se trabaja con dos zonas.
sufijos_zona = ["a", "c"]