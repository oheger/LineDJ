# Media Archive Startup project

This module has the purpose to start the media archive in an OSGi environment.

## Description

The media archive has no dependency to classes of the OSGi framework; it
could run as a stand-alone Java application as well.

Therefore, in order to integrate it into the OSGi-based LineDJ platform, this
module has been created which takes care of configuring and starting the
archive. Basically, it

* obtains the `ClientApplicationContext` from the OSGi registry
* reads the configuration for the management application which also contains
the settings for the media archive
* creates the actors implementing the functionality of the media archive
* triggers a scan of the configured media root directories

This module must be deployed for applications that need to run the media
archive in the local JVM.
