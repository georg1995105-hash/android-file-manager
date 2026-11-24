"""CI-only TLS endpoint. Ephemeral keys and data; never deploy this fixture."""
import hashlib, ssl, sys
from server import Vault, Server

data, cert, key, code, port = sys.argv[1:]
vault = Vault(data, hashlib.sha256(code.encode()).hexdigest(), 128 * 1024 * 1024)
server = Server(("127.0.0.1", int(port)), vault)
tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
tls.load_cert_chain(cert, key)
server.socket = tls.wrap_socket(server.socket, server_side=True)
try: server.serve_forever()
finally: server.server_close(); vault.close()
