# Window Hiding Application Manager

This module provides a special implementation of the `ApplicationManager`
service which hides an application window when it is closed, or the
application is exited.

## Functionality

This `ApplicationManager` implementation registers itself as listener on all
`ClientApplication` services installed on the platform. When the user closes
the window of one application or uses a menu command to exit the application
it stays alive, but its window is made invisible. It can later be shown again.

A set of commands can be published on the UI message bus to manipulate the
visibility state of application windows; windows can be shown or hidden. In
addition, there is a special command to shutdown the whole platform.

Via configuration (see below), it is possible to mark one or multiple
applications as _main applications_. When a user closes such an application the
platform will exit.

This `ApplicationManager` implementation is appropriate for platform
deployments hosting multiple (more or less independent) applications. The user
can work with a subset of applications, hiding the ones which are currently not
needed. The concept of _main applications_ makes it possible to have a central
application and a number of helper applications: The helper applications are
needed only temporarily for specific use cases; the main application is open
permanently - closing it means a shutdown of the platform. An example of such a
setup could be an audio player application that is bundled with a playlist
generator. Here the audio player is the main application. The playlist
generator is only needed to define or change the current playlist and can be
hidden otherwise.

Note that typically another component is needed to complete the functionality
offered by this module: a component that can display a list of all currently
available applications and allowing the user to show or hide specific
application windows.

## Configuration

This component uses configuration to persist the visibility state of the
applications available and to define which applications are _main
applications_. The configuration can be stored by the client management
application or by one of the applications installed on the platform. The
configurations of the encountered applications are scanned. The first one
containing a specific property that enables window management is used. The key
of this property is `platform.windowManagement.config`, and it must have a
value of **true**.

Under the key `platform.windowManagement.main.name` a list with the names of
applications that are considered main applications can be specified. Such
applications cause the platform to exit when they are closed. (The names here
reference the application names passed to the constructor of
`ClientApplication`.)

Information about applications that are invisible is stored under the key
`platform.windowManagement.apps`. Below this section boolean properties using
the application name as keys are stored. Per default, only invisible
applications are listed here; a missing application is considered visible.
