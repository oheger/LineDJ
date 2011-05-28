package de.olix.playa.playlist.xml;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.XMLConfiguration;

import de.olix.playa.playlist.PlaylistException;

/**
 * <p>
 * A class for managing settings of a playlist.
 * </p>
 * <p>
 * For each known playlist (e.g. a medium with sound files) a settings file will
 * be created that contains information about this medium, e.g. the order in
 * which the files are to be played. When then a medium is to be played the
 * corresponding settings file is consulted to find out how this is done.
 * </p>
 * <p>
 * The format of the settings files as they are implemented by this class is
 * documented in the header comment of the
 * <code>{@link XMLPlaylistManager}</code> class.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id$
 */
public class PlaylistSettings
{
    /** Constant for the file extension for settings files. */
    public static final String SETTINGS_EXT = ".settings";

    /** Constant for the playlist name key. */
    private static final String KEY_NAME = "name";

    /** Constant for the playlist description key. */
    private static final String KEY_DESC = "description";

    /** Constant for the order section. */
    private static final String SEC_ORDER = "order";

    /** Constant for the order key. */
    private static final String KEY_ORDER = SEC_ORDER + ".mode";

    /** Constant for the file name key. */
    private static final String KEY_FILENAME = "[@name]";

    /** Constant for the file key. */
    private static final String KEY_FILE = "file";

    /** Constant for the files key. */
    private static final String KEY_FILES = KEY_FILE + KEY_FILENAME;

    /** Constant for the keep key. */
    private static final String KEY_KEEP = SEC_ORDER + ".keep";

    /** Constant for the exact list key. */
    private static final String KEY_LIST = SEC_ORDER + ".list";

    /** Constant for the exact list files key. */
    private static final String KEY_LISTFILES = KEY_LIST + '.' + KEY_FILES;

    /** Constant for the index for creating new elements. */
    private static final String KEY_NEWIDX = "(-1)";

    /** Constant for the new file key. */
    private static final String KEY_NEWFILE = "." + KEY_FILE + KEY_NEWIDX
            + KEY_FILENAME;

    /** Stores the name of the associated playlist. */
    private String name;

    /** Stores a description for the represented playlist. */
    private String description;

    /** Stores the order of the playlist. */
    private Order order;

    /**
     * Stores the file of the settings file, in which the state of this object
     * is to be stored.
     */
    private File settingsFile;

    /** Stores the keep groups. */
    private Map<PlaylistItem, List<PlaylistItem>> keepGroups;

    /** Stores the exact playlist. */
    private List<PlaylistItem> exactList;

    /**
     * Creates a new instance of <code>PlaylistSettings</code>.
     */
    public PlaylistSettings()
    {
        keepGroups = new HashMap<PlaylistItem, List<PlaylistItem>>();
    }

    /**
     * Returns the settings file.
     *
     * @return the settings file
     */
    public File getSettingsFile()
    {
        return settingsFile;
    }

