package de.oliver_heger.mediastore.client;

import com.google.gwt.resources.client.ClientBundle;
import com.google.gwt.resources.client.ImageResource;

/**
 * <p>
 * Interface allowing access to images used by the application.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public interface ImageResources extends ClientBundle
{
    /**
     * Returns the progress indicator image.
     *
     * @return the progress indicator image
     */
    ImageResource progressIndicator();

    /**
     * Returns the icon for opening the details view of an element.
     *
     * @return the details view icon
     */
    ImageResource viewDetails();
}
