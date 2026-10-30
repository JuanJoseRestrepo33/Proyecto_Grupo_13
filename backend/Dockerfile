# Imagen del núcleo transaccional. El job `imagen` del CI verifica que construya.
FROM python:3.12-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /opt/solventa

COPY pyproject.toml ./
COPY app ./app
# Pólizas de prueba de staging; solo se cargan si POLIZAS_SEMILLA apunta al archivo (WEB-F05).
COPY semillas ./semillas

RUN pip install --no-cache-dir ".[gcs]" \
    && useradd --create-home --uid 10001 solventa
USER solventa

# El puerto lo decide la plataforma por la variable PORT (Cloud Run la inyecta). Sin ella, 8000.
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import os,urllib.request,sys; sys.exit(0 if urllib.request.urlopen(f\"http://127.0.0.1:{os.environ.get('PORT', '8000')}/salud\").status==200 else 1)"

CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
