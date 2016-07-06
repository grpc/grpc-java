Flow Control Simulator
==============================================

Bare bones version of a simulator for flow control. Currently attempts to simulate default http2 flow control. A Simulation consists of a clock, at least one connection and at least one stream. Streams and connections implement the ClockListener interface and advance their state when the clock ticks. Right now, actions can be added on ticks, for example an action could be added to have each stream update its window. The next step is to be able to add actions on receipt of a data frame or header frame. 
 
## Interfaces
Clock Listener 

## Classes 
Simulation

Simulation Clock

Connection

Stream
