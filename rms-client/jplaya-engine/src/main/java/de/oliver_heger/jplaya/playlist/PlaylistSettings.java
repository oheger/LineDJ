package de.oliver_heger.jplaya.playlist;

import java.util.List;

/**
 * <p>
 * Definition of an interface defining settings for a playlist.
 * </p>
 * <p>
 * This interface defines some meta data properties which can be assigned to a
 * playlist. These properties are evaluated by a <em>playlist manager</em>. In
 * addition to a name and a description, there are hints about the order in
 * which songs can be played.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface PlaylistSettings
{
    /**
     * Returns a name for this playlist.
     *
     * @return a name
     */
    String getName();

    /**
     * Returns a description of this playlist.
     *
     * @return a description
     */
    String getDescription();

    /**
     * Returns the preferred order of this playlist. This property is taken into
     * account by a playlist manager when it constructs a new playlist from the
     * songs found on the source medium.
     *
     * @return the preferred order of this playlist
     */
    PlaylistOrder getOrder();

    /**
     * Returns the exact list of songs that are to be played. This method has to
     * be used if the order is {@link PlaylistOrder#EXACT}. In this case the
     * exact sequence of songs to be played is returned.
     *
     * @return a list with the URIs of the songs that are to be played for exact
     *         order
     */
    List<String> getExactPlaylist();

    /**
     * Returns the keep groups defined for this playlist. These groups are taken
     * into account when sorting a playlist. If there are no keep groups, result
     * is an empty list.
     *
     * @return a list with the keep groups defined for this playlist
     */
    List<KeepGroup> getKeepGroups();
}
