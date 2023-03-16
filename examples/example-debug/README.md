# gRPC Debug Example

The debug example uses a Hello World style server whose response includes its
hostname. It demonstrates usage of the AdminInterface and the grpcdebug
commandline tool.

The example requires grpc-java to already be built. You are strongly encouraged
to check out a git release tag, since there will already be a build of grpc
available. Otherwise, you must follow [COMPILING](../../COMPILING.md).

### Build the example

1. Optional:  Build the hello-world-debug example client.
   See [the examples README](../README.md)

2. Build the debuggable server and client. From the
   `grpc-java/examples/examples-debug` directory run:

```bash
$ ../gradlew installDist
```

This creates the
scripts `build/install/debug/bin/hostname-debuggable-server/bin/hostname-debuggable-server`
that
runs the example.

To run the debug example, run:

```bash
$ ./build/install/debug/bin/hostname-debuggable-server/bin/hostname-debuggable-server
```

And in a different terminal window run the client.

Note:  You can use the standard hello-world client with no debugging enabled and
still see results on the server. However, if you want to get debug information
about the client you need to run the hello-world-debuggable client.

Simple client

```bash
$ ../build/install/examples/bin/hello-world-client
```

debug enabled client

```bash
$ ./build/install/examples-debug/bin/hello-world-debuggable-client
```

### Maven

If you prefer to use Maven:

1. Build the hello-world example client. See [the examples README](../README.md)

2. Run in this directory:

```bash
$ mvn verify
$ # Run the server (from the examples-debug directory)
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.debug.HostnameServer
$ # In another terminal run the client (from the examples directory)
$ cd ..
$ mvn exec:java -Dexec.mainClass=io.grpc.examples.helloworld.HelloWorldClient
```

## Using grpcdebug

grpcdebug is a tool that has been created to access the metrics from the
channelz and health services.

### Installing the grpcdebug tool

