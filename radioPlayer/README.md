# LineDJ Radio Player

This module implements a player for internet radio streams.

## Functionality

The radio player application comes with a minimum UI that has two buttons for
starting and stopping playback, and a combo box for selecting the radio source
to be played. A status line shows some information about the currently played
radio source.

Radio sources are defined in a configuration file (see below). In future, there
may be a graphical editor for the configuration of radio sources, but currently
all configuration has to be done manually.

Have you ever been annoyed by commercials while listening to your favourite
radio program? Well, then there is a special feature: In the configuration for
radio sources exclusions can be defined. During such an exclusion interval, the
player switches automatically to a different radio source for which no
exclusion applies currently. That way the player can be configured to skip
commercials blocks by temporarily playing another station.

## Installation

The radio player application is an OSGi bundle. It depends on the following
bundles which must be present in the OSGi container:

* akka-actor_2.11-2.4.8.jar (akka)
* akka-osgi_2.11-2.4.8.jar (akka)
* akka-protobuf_2.11-2.4.8.jar (akka)
* akka-remote_2.11-2.4.8.jar (akka)
* akka-stream_2.11-2.4.8.jar (akka)
* commons-beanutils-1.9.2.jar
* commons-cli-osgi-1.0.jar
* commons-codec-1.9.jar
* commons-collections-3.2.2.jar
* commons-configuration-1.10.jar
* commons-jelly-osgi-1.0.jar
* commons-jexl-osgi-1.0.jar
* commons-jxpath-1.3.jar
* commons-lang-2.6.jar
* commons-logging-1.2.jar
* config-1.3.0.jar  (akka)
* disabled-mediaifc_2.11-1.0-SNAPSHOT.jar (lineDJ)
* dom4j-osgi-1.5.2.jar
* jguiraffe-1.3.1.jar
* jguiraffe-java-fx-1.3.1.jar
* jlayer-1.0.1-2.jar (soundlibs)
* linedj-actorsystem_2.11-1.0-SNAPSHOT.jar (lineDJ)
* linedj-client_2.11-1.0-SNAPSHOT.jar (lineDJ)
* linedj-shared_2.11-1.0-SNAPSHOT.jar (lineDJ)
* mp3-playback-context-factory_2.11-1.0-SNAPSHOT_spifly.jar (lineDJ)
* mp3spi-1.9.5-2.jar (soundlibs)
* netty-3.10.3.Final.jar (akka)
* org.apache.aries.spifly.static.bundle-1.0.8.jar
* org.apache.aries.util-1.1.1.jar
* player-engine_2.11-1.0-SNAPSHOT.jar (lineDJ)
* protobuf-java-2.5.0.jar (akka)
* radio-player_2.11-1.0-SNAPSHOT.jar (lineDJ)
* reactive-streams-1.0.0.M3.jar (akka)
* scala-java8-compat_2.11-0.7.0.jar
* scala-library-2.11.8.jar
* scala-parser-combinators_2.11-1.0.1.jar
* servlet-api-osgi-2.3.jar
* slf4j-api-1.7.10.jar
* slf4j-simple-1.7.10.jar
* ssl-config-akka_2.11-0.2.1.jar (akka)
* ssl-config-core_2.11-0.2.1.jar (akka)
* tritonus-share-0.3.7-3.jar (soundlibs)
* uncommons-maths-osgi-1.2.2a.jar (akka)

_Notes_:

