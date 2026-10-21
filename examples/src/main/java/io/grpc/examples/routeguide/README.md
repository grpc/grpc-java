gRPC Route Guide Example
=====================

This example illustrates how to implement and use a gRPC server and client for a RouteGuide service, 
which demonstrates all 4 types of gRPC methods (unary, client streaming, server streaming, and bidirectional streaming).
Additionally, the service loads geographic features from a JSON file [route_guide_db.json](https://github.com/grpc/grpc-java/blob/master/examples/src/main/resources/io/grpc/examples/routeguide/route_guide_db.json) and retrieves features based on latitude and longitude.

The route_guide.proto file defines a gRPC service with 4 types of RPC methods, showcasing different communication patterns between client and server.
1. Unary RPC
   - rpc GetFeature(Point) returns (Feature) {}
2. Server-Side Streaming RPC
    - rpc ListFeatures(Rectangle) returns (stream Feature) {}
3. Client-Side Streaming RPC
    - rpc RecordRoute(stream Point) returns (RouteSummary) {}  
4. Bidirectional Streaming RPC
    - rpc RouteChat(stream RouteNote) returns (stream RouteNote) {}    

These RPC methods illustrate the versatility of gRPC in handling various communication patterns, 
from simple request-response interactions to complex bidirectional streaming scenarios.

For more details, refer to the full route_guide.proto file on GitHub: https://github.com/grpc/grpc-java/blob/master/examples/src/main/proto/route_guide.proto

Refer the gRPC documentation for more details on creation, build and execution of route guide example with explanation
https://grpc.io/docs/languages/java/basics/