from BaseHTTPServer import BaseHTTPRequestHandler, HTTPServer
class Handler(BaseHTTPRequestHandler):
    def _set_headers(self):
        self.send_response(200)
        self.end_headers()

    def do_POST(self):
        self._set_headers()
        return

server_address = ('', 4567)
httpd = HTTPServer(server_address, Handler)
print "Starting server"
httpd.serve_forever()
