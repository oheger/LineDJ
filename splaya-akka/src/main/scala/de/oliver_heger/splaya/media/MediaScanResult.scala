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
case class MediaFile(path: Path, size: Long)

/**
 * A trait representing the ID of a medium.
 *
 * A medium is a root of a directory structure containing media files. The
 * medium is described by a medium description file.
 *
 * Alternatively, all media files that do not belong to a specific medium are
 * assigned to a synthetic medium with no medium description path. These
 * scenarios are implemented by the implementations of this trait.
 */
sealed trait MediumID {
  /**
   * The optional path to the medium description file. If defined, this file
   * contains information about this medium.
   */
  val mediumDescriptionPath: Option[Path]
}

/**
 * An implementation of ''MediumID'' for an existing medium. The path to the
 * medium description file actually exists and is passed to the constructor.
 *
 * @param path the path to the medium description file
 */
case class DefinedMediumID(path: Path) extends MediumID {
  override val mediumDescriptionPath = Option(path)
}

/**
 * An implementation of ''MediumID'' representing the synthetic medium that
 * contains all files without a real medium. This implementation does not
 * provide a path to a description file.
 */
case object UndefinedMediumID extends MediumID {
  override val mediumDescriptionPath = None
}

/**
 * A data class storing the results of a directory scan for media files (in its
 * raw form).
 *
 * This class consists of a map with ''MediumID'' objects and the files of this
 * medium assigned to it. Files which could not be assigned to a medium are
 * stored under the key [[UndefinedMediumID]]. The medium ID can be used to
 * obtain a path pointing to the corresponding medium description file.
 *
 * @param root the root path that has been scanned
 * @param mediaFiles a map with files assigned to a medium
 */
case class MediaScanResult(root: Path, mediaFiles: Map[MediumID, List[MediaFile]])
