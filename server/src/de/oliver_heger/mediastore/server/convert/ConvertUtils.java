package de.oliver_heger.mediastore.server.convert;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import de.oliver_heger.mediastore.server.model.AbstractSynonym;

/**
 * <p>
 * A class with utility methods related to the conversion of entity objects.
 * </p>
 * <p>
 * This class provides some helper method for converting a number of entities to
 * their corresponding info objects.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public final class ConvertUtils
{
    /**
     * Private constructor so that no instances can be created.
     */
    private ConvertUtils()
    {
    }

    /**
     * Converts the specified list with entities to a list with info objects to
     * be passed to the client. This method is called if entity objects of a
     * certain type are not be passed to the client, but a corresponding data
     * object type is to be used. The passed in converter object is called for
     * each entity contained in the source collection; the resulting data object
     * is added to the list with the results.
     *
     * @param <E> the type of entity objects to be processed
     * @param <D> the type of data objects to be returned
     * @param src the list with the source entity objects
     * @param converter the converter which performs the conversion
     * @return the list with the converted objects
     */
    public static <E, D> List<D> convertEntities(Collection<? extends E> src,
            EntityConverter<E, D> converter)
    {
        if (converter == null)
        {
            throw new NullPointerException("Converter must not be null!");
        }
        if (src == null)
        {
            return Collections.emptyList();
        }

        List<D> results = new ArrayList<D>(src.size());
        for (E e : src)
        {
            results.add(converter.convert(e));
        }

        return results;
    }

    /**
     * Extracts the synonym names from the given collection of synonym entities.
     * The extracted names are returned as a set of strings.
     *
     * @param synonyms the collection with synonym entities (may be <b>null</b>)
     * @return a set with the synonym names that have been extracted
     */
    public static Set<String> extractSynonymNames(
            Collection<? extends AbstractSynonym> synonyms)
    {
        Set<String> names = new TreeSet<String>();

        if (synonyms != null)
        {
            for (AbstractSynonym as : synonyms)
            {
                names.add(as.getName());
            }
        }

        return names;
    }
}
