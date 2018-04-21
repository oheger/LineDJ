# Local Media Archive Startup project

This module has the purpose to start the local media archive in an OSGi
environment.

## Description

This module is analogous to the startup project for the union archive, but
it is responsible for the _local media archive_. The local media archive
scans a folder structure with media files and passes the extracted meta data
to a union archive which can either run on the same JVM or is accessed
remotely.

Access to the union archive is obtained through the _client application
context_ available to all LineDJ platform components: actor references for the
actors implementing the archive are simply obtained from the _media facade_.
That way the location of the union archive is transparent.

The local archive is implemented by the following actors:

| Actor class | Name | Description |
| ----------- | ---- | ----------- |
| PersistentMetaDataManagerActor | persistentMetaDataManager | The actor responsible for persistent meta data files. It takes care that meta data extracted from media files is stored on the local file system and is loaded again when the local archive restarts. |
| MetaDataManagerActor | localMetaDataManager | Controls the extraction of meta data from single media files and sends the results to the union archive. |
| MediaManagerActor | localMediaManager | Scans a specified folder structure for media files and collects information about media available. This information is sent to the union archive. |

## Configuration

The configuration of the local media archive is also read from the
configuration file of the LineDJ management application (analogous as the
configuration for the union media archive; if both components are deployed in
the same platform container, they actually use the same configuration file,
and also their configuration sections overlap). The settings are placed in a
section named _media_. Below is an example fragment with all supported
configuration options:

```xml
<configuration>
  <media>
    <excludedExtensions>JPG</excludedExtensions>
    <excludedExtensions>PDF</excludedExtensions>
    <excludedExtensions>TEX</excludedExtensions>
    <excludedExtensions>DB</excludedExtensions>
    <infoSizeLimit>32768</infoSizeLimit>
    <scan>
      <parseInfoTimeout>60</parseInfoTimeout>
      <mediaBufferSize>8</mediaBufferSize>
    </scan>
    <metaDataExtraction>
      <readChunkSize>16384</readChunkSize>
      <tagSizeLimit>4096</tagSizeLimit>
      <processingTimeout>60</processingTimeout>
      <metaDataMediaBufferSize>4</metaDataMediaBufferSize>
    </metaDataExtraction>
    <metaDataPersistence>
      <path>C:\data\music\metadata</path>
      <chunkSize>4096</chunkSize>
      <parallelCount>2</parallelCount>
      <writeBlockSize>40</writeBlockSize>
    </metaDataPersistence>
    <rootPath>C:\data\music\archive</rootPath>
    <processorCount>1</processorCount>
    <toc>
      <file>C:\data\music\testArchive\testContent.json</file>
      <descRemovePrefix>C:\data\music\testArchive</descRemovePrefix>
      <descPathSeparator>\</descPathSeparator>
      <descUrlEncoding>true</descUrlEncoding>
      <rootPrefix>/test</rootPrefix>
      <metaDataPrefix>/metadata/</metaDataPrefix>
    </toc>
  </media>
</configuration>
```

The options can be grouped into different categories and are described in the
following subsections.

### Location of media files

A number of options define where - on the local hard disk - media files can be
found:

| Setting | Description |
| ------- | ----------- |
| excludedExtensions | With this list property (elements can be repeated as often as necessary) the extensions of files can be specified which should be ignored when scanning for media files. Such files are not included in media. |
| includedExtensions | As an alternative to _excludedProperties_, with this property a set of files extensions can be specified that are included; files with other extensions are ignored. If both file extensions to include and to exclude are specified, inclusions take precedence. |
| infoSizeLimit | Files with information about a medium (typically called `playlist.settings`) are fully read and processed in-memory. To avoid unrestricted memory consumption, with this property a maximum file size (in bytes) can be specified. Info files which are larger will not be processed. |
| rootPath | This property defines the folder to be scanned for media files. |
| processorCount | Defines the number of reader actors processing this folder structure in parallel. |
| metaDataMediaBufferSize | A property determining the maximum size of the buffer for media waiting to be processed for meta data extraction. During a meta data scan operation, in a first step the content of media is determined. Then the meta data for the files on the media is obtained (either from a persistent storage or by meta data extraction). As this may take more time, the number of media waiting to be processed for meta data extraction may increase. This property defines a threshold for this number. When it is reached the scan operation is blocked until media have been processed completely. This reduces the amount of memory consumption during a scan operation. The property is optional; a default value is used if it is not specified. |

