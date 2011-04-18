package de.oliver_heger.mediastore.localstore.impl;

import java.math.BigInteger;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.concurrent.ConcurrentInitializer;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.UniformInterface;
import com.sun.jersey.api.client.WebResource;

import de.oliver_heger.mediastore.localstore.SyncController;
import de.oliver_heger.mediastore.localstore.model.SongEntity;
import de.oliver_heger.mediastore.oauth.NotAuthorizedException;
import de.oliver_heger.mediastore.oauth.OAuthTemplate;
import de.oliver_heger.mediastore.oauth.ResourceProcessor;
import de.oliver_heger.mediastore.service.AlbumData;
import de.oliver_heger.mediastore.service.ArtistData;
import de.oliver_heger.mediastore.service.ObjectFactory;
import de.oliver_heger.mediastore.service.SongData;
import de.oliver_heger.mediastore.service.utils.DTOTransformer;

/**
 * <p>
 * A specialized command implementation for synchronizing a song.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
class SyncSongCommand extends JPACommand implements ResourceProcessor
{
    /** Constant for the path for the artist resource. */
    private static final String PATH_ARTIST = "artist";

    /** Constant for the path for the album resource. */
    private static final String PATH_ALBUM = "album";

    /** Constant for the path for the song resource. */
    private static final String PATH_SONG = "song";

    /** Constant for the milliseconds factor. */
    private static final long MILLIS = 1000;

    /** The song to be synchronized. */
    private final SongEntity song;

    /** The controller object for the sync operation. */
    private final SyncController controller;

    /** The OAuth template. */
    private final OAuthTemplate oauthTemplate;

    /** The object factory. */
    private final ObjectFactory factory;

    /** A cache for the current song data object. */
    private SongData songData;

    /**
     * Creates a new instance of {@code SyncSongCommand} and initializes it.
     *
     * @param emfInit the initializer for the {@code EntityManagerFactory}
     * @param songEntity the song entity to be synchronized
     * @param ctrl the controller for the sync operation
     * @param templ the template for OAuth calls
     */
    public SyncSongCommand(ConcurrentInitializer<EntityManagerFactory> emfInit,
            SongEntity songEntity, SyncController ctrl, OAuthTemplate templ)
    {
        super(emfInit, false);
        song = songEntity;
        controller = ctrl;
        oauthTemplate = templ;
        factory = new ObjectFactory();
    }

    /**
     * Returns the controller of the sync operation used by this command.
     *
     * @return the sync controller
     */
    public SyncController getController()
    {
        return controller;
    }

    /**
     * Returns the template for OAuth requests used by this command.
     *
     * @return the OAuth template
     */
    public OAuthTemplate getOAuthTemplate()
    {
        return oauthTemplate;
    }

    /**
     * Returns the entity for the song that is to be synchronized by this
     * command.
     *
     * @return the song entity
     */
    public SongEntity getSong()
    {
        return song;
    }

    /**
     * {@inheritDoc} This implementation first handles the synchronization with
     * the server. Then the features of the base class related to JPA processing
     * are used in order to update the song entity.
     */
    @Override
    public void execute() throws Exception
    {
        if (executeSyncOperation())
        {
            super.execute();
        }
    }

    /**
     * {@inheritDoc} This implementation performs the actual synchronization of
     * the current song entity and related data. Because it may execute multiple
     * server calls no response object is returned; response codes indicating
     * insufficient privileges are directly handled.
     */
    @Override
    public ClientResponse doWithResource(WebResource resource)
            throws NotAuthorizedException
    {
        boolean artistResult = syncArtist(resource);
        boolean albumResult = syncAlbum(resource);
        boolean songResult = syncSong(resource, fetchSongData());

        getController().afterSongSync(fetchSongData(), songResult, artistResult,
                albumResult);
        return null;
    }

    /**
     * {@inheritDoc} This is just a dummy implementation because no response
     * processing is needed.
     */
    @Override
    public void processResponse(ClientResponse resp)
            throws NotAuthorizedException
    {
    }

    /**
     * {@inheritDoc} This implementation resets the current play count.
     */
    @Override
    protected void executeJPAOperation(EntityManager em)
    {
        SongEntity e = em.find(SongEntity.class, getSong().getId());

        if (e == null)
        {
            getLog().warn("Could not find song entity: " + getSong());
        }
        else
        {
            e.resetCurrentCount();
        }
    }

    /**
     * Performs the actual sync operation. This implementation uses the OAuth
     * template passed to the constructor to call the server for the song and
     * the related data objects.
     *
     * @return a flag whether the sync operation was executed
     */
    boolean executeSyncOperation()
    {
        SongData data = fetchSongData();
        boolean executed = false;

        if (getController().beforeSongSync(data))
        {
            executed = true;
            if (!getOAuthTemplate().execute(this, getController().getOAuthCallback()))
            {
                getController().failedSongSync(data);
                executed = false;
            }
        }

        return executed;
    }

    /**
     * Creates a {@link SongData} object for the synchronization of the song
     * entity. This object is sent to the server.
     *
     * @return the {@link SongData} object
     */
    SongData createSongData()
    {
        SongData data = factory.createSongData();
        DTOTransformer.transform(getSong(), data);

        data.setPlayCount(getSong().getCurrentPlayCount());
        if (getSong().getDuration() != null)
        {
            data.setDuration(BigInteger.valueOf(getSong().getDuration().intValue()
                    * MILLIS));
        }
        if (getSong().getAlbum() != null)
        {
            data.setAlbumName(getSong().getAlbum().getName());
        }
        if (getSong().getArtist() != null)
        {
            data.setArtistName(getSong().getArtist().getName());
        }

        return data;
    }

    /**
     * Obtains the {@link SongData} object for the current song entity to be
     * synchronized. If no data object has been created yet, it is created now.
     * Otherwise, the cached instance is returned.
     *
     * @return the {@link SongData} object for the current song entity
     */
    SongData fetchSongData()
    {
        if (songData == null)
        {
            songData = createSongData();
        }
        return songData;
    }

    /**
     * Creates an {@link ArtistData} object for the synchronization of the
     * artist of the current song. This object is sent to the server. This
     * implementation expects that an artist entity is available.
     *
     * @return the {@link ArtistData} object
     */
    ArtistData createArtistData()
    {
        ArtistData data = factory.createArtistData();
        DTOTransformer.transform(getSong().getArtist(), data);
        return data;
    }

    /**
     * Creates an {@link AlbumData} object for the synchronization of the album
     * of the current song. This object is sent to the server. This
     * implementation expects that an album entity is available.
     *
     * @return the {@link AlbumData} object
     */
    AlbumData createAlbumData()
    {
        AlbumData data = factory.createAlbumData();
        DTOTransformer.transform(getSong().getAlbum(), data);
        return data;
    }

    /**
     * Synchronizes data about the artist of the current song.
     *
     * @param resource the fully initialized resource object
     * @return a flag whether a new artist was created on the server
     * @throws NotAuthorizedException if the call was not authorized
     */
    boolean syncArtist(WebResource resource) throws NotAuthorizedException
    {
        return syncRequest(resource, PATH_ARTIST, createArtistData());
    }

    /**
     * Synchronizes data about the album of the current song.
     *
     * @param resource the fully initialized resource object
     * @return a flag whether a new album was created on the server
     * @throws NotAuthorizedException if the call was not authorized
     */
    boolean syncAlbum(WebResource resource) throws NotAuthorizedException
    {
        return syncRequest(resource, PATH_ALBUM, createAlbumData());
    }

    /**
     * Synchronizes the current song.
     *
     * @param resource the fully initialized resource object
     * @param songData the data object for the song to be synchronized
     * @return a flag whether a new song was created on the server
     * @throws NotAuthorizedException if the call was not authorized
     */
    boolean syncSong(WebResource resource, SongData songData)
            throws NotAuthorizedException
    {
        return syncRequest(resource, PATH_SONG, createSongData());
    }

    /**
     * Helper method for performing a request for a specific resource. This
     * method executes the call and evaluates the response.
     *
     * @param resource the fully initialized resource object
     * @param path the sub-path of the resource this request is about
     * @param data the data object to be sent
     * @return a flag whether a new object was created on the server
     * @throws NotAuthorizedException if the call was not authorized
     */
    boolean syncRequest(WebResource resource, String path, Object data)
            throws NotAuthorizedException
    {
        if (data == null)
        {
            return false;
        }

        ClientResponse resp =
                prepareResource(resource, path).put(ClientResponse.class, data);
        OAuthTemplate.checkAuthorizedResponse(resp);

        return resp.getStatus() == ClientResponse.Status.CREATED
                .getStatusCode();
    }

    /**
     * Prepares the specified resource for a call.
     *
     * @param resource the resource
     * @param path the sub path
     * @return the builder for the request
     */
    UniformInterface prepareResource(WebResource resource, String path)
    {
        return resource.path(path).accept(MediaType.APPLICATION_XML);
    }
}