* Bundles labeled with _akka_ are part of or dependencies of
  [Akka](http://akka.io). They are downloaded during the build and can be
  found in the local Ivy repository.
  
* Bundles with the prefix _commons_ are part of the [Apache
  Commons](http://commons.apache.org) project and can be downloaded from there.
  
* The _jguiraffe_ bundles are from the library used to build the player's UI:
  [JGUIraffe](http://jguiraffe.sourceforge.net/)
  
* Bundles marked with _soundlibs_ are part of the
  [Soundlibs](https://github.com/pdudits/soundlibs) project.
  
* The _aries_ bundles can be obtained from the [Apache Aries
  SpiFly](http://aries.apache.org/modules/spi-fly.html) project.
  
* As LineDJ is written in Scala, the Scala libraries are required. These
  bundles should be downloaded during the build into the local Ivy repository.
  
* Bundles marked with _lineDJ_ are built as part of this project.

* The bundles with the suffix _-osgi_ are the problematic ones; theese are
  libraries that do not exist as OSGi bundles (at least in the referenced
  versions). So they have to be created manually.
  
## Configuration

The radio player application is configured by a single XML-based configuration
file with the name `.lineDJ-radioplayer.xml` located in the current user's home
directory. When started for the first time the file is created automatically.
(The player is then inactive because it does not yet have any sources to play.)
The file has to be edited in order to define radio sources or other settings
for the application. An example file showing all configuration options
available can be found in the [test resources](src/test/resources). The options
can be divided into the following sections:

### Radio sources

This section defines the known radio sources. It starts with the `sources`
element under the `radio` element. Each radio source is configured in a sub
`source` element. A source is defined by a name (to be displayed to the user)
and the URL of the stream to be played. This can point to the data stream
directly or to a `m3u` file, from which the URL to the data stream has to be
extracted first. In order to determine the correct audio codec, the player
relies on file extensions. For mp3 streams, the URL should have the `mp3` file
extension. If this is not the case, this can be enforced with the `extension`
element. Optionally, a source can be assigned a _ranking_ which defines a
priority for a source. When searching for a replacement source (if the current
sources reaches an exclusion interval or has a playback error) radio sources
with a higher ranking are preferred.

With the `exclusions` element the already mentioned exclusions can be defined
for a source. Exclusion intervals can be defined in a flexible way. An interval
definition can consist of a set of week days, an hours interval, and a minutes
interval. For instance, a radio station may send commercials every half hour
between 6 a.m. and 8 p.m., but not on Sundays. The corresponding configuration
would look as follows:

```xml
      <exclusions>
        <exclusion>
          <days>
            <day>MONDAY</day>
            <day>TUESDAY</day>
            <day>WEDNESDAY</day>
            <day>THURSDAY</day>
            <day>FRIDAY</day>
            <day>SATURDAY</day>
          </days>
          <hours from="6" to="20"/>
          <minutes from="27" to="30"/>
        </exclusion>
        <exclusion>
          <days>
            <day>MONDAY</day>
            <day>TUESDAY</day>
            <day>WEDNESDAY</day>
            <day>THURSDAY</day>
            <day>FRIDAY</day>
            <day>SATURDAY</day>
          </days>
          <hours from="6" to="20"/>
          <minutes from="57" to="60"/>
        </exclusion>
      </exclusions>
```

Here two exclusion intervals are defined, one for the time before the full
hour and one for the half hour. All the parts of an interval definition are
optional. For instance, if the day of week is irrelevant, the `days` element
can be skipped; then the other defined intervals apply for all days. Or if the
`hours` element is missing, the `minutes` interval applies for the whole day
without any restrictions. This configuration is a bit verbose, but it supports
a bunch of possible scenarios.

For a single radio source an arbitrary number of exclusion intervals can be
specified.

### Error handling

When processing audio streams via the internet a number of errors can occur, e.
g. the network may be unavailable, a specific radio station may have a
temporary problem, or the URL of a station may be wrong (in which case the
problem is permanent). In the configuration section _error_ the behavior of the
player application in such cases can be configured. Below is an example
fragment:

```xml
  <error>
    <retryInterval>1000</retryInterval>
    <retryIncrement>2</retryIncrement>
    <maxRetries>5</maxRetries>
    <recovery>
      <time>300</time>
      <minFailedSources>2</minFailedSources>
    </recovery>
  </error>
```

In principle, when an error of a radio source is encountered, the application
waits for a while and then tries to restart playback of this source again. If
this causes an error again, the waiting interval is increased. This is
expressed by the settings _retryInterval_, and _retryIncrement_. The former is
the minimum interval (in milliseconds) to wait for before playback is started
again. This interval is multiplied after each playback error with the value of
_retryIncrement_ which can be a floating point number greater than 1. So on
repeating errors the interval grows.

The _maxRetries_ setting defines the number of attempts to restart playback of
a radio source. When this number is exceeded the player application assumes
that there is a permanent problem with this radio source and puts it on a
blacklist. Then it automatically switches to the source with the highest
ranking that is not yet black-listed.

With these settings the player can handle a failing radio source well by simply
switching to another source. If there is a problem with the network, one source
after the other is tried, until the blacklist contains all radio sources. In
this state the player switches back to the original source and tries to restart
playback again and again using the maximum waiting interval.

The settings in the _recovery_ section apply after playback is successful for a
while after an error has occurred. The player then assumes that a temporary
problem is solved and tries to switch back to the original source. This
certainly makes sense after a temporary network outage: Then the player may
have switched to a replacement source, but the user's preferred source is
likely to be available again, too. It is less useful in case of a single
source failing permanently because playback will be interrupted every time a
recover operation is attempted.

To deal with these different scenarios, the recovery behavior can be defined
with multiple settings:

* _time_ is the interval in seconds when a recovery should be attempted.
* _minFailedSources_ sets a further restriction: A recovery is only attempted
  if the blacklist contains at least this number of sources. The background is
  that in case of a temporary network outage typically multiple sources are
  affected, while a permanent problem of a specific source only causes this
  source to be black-listed; in the latter case, a recovery does not make
  sense.
  
With these settings a certain flexibility in error handling can be achieved.

### Other configuration

In addition to the settings discussed so far, there is a number of other
options that do not fall into a specific category. They are listed in the
table below:

| Setting | Description |
| ------- | ----------- |
| current | Stores the name of the current radio source. This is set by the player application, so that it can play the same radio source when restarted. |
| initialDelay | A delay (in milliseconds) to wait after startup before starting playback of the first source. The reason for this property is that the player engine relies on some audio codecs being installed in the system. As those are started dynamically during startup of the OSGi container, it may take some time until they are available. This setting has to be adapted to the current machine; on fast computers, there should not be a big delay. |
 