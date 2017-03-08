# Media Archive

The sub projects in this folder structure implement functionality for managing
media files.

## Description

The media archive is a central component for the LineDJ platform. It stores
meta data about all audio files currently available and allows downloading
media files. Audio files are grouped in _media_. A single medium has a name and
a description. It is possible to query information about all available media
and to list the files contained on a specific medium.

An application running on the platform has access to the media archive through
its ``ClientApplicationContext``.  From there a reference to a facade to the
media archive can be queried. Audio applications typically make use of the
media archive in some form, for instance

* A browser application shows the content of the archive grouped by different
  categories like media, artists, albums, etc.
* A playlist editor allows selecting songs from the archive and export them as
  playlist.
* An audio player plays songs from the media archive.

There may also be cases in which no access to a media archive is required. In
this case, a special dummy archive implementation can be deployed which simply
does nothing.

## Different kinds of archives

The archive is available in different flavors which can collaborate together
to provide extended functionality. The supported variants are introduced in the
following sub sections.

### Local archive

A _local archive_ manages media files that are stored on the same machine as
the archive runs on. A local folder structure with media files is scanned, and
meta data is extracted. However, the meta data is not directly served by the
local archive, but propagated to a _union archive_.

### Union archive

The _union archive_ actually implements the interface for querying media files
and meta data. An application running on the LineDJ platform interacts with one
central union archive. As the name implies, the archive collects the
distribution of other archives - so-called _archive components_ - and provides
access to them.

For small deployments, there is typically a local archive responsible for
reading media files and their meta data collaborating with a union archive. The
latter exposes the data loaded by the former. However, this architecture makes
it possible to collect data from multiple machines and combine it in a central
union archive. For instance, a work group could setup a central server on which
the union archive runs. Each member of the work group could have an own local
archive with favorite songs. These local archives connect remotely to the
central union archive. The union archive then allows access to the whole
collection of media files available in the work group.

## Archive startup components

The archive projects do not use any specific features of the LineDJ
platform. They merely contain the actors which implement the archive logic.
Hence, they could also be started as stand-alone applications (of course, this
would require that a startup class is written which creates the actors and
plugs them together).

For the integration into the LineDJ platform such startup classes exist. They
are located in the different _XXXStartup_ projects. What these projects do is
declaring a dependency to the LineDJ _client application context_ from which
they can obtain the central actor system and the configuration of the
management application. With these objects, actors can be created,
configured, and the archive implementation can be started.

Refer to the descriptions of the startup components for more details and a
list of all supported configuration options.
