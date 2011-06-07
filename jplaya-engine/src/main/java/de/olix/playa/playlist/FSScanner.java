package de.olix.playa.playlist;

import java.io.IOException;
import java.util.Collection;

/**
 * <p>
 * Definition of an interface for a component which scans a file system and
 * extracts all audio files from it.
 * </p>
 * <p>
 * An object implementing this interface is used to search the source media for
 * audio files which can be added to the play list. Typically an implementation
 * will recursively scan the directory structures below a given root directory
 * and collect all compatible sound files. A collection with the URIs of all
 * audio files found is the result of this scan operation.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface FSScanner
{
    /**
     * Returns the URI of the root directory which is scanned for audio files.
     *
     * @return the URI of the root directory
     */
    String getRootURI();

    /**
     * Sets the URI of the root directory which is scanned for audio files. This
     * method should be called before this component is actually used to ensure
     * that it is properly initialized.
     *
     * @param rootURI the URI of the root directory
     */
    void setRootURI(String rootURI);

    /**
     * Scans the root directory and returns a collection with the URIs of all
     * audio files which could be found. Note that the order in which the URIs
     * are returned does not matter.
     *
     * @return a collection with the URIs of the audio files found on the medium
     * @throws IOException if an error occurs while scanning the medium
     */
    Collection<String> scan() throws IOException;
}
