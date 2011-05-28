package de.olix.playa.playlist.xml;

import java.io.File;

import org.apache.commons.lang.StringUtils;

/**
 * <p>
 * A simple class that represents an item in a playlist.
 * </p>
 * <p>
 * This class stores the relative path and the file name of an audio file that
 * is part of a playlist. It implements the typical methods inherited from
 * <code>Object</code> in a meainingful way, so that it fully supports all
 * kinds of collections.
 * </p>
 * 
 * @author Oliver Heger
 * @version $Id$
 */
class PlaylistItem implements Comparable<PlaylistItem>
{
    /** Constant for the internally used path separator. */
    static final String PATH_SEPARATOR = "/";

    /** Constant for an empty relative path. */
    private static final String EMPTY_PATH = StringUtils.EMPTY;

    /** Stores the relative path of this item. */
    private String pathName;

    /**
     * Creates a new instance of <code>PlaylistItem</code> and initializes it.
     * 
     * @param rootPath the root path of the directory structure; this path will
     * be stripped, so that only the relative path information is saved
     * @param file the file representing this playlist item
     */
    public PlaylistItem(String rootPath, File file)
    {
        String rootName = toInternalForm(rootPath);
        if (!rootName.endsWith(PATH_SEPARATOR))
        {
            rootName += PATH_SEPARATOR;
        }
        String filePath = toInternalForm(file.getAbsolutePath());
        assert filePath.startsWith(rootName) : "Invalid file or root path!";
        pathName = filePath.substring(rootName.length());
    }

    /**
     * Creates a new instance of <code>PlaylistItem</code> and initializes it
     * with the given path name. This name will be saved unchanged.
     * 
     * @param path the path name
     */
    public PlaylistItem(String path)
    {
        pathName = path;
    }

    /**
     * Returns the file name of this playlist item. This method strips the
     * (relative) path and only returns the file name.
     * 
     * @return the file name
     */
    public String getName()
    {
        int pos = splitPos();
        return (pos < 0) ? getPathName() : getPathName().substring(pos + 1);
    }

    /**
     * Returns the relative path of this playlist item.
     * 
     * @return the relative path
     */
    public String getPath()
    {
        int pos = splitPos();
        return (pos <= 0) ? EMPTY_PATH : getPathName().substring(0, pos);
    }

    /**
     * Returns the position in the path name where the path ends and the file
     * name begins.
     * 
     * @return the split position
     */
    private int splitPos()
    {
        return getPathName().lastIndexOf(PATH_SEPARATOR);
    }

    /**
     * Returns the path name of this playlist item. This is the relative path
     * including the file name.
     * 
     * @return the path name of this item
     */
    public String getPathName()
    {
        return pathName;
    }

    /**
     * Returns the file object for this playlist item. For this purpose the
     * internally stored relative path information is appended to the passed in
     * root path.
     * 
     * @param rootPath the root of the source directory structure
     * @return the file object for this playlist item
     */
    public File getFile(File rootPath)
    {
        return new File(rootPath, replace(getPathName(), PATH_SEPARATOR,
                File.separator));
    }

    @Override
    /**
     * Tests whether this object equals another one. Two objects of this class
     * are equal if they point to the same relative file name.
     * 
     * @param obj the object to be compared
     * @return a flag whether these objects are equal
     */
    public boolean equals(Object obj)
    {
        if (obj == this)
        {
            return true;
        }
        if (!(obj instanceof PlaylistItem))
        {
            return false;
        }

        PlaylistItem c = (PlaylistItem) obj;
        return getPathName().equals(c.getPathName());
    }

    @Override
    /**
     * Calculates a hash code for this object.
     * 
     * @return a hash code
     */
    public int hashCode()
    {
        return getPathName().hashCode();
    }

    @Override
    /**
     * Returns a string representation of this object.
     * 
     * @return a string for this object
     */
    public String toString()
    {
        StringBuilder buf = new StringBuilder(64);
        buf.append(getClass().getName()).append(": ");
        buf.append(getPathName());
        return buf.toString();
    }

    /**
     * Compares this item with another one. Playlist items are ordered
     * alphabetically by their relative path and their file name.
     * 
     * @param o the object to compare to
     * @return a flag, which object is greater
     */
    public int compareTo(PlaylistItem o)
    {
        int c = getPath().compareTo(o.getPath());
        if (c == 0)
        {
            c = getName().compareTo(o.getName());
        }
        return c;
    }

    /**
     * A helper method for replacing the system file separator by the internally
     * used one. If the separators differ, a replace operation is executed.
     * 
     * @param src the source string
     * @param sep1 the first separator to be replaced
     * @param sep2 the second separator, the replacement for the first one
     * @return the resulting string
     */
    String replace(String src, String sep1, String sep2)
    {
        return (!sep1.equals(sep2)) ? StringUtils.replace(src, sep1, sep2)
                : src;
    }

    /**
     * Converts the passed in path name into a canonical, internally used
     * representation. The system specific file path separator will be replaced
     * by a generic one.
     * 
     * @param src the string to be converted
     * @return the converted string
     */
    private String toInternalForm(String src)
    {
        return replace(src, File.separator, PATH_SEPARATOR);
    }
}