    /**
     * Sets the settings file. This is the file used by the <code>load()</code>
     * and <code>save()</code> methods.
     *
     * @param settingsFile the underlying data file
     */
    public void setSettingsFile(File settingsFile)
    {
        this.settingsFile = settingsFile;
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
     * Sets a description for the associated playlist.
     *
     * @param description the new description
     */
    public void setDescription(String description)
    {
        this.description = description;
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
     * Sets the name of the associated playlist.
     *
     * @param name the new name
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns the order of the playlist.
     *
     * @return the order
     */
    public Order getOrder()
    {
        return order;
    }

    /**
     * Allows to set the order of the playlist.
     *
     * @param order the new order
     */
    public void setOrder(Order order)
    {
        this.order = order;
    }

    /**
     * Returns a set with the playlist items that must be kept together with
     * other items. This information is evaluated in the order mode
     * <em>random</em>. Here it is possible to define groups of songs that
     * must be always played in serial.
     *
     * @return a set with the defined keep groups
     */
    public Set<PlaylistItem> getKeepGroups()
    {
        return keepGroups.keySet();
    }

    /**
     * Returns the keep group for the passed in playlist item. A keep group is
     * always identified by its first song. Its content is merely a list with
     * the other songs that must be played after that song in this exact order.
     *
     * @param first the first song of the desired keep group
     * @return the keep group associated with this song or <b>null</b> if there
     * is no such group
     */
    public List<PlaylistItem> getKeepGroup(PlaylistItem first)
    {
        return keepGroups.get(first);
    }

    /**
     * Defines a keep group. With this method the content of a keep group can be
     * set.
     *
     * @param first the first song of this group
     * @param items the remaining songs (the first song should not be contained
     * in this list; otherwise it will be played twice)
     */
    public void setKeepGroup(PlaylistItem first, List<PlaylistItem> items)
    {
        if (first == null)
        {
            throw new IllegalArgumentException(
                    "First item of keep group must not be null!");
        }
        if (items == null)
        {
            throw new IllegalArgumentException(
                    "Item list of keep group must not be null!");
        }
        keepGroups.put(first, items);
    }

    /**
     * Removes the specified keep group.
     *
     * @param first the first song of this group
     * @return the content of the removed group (<b>null</b> if the group was
     * unknown)
     */
    public List<PlaylistItem> removeKeepGroup(PlaylistItem first)
    {
        return keepGroups.remove(first);
    }

    /**
     * Returns the &quot;exact&quot; playlist. This list contains the songs that
     * are to be played in the order mode <em>exact</em>.
     *
     * @return a list with the songs to be played in <em>exact</em> mode
     */
    public List<PlaylistItem> getExactPlaylist()
    {
        List<PlaylistItem> result;
        if (exactList != null)
        {
            result = exactList;
        }
        else
        {
            result = Collections.emptyList();
        }
        return result;
    }

    /**
     * Sets the &quot;exact&quot; playlist. If the order mode is set to
     * <em>exact</em> the songs in this list will be played in exactly this
     * order. Otherwise this list is ignored.
     *
     * @param list the list with the songs to be played in <em>exact</em> mode
     */
    public void setExactPlaylist(List<PlaylistItem> list)
    {
        exactList = list;
    }

    /**
     * Applies the stored order to the passed in playlist. This method is called
     * whenever a playlist needs to be sorted (e.g. when it was newly loaded or
     * after it has been completely played). This method will bring the songs
     * stored in the passed in playlist in the appropriate order.
     *
     * @param src the source playlist
     * @return a playlist with the correct order
     */
    public Playlist applyOrder(Playlist src)
    {
        Playlist result;

        switch (getOrder())
        {
        case random:
            result = createRandomOrder(src);
            break;
        case exact:
            result = createExactOrder();
            break;
        default:
            result = src;
        }

        return result;
    }

    /**
     * Loads the settings from the underlying settings file.
     *
     * @throws PlaylistException if an error occurs
     * @see #setSettingsFile(File)
     */
    public void load() throws PlaylistException
    {
        checkFile();
        try
        {
            XMLConfiguration config = new XMLConfiguration();
            config.setFile(getSettingsFile());
            config.setListDelimiter((char) 0);
            config.load();
            setName(config.getString(KEY_NAME));
            setDescription(config.getString(KEY_DESC));
            setOrder(Order.valueOf(config.getString(KEY_ORDER)));

            for (Iterator it = config.configurationsAt(KEY_KEEP).iterator(); it
                    .hasNext();)
            {
                HierarchicalConfiguration c = (HierarchicalConfiguration) it
                        .next();
                List keepFiles = c.getList(KEY_FILES);
                PlaylistItem item = new PlaylistItem(keepFiles.get(0)
                        .toString());
                List<PlaylistItem> group = new ArrayList<PlaylistItem>(
                        keepFiles.size() - 1);
                for (int i = 1; i < keepFiles.size(); i++)
                {
                    group.add(new PlaylistItem((String) keepFiles.get(i)));
                }
                setKeepGroup(item, group);
            }

            if (config.containsKey(KEY_LISTFILES))
            {
                List exactLst = config.getList(KEY_LISTFILES);
                List<PlaylistItem> items = new ArrayList<PlaylistItem>(exactLst
                        .size());
                for (Iterator it = exactLst.iterator(); it.hasNext();)
                {
                    items.add(new PlaylistItem((String) it.next()));
                }
                setExactPlaylist(items);
            }
        }
        catch (ConfigurationException cex)
        {
            throw new PlaylistException("Could not load playlist file "
                    + getSettingsFile(), cex);
        }
    }

    /**
     * Stores the settings in the underlying settings file.
     *
     * @throws PlaylistException if an error occurs
     * @see #setSettingsFile(File)
     */
    public void save() throws PlaylistException
    {
        checkFile();
        try
        {
            XMLConfiguration config = new XMLConfiguration();
            config.setListDelimiter((char) 0);
            config.addProperty(KEY_NAME, getName());
            config.addProperty(KEY_DESC, getDescription());
            config.addProperty(KEY_ORDER, getOrder().name());

            for (PlaylistItem item : keepGroups.keySet())
            {
                List<PlaylistItem> group = keepGroups.get(item);
                if (group.size() > 0)
                {
                    config.addProperty(KEY_KEEP + KEY_NEWIDX + KEY_NEWFILE,
                            item.getPathName());
                    String key = KEY_KEEP + KEY_NEWFILE;
                    for (PlaylistItem groupItem : group)
                    {
                        config.addProperty(key, groupItem.getPathName());
                    }
                }
            }

            if (getExactPlaylist().size() > 0)
            {
                String key = KEY_LIST + KEY_NEWFILE;
                for (PlaylistItem item : getExactPlaylist())
                {
                    config.addProperty(key, item.getPathName());
                }
            }

            config.setEncoding(XMLPlaylistManager.ENCODING);
            config.save(getSettingsFile());
        }
        catch (ConfigurationException cex)
        {
            throw new PlaylistException("Could not save settings file to "
                    + getSettingsFile(), cex);
        }
    }

    /**
     * Checks if a playlist file was specified. If this is not the case, an
     * exception will be thrown.
     *
     * @throws PlaylistException if no file was set
     */
    private void checkFile() throws PlaylistException
    {
        if (getSettingsFile() == null)
        {
            throw new PlaylistException("A settings file was not specified!");
        }
    }

    /**
     * Creates a playlist with a random order and also takes the keep groups
     * into account.
     *
     * @param src the source playlist
     * @return the resulting playlist
     */
    private Playlist createRandomOrder(Playlist src)
    {
        Set<PlaylistItem> keepItems = new HashSet<PlaylistItem>();
        for (PlaylistItem groupItem : getKeepGroups())
        {
            keepItems.addAll(getKeepGroup(groupItem));
        }

        // Add only those items that do not belong to a keep group
        Playlist lstTemp = new Playlist();
        for (Iterator<PlaylistItem> it = src.items(); it.hasNext();)
        {
            PlaylistItem item = it.next();
            if (!keepItems.contains(item))
            {
                lstTemp.addItem(item);
            }
        }

        // Create random order
        lstTemp.shuffle();

        // Add the keep group items again
        Playlist result = new Playlist();
        while (!lstTemp.isEmpty())
        {
            PlaylistItem item = lstTemp.take();
            result.addItem(item);
            List<PlaylistItem> groupItems = getKeepGroup(item);
            if (groupItems != null)
            {
                for (PlaylistItem pli : groupItems)
                {
                    result.addItem(pli);
                }
            }
        }

        return result;
    }

    /**
     * Creates a playlist with the defined exact order.
     *
     * @return the playlist with exact order
     */
    private Playlist createExactOrder()
    {
        Playlist result = new Playlist();
        for (PlaylistItem item : getExactPlaylist())
        {
            result.addItem(item);
        }
        return result;
    }

    /**
     * An enumeration for the different supported order modes, in which a
     * playlist can be played.
     */
    public enum Order {
        directories, random, exact
    }
}
