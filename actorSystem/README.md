# Actor System

This module is responsible for the creation of the central actor system used by
the LineDJ platform modules.

## Description

Many modules of the LineDJ platform make use of an _actor system_ to implement
their functionality; most tasks that require concurrency and multi-threading
are executed by actors. The actor system can be obtained from the central
`ClientApplicationContext` and thus is available to all platform modules.

The _ActorSystem_ module creates the actor system and registers it as a service
in the OSGi registry. This is a precondition for the platform to start up
successfully.

## Configuration

A couple of configuration options are supported to customize the actor system.
These options have to be set as system properties. (Configuration files are
read by the platform module, but this module can only start after the actor
system service is available; therefore, a more basic configuration mechanism
has to be used.)

The options mainly control the remoting subsystem of Akka (refer to the
[Remoting documentation](http://doc.akka.io/docs/akka/2.4/scala/remoting.html)).
The table below lists the system properties that are evaluated:

| Setting | Description | Default value |
| ------- | ----------- | ------------- |
| akka.remote.netty.tcp.hostname | The host name to be used by Akka remoting. This overrides the corresponding property from the Akka remoting configuration. | (none) |
| akka.remote.netty.tcp.port | The port on which the remoting subsystem listens. This overrides the corresponding property from the Akka remoting configuration. | 0 |
| LineDJ_ActorSystemName | The name to be set for the actor system. | LineDJ_PlatformActorSystem |

_Notes:_

The default values are typically appropriate for a LineDJ application acting as
client of the media archive. They caue the remoting subsystem to bind to the
local IP address under a random port.

For the media archive itself, at least a port number should be defined; so that
client applications have a defined address to connect to. If the machine the
archive runs on has multiple network interfaces, the IP address or the host
name to be used should be specified in the _hostname_ property. The best way to
do this is probably a small shell script starting up the OSGi container with
corresponding system properties. Below is an example script that starts an
[Apache Felix](http://felix.apache.org/) container and sets the properties for
host name and port:

```
java -Dakka.remote.netty.tcp.hostname=ArchiveHost -Dakka.remote.netty.tcp.port=1234 \
  -jar bin/felix.jar
```
