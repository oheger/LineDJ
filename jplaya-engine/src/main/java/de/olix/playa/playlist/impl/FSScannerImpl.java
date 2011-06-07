package de.olix.playa.playlist.impl;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.vfs.FileFilter;
import org.apache.commons.vfs.FileFilterSelector;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSelectInfo;
import org.apache.commons.vfs.FileSelector;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;

import de.olix.playa.playlist.FSScanner;

/**
 * <p>
 * A default implementation of the {@code FSScanner} interface.
 * </p>
 * <p>
 * This implementation uses <em>Commons VFS</em> to traverse all directories
 * below the root directory. All audio files that match one of the configured
 * extensions are collected.
 * </p>
 * <p>
 * Implementation note: This class is thread-safe. The URI of the root directory
 * can be changed at any time by any thread. However, a currently running scan
 * process will not be affected.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class FSScannerImpl implements FSScanner
{
    /** Constant for the default extension string. */
    private static final String DEFAULT_EXTS = "mp3";

    /** Constant for the regular expression for splitting file extensions. */
    private static final String REG_SPLIT_EXTS = "\\s*[,;]\\s*";

    /** The file system manager. */
    private final FileSystemManager manager;

    /** The set with supported extensions. */
    private final Set<String> supportedExtensions;

    /** A filter for filtering for audio files with supported extensions. */
    private final FileFilter filter;

    /** The URI of the root directory to be scanned. */
    private volatile String rootURI;

    /**
     * Creates a new instance of {@code FSScannerImpl} and initializes it with
     * the {@code FileSystemManager} and the file extensions of audio files
     * supported. The extensions are provided as a comma-separated string, e.g.
     * "mp3,mp4,wav".
     *
     * @param fsm the {@code FileSystemManager} (must not be <b>null</b>)
     * @param extensions a string with supported extensions for audio files
     *        (must not be <b>null</b> or empty)
     * @throws IllegalArgumentException if a required parameter is missing
     */
    public FSScannerImpl(FileSystemManager fsm, String extensions)
    {
        if (fsm == null)
        {
            throw new IllegalArgumentException(
                    "FileSystemManager must not be null!");
        }
        if (StringUtils.isEmpty(extensions))
        {
            throw new IllegalArgumentException(
                    "Extension string must not be empty!");
        }

        manager = fsm;
        supportedExtensions =
                Collections.unmodifiableSet(parseExtensions(extensions));
        filter = createFilter();
    }

    /**
     * Creates a new instance of {@code FSScannerImpl} and initializes it with
     * the {@code FileSystemManager} and default file extensions.
     *
     * @param fsm the {@code FileSystemManager} (must not be <b>null</b>)
     * @throws IllegalArgumentException if a required parameter is missing
     */
    public FSScannerImpl(FileSystemManager fsm)
    {
        this(fsm, DEFAULT_EXTS);
    }

    /**
     * Returns a set with the file extensions supported by this scanner. The
     * scan operation only takes files with these extensions into account.
     *
     * @return a set with the file extensions supported by this scanner
     */
    public Set<String> getSupportedExtensions()
    {
        return supportedExtensions;
    }

    /**
     * Returns the URI of the root directory of the structure to be scanned.
     * This may be <b>null</b> if no root directory has been set so far.
     *
     * @return the URI of the root directory
     */
    @Override
    public String getRootURI()
    {
        return rootURI;
    }

    /**
     * Sets the URI of the root directory of the folder structure to be scanned.
     * This property must be set before the {@code scan()} method is called.
     *
     * @param rootURI the URI of the root directory
     */
    @Override
    public void setRootURI(String rootURI)
    {
        this.rootURI = rootURI;
    }

    /**
     * Scans the directory structure defined by the root URI and collects the
     * URIs of files that match the selection criteria. If no root URI has been
     * set, an exception is thrown.
     *
     * @return a collection with the files that have been retrieved
     * @throws IOException if an IO exception occurs
     * @throws IllegalStateException if the root directory has not been set
     */
    @Override
    public Collection<String> scan() throws IOException
    {
        String uri = getRootURI();
        if (uri == null)
        {
            throw new IllegalStateException("No root URI has been set!");
        }

        Collection<String> results = new LinkedHashSet<String>();
        FileObject root = manager.resolveFile(uri);
        scanDirecotry(root, results, new FileFilterSelector(getFilter()));

        return results;
    }

    /**
     * Returns the filter which determines which audio files to be included.
     *
     * @return the file filter for audio files
     */
    FileFilter getFilter()
    {
        return filter;
    }

    /**
     * Checks whether the specified file has one of the supported file
     * extensions.
     *
     * @param fo the file object in question
     * @return a flag whether the extension of this file is supported
     */
    private boolean checkExtension(FileObject fo)
    {
        FileName name = fo.getName();
        String ext = name.getExtension();
        return getSupportedExtensions().contains(
                ext.toLowerCase(Locale.ENGLISH));
    }

    /**
     * Creates a filter object for traversing a directory structure and
     * collecting files with the supported extensions.
     *
     * @return the filter object
     */
    private FileFilter createFilter()
    {
        return new FileFilter()
        {
            @Override
            public boolean accept(FileSelectInfo info)
            {
                FileObject file = info.getFile();
                try
                {
                    FileType type = file.getType();
                    if (FileType.FOLDER.equals(type))
                    {
                        return true;
                    }

                    return FileType.FILE.equals(type) && checkExtension(file);
                }
                catch (FileSystemException fsex)
                {
                    return false;
                }
            }
        };
    }

    /**
     * Helper method for recursively scanning a director structure.
     *
     * @param dir the current root directory
     * @param results the collection for the results
     * @param sel the file selector
     * @throws FileSystemException if an IO error occurs
     */
    private void scanDirecotry(FileObject dir, Collection<String> results,
            FileSelector sel) throws FileSystemException
    {
        FileObject[] files = dir.findFiles(sel);

        for (FileObject fo : files)
        {
            if (FileType.FOLDER.equals(fo.getType()))
            {
                scanDirecotry(fo, results, sel);
            }
            else
            {
                results.add(fo.getName().getURI());
            }
        }
    }

    /**
     * Parses the string with the expressions and adds the results to a set.
     *
     * @param exts the expression string
     * @return the set with the extracted extensions
     */
    private static Set<String> parseExtensions(String exts)
    {
        String[] extensions = exts.split(REG_SPLIT_EXTS);
        Set<String> extSet = new HashSet<String>();
        for (String e : extensions)
        {
            extSet.add(e.toLowerCase(Locale.ENGLISH));
        }
        return extSet;
    }
}
