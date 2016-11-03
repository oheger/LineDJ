# Media Archive Admin project

An application providing a simple admin UI for the media archive.

## Description

This sub project contains an application that displays information about the
current status of the media archive. There are some statistics such as the
number of medias and songs currently in the archive and the total size
occupied on disk and playback duration. There are actions to trigger a new
media scan or to cancel a currently running scan.

A separate dialog window can be opened allowing the management of persistent
meta data files. For each medium that has been added to the archive, such a
meta data file is written after it has been scanned. The files are named by a
checksum value calculated based on the content of the medium. Once written meta
data files are not deleted automatically, even if the associated medium is
changed or no longer exists. This can be done manually via this dialog.

The OSGi bundle produced by this project can be deployed into an OSGi framework
hosting the LineDJ platform. The archive admin UI is then available;
communication with the media archive happens via the same channel as used by
other platform applications.
