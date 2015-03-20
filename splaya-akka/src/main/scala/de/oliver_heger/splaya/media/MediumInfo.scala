package de.oliver_heger.splaya.media

/**
 * A trait defining meta data of a medium.
 *
 * This trait defines some properties with information about a medium. The data
 * which can be queried from an instance can be used e.g. in a UI when
 * presenting an overview over the media currently available.
 */
trait MediumInfo {
  /** The name of the represented medium. */
  val name: String

  /** An additional description about the represented medium. */
  val description: String

  /**
   * The root URI of the represented medium. This information is typically
   * transient; it is determined when the medium is loaded. It merely serves
   * informational purpose.
   */
  val mediumURI: String
}
