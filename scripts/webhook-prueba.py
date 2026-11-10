"""Receptor temporal para probar alertas sin publicar en un canal externo."""
from http.server import BaseHTTPRequestHandler, HTTPServer


class Webhook(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'OK')

    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', '0')))
        # No imprimir el contenido recibido ni credenciales del emisor.
        print('Notificación de prueba recibida', flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    HTTPServer(('0.0.0.0', 8080), Webhook).serve_forever()
