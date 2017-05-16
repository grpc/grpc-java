Flow Control Simulator
==============================================

Bare bones version of a simulator for flow control. Currently attempts to simulate default http2 flow control. A Simulation consists of a clock, at least one connection and at least one stream. Streams and connections implement the ClockListener interface and advance their state when the clock ticks. Actions can be added on tick, headers, or messages by overriding those methods in connection or stream. 
 
## Interfaces
Clock Listener 
Visitor

## Classes 
Simulation
Simulation Clock
Connection
Stream
Client
Application

