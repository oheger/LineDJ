package de.oliver_heger.mediastore.client;

import java.util.Date;

import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.junit.client.GWTTestCase;

/**
 * Test class for {@code I18NFormatter}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestI18NFormatter extends GWTTestCase
{
    @Override
    public String getModuleName()
    {
        return "de.oliver_heger.mediastore.RemoteMediaStore";
    }

    /**
     * Helper method for testing a default date/time format. The method checks
     * whether the passed in format is one of the given default formats.
     *
     * @param fmt the actual format
     * @param defs the list of allowed default formats
     */
    private void checkDefaultFormat(DateTimeFormat fmt,
            DateTimeFormat.PredefinedFormat... defs)
    {
        boolean found = false;
        for (DateTimeFormat.PredefinedFormat def : defs)
        {
            if (fmt.equals(DateTimeFormat.getFormat(def)))
            {
                found = true;
                break;
            }
        }
        assertTrue("Unexpected default format", found);
    }

    /**
     * Tests whether a valid default date format is returned.
     */
    public void testGetDefaultDateFormat()
    {
        checkDefaultFormat(new I18NFormatter().getDefaultDateFormat(),
                DateTimeFormat.PredefinedFormat.DATE_FULL,
                DateTimeFormat.PredefinedFormat.DATE_LONG,
                DateTimeFormat.PredefinedFormat.DATE_MEDIUM,
                DateTimeFormat.PredefinedFormat.DATE_SHORT);
    }

    /**
     * Tests whether a valid default time format is returned.
     */
    public void testGetDefaultTimeFormat()
    {
        checkDefaultFormat(new I18NFormatter().getDefaultTimeFormat(),
                DateTimeFormat.PredefinedFormat.TIME_FULL,
                DateTimeFormat.PredefinedFormat.TIME_LONG,
                DateTimeFormat.PredefinedFormat.TIME_MEDIUM,
                DateTimeFormat.PredefinedFormat.TIME_SHORT);
    }

    /**
     * Tests whether a valid default date time format is returned.
     */
    public void testGetDefaultDateTimeFormat()
    {
        checkDefaultFormat(new I18NFormatter().getDefaultDateTimeFormat(),
                DateTimeFormat.PredefinedFormat.DATE_TIME_FULL,
                DateTimeFormat.PredefinedFormat.DATE_TIME_LONG,
                DateTimeFormat.PredefinedFormat.DATE_TIME_MEDIUM,
                DateTimeFormat.PredefinedFormat.DATE_TIME_SHORT);
    }

    /**
     * Tests formatDate() if a null format object is passed in.
     */
    public void testFormatDateNullFmt()
    {
        I18NFormatter fmt = new I18NFormatter();
        try
        {
            fmt.formatDate(new Date(), null);
            fail("Could format with null format!");
        }
        catch (NullPointerException npex)
        {
            // ok
        }
    }

    /**
     * Tests whether a format object is correctly applied.
     */
    public void testFormatDateWithFormat()
    {
        final Date dateToFmt = new Date();
        final String result = "resultOfFormatting";
        DateTimeFormat dtf = new DateTimeFormat("yyyy.MM.dd")
        {
            @Override
            public String format(Date date)
            {
                assertSame("Unexpected date", dateToFmt, date);
                return result;
            }
        };
        I18NFormatter fmt = new I18NFormatter();
        assertEquals("Wrong result", result, fmt.formatDate(dateToFmt, dtf));
    }

    /**
     * Tests formatDate() for a null date.
     */
    public void testFormatDateNull()
    {
        I18NFormatter fmt = new I18NFormatter();
        assertEquals("Wrong result for null date", "",
                fmt.formatDate(null, fmt.getDefaultDateFormat()));
    }

    /**
     * Tests whether a date can be formatted using the default date format.
     */
    public void testFormatDateDefault()
    {
        I18NFormatter fmt = new I18NFormatter();
        Date date = new Date();
        String res = fmt.formatDate(date);
        assertEquals("Wrong result",
                fmt.formatDate(date, fmt.getDefaultDateFormat()), res);
    }

    /**
     * Tests whether a date can be formatted using the default time format.
     */
    public void testFormatTimeDefault()
    {
        I18NFormatter fmt = new I18NFormatter();
        Date date = new Date();
        String res = fmt.formatTime(date);
        assertEquals("Wrong result",
                fmt.formatDate(date, fmt.getDefaultTimeFormat()), res);
    }

    /**
     * Tests whether a date can be formatted using the default date/time format.
     */
    public void testFormatDateTimeDefault()
    {
        I18NFormatter fmt = new I18NFormatter();
        Date date = new Date();
        String res = fmt.formatDateTime(date);
        assertEquals("Wrong result",
                fmt.formatDate(date, fmt.getDefaultDateTimeFormat()), res);
    }
}
