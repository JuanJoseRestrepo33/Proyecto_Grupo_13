"""Lo que comparten los dos routers: configuración, cliente del núcleo, errores y sesión.

Ningún módulo de aquí importa `app.web` ni `app.movil`: es lo que permite partir el servicio
en dos sin reescribir nada (ADR 0001).
"""
