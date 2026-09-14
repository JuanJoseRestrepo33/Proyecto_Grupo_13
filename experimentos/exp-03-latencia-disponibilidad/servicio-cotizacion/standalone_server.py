import json
import os
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ZONA = os.environ.get("ZONA", "desconocida")


class Handler(BaseHTTPRequestHandler):
    def _json(self, status, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._json(200, {"status": "ok", "zona": ZONA, "hostname": socket.gethostname()})
            return
        if parsed.path == "/cotizacion":
            qs = parse_qs(parsed.query)
            cliente = qs.get("cliente", ["cliente-demo"])[0]
            ramo = qs.get("ramo", ["hogar"])[0]
            prima = 45000 + (hash(cliente + ramo) % 15000)
            self._json(200, {
                "cliente": cliente,
                "ramo": ramo,
                "prima": prima,
                "zona_atendida": ZONA,
                "servido_en": time.time(),
            })
            return
        self._json(404, {"detail": "not found"})

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8000"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    server.serve_forever()
