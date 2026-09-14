FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY cotizacion_service.py .
CMD ["uvicorn","cotizacion_service:app","--host","0.0.0.0","--port","8080"]
