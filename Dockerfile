# Una sola imagen para todos los servicios y scripts de los experimentos.
# Cada servicio cambia el comando de arranque (ver docker-compose.yml y k8s/).
FROM python:3.12-slim
WORKDIR /app
COPY servicios/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY servicios/ servicios/
COPY exp02/ exp02/
COPY exp03/ exp03/
COPY exp04/ exp04/
COPY db/ db/
ENV PYTHONUNBUFFERED=1
WORKDIR /app/servicios
CMD ["uvicorn", "cotizacion_service:app", "--host", "0.0.0.0", "--port", "8080"]
