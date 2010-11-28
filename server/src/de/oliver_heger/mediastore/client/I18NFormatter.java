package de.oliver_heger.mediastore.client;

import java.util.Date;

import com.google.gwt.i18n.client.DateTimeFormat;

/**
 * <p>
 * A helper class for formatting data based on the user's locale.
 * </p>
 * <p>
 * This class is used by some UI components in order to format date and time
 * values or numbers. Internally, the class makes use of functionality provided
 * by GWT to do the formatting. The main goal of this class is to have this
 * functionality at a central location. This also simplifies testing because
 * formatting methods do not have to be tested for each UI component.
 * </p>
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class I18NFormatter
{
    /** Constant for an empty string. */
    private static final String EMPTY = "";

    /**
     * Returns the default format for dates.
     *
     * @return the default format for dates
     */
    public DateTimeFormat getDefaultDateFormat()
    {
        return DateTimeFormat
                .getFormat(DateTimeFormat.PredefinedFormat.DATE_SHORT);
    }

    /**
     * Returns the default format for times.
     *
     * @return the default format for times
     */
    public DateTimeFormat getDefaultTimeFormat()
    {
        return DateTimeFormat
                .getFormat(DateTimeFormat.PredefinedFormat.TIME_SHORT);
    }

    /**
     * Returns the default format for dates and times (i.e. timestamp values).
     *
     * @return the default format for dates and times
     */
    public DateTimeFormat getDefaultDateTimeFormat()
    {
        return DateTimeFormat
                .getFormat(DateTimeFormat.PredefinedFormat.DATE_TIME_SHORT);
    }

    /**
     * Formats the specified date with the given format. This method handles
     * <b>null</b> date values gracefully.
     *
     * @param date the date to be formatted
     * @param fmt the format (must not be <b>null</b>)
     * @return the formatted date as string
     * @throws NullPointerException if the format object is <b>null</b>
     */
    public String formatDate(Date date, DateTimeFormat fmt)
    {
        return (date == null) ? EMPTY : fmt.format(date);
    }

    /**
     * Formats a date object using the default format. This is just a
     * convenience method.
     *
     * @param date the date to be formatted
     * @return the formatted date as string
     */
    public String formatDate(Date date)
    {
        return formatDate(date, getDefaultDateFormat());
    }

    /**
     * Formats a date object using the default time format. This is just a
     * convenience method.
     *
     * @param date the date to be formatted
     * @return the formatted date as string
     */
    public String formatTime(Date date)
    {
        return formatDate(date, getDefaultTimeFormat());
    }

    /**
     * Formats a date object using the default date/time format. This is just a
     * convenience method.
     *
     * @param date the date to be formatted
     * @return the formatted date as string
     */
    public String formatDateTime(Date date)
    {
        return formatDate(date, getDefaultDateTimeFormat());
    }
}
