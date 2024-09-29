# LineDJ Audio Platform

This project provides specific functionality related to playing audio on top
of the basic [LineDJ Platform](../platform) project.

## Functionality

While the LineDJ Platform project contains base classes for creating
general-purpose applications using the OSGi-based LineDJ programming model,
the _Audio Platform_ project is rather concerned with applications in the
domain of audio playback. It offers the following functionality:
* An integration of the audio player engine (from the _playerEngine_ module).
  The pretty low-level API of the player engine has been wrapped to fit into
  the event-based programming model of LineDJ applications.
* Support for playlists. There are classes modelling playlists and services to
  create and manipulate them. A current playlist for the audio player managed
  by the platform is maintained.
* Support for metadata resolving for songs in the current playlist. A playlist
  only contains references to the songs to be played. An application that wants
  to display the current playlist also needs the metadata available for the
  songs, such as artists, titles, containing albums, etc. There is a service
  that obtains the metadata automatically from the archive and sends
  notifications when new information is available.
* Special events for state changes of the managed audio player. They can be
  evaluated for instance by applications implementing a UI for an audio player.

In the following sections, this functionality is described in more details.

### Model classes for songs and playlists

The _Audio Platform_ module contains classes that represent songs and 
playlists. They can be found in the packages
[model](src/main/scala/de/oliver_heger/linedj/platform/audio/model) and
[playlist](src/main/scala/de/oliver_heger/linedj/platform/audio/playlist)
respective. A song is defined as an object consisting of an ID (that has all
the information required to fetch the associated media file from a media
archive) and metadata. The metadata corresponds to the data managed by the
media archive for each song. Some frequently used properties like _title_,
_artist_, and _album_ are available as bean properties. This is especially
useful for UI applications that want to display songs in table views; here the
properties can be referenced directly from the objects in the table model.

A playlist is defined as two lists with song IDs. One list contains the songs
that are pending for playback; in the other one the songs are stored that have
already been played. That way some kind of history information is kept.

The `PlaylistService` trait offers functions to create playlist objects, query
their properties, and manipulate them.

### Playlist metadata resolution

In order to display a playlist to the end user, the plain song IDs have to be
replaced by meaningful metadata. As this is a typical use case for audio
applications, the _Audio Platform_ handles metadata resolution automatically
in background: Whenever new songs are added to the current playlist, their 
metadata is requested automatically from the media archive. Clients interested in 
this metadata can register themselves as consumers for `PlaylistMetaData`
objects. The corresponding classes are defined in the
[PlaylistMetaData](src/main/scala/de/oliver_heger/linedj/platform/audio/playlist/PlaylistMetaData.scala)
file.

A consumer has to publish a `PlaylistMetaDataRegistration` message on the
system message bus that contains a reference to a consumer function. Whenever
new metadata becomes available, the consumer function is invoked (in the UI
thread) with the complete metadata fetched so far.

Metadata is fetched chunk-wise; so UI applications have to determine which 
metadata is available currently and update their display accordingly. This use
case is supported by the
[PlaylistMetaDataService](src/main/scala/de/oliver_heger/linedj/platform/audio/playlist/PlaylistMetaDataService.scala).
The service offers methods to translate incoming updates of metadata to
operations for typical UI applications to update their visual representation.
The service assumes that the application holds songs in an indexed
collection serving as table model. It returns information about the indices in
this collection that have to be updated to reflect the metadata newly 
available. The [Audio Player UI](../audioPlayerUI) application is an example of
using this service.

### Event-based interface of an audio player

In order to allow interactions with the managed audio player component, the
_Audio Platform_ module registers a special listener on the system message bus
that reacts on audio player commands. So, rather than obtaining a reference to
an audio player and invoking methods on it, command objects are published on
the message bus. Such commands cause updates in the state of the audio player,
which are in turn propagated via the bus. This programming model emphasizes the
asynchronous nature of such interactions: a client fires a command and then
waits for incoming state changed events to update its UI. This also fits to the
shared nature of the audio player.

The commands supported by the audio player are defined in the
[AudioPlayerCommand](src/main/scala/de/oliver_heger/linedj/platform/audio/AudioPlayerCommand.scala)
file. They allow basic playback control (such as start, stop, skip forwards and
backwards) and updates of the current playlist. It is possible to append songs
to the current playlist, or to set the current playlist at once. Per default,
songs added to the playlist are directly passed to the player engine. This may
cause some actions to be triggered, e.g. the download of media files from the
media archive. This may not always be desired; for instance, a playlist may be
populated first and then reordered before the songs are actually played. In 
this case, songs can be added to the playlist without activating them. They are
then stored, but not yet passed to the player engine. This only happens when
playback is started or a playlist is set explicitly.

