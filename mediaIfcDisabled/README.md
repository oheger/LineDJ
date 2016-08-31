# Disabled media archive interface

This project provides a dummy implementation of the `MediaFacadeFactory` and
`MediaFacade` interfaces defined by the _client_ project.

A LineDJ client application requires the existence of a `MediaFacadeFactory` as
an OSGi service; otherwise, it does not start. There are applications, however,
which do not use a media archive, e.g. a pure internet radio player
application. Such applications can make use of this dummy implementation.

The bundle can be deployed as is and does not support any configuration
options.
