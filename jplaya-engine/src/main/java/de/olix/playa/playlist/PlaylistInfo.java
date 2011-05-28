package de.olix.playa.playlist;

/**
 * <p>
 * Definition of an interface for obtaining information about a playlist.
 * </p>
 * <p>
 * This interface allows access to simple information about a playlist, e.g. its
 * name and a description. An object implementing this interface can be obtained
 * from a <code>{@link PlaylistManager}</code> object.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id$
 */
public interface PlaylistInfo
{
	/**
     * Returns a name for the represented playlist.
     * 
     * @return a name for this playlist
     */
	String getName();

	/**
     * Returns a description for this playlist.
     * 
     * @return a description
     */
	String getDescription();

	/**
     * Returns the total number of songs that are contained in the playlist.
     * 
     * @return the number of songs in the playlist
     */
	int getNumberOfSongs();

	/**
     * Returns the index of the currently played song.
     * 
     * @return the index of the currently played song
     */
	int getCurrentSongIndex();
}
