namespace java io.thrift.examples.helloworld

struct HelloRequest {
	1:string name
}

struct HelloResponse {
	1:string message
}

service Greeter {
	HelloRequest sayHello(1:HelloRequest request);
}
