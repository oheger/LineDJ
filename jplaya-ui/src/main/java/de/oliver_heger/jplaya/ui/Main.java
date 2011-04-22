package de.oliver_heger.jplaya.ui;

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
     * The main method of this application
     *
     * @param args command line arguments
     */
    public static void main(String[] args) throws ApplicationException
    {
        startup(new Main(), args);
    }
}