### Settings related to scans for media files

These settings control the process of scanning a directory structure for media
files. Here the file system is traversed, media files are assigned to media
(identified by _medium description files_ with the file extension _.settings_),
and the description files are parsed to extract meta data about these media.
The settings are placed in a section named _scan_.

| Setting | Description | Default |
| ------- | ----------- | ------- |
| parseInfoTimeout | A timeout (in seconds) for parsing a medium description file. If a parse operation takes longer than this time span, it is aborted and dummy meta data is used for this medium. | 60 seconds |
| mediaBufferSize | The size of the buffer for media to be processed in parallel. When scanning a directory structure for media files some temporary data is created for assignments of files to media, parsed description files, etc. This property defines the number of temporary artifacts of those types that can exist. If this limit is reached, stream processing pauses until the limiting temporary artifacts have been processed. | 8 |

### Meta data extraction

The archive parses all detected media files in order to extract meta data
(e.g. ID3 tags) from them. With this group of options the behavior of this
meta data extraction process can be specified. The options are grouped in a
sub section named _metaDataExtraction_:

| Setting | Description |
| ------- | ----------- |
| metaDataExtraction.readChunkSize | Block size to be used when reading media files. A buffer of this size is created in memory. |
| metaDataExtraction.tagSizeLimit | Defines a maximum size of an ID3 tag to be processed. Tags can become large, e.g. when they contain an image. The archive only extracts text-based meta data. If a tag length is greater than this value, it is ignored. |
| metaDataExtraction.processingTimeout | Here a timeout (in seconds) for the processing of a single media file can be specified. If meta data extraction for this file takes longer, processing is aborted, and the file is ignored. |

### Meta data persistence

Once extracted, meta data is stored files in JSON format on the local file
system. How this is done is specified with another group of options in the
_metaDataPersistence_ sub section:

| Setting | Description |
| ------- | ----------- |
| metaDataPersistence.path | Defines a path (on the local file system) where files with extracted meta data information can be stored. Here files with the extension `.mdt` (for meta data) are created containing the ID3 information extracted from media files. These files are loaded when the archive starts up, so that media files do not have to be scanned again. |
| metaDataPersistence.chunkSize | Specifies the block size to be used when reading or writing meta data files. |
| metaDataPersistence.parallelCount | Here a number can be specified how many meta data files are read in parallel. Increasing the number can speedup startup time of the archive (provided that the local disc can handle the load). |
| metaDataPersistence.writeBlockSize | If no persistent meta data file for a medium is available, a new one is created automatically when the media files from the medium are scanned. After some media files have been processed, an `.mdt` file is written out, so that the information is already persisted in case the scan is aborted. The _writeBlockSize_ property defines the number of media files to be processed after the currently collected meta data is persisted. |

### Archive table of contents

A local archive can be configured to generate a JSON file with a table of
contents, i.e. a list with all media it contains and their corresponding meta
data files. This file has the same format as used by an _HTTP archive_ to
define its content.

The options are declared in a sub section named _toc_. They are optional; the
ToC file is generated only if a target file is specified.

| Setting | Description |
| ------- | ----------- |
| file | Defines the location where the table of contents file is to be stored. If this property is missing, no such file is generated. |
| descRemovePrefix | The paths to the single media contained in the archive are typically absolute paths. For some use cases, e.g. if they are to be exposed via an HTTP server, they have to be converted to relative URIs. This property defines the prefix of the paths that must be removed for this purpose. Note that only media are included in the ToC document whose path starts with this prefix. If no prefix is specified, the whole paths to media are used. |
| descPathSeparator | The path separator used in paths to media description files. This is typically the slash on Linux and the backslash on Windows. |
| descUrlEncoding | A flag whether URL encoding should be applied to paths to media. If the media should be exposed via an HTTP server, this is typically needed. |
| rootPrefix | Here a prefix can be specified which is added to paths to media files. That way, they can be referenced correctly, even if they are stored in a sub folder structure. |
| metaDataPrefix | Analogous to _rootPrefix_, this a prefix added to meta data files. Such files may be stored in a dedicated folder; with this prefix, the folder can be selected. |
