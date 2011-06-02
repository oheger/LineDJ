package de.olix.playa.engine.mediainfo;

/**
 * <p>
 * A class providing access to media information for the song files to be
 * played.
 * </p>
 * <p>
 * An instance of this class can be passed an arbitrary number of URIs to audio
 * files. It will then try to extract media information for all of these files.
 * It is then possible to query this data.
 * </p>
 * <p>
 * Because extracting media information from a file may take a while data is
 * obtained asynchronously in a background thread. Once new data becomes
 * available, an event is fired to registered listeners. Clients can react on
 * such events for instance by updating their UI if necessary. If data about a
 * media file is queried which has not yet been retrieved, <b>null</b> is
 * returned. This data may or may not become available later (if the file does
 * not contain media information in a supported form, it is not stored, and
 * queries always return a <b>null</b> result).
 * </p>
 * <p>
 * Some objects an instance depends on have to be passed to the constructor.
 * This also includes the {@code ExecutorService} responsible for background
 * execution. This class is thread-safe. Queries can be issued, and new songs to
 * be processed, can be passed from arbitrary threads.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDataManager
{

}
