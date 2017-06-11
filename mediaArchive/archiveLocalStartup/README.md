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
    <metaDataExtraction>
      <readChunkSize>16384</readChunkSize>
      <tagSizeLimit>4096</tagSizeLimit>
      <processingTimeout>60</processingTimeout>
    </metaDataExtraction>
    <metaDataPersistence>
      <path>C:\data\music\metadata</path>
      <chunkSize>4096</chunkSize>
      <parallelCount>2</parallelCount>
      <writeBlockSize>40</writeBlockSize>
    </metaDataPersistence>
    <roots>
      <root>
        <path>C:\data\music\archive</path>
        <processorCount>1</processorCount>
      </root>
      <root>
        <path>D:\</path>
        <processorCount>2</processorCount>
      </root>
    </roots>
    <readerTimeout>3600</readerTimeout>
    <readerCheckInterval>600</readerCheckInterval>
    <readerCheckInitialDelay>480</readerCheckInitialDelay>
  </media>
</configuration>
```

The options have the following meaning:

| Setting | Description |
| ------- | ----------- |
| excludedExtensions | With this list property (elements can be repeated as often as necessary) the extensions of files can be specified which should be ignored when scanning for media files. Such files are not included in media. |
| infoSizeLimit | Files with information about a medium (typically called `playlist.settings`) are fully read and processed in-memory. To avoid unrestricted memory consumption, with this property a maximum file size (in bytes) can be specified. Info files which are larger will not be processed. |
| metaDataExtraction.readChunkSize | Block size to be used when reading media files. A buffer of this size is created in memory. |
| metaDataExtraction.tagSizeLimit | Defines a maximum size of an ID3 tag to be processed. Tags can become large, e.g. when they contain an image. The archive only extracts text-based meta data. If a tag length is greater than this value, it is ignored. |
| metaDataExtraction.processingTimeout | Here a timeout (in seconds) for the processing of a single media file can be specified. If meta data extraction for this file takes longer, processing is aborted, and the file is ignored. |
| metaDataPersistence.path | Defines a path (on the local file system) where files with extracted meta data information can be stored. Here files with the extension `.mdt` (for meta data) are created containing the ID3 information extracted from media files. These files are loaded when the archive starts up, so that media files do not have to be scanned again. |
| metaDataPersistence.chunkSize | Specifies the block size to be used when reading or writing meta data files. |
| metaDataPersistence.paralellCount | Here a number can be specified how many meta data files are read in parallel. Increasing the number can speedup startup time of the archive (provided that the local disc can handle the load). |
| metaDataPersistence.writeBlockSize | If no persistent meta data file for a medium is available, a new one is created automatically when the media files from the medium are scanned. After some media files have been processed, an `.mdt` file is written out, so that the information is already persisted in case the scan is aborted. The _writeBlockSize_ property defines the number of media files to be processed after the currently collected meta data is persisted. |
| roots.root | This section defines the folders to be scanned for media files. An arbitrary number of _root_ elements can be specified, each of which defining the top-level folder of a directory structure with media files. A _root_ element can have the sub elements _path_ for the corresponding path in the local file system, and _processorCount_ for the number of reader actors processing this folder structure in parallel. |
| readerTimeout | This property is evaluated when downloading media files from the archive. Here the protocol is that the archive returns a reader actor to the client which can be used to read the data of the file. When the client is done it stops the reader actor. If the client forgets to close the actor, there can be dangling references. To avoid this, a timeout is set: If a download takes longer than this value, the reader actor is stopped. As downloads can indeed take long (for instance, if streamed audio data is directly played, and the user pauses playback), there is a mechanism to tell the archive that a download is still in progress. Clients send a notification in regular intervals telling the archive that the reader actor is still in use. Then the timeout is reset for this reader actor. The _readerTimeout_ property defines the timeout for reader actors in seconds. |
| readerCheckInterval | This property is related to the _readerTimeout_ property. It defines an interval how often checks for timed out reader actors should be executed (in seconds). |
| readerCheckInitialDelay | This property is related to the _readerTimeout_ property. It defines the delay until the first check for timed out reader actors (in seconds). |
