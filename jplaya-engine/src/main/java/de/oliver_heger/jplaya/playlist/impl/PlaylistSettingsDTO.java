package de.oliver_heger.jplaya.playlist.impl;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.lang3.StringUtils;

import de.oliver_heger.jplaya.playlist.KeepGroup;
import de.oliver_heger.jplaya.playlist.PlaylistOrder;
import de.oliver_heger.jplaya.playlist.PlaylistSettings;

/**
 * <p>
 * A simple data class implementing the {@code PlaylistSettings} interface.
 * </p>
 * <p>
 * This class is just a plain Java Beans implementation of the interface with
 * get and set methods for the single properties. It is intended for creating
 * playlist settings files and for editing them in UI-based applications.
 * </p>
 * <p>
 * Implementation note: This is just a dump data transfer object class. No
 * attempts have been made to make the class thread-safe or secure in any way.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlaylistSettingsDTO extends AbstractPlaylistSettings
{
    /** Constant for the index for creating new elements. */
    private static final String KEY_NEWIDX = "(-1)";

    /** Constant for the new file key. */
    private static final String KEY_NEWFILE = "." + KEY_FILE + KEY_NEWIDX
            + KEY_FILENAME;

    /** The name of the playlist. */
    private String name;

    /** The description. */
    private String description;

    /** The playlist order. */
    private PlaylistOrder order;

    /** The list with keep groups. */
    private List<KeepGroup> keepGroups;

    /** The exact playlist. */
    private List<String> exactPlaylist;

    /**
     * Returns the name of this playlist.
     *
     * @return the name
     */
    @Override
    public String getName()
    {
        return name;
    }

    /**
     * Returns a description for this playlist.
     *
     * @return a description
     */
    @Override
    public String getDescription()
    {
        return description;
    }

    /**
     * Returns the order of this playlist.
     *
     * @return the order
     */
    @Override
    public PlaylistOrder getOrder()
    {
        return order;
    }

    /**
     * Returns the exact playlist. This method never returns <b>null</b>. If no
     * list has been set so far, a new, empty list is created now.
     *
     * @return the exact playlist
     */
    @Override
    public List<String> getExactPlaylist()
    {
        if (exactPlaylist == null)
        {
            exactPlaylist = new ArrayList<String>();
        }
        return exactPlaylist;
    }

    /**
     * Returns a list with the keep groups defined for this playlist. This
     * method never returns <b>null</b>. If no list has been set so far, a new,
     * empty list is created now.
     *
     * @return the keep groups of this playlist
     */
    @Override
    public List<KeepGroup> getKeepGroups()
    {
        if (keepGroups == null)
        {
            keepGroups = new ArrayList<KeepGroup>();
        }
        return keepGroups;
    }

    /**
     * Sets the name of this playlist.
     *
     * @param name the name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Sets a description for this playlist.
     *
     * @param description the description
     */
    public void setDescription(String description)
    {
        this.description = description;
    }

    /**
     * Sets the order for this playlist.
     *
     * @param order
     */
    public void setOrder(PlaylistOrder order)
    {
        this.order = order;
    }

    /**
     * Sets the keep groups for this playlist.
     *
     * @param keepGroups the keep groups
     */
    public void setKeepGroups(List<KeepGroup> keepGroups)
    {
        this.keepGroups = keepGroups;
    }

    /**
     * Sets the exact playlist.
     *
     * @param exactPlaylist the exact playlist
     */
    public void setExactPlaylist(List<String> exactPlaylist)
    {
        this.exactPlaylist = exactPlaylist;
    }

    /**
     * Writes the data of this settings object to the specified configuration
     * object so it can be persisted.
     *
     * @param config the configuration object (must not be <b>null</b>)
     * @throws IllegalArgumentException if the configuration is <b>null</b>
     */
    public void save(HierarchicalConfiguration config)
    {
        if (config == null)
        {
            throw new IllegalArgumentException(
                    "Configuration must not be null!");
        }

        setOptionalProperty(config, KEY_NAME, getName());
        setOptionalProperty(config, KEY_DESC, getDescription());
        if (getOrder() != null)
        {
            config.addProperty(KEY_ORDER, getOrder().name());
        }

        saveKeepGroups(config);
        saveExactList(config);
    }

    /**
     * Compares this object with another one. Two instances of this class are
     * considered equal if all of their properties are equal.
     *
     * @param obj the object to compare to
     * @return a flag whether these objects are equal
     */
    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (!(obj instanceof PlaylistSettingsDTO))
        {
            return false;
        }

        return equalsProperties((PlaylistSettings) obj);
    }

    /**
     * Stores information about keep groups in the specified configuration
     * object.
     *
     * @param config the configuration
     */
    private void saveKeepGroups(HierarchicalConfiguration config)
    {
        for (KeepGroup group : getKeepGroups())
        {
            config.addProperty(KEY_KEEP + KEY_NEWIDX + KEY_NEWFILE,
                    group.getSongURI(0));
            String key = KEY_KEEP + KEY_NEWFILE;
            for (int i = 1; i < group.size(); i++)
            {
                config.addProperty(key, group.getSongURI(i));
            }
        }
    }

    /**
     * Stores the exact playlist in the specified configuration object.
     *
     * @param config the configuration
     */
    private void saveExactList(HierarchicalConfiguration config)
    {
        String key = KEY_LIST + KEY_NEWFILE;
        for (String item : getExactPlaylist())
        {
            config.addProperty(key, item);
        }
    }

    /**
     * Helper method for setting a configuration property value if it is
     * defined.
     *
     * @param config the configuration
     * @param key the key
     * @param value the property value
     */
    private static void setOptionalProperty(HierarchicalConfiguration config,
            String key, String value)
    {
        if (StringUtils.isNotEmpty(value))
        {
            config.setProperty(key, value);
        }
    }
}
