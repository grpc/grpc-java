# Generating certificates and keys for testing mTLS-S2A

Content from: https://github.com/google/s2a-go/blob/main/testdata/README.md

Create root CA

```
openssl req -x509 -sha256 -days 7305 -newkey rsa:2048 -keyout root_key.pem -out
root_cert.pem
```

Generate private keys for server and client

```
openssl genrsa -out server_key.pem 2048
openssl genrsa -out client_key.pem 2048
```

Generate CSRs for server and client (set Common Name to localhost, leave all
other fields blank)

```
openssl req -key server_key.pem -new -out server.csr -config config.cnf
openssl req -key client_key.pem -new -out client.csr -config config.cnf
```

Sign CSRs for server and client

```
openssl x509 -req -CA root_cert.pem -CAkey root_key.pem -in server.csr -out server_cert.pem -days 7305 -extfile config.cnf -extensions req_ext
openssl x509 -req -CA root_cert.pem -CAkey root_key.pem -in client.csr -out client_cert.pem -days 7305
```