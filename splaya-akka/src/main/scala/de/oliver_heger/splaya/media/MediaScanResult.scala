package de.oliver_heger.splaya.media

import java.nio.file.Path

/**
 * A data class representing a file on a medium.
 *
 * The file is uniquely identified by the ''Path'' to the data on disk. In
 * addition, some meta data is provided.
 *
 * @param path the path to the represented file
 * @param size the size of the file
 */
private case class MediaFile(path: Path, size: Long)

/**
 * A data class storing the results of a directory scan for media files (in its
 * raw form).
 *
 * This class consists of a map with files that could be assigned to a medium
 * and all other files encountered in the processed directory structure. A
 * medium is represented by a path pointing to it medium description file.
 *
 * @param root the root path that has been scanned
 * @param mediaFiles a map with files assigned to a medium
 * @param otherFiles a list with other files
 */
private case class MediaScanResult(root: Path, mediaFiles: Map[Path, List[MediaFile]], otherFiles:
List[MediaFile])
