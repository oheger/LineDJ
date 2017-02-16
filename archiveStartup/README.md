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
