package de.olix.playa.playlist;

/**
 * <p>
 * An enumeration for the different supported order modes in which a playlist
 * can be played.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public enum PlaylistOrder
{
    /**
     * The order mode <em>directories</em>. In this mode the directory structure
     * in which the songs are stored is taken into account.
     */
    DIRECTORIES,

    /**
     * The order mode <em>random</em>. In this mode the songs are played in a
     * random order.
     */
    RANDOM,

    /**
     * The order mode <em>exact</em>. In this mode the order of the songs is
     * exactly defined.
     */
    EXACT,

    /**
     * The order mode <em>undefined</em>. This constant simply means that no
     * order is available. In this case a default order mode should be used.
     */
    UNDEFINED
}
