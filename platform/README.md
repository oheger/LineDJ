# LineDJ Platform

A project offering basic functionality for applications running on the LineDJ
platform.

## Functionality

The main purpose of this project is to implement basic services and
infrastructure that can be used by applications. By providing a base class for
applications, it also kind of defines a programming model for such
applications. Applications are based on the
[JGUIraffe](http://jguiraffe.sourceforge.net/) library; this library allows the
creation of UI applications using concepts like XML-based UI definitions,
action and command objects, and dependency injection.

The classes and interfaces contained in the _Platform_ project can roughly be
divided into two parts:
* Providing basic functionality for the platform.
* Defining a framework for client applications running on the platform.

## Platform services

The platform offers a number of fundamental services available to all client
applications. The services are managed and started by the
`ClientManagementApplication` class. This is a non-visual _JGUIraffe_
application; it is the first application which starts on the platform. It then
performs the necessary initialization and creates an object of type
`ClientApplicationContext` serving as main entry point into platform
functionality. This object is registered as on OSGi services. Then client
applications depending on this service can start.

Via the client application context the following central services are
available:

### Actor system

The platform manages an actor system which can be used by client applications
to run non-blocking background tasks. Actually, all actions not directly
related to UI updates should be run in the background, and the actor system is
a good way to achieve this.

The actor system can also be used for interactions with the media archive, for
instance to request media files from the archive. In order to create new actors
and deploy them on the actor system, the `ClientApplicationContext` offers an
_actor factory_.

### Message bus

The message bus is a generic means for UI applications running on the platform
to communicate with each other. It offers a simple publish-subscribe model. One
speciality is that the bus is especially intended for UI-related communication;
therefore, all messages published via it are delivered in the UI thread.

The message bus is also used to deliver the responses of requests to the media
archive. An application can for instance query a list of media currently
available in the archive. The request is processed by actors communicating with
the archive (which may involve remote calls, depending on the way the archive
is connected). The response is then published on the message bus from where it
can be received by registered listeners.

Client applications typically register specialized listeners on the message bus
when they start up. The listeners are then notified about certain events or
when specific data becomes available. They can then react accordingly, e.g.
update the UI.

### Media facade

This object allows communication with the media archive. The interface is
rather low-level: The functionality provided by the archive is implemented by a
couple of actors. Via the facade messages can be sent to these actors. (As has
already been mentioned, the responses then typically arrive on the message
bus.) As this may not always be the most convenient form of interaction, there
are some extension mechanisms in place managing dedicated data. Those are
described later on.

### Central configuration

The client application context offers a way to query or store central
configuration. In addition, each client application can have its own specific
configuration. Access to configuration data is provided by the
[Apache Commons Configuration](http://commons.apache.org/proper/commons-configuration/)
library.

## Client applications

LineDJ compatible applications extend the
[ClientApplication](src/main/scala/de/oliver_heger/linedj/platform/app/ClientApplication.scala)
base class, which in turn is a _JGUIraffe_ application class. Client application
classes themselves are typically very simple because the whole functionality for
correctly startup the application and register required OSGi services is
provided by the base class. The application's UI is described in an XML-based
script.

The client application base class offers the following functionality available
for all client applications:

### Life-cycle management

The class implements the typical life-cycle hooks of a JGUIraffe
application to make sure that state related to the LineDJ platform gets
correctly initialized. This includes publishing some life-cycle
notifications on the UI message bus.

The application object itself is registered as an OSGi service. This is a
prerequisite for it to take part in the life-cycle management of the whole
platform.

### Providing access to central beans

Each client application has access to the central `ClientApplicationContext`
object; it is stored as a property in the application base class. During
startup, the service objects described in the previous section are also added
the application's bean context (the central configuration for dependency
injection), so that they are available for UI scripts and can be passed to
controller or event listener classes.

### Configuration management

Each client application can have its own configuration in which it can store
persistent data. The base name of the configuration must be provided as a
constructor argument to the super class; this class then ensures that a
corresponding configuration file is created in the user's home directory.
Further information about naming conventions and how they can be adapted using
system properties can be found at the
[ApplicationStartup](src/main/scala/de/oliver_heger/linedj/platform/app/ApplicationStartup.scala)
trait.

## Media Interface Extensions

Direct interactions with the media archive can be inconvenient due to the
low-level nature of the interface. In addition, it can be problematic if
multiple applications running on the platform send requests in parallel to the
archive and receive responses on the shared message bus. How can it be
determined which response is for which client? Another point is that multiple
client applications may request the same data from the media archive; should
data be fetched again (maybe via remote calls) that has already been retrieved
by the client platform?

To solve these problems, so-called _media interface extensions_ have been
introduced. The idea is that an extension manages specific data in a way global
for a client platform (i.e. the OSGi container hosting the platform and all
currently deployed client applications). Applications do not interact with the
media archive directly, but send a request to the corresponding extension which
also contains a callback for being notified when results become available or
state changes occur. This is referred to as a _consumer_ relation.

The extension is responsible for managing the data it controls: It will
typically send a request to the media archive when the first consumer request
is received. When the answer to the request arrives it is distributed to all
consumers currently registered. The extension is also free to cache the
response, so that new consumers requesting this data can be served instantly.

Extensions also keep track about the status of the archive: If it becomes
unavailable and available again later, or if a new scan for media data is
started, the data under its control is considered stale, and a new request is
sent (if still consumers are registered).

In the following the extensions available are shortly described. The source
code for extensions can be found in the [mediaifc.ext
package](src/main/scala/de/oliver_heger/linedj/platform/mediaifc/ext)

### Media Archive Available extension
 
 As the media archive can run on a remote machine, the connection can drop at
 any time. This extension permanently monitors the current state of the archive
 availability. Registered consumers receive an _archive available_ or _archive
 unavailable_ notification as soon as a change in state is detected.
 
 This is useful for applications that interact with the media archive. When the
 archive is currently not available this can be reflected in the UI of the
 application; for instance, menu items could be disabled.
 
 ### Media Archive State Listener extension
 
 With this extension update notifications about the state of the media archive
 can be received. The state of the archive contains statistics information
 about the data currently stored in the archive (such as the number of media or
 songs, the total playback duration, etc.).
 
 It is also possible that a scan for media is triggered. Then the archive
 re-inspects the folder structure with media files and updates itself. This may
 leed to changes in the list of media available.
 
 Applications that rely on this information - for instance a media browser
 application - can use this extension to keep track on changes in the amount of
 data managed by the archive.
 
 ### Available Media extension
 
 _Available media_ is a data structure listing IDs and some meta data about all
 media currently managed by the media archive. This is probably of interest for
 many applications running on the platform allowing the user to do something
 (browse, search, playback, ...) with media files. When a scan for media files
 runs and changes are detected this information is updated automatically and
 published to registered consumers. So this extension could be an alternative
 to the _Media Archive State Listener_ extension for applications only
 interested in media information.
 
 ### Meta Data Cache extension
 
 It is a frequent use case to display information about media and the songs
 they contain. From the media archive the content of a medium can be queried
 (there is even support for a listener registration if currently a scan is in
 progress and the information about the medium is updated). Querying a medium
 every time it is accessed by the user from the archive may be a waste of
 bandwidth, especially if the user only works on a subset of media. Therefore,
 this extension offers caching functionality on media.
 
 To use this extension, media information is not directly requested from the
 archive, but by sending a corresponding message on the message bus. The
 message is received by the cache extension, and it checks whether data for
 this medium is already contained in the cache. If so, an answer can be sent
 directly to the consumer; otherwise, a request to the media archive is
 created, and the consumer is given a response when this request is processed.
 
 The cache can be configured with a maximum number of entries. When this limit
 is reached media that have not been accessed recently are removed from the
 cache (the cache has LRU semantics).
 
 ## Configuration
 
 The following table lists the configuration options supported by the
 _Platform_ module:
 
 | Setting | Description |
 | ------- | ----------- |
 | media.cacheSize | The number of entries that can be stored in the _Meta Data Cache_ extension. When this limit is reached older media are removed from the cache. |
