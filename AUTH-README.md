# Authentication

As outlined <a href="https://github.com/grpc/grpc-common/blob/master/grpc-auth-support.md">here</a> gRPC supports a number of different mechanisms for asserting identity between an client and server. We'll present some code-samples here demonstrating how to provide SSL/TLS support encryption and identity assertions as well as passing OAuth2 tokens to services that support it.

# Java 7, HTTP2 & Crypto

## Cipher-Suites
Java 7 does not support the <a href="https://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-9.2.2">the cipher suites recommended</a> by the HTTP2 specification. To address this we suggest servers use Java 8 where possible. If this is not possible it is possible to use other ciphers but you need to ensure that the services you intend to call have <a href="https://github.com/grpc/grpc/issues/681">allowed out-of-spec ciphers</a>. Android does not suffer from this issue as it uses OpenSSL instead of SSLEngine to provide these features.

## Protocol Negotiation (TLS-ALPN)
HTTP2 mandates the use of <a href="https://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg-05">ALPN</a> to negotiate the use of the protocol over SSL. No standard Java release has built-in support for ALPN today (<a href="https://bugs.openjdk.java.net/browse/JDK-8051498">there is a tracking issue</a> so go upvote it!) so we need to use the <a href="https://github.com/jetty-project/jetty-alpn">Jetty-ALPN</a> bootclasspath extension for OpenJDK to make it work.

```sh
java -Xbootclasspath/p:/path/to/jetty/alpn/extension.jar ...
```

Note that you must use the release of the Jetty-ALPN jar specific to the version of Java you are using.

An option is provided to use GRPC over plaintext without TLS which is convenient for testing environments but users must be aware of the secuirty risks of doing so for real production systems.


# Using OAuth2

The following code snippet shows how you can call the Google Cloud PubSub API using GRPC with a service account. The credentials are loaded from a key stored in a well-known location or by detecting that the application is running on AppEngine or Google Compute Engine. While this example is specific to Google and it's services similar patterns can be followed for other service providers.
```java
// Create a channel to the test service.
ChannelImpl channelImpl = NettyChannelBuilder.forAddress("pubsub.googleapis.com")
    .negotiationType(NegotiationType.TLS)
    .build();
// Get the default credentials from the environment
GoogleCredentials creds = GoogleCredentials.getApplicationDefault();
// Down-scope the credential to just the scopes required by the service
creds = creds.createScoped(Arrays.asList("https://www.googleapis.com/auth/pubsub"));
// Inercept the channel to bind the credential
OAuth2ClientInterceptor interceptor = new OAuth2ClientInterceptor(creds);
Channel channel = ClientInterceptors.intercept(channelImpl, interceptor);
// Create a stub using the channel that has the bound credential
PublisherGrpc.PublisherBlockingStub publisherStub = PublisherGrpc.newBlockingStub(channel);
publisherStub.publish(someMessage);
```


# Enabling TLS on a server

In this example the service owner provides a certificate chain and private key to create an SslContext. This is then bound the server which is started on a specific port, in this case 443 which is the standard SSL port. Note that the service implementation is also bound while creating the server.


```java
// Load certificate chain and key for SSL server.
SslContext sslContext = SslContext.newServerContext(certChainFile, privateKeyFile);
// Create a server, bound to port 443 and exposing a service implementation
ServerImpl server = NettyServerBuilder.forPort(443)
    .sslContext(sslContext)
    .addService(TestServiceGrpc.bindService(serviceImplmenetation))
    .build();
server.start();
```

If the issuing certificate authority for a server is not known to the client then a similar process should be followed on the client to load it so that it may validate the certificate issued to the server. If <a href="http://en.wikipedia.org/wiki/Transport_Layer_Security#Client-authenticated_TLS_handshake">mutual authentication</a> is desired this can also be supported by creating the appropraite SslContext.