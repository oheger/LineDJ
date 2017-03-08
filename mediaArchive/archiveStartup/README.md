# Media Archive Startup project

This module has the purpose to start the union media archive in an OSGi
environment.

## Description

The media archive has no dependency to classes of the OSGi framework; it
could run as a stand-alone Java application as well.

Therefore, in order to integrate it into the OSGi-based LineDJ platform, this
module has been created which takes care of configuring and starting the
archive. Basically, it

* obtains the `ClientApplicationContext` from the OSGi registry
* reads the configuration for the management application which also contains
the settings for the media archive
* creates the actors implementing the functionality of the union media archive

The union media archive is then up and running. It can be populated from
other archive components, e.g. a local archive on the same or a remote
machine.

## Configuration

The configuration of the union media archive is read from the configuration
file of the LineDJ management application (this is located in the user's home
directory and per default called `.lineDJ-XXX-management.xml` where _XXX_ is
the name of the deployment). All settings are placed in a section named
_media.metaDataExtraction_. Below is an example fragment showing the
available configuration options:

```xml
<configuration>
<media>
    <metaDataExtraction>
      <metaDataUpdateChunkSize>8</metaDataUpdateChunkSize>
      <metaDataMaxMessageSize>160</metaDataMaxMessageSize>
    </metaDataExtraction>
  </media>
</configuration>
```

The options have the following meaning:

| Setting | Description |
| ------- | ----------- |
| metaDataUpdateChunkSize | Defines a threshold how often media listeners should be updated when new data becomes available. Scanning for media files can take a while. It is possible that a a client already queries the content of a medium before it has been fully processed. In this case, the client can register itself as listener for this medium and gets update notifications when more songs have been processed. However, update notifications are not sent for each new song as this would cause too much network traffic. With this property, the number of new songs is defined which have to be read before sending the next update notification. (A notification is always sent when the last song was added.) |
| metaDataMaxMessageSize | Defines the maximum number of songs in a _MetaDataResponse_ message. For large media containing a huge number of songs a message with all meta data about these songs will also become large. Akka places a size restriction on remote messages; therefore, large meta data messages have to be split. This property defines how many songs can be contained in one _MetaDataResponse_ message. If a medium contains more songs than this limit, multiple response messages are sent in reaction on a query. |
