# LineDJ Audio Player

This module implements a player for audio files that are managed by a
[Media archive](../mediaArchive/README.md).

## Functionality

The UI of the player application consists of a button bar to control playback,
a details view for the current song, and a table showing the playlist.

The buttons in the toolbar allow starting and stopping playback, moving
forwards to the next song, and moving backwards to the previous song in the
playlist. The backwards button's behavior depends on the playback progress for
the current song: if the song has already been played for a configurable time,
it is started again; otherwise, playback moves to the previous song in the
playlist (if any).

Detailed information about the current song is displayed in the details view.
The current song is also selected in the playlist table view. It is possible to
play a specific song in the playlist by double-clicking the corresponding row
in the table view. Alternatively, the desired song can be selected in the
table, and the _Goto song_ action can be selected from the main menu.

## Installation

In addition to the standard dependencies for LineDJ UI applications, the Audio
player module depends on the [Audio Platform](../audioPlatform/README.md)
module and a component that manages the playlist.

The application does not handle playlists on its own. It rather expects that an
external component defines a playlist via the means offered by the audio
platform module. The songs in this playlist are then played in sequence and
displayed in the table view. The player application can be configured to start
playback automatically when there is a change in the current playlist.
  
## Configuration

The configuration of the Audio player application is located together with the
configuration of the Audio Platform module in a file named
`lineDJ-audioPlayer-management.xml` (per default) in the current user's home
directory. All options specific to the application are expected in a section
named _audio.ui_ as shown in the following fragment:

```xml
<audio>
  <ui>
    <maxFieldSize>25</maxFieldSize>
    <rotationSpeed>1</rotationSpeed>
    <skipBackwardsThreshold>7</skipBackwardsThreshold>
    <autoStartPlayback>true</autoStartPlayback>
  </ui>
</audio>
```

The properties have the following meaning:

| Setting | Type | Description |
| ------- | ---- | ----------- |
| maxFieldSize | Int | Defines the maximum size of a field in the details view. If the value of a field exceeds this limit, the field only shows a substring which is rotated (i.e. it scrolls). |
| rotationSpeed | Int | This property defines the speed for rotation, which is used for fields in the UI exceeding their maximum size. In this case, the portion of the value displayed is changed with the playback progress of the current song. A value of `1` in this property means, that the value is rotated for every second of elapsed playback time. With a value of `2` it changes only for every second second of elapsed playback time and so on. |
| skipBackwardsThreshold | Int | This value is a threshold in seconds and determines the behavior of the move backwards action. The action checks the playback time of the current song: If it is below this threshold, playback moves to the previous song in the playlist. Otherwise, the current song is played again from the beginning. |
| autoStartPlayback | Boolean | A flag that determines whether playback should start directly when there is a change in the playlist. This is especially useful at application startup: if this mode is enabled (the default value is *false*), playback starts automatically as soon as a playlist becomes available. |

All configuration settings are optional; for a missing value a meaningful
default is applied.
