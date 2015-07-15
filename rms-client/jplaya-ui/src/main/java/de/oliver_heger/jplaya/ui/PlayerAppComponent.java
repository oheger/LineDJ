package de.oliver_heger.jplaya.ui;

import net.sf.jguiraffe.gui.app.ApplicationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

import scala.actors.Actor;
import de.oliver_heger.splaya.AudioPlayer;
import de.oliver_heger.splaya.AudioPlayerFactory;

/**
 * <p>
 * This class implements an OSGi declarative services component for the whole
 * audio player application.
 * </p>
 * <p>
 * This component class declares a mandatory reference to the
 * {@link AudioPlayerFactory} service. As soon as this service becomes
 * available, an audio player instance is created, the main player application
 * is started, and the reference is passed to this application.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class PlayerAppComponent
{
    /** The logger. */
    private final Log log = LogFactory.getLog(getClass());

    /** Stores the audio player. */
    private volatile AudioPlayer audioPlayer;

    /**
     * Activates this component. This method actually starts the audio player
     * application. When it is called all mandatory service references have been
     * set.
     *
     * @param ctx the bundle context
     */
    void activate(final BundleContext ctx)
    {
        log.info("Activating PlayerAppComponent.");
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Main app = createAndStartupApplication();
                    initApplication(app, ctx);
                }
                catch (ApplicationException aex)
                {
                    log.error("Could not start application!", aex);
                }
            }
        }.start();
    }

    /**
     * Injects an instance of the {@code AudioPlayerFactory} service into this
     * component.
     *
     * @param factory the factory service
     */
    void bindAudioPlayerFactory(AudioPlayerFactory factory)
    {
        log.info("Binding AudioPlayerFactory.");
        audioPlayer = factory.createAudioPlayer();
        log.info("Audio player was created.");
    }

    /**
     * Notifies this component that the audio player factory service is no
     * longer available.
     *
     * @param factory the factory service instance to be removed
     */
    void unbindAudioPlayerFactory(AudioPlayerFactory factory)
    {
        log.info("AudioPlayerFactory unbound.");
    }

    /**
     * Creates an instance of the main application class and starts it.
     *
     * @return the newly created application object
     * @throws ApplicationException if an error occurs
     */
    Main createAndStartupApplication() throws ApplicationException
    {
        Main app = new Main();
        Main.startup(app, new String[0]);
        return app;
    }

    /**
     * Initializes the application so that playback can start. This
     * implementation passes the audio player to the UI components. It also
     * registers all listener actors.
     *
     * @param app the main player application
     * @param ctx the bundle context
     */
    void initApplication(Main app, BundleContext ctx)
    {
        app.setExitHandler(createExitHandler(ctx));
        AudioPlayer player = audioPlayer;
        registerListenerActors(app, player);
        injectAudioPlayer(app, player);
    }

    /**
     * Creates an exit handler which terminates the OSGi framework when called.
     *
     * @param ctx the bundle context
     * @return the exit handler
     */
    Runnable createExitHandler(final BundleContext ctx)
    {
        return new Runnable()
        {
            @Override
            public void run()
            {
                Bundle sysBundle = ctx.getBundle(0);
                try
                {
                    sysBundle.stop();
                }
                catch (BundleException bex)
                {
                    log.error("Could not stop OSGi framework!", bex);
                }
            }
        };
    }

    /**
     * Registers the event listener actors at the current audio player instance.
     *
     * @param app the main application object
     * @param player the audio player instance
     */
    private static void registerListenerActors(Main app, AudioPlayer player)
    {
        for (Actor actor : app.fetchAudioPlayerListenerActors())
        {
            player.addActorListener(actor);
        }
    }

    /**
     * Passes the audio player reference to the UI components of the
     * application.
     *
     * @param app the application
     * @param player the audio player instance
     */
    private static void injectAudioPlayer(Main app, AudioPlayer player)
    {
        app.getAudioPlayerClientRef().get().bindAudioPlayer(player);
    }
}