The source code is located in a github project
[grpc-ecosystem/grpcdebug](https://github.com/grpc-ecosystem/grpcdebug). You
can either download [the latest built version]
(https://github.com/grpc-ecosystem/grpcdebug/releases/latest) (recommended) or
follow the README.md to build it yourself.

### Running the grpcdebug tool
#### Usage
`grpcdebug <target address> [flags] channelz <command> [argument]`


| Command    |       Argument       | Description                                       |
|:-----------|:--------------------:|:--------------------------------------------------|
| channel    | \<channel id or URL> | Display channel states in a human readable way.   |
| channels   |                      | Lists client channels for the target application. |
| server     |     \<server ID>     | Displays server state in a human readable way.    |
| servers    |                      | Lists servers in a human readable way.            |
| socket     |     \<socket ID>     | Displays socket states in a human readable way.   |
| subchannel |        \<id>         | Display subchannel states in human readable way.  |

Generally, you will start with either `servers` or `channels` and then work down
to the details
<br><br>

#### Getting overall server info
```bash
bin/grpcdebug/grpcdebug localhost:50051 channelz servers
```
This will show you the server ids with their activity
```text
Server ID   Listen Addresses   Calls(Started/Succeeded/Failed)   Last Call Started   
2           [[::]:50051]       38/34/3                           now                 
```
<br>

#### Getting details for a service
```bash
bin/grpcdebug/grpcdebug localhost:50051 channelz server 2
```

The output will include more communication details and will show socket ids for
currently connected clients

```text
Server Id:           2              
Listen Addresses:    [[::]:50051]   
Calls Started:       33             
Calls Succeeded:     29             
Calls Failed:        3              
Last Call Started:   now
---
Socket ID   Local->Remote              Streams(Started/Succeeded/Failed)   Messages(Sent/Received)   
19          [::1]:50051->[::1]:39834   4/3/0                               3/4
```

#### Displaying detailed info for a server side connection (socket)

```bash
bin/grpcdebug/grpcdebug localhost:50051 channelz socket 19
```

This will show a lot of gRPC internal information

```text
Socket ID:                       19                         
Address:                         [::1]:50051->[::1]:50094   
Streams Started:                 1                          
Streams Succeeded:               0                          
Streams Failed:                  0                          
Messages Sent:                   0                          
Messages Received:               1                          
Keep Alives Sent:                0                          
Last Local Stream Created:                                  
Last Remote Stream Created:      now                        
Last Message Sent Created:                                  
Last Message Received Created:   now                        
Local Flow Control Window:       65535                      
Remote Flow Control Window:      1048569
---
Socket Options Name                                                                Value                                                                         
SO_LINGER                                                                          [type.googleapis.com/grpc.channelz.v1.SocketOptionLinger]:{}                  
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#TCP_CORK            false                                                                         
WRITE_BUFFER_HIGH_WATER_MARK                                                       65536                                                                         
WRITE_BUFFER_LOW_WATER_MARK                                                        32768                                                                         
IP_TOS                                                                             0                                                                             
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#TCP_KEEPCNT         9                                                                             
SINGLE_EVENTEXECUTOR_PER_GROUP                                                     true                                                                          
SO_SNDBUF                                                                          2626560                                                                       
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#TCP_NOTSENT_LOWAT   0                                                                             
WRITE_BUFFER_WATER_MARK                                                            WriteBufferWaterMark(low: 32768, high: 65536)                                 
TCP_NODELAY                                                                        true                                                                          
SO_RCVBUF                                                                          131072                                                                        
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#SO_BUSY_POLL        0                                                                             
IP_TRANSPARENT                                                                     false                                                                         
SO_KEEPALIVE                                                                       true                                                                          
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#TCP_QUICKACK        false                                                                         
ALLOCATOR                                                                          PooledByteBufAllocator(directByDefault: true)                                 
TCP_FASTOPEN_CONNECT                                                               false                                                                         
MESSAGE_SIZE_ESTIMATOR                                                             io.grpc.netty.shaded.io.netty.channel.DefaultMessageSizeEstimator@48d475b6    
WRITE_SPIN_COUNT                                                                   16                                                                            
SO_REUSEADDR                                                                       true                                                                          
CONNECT_TIMEOUT_MILLIS                                                             30000                                                                         
ALLOW_HALF_CLOSURE                                                                 false                                                                         
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#EPOLL_MODE          EDGE_TRIGGERED                                                                
MAX_MESSAGES_PER_READ                                                              16                                                                            
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#TCP_KEEPIDLE        7200                                                                          
AUTO_CLOSE                                                                         true                                                                          
io.grpc.netty.shaded.io.netty.channel.epoll.EpollChannelOption#TCP_KEEPINTVL       75                                                                            
MAX_MESSAGES_PER_WRITE                                                             2147483647                                                                    
AUTO_READ                                                                          true                                                                          
TCP_MD5SIG                                                                         null                                                                          
RCVBUF_ALLOCATOR                                                                   io.grpc.netty.shaded.io.netty.channel.AdaptiveRecvByteBufAllocator@360691a0   
```
#### Displaying the list of gRPC client channels
Command
```bash
bin/grpcdebug/grpcdebug localhost:50051 channelz channels
```
Output
```text
Channel ID   Target            State     Calls(Started/Succeeded/Failed)   Created Time   
1            localhost:50051   READY     34/34/0                                          
3            localhost:50051   READY     16/16/0
```
Note:  If you have a simple server that doesn't use gRPC clients to contact other
servers, then this table will be empty.

#### Displaying details of a gRPC client channel
Command
```bash
bin/grpcdebug/grpcdebug localhost:50051 channelz channel 3
```
Output
```text   
Channel ID:        3                 
Target:            localhost:50051   
State:             READY             
Calls Started:     16                
Calls Succeeded:   16                
Calls Failed:      0                 
Created Time:                        
---
Subchannel ID   Target                                               State     Calls(Started/Succeeded/Failed)   CreatedTime   
10              [[[localhost/127.0.0.1:50051]/{}], [[localhost/0:0   READY     16/16/0                                                                                
```

#### Displaying details of a gRPC client subchannel
Command
```bash
bin/grpcdebug/grpcdebug localhost:50051 channelz subchannel 10
```
Output
```text
Subchannel ID:     10                                                                           
Target:            [[[localhost/127.0.0.1:50051]/{}], [[localhost/0:0:0:0:0:0:0:1:50051]/{}]]   
State:             READY                                                                        
Calls Started:     16                                                                           
Calls Succeeded:   16                                                                           
Calls Failed:      0                                                                            
Created Time:                                                                                   
---
Socket ID   Local->Remote                      Streams(Started/Succeeded/Failed)   Messages(Sent/Received)   
11          127.0.0.1:48536->127.0.0.1:50051   16/16/0                             12/12                     
```