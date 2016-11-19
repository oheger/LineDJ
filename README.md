# LineDJ

LineDJ is a platform that implements components which can be combined to create
applications for audio playback and processing of audio meta data.

## Background

First of all, this is an experimental project to explore various technologies
and how they collaborate to create modular, reactive UI applications.
Technologies used here include:
* [Scala](http://www.scala-lang.org/)
* [Akka](http://akka.io/)
* [OSGi](https://www.osgi.org/)
* JavaFX

The components implemented in these technologies come from the domain of audio
playback. They offer functionality which is of personal interest for me, but
can be useful in general to create applications dealing with audio files.

## Functionality

This repository contains multiple modules that implement single platform
components. The top-level folders of the modules contain further README files
with additional information for this specific component.

Currently, the following components and applications are available:
* [Platform](platform)
* [Archive Admin application](archiveAdmin)
* [Internet Radio Player application](radioPlayer)

## Building

LineDJ uses [SBT](http://www.scala-sbt.org/) as its build tool. The project can
be built by issuing

`sbt publishLocal`

in the top-level directory. This command produces the jars and OSGi bundles in
the `target` sub folders of the module directories.

## General setup

The components are OSGi bundles that can be installed in an OSGi framework.
Some of them provide a (typically simple) user interface and are mini
applications. The basic idea is to combine multiple components together in one
OSGi container to create an application with specific functionality. Typically,
components have some dependencies to other libraries which need to be available
in the OSGi container in form of bundles as well. The documentation of the
components list the required bundles.

Installations have been tested with the [Apache
Felix framework](http://felix.apache.org/), but should be compatible with other
OSGi frameworks, too. 

Unfortunately, some required dependencies are not available as OSGi bundles
officially; therefore, it is necessary to convert them to bundles manually -
which can be done for instance with the [Apache Felix Maven Bundle
Plugin](http://felix.apache.org/documentation/subprojects/apache-felix-maven-bundle-plugin-bnd.html).

## Where the name comes from

I was working in a company where teams were organized into _lines_, named from
the reporting lines in the orga chart. The offices of the line my team belonged
to were located along a corridor. One guy used to play loud music and thus
produced background sound for the whole corridor. I usually was happy with the
music he played - he preferred Heavy Metal -, but came to the idea that it
would be cool if everybody could somehow influence the type of music that was
played; a kind of social audio platform. Implementing this idea is one of the
goals of this project.

## License

This code is open source software licensed under the
[Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
