package de.oliver_heger.linedj.client.model

/**
 * A trait for resolving the names of unknown references.
 *
 * Meta data for a song may be incomplete, for instance information about the
 * artist or the album of a song may be missing. What to display in this case
 * is probably not constant, but can depend on the language of the current
 * user. This trait defines methods for querying names of unknown references.
 * Concrete implementations can use different strategies for looking up such
 * names, e.g. via a resource manager.
 */
trait UnknownNameResolver {
  /** The name to be returned for an unknown artist. */
  val unknownArtistName: String

  /** The name to be returned for an unknown album. */
  val unknownAlbumName: String
}
