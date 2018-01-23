COMPUTERNAME=mate
CLIENTCOMPUTERNAME=mate

# Certificate Authority
echo Generate CA key:
openssl genrsa -passout pass:1111 -des3 -out ca.key 4096
echo Generate CA certificate:
openssl req -passin pass:1111 -new -x509 -days 365 -key ca.key -out ca.crt -subj "/CN=localhost"

# Server side key
echo Generate server key:
openssl genrsa -passout pass:1111 -des3 -out server.key 4096
echo Generate server signing request:
openssl req -passin pass:1111 -new -key server.key -out server.csr -subj "/CN=localhost"
echo Self-signed server certificate:
openssl x509 -req -passin pass:1111 -days 365 -in server.csr -CA ca.crt -CAkey ca.key -set_serial 01 -out server.crt
echo Remove passphrase from server key:
openssl rsa -passin pass:1111 -in server.key -out server.key

# Client side key
echo Generate client key
openssl genrsa -passout pass:1111 -des3 -out client.key 4096
echo Generate client signing request:
openssl req -passin pass:1111 -new -key client.key -out client.csr -subj "/CN=localhost"
echo Self-signed client certificate:
openssl x509 -passin pass:1111 -req -days 365 -in client.csr -CA ca.crt -CAkey ca.key -set_serial 01 -out client.crt
echo Remove passphrase from client key:
openssl rsa -passin pass:1111 -in client.key -out client.key

openssl pkcs8 -topk8 -nocrypt -in client.key -out client.pem
openssl pkcs8 -topk8 -nocrypt -in server.key -out server.pem
