package de.oliver_heger.jplaya.engine.mediainfo;

import java.util.EventObject;

/**
 * <p>
 * An event class which indicates that a new {@code SongData} object for a media
 * file has been retrieved.
 * </p>
 * <p>
 * Events of this type are fired by the {@link SongDataManager} class when media
 * information for a file has been successfully obtained. They contain
 * properties identifying the media file in question. The corresponding
 * {@code SongData} object can then be retrieved from the
 * {@link SongDataManager} object which is the source of this event. Event
 * receivers can react by updating their information about the media file in
 * question.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class SongDataEvent extends EventObject
{
    /**
     * The serial version UID.
     */
    private static final long serialVersionUID = 20110602L;

    /** The URI of the media file this event is about. */
    private final String mediaFileURI;

    /** The ID of the media file this event is about. */
    private final Object mediaFileID;

    /**
     * Creates a new instance of {@code SongDataEvent} and initializes all
     * properties.
     *
     * @param source the manager which is the source of this event
     * @param uri the URI of the media file in question
     * @param id the ID of the media file in question
     */
    public SongDataEvent(SongDataManager source, String uri, Object id)
    {
        super(source);
        mediaFileURI = uri;
        mediaFileID = id;
    }

    /**
     * Returns the URI of the media file this event is about.
     *
     * @return the URI of the affected media file
     */
    public String getMediaFileURI()
    {
        return mediaFileURI;
    }

    /**
     * Returns the ID of the media file this event is about. This is the object
     * that was passed to the {@code SongDataManager} when it was passed the
     * file. The ID is optional, so this method may return <b>null</b>.
     *
     * @return the ID of the affected media file
     */
    public Object getMediaFileID()
    {
        return mediaFileID;
    }

    /**
     * Returns the {@code SongDataManager} that has generated this event. This
     * manager can be queried for the {@code SongData} object for the affected
     * media file.
     *
     * @return the {@code SongDataManager} which is the source of this event
     */
    public SongDataManager getManager()
    {
        return (SongDataManager) getSource();
    }
}
