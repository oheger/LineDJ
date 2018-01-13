# Persistent Playlist Handler

A helper module that takes care about persisting playlist information. It can
be used together with other modules (e.g. an audio player application) to store
the current playlist and reload it when the application is restarted.

## Functionality

This module operates in background. When it is activated it checks whether
information about a playlist is available on disk in configurable paths. If so,
the files are loaded, a playlist is generated, and it is passed to the
[Audio Platform](../audioPlatform/) in order to activate it.

Before the playlist is activated, it is checked whether all media it references
are available. The module also receives notifications about changes in the 
state of available media. Thus, it can delay the activation of the playlist
until all songs to be played can actually be accessed. This is useful if 
[Media archives](../mediaArchive/) are involved that are added dynamically.

After setting the playlist, the module remains active and listens for change
notifications for the audio player state and its playlist. Relevant changes are
persisted in the configured files. On deactivation, the component stores again
all data to make sure that the persistent playlist reflects the current state
of the audio platform. So when starting the application for the next time, the
playlist should be in the exact same state as before.

## Persistent data

The data managed by this module is stored in two (JSON) files:
* One file contains the actual playlist, i.e. the sequence of songs to be
  played. It consists of references to songs that can be downloaded from a
  media archive.
* The other file stores information about the current position in the playlist.
  This consists of the song that is currently played, the offset into the
  binary media file, and the elapsed playback time. With this information,
  playback can start at the very position where it has been interrupted, even
  if in the middle of a song.

If no playlist file is found when the component starts up, it assumes that no
current playlist is available and does not take any actions to set a playlist.
(However, it stays active to monitor further changes on the playlist that may
have to be persisted.)

If no position file is found, playback will start with the first song
referenced in the playlist.

Note that the same assumptions are taken if files with playlist information
cannot be loaded or contain invalid or corrupt data.
  
## Configuration

The configuration of the playlist handler module is read from the management
configuration of the hosting application (a file typically named
`lineDJ-XXX-management.xml`, where _XXX_ is the name of the application; the
file is located in the current user's home directory). The options are expected
in a section named _audio.playlist.persistence_. The following fragment shows
an example configuration:

```xml
<audio>
  <playlist>
    <persistence>
      <path>${sys:user.home}/.lineDJ/playlist.json</path>
      <pathPosition>${sys:user.home}/.lineDJ/position.json</pathPosition>
      <autoSave>90</autoSave>
      <maxFileSize>262144</maxFileSize>
      <shutdownTimeout>8000</shutdownTimeout>
    </persistence>
  </playlist>
</audio>
```

The properties have the following meaning:

| Setting | Type | Description |
| ------- | ---- | ----------- |
| path | String | Defines the path of the file with playlist information. Note that variables can be used to reference a file in the current user's home directory. |
| pathPosition | String | Like _path_, but defines the path to the file with position information. |
| autoSave | Int | A time interval (in seconds) that determines when updates of the playback position are to be written to disk. The position file is written per default every time playback of a song finishes. By defining this property it is possible to write an update during playback of a song when a specific amount of playback time has elapsed. This makes sure that playback can start again after a restart at a position close to the most recent one, even if the application crashes. (During a normal shutdown, this information is persisted; so this property only relates to an unexpected termination of the application.) |
| maxFileSize | Long | The files with playlist information are read into memory. With this property a maximum file size (in bytes) can be specified to avoid _OutOfMemory_ exceptions if the files are unexpectedly large. |
| shutdownTimeout | Long | When the playlist handler module is deactivated it has to flush any updates regarding the playlist position to disk. This is done in an asynchronous operation. With this property a maximum duration of this operation (in milliseconds) can be specified. If the save operation takes longer, the component stops anyway to avoid blocking the shutdown operation of the hosting application in an unbounded manner. |

The settings for the file paths are mandatory. The other configuration settings 
are optional; for missing values meaningful default values are applied.