A playlist can also be in an open or closed state. When it is closed no more
songs can be added, and playback stops after the last song. An open playlist in
contrast can be appended dynamically. However, care has to be taken to keep the
playlist filled with audio data to a certain amount. Otherwise, playback may
stop due to a buffer under-run.

The state the audio player is in is determined by the
[AudioPlayerState](src/main/scala/de/oliver_heger/linedj/platform/audio/AudioPlayerState.scala)
class. It mainly consists of the current playlist and some flags like the
activated or closed flags. The state also contains a sequence number for the
current playlist. This is a convenient means to determine whether there was a
change in the playlist: it is increased if the sequence of songs in the 
playlist has changed, but not if only the position in the playlist has moved.
This can be useful for client applications that need to trigger major update
steps in case of a playlist change.

In order to retrieve notifications about changes of the audio player state, 
interested parties have to send a consumer registration message on the system
message bus that contains a consumer function for state changed events. The
classes needed for this purpose are located in the file
[AudioPlayerStateChangedEvent](src/main/scala/de/oliver_heger/linedj/platform/audio/AudioPlayerStateChangedEvent.scala)

## Service dependencies

As a major part of the functionality offered by the _Audio Platform_ is 
available by just sending messages on the event bus, client applications have
no need to request a service reference and therefore have no direct dependency.
This may cause race conditions during startup of the OSGi container when a
client application already tries to send messages before the _Audio Platform_
has started and registered the listeners on the bus.

To avoid this, the platform registers some dummy dependencies as OSGi
services that represent the audio player controller and the metadata resolver
services. Client applications can depend on these synthetic services. The
following fragment shows how this is done in a declarative services component
definition file:

```xml
<component xmlns="...">
  <reference interface="de.oliver_heger.linedj.platform.comm.ServiceDependencies$ServiceDependency"
             name="playerController" target="(serviceName=lineDJ.audioPlayerController)"/>
  <reference interface="de.oliver_heger.linedj.platform.comm.ServiceDependencies$ServiceDependency"
             name="metaDataResolver" target="(serviceName=lineDJ.playlistMetaDataResolver)"/>
</component>
```

OSGi will then ensure that this component is not activated before the
corresponding listeners are registered at the message bus.
 
## Configuration

The configuration of this component is located in a section named _audio_. It
contains sub sections for general configuration of the audio player and the 
metadata resolver service (for the current playlist.) All configuration 
settings are optional; for missing properties default values are set. The
following fragment shows an XML configuration with the supported properties:

```xml
<audio>
  <player>
    <shutdownTimeout>5000</shutdownTimeout>  
  </player>
  <metaData>
    <queryChunkSize>32</queryChunkSize>
    <cacheSize>512</cacheSize>
    <requestTimeout>20000</requestTimeout>
  </metaData>
</audio>
```

The table below describes the properties and their default values.
 
| Setting | Description | Default |
| ------- | ----------- | --------
| shutdownTimeout | A time (in milliseconds) to wait for the shutdown of the audio platform. When the component for the _Audio Platform_ is deactivated it closes the player engine and waits until it is down (blocking the OSGi thread). During shutdown of the player engine, some steps are performed, e.g. closing a bunch of actors and removing temporary files; so this might take some time. With this property the maximum time to wait for a clean shutdown is specified. If the time has passed and the player engine has not yet gracefully shutdown, the component goes in inactive state, causing a hard shutdown of the player engine. | 3000 ms |
| queryChunkSize | When the current playlist is changed metadata resolution for newly added songs starts automatically. This requires requests for metadata to be sent to the media archive. This property determines the maximum number of songs that are queried at once in one of such requests. With larger numbers fewer round-trips to the archive are required, but it might take longer until the user sees first results coming in. So a balance has to be found. | 20 |
| cacheSize | The resolver for metadata maintains a cache with songs for which metadata has already been obtained. With this property the size of this cache (i.e. the number of songs that are stored) can be defined. | 1000 |
| requestTimeout | Here a timeout (in milliseconds) for metadata requests to the media archive can be specified. If no response is received within this time, the request is considered a failure, and the songs affected are assigned dummy metadata. | 30000 |
