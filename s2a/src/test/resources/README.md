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

Generate self-signed ECDSA root cert

```
openssl ecparam -name prime256v1 -genkey -noout -out temp.pem
openssl pkcs8 -topk8 -in temp.pem -out root_key_ec.pem -nocrypt
rm temp.pem
openssl req -x509 -days 7305 -new -key root_key_ec.pem -nodes -out root_cert_ec.pem -config root_ec.cnf -extensions 'v3_req'
```

Generate a chain of ECDSA certs

```
openssl ecparam -name prime256v1 -genkey -noout -out temp.pem
openssl pkcs8 -topk8 -in temp.pem -out int_key2_ec.pem -nocrypt
rm temp.pem
openssl req -key int_key2_ec.pem -new -out temp.csr -config int_cert2.cnf
openssl x509 -req -days 7305 -in temp.csr -CA root_cert_ec.pem -CAkey root_key_ec.pem -CAcreateserial -out int_cert2_ec.pem -extfile int_cert2.cnf -extensions 'v3_req'


openssl ecparam -name prime256v1 -genkey -noout -out temp.pem
openssl pkcs8 -topk8 -in temp.pem -out int_key1_ec.pem -nocrypt
rm temp.pem
openssl req -key int_key1_ec.pem -new -out temp.csr -config int_cert1.cnf
openssl x509 -req -days 7305 -in temp.csr -CA int_cert2_ec.pem -CAkey int_key2_ec.pem -CAcreateserial -out int_cert1_ec.pem -extfile int_cert1.cnf -extensions 'v3_req'


openssl ecparam -name prime256v1 -genkey -noout -out temp.pem
openssl pkcs8 -topk8 -in temp.pem -out leaf_key_ec.pem -nocrypt
rm temp.pem
openssl req -key leaf_key_ec.pem -new -out temp.csr -config leaf.cnf
openssl x509 -req -days 7305 -in temp.csr -CA int_cert1_ec.pem -CAkey int_key1_ec.pem -CAcreateserial -out leaf_cert_ec.pem -extfile leaf.cnf -extensions 'v3_req'
```

```
cat leaf_cert_ec.pem int_cert1_ec.pem int_cert2_ec.pem > cert_chain_ec.pem
```