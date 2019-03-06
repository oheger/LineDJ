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

## Configuration

As usual, the configuration for the archive admin application is read from the
configuration file of the LineDJ management application. It is placed in a
section named _media.validation_ and supports a limited number of settings:

```xml
<configuration>
  <media>
    <validation>
      <validationParallelism>4</validationParallelism>
      <uiUpdateChunkSize>16</uiUpdateChunkSize>
    </validation>
  </media>
</configuration>
```
The options available are explained in the table below:

| Setting | Description | Default |
| ------- | ----------- | ------- |
| validationParallelism | Defines the degree of parallelism in which validation of archive entries is performed. This corresponds to the number of elements that are processed in parallel. | 4 |
| uiUpdateChunkSize | Defines an update rate for the UI that displays validation results. Validation happens in background; the UI is updated when new results are available. With this property it can be specified that updates are not needed for every new result, but only when the given number of results is reached. If there are many validation errors, a larger chunk size can improve performance. | 16 |
