package de.olix.playa.playlist.impl;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;

import de.olix.playa.playlist.PlaylistSettings;

/**
 * <p>
 * An abstract base class for implementations of the {@code PlaylistSettings}
 * interface.
 * </p>
 * <p>
 * This class provides some common functionality, especially related to methods
 * inherited from {@code Object} and for defining the persistence format. Note
 * that no data fields are defined as derived classes may have their special
 * requirements.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
abstract class AbstractPlaylistSettings implements PlaylistSettings
{
    /** Constant for the playlist name key. */
    protected static final String KEY_NAME = "name";

    /** Constant for the playlist description key. */
    protected static final String KEY_DESC = "description";

    /** Constant for the order section. */
    protected static final String SEC_ORDER = "order";

    /** Constant for the order key. */
    protected static final String KEY_ORDER = SEC_ORDER + ".mode";

    /** Constant for the keep key. */
    protected static final String KEY_KEEP = SEC_ORDER + ".keep";

    /** Constant for the exact list key. */
    protected static final String KEY_LIST = SEC_ORDER + ".list";

    /** Constant for the file name key. */
    protected static final String KEY_FILENAME = "[@name]";

    /** Constant for the file key. */
    protected static final String KEY_FILE = "file";

    /** Constant for the files key. */
    protected static final String KEY_FILES = KEY_FILE + KEY_FILENAME;

    /** Constant for the exact list files key. */
    protected static final String KEY_LISTFILES = KEY_LIST + '.' + KEY_FILES;

    /**
     * Returns a hash code for this object.
     *
     * @return a hash code
     */
    @Override
    public int hashCode()
    {
        return new HashCodeBuilder().append(getName()).append(getDescription())
                .append(getOrder()).append(getKeepGroups())
                .append(getExactPlaylist()).toHashCode();
    }

    /**
     * Returns a string representation of this object.
     *
     * @return a string for this object
     */
    @Override
    public String toString()
    {
        return new ToStringBuilder(this).append("name", getName())
                .append("description", getDescription()).toString();
    }

    /**
     * Tests whether the properties of this object are equal to the specified
     * object.
     *
     * @param c the object to compare to
     * @return a flag whether these objects have equal properties
     */
    protected boolean equalsProperties(PlaylistSettings c)
    {
        return new EqualsBuilder().append(getName(), c.getName())
                .append(getDescription(), c.getDescription())
                .append(getOrder(), c.getOrder())
                .append(getKeepGroups(), c.getKeepGroups())
                .append(getExactPlaylist(), c.getExactPlaylist()).isEquals();
    }
}
