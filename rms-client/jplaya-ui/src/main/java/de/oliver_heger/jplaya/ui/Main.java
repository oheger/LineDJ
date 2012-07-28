package de.oliver_heger.jplaya.ui;

import java.util.concurrent.atomic.AtomicReference;

import net.sf.jguiraffe.gui.app.Application;
import net.sf.jguiraffe.gui.app.ApplicationException;

/**
 * <p>
 * The main class of the JPlaya application.
 * </p>
 * <p>
 * This class starts up the <em>JGUIraffe</em> application and shows the main
 * window.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class Main extends Application
{
    /**
     * Constant for the name of the bean with the audio player client reference.
     */
    static final String BEAN_PLAYER_CLIENT_REF = Main.class.getName()
            + ".audioPlayerClientRef";

    /**
     * The main method of this application
     *
     * @param args command line arguments
     */
    public static void main(String[] args) throws ApplicationException
    {
        startup(new Main(), args);
    }

    /**
     * Returns the reference to the audio player client.
     *
     * @return the reference to the {@code AudioPlayerClient}
     */
    AtomicReference<AudioPlayerClient> getAudioPlayerClientRef()
    {
        // The type of this singleton bean is the same everywhere in this
        // application, therefore it is save to cast
        @SuppressWarnings("unchecked")
        AtomicReference<AudioPlayerClient> result =
                (AtomicReference<AudioPlayerClient>) getApplicationContext()
                        .getBeanContext().getBean(BEAN_PLAYER_CLIENT_REF);
        return result;
    }
}
