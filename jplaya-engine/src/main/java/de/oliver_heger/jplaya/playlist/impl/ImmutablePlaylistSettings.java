package de.oliver_heger.jplaya.playlist.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.configuration.HierarchicalConfiguration;

import de.oliver_heger.jplaya.playlist.KeepGroup;
import de.oliver_heger.jplaya.playlist.PlaylistOrder;

/**
 * <p>
 * An immutable implementation of the {@code PlaylistSettings} interface.
 * </p>
 * <p>
 * For each known playlist (e.g. a medium with sound files) a settings file will
 * be created that contains information about this medium, e.g. the order in
 * which the files are to be played. When then a medium is to be played the
 * corresponding settings file is consulted to find out how this is done and to
 * obtain meta information.
 * </p>
 * <p>
 * The class represents playlist settings when a medium is played. It can be
 * initialized from an XML file. The tags in the XML definition file are all
 * optional so all properties may be <b>null</b>. Client code should be aware of
 * this. Because it is immutable an instance can be shared between multiple
 * threads.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class ImmutablePlaylistSettings extends AbstractPlaylistSettings
{
    /** Constant for the empty instance. */
    private static final ImmutablePlaylistSettings EMPTY =
            new ImmutablePlaylistSettings(new HierarchicalConfiguration());

    /** Stores the name of the associated playlist. */
    private final String name;

    /** Stores a description for the represented playlist. */
    private final String description;

    /** Stores the order of the playlist. */
    private final PlaylistOrder order;

    /** Stores the keep groups. */
    private final List<KeepGroup> keepGroups;

    /** Stores the exact playlist. */
    private final List<String> exactList;

    /**
     * Creates a new instance of {@code ImmutablePlaylistSettings} and
     * initializes it from the specified configuration object.
     *
     * @param config the configuration object containing the settings
     */
    private ImmutablePlaylistSettings(HierarchicalConfiguration config)
    {
        name = config.getString(KEY_NAME);
        description = config.getString(KEY_DESC);
        order = initOrder(config);
        keepGroups = initKeepGroups(config);
        exactList = initExactList(config);
    }

    /**
     * Factory method for creating a new instance of
     * {@code ImmutablePlaylistSettings} from the specified configuration
     * object. The properties stored in the configuration are extracted and
     * stored in internal fields.
     *
     * @param config the configuration object containing the settings (must not
     *        be <b>null</b>)
     * @return the newly created instance
     * @throws IllegalArgumentException if the configuration is <b>null</b>
     */
    public static ImmutablePlaylistSettings newInstance(
            HierarchicalConfiguration config)
    {
        if (config == null)
        {
            throw new IllegalArgumentException(
                    "Configuration must not be null!");
        }
        return new ImmutablePlaylistSettings(config);
    }

    /**
     * Returns an instance of {@code ImmutablePlaylistSettings} which does not
     * contain any data. This instance can be used if no playlist information is
     * available.
     *
     * @return the empty instance
     */
    public static ImmutablePlaylistSettings emptyInstance()
    {
        return EMPTY;
    }

    /**
     * Returns a description for the associated playlist.
     *
     * @return a description
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * Returns the name of the associated playlist.
     *
     * @return the name
     */
    public String getName()
    {
        return name;
    }

    /**
     * Returns the order of the playlist.
     *
     * @return the order
     */
    public PlaylistOrder getOrder()
    {
        return order;
    }

    /**
     * Returns a set with the playlist items that must be kept together with
     * other items. This information is evaluated in the order mode
     * <em>random</em>. Here it is possible to define groups of songs that must
     * be always played in serial.
     *
     * @return a set with the defined keep groups
     */
    public List<KeepGroup> getKeepGroups()
    {
        return keepGroups;
    }

    /**
     * Returns the &quot;exact&quot; playlist. This list contains the songs that
     * are to be played in the order mode <em>exact</em>.
     *
     * @return a list with the songs to be played in <em>exact</em> mode
     */
    public List<String> getExactPlaylist()
    {
        return exactList;
    }

    /**
     * Compares this object with another one. Two instances of this class are
     * considered equal if all of their properties match.
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
        if (!(obj instanceof ImmutablePlaylistSettings))
        {
            return false;
        }

        ImmutablePlaylistSettings c = (ImmutablePlaylistSettings) obj;
        return equalsProperties(c);
    }

    /**
     * Initializes the order from the configuration object.
     *
     * @param config the configuration
     * @return the order of the playlist
     */
    private static PlaylistOrder initOrder(HierarchicalConfiguration config)
    {
        String orderStr =
                config.getString(KEY_ORDER, PlaylistOrder.UNDEFINED.name());
        try
        {
            return PlaylistOrder.valueOf(orderStr.toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException iex)
        {
            return PlaylistOrder.UNDEFINED;
        }
    }

    /**
     * Obtains a string list from the specified configuration. This method is
     * mainly necessary because the configuration API is not yet type-safe.
     *
     * @param config the configuration
     * @param key the key
     * @return the list with strings
     */
    private static List<String> getStringList(HierarchicalConfiguration config,
            String key)
    {
        List<?> lst = config.getList(key);
        List<String> result = new ArrayList<String>(lst.size());
        for (Object o : lst)
        {
            result.add(String.valueOf(o));
        }
        return result;
    }

    /**
     * Initializes the list with keep groups from the configuration object.
     *
     * @param config the configuration
     * @return the list with keep groups
     */
    private static List<KeepGroup> initKeepGroups(
            HierarchicalConfiguration config)
    {
        List<?> subConfigs = config.configurationsAt(KEY_KEEP);
        if (subConfigs.isEmpty())
        {
            return Collections.emptyList();
        }

        List<KeepGroup> groups = new ArrayList<KeepGroup>(subConfigs.size());
        for (Object o : subConfigs)
        {
            HierarchicalConfiguration c = (HierarchicalConfiguration) o;
            List<String> keepFiles = getStringList(c, KEY_FILES);
            groups.add(new KeepGroup(keepFiles));
        }

        return Collections.unmodifiableList(groups);
    }

    /**
     * Initializes the exact playlist from the configuration object.
     *
     * @param config the configuration
     * @return the exact playlist
     */
    private static List<String> initExactList(HierarchicalConfiguration config)
    {
        List<String> exactlst = getStringList(config, KEY_LISTFILES);
        return Collections.unmodifiableList(exactlst);
    }
}
