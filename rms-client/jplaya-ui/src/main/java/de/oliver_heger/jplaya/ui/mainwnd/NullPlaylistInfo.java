package de.oliver_heger.jplaya.ui.mainwnd;

import org.apache.commons.lang3.StringUtils;

import de.oliver_heger.jplaya.playlist.PlaylistInfo;

/**
 * <p>
 * A dummy implementation of the {@code PlaylistInfo} interface which returns
 * default values for all methods.
 * </p>
 * <p>
 * This implementation is used to clear the UI of the playlist view. If this
 * implementation is used, all fields in the UI remain empty. Note: This is an
 * enumeration class to ensure the singleton property.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
enum NullPlaylistInfo implements PlaylistInfo
{
    /**
     * The single instance of this class.
     */
    INSTANCE
    {
        /**
         * {@inheritDoc} This implementation returns an empty name.
         */
        @Override
        public String getName()
        {
            return StringUtils.EMPTY;
        }

        /**
         * {@inheritDoc} This implementation returns an empty description.
         */
        @Override
        public String getDescription()
        {
            return StringUtils.EMPTY;
        }

        /**
         * {@inheritDoc} This implementation always returns 0.
         */
        @Override
        public int getNumberOfSongs()
        {
            return 0;
        }
    }
}
