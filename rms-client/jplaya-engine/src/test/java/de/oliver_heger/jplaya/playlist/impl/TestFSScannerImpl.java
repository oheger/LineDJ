package de.oliver_heger.jplaya.playlist.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.vfs.FileName;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSelectInfo;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.FileType;
import org.apache.commons.vfs.VFS;
import org.easymock.EasyMock;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.oliver_heger.jplaya.playlist.impl.FSScannerImpl;

/**
 * Test class for {@code FSScannerImpl}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestFSScannerImpl
{
    /** An array with the test extensions. */
    private static final String[] EXTENSIONS = {
            "mp3", "wav"
    };

    /** An array with some test interprets. */
    private static final String[] INTERPRETS = {
            "Mike Oldfield", "Pink Floyd"
    };

    /** An array with some albums of the test interprets. */
    private static final String[][] ALBUMS = {
            {
                    "Crisis", "Discovery", "Islands", "QE2", "Tubular Bells"
            },
            {
                    "Animals", "At\u00f6m Heart Mother",
                    "Dark Side of the Moon", "The Wall"
            }
    };

    /** An array with the numbers of tracks stored on the test albums. */
    private static final int[][] TRACKS = {
            {
                    6, 9, 8, 9, 2
            }, {
                    5, 7, 5, 16
            }
    };

    /** Constant for the name of a track file. */
    private static final String TRACK_FILE = " - TRACK.mp3";

    /** Constant for the content of a dummy music file. */
    private static final String FILE_CONTENT = "This is a test dummy file.";

    /** A set with test file URIs that have been created. */
    private Set<String> testFileURIs;

    /** The VFS file system manager. */
    private static FileSystemManager manager;

    /** A helper object for dealing with temporary files and folders. */
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @BeforeClass
    public static void setUpBeforeClass() throws Exception
    {
        manager = VFS.getManager();
    }

    /**
     * Generates an extension string using the specified separator.
     *
     * @param separator the separator
     * @return the extension string
     */
    private static String extensionString(String separator)
    {
        return StringUtils.join(EXTENSIONS, separator);
    }

    /**
     * Generates a default extension string.
     *
     * @return the default extension string
     */
    private static String extensionString()
    {
        return extensionString(",");
    }

    /**
     * Tries to create an instance without a file system manager.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoManager()
    {
        new FSScannerImpl(null, extensionString());
    }

    /**
     * Tries to create an instance without file extensions.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitNoExtensions()
    {
        new FSScannerImpl(manager, null);
    }

    /**
     * Tries to create an instance with empty file extensions.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testInitEmptyExtensions()
    {
        new FSScannerImpl(manager, "");
    }

    /**
     * Helper method for testing whether extensions have been parsed correctly.
     *
     * @param scan the scanner to be tested
     */
    private static void checkExtensions(FSScannerImpl scan)
    {
        Set<String> exts = scan.getSupportedExtensions();
        assertEquals("Wrong number of extensions", EXTENSIONS.length,
                exts.size());
        for (String e : EXTENSIONS)
        {
            assertTrue("Extension not found: " + e, exts.contains(e));
        }
    }

    /**
     * Helper method for testing whether an extension string can be parsed.
     *
     * @param ext the extension string
     */
    private void checkParseExtensionString(String ext)
    {
        FSScannerImpl scan = new FSScannerImpl(manager, ext);
        checkExtensions(scan);
    }

    /**
     * Tests whether the extension string is correctly parsed.
     */
    @Test
    public void testGetSupportedExtensions()
    {
        checkParseExtensionString(extensionString());
    }

    /**
     * Tests whether an extension string with an alternative separator can be
     * parsed.
     */
    @Test
    public void testGetSupportedExtensionsOtherSeparator()
    {
        checkParseExtensionString(extensionString(";"));
    }

    /**
     * Tests that whitespace is ignored in the extension string.
     */
    @Test
    public void testGetSupportedExtensionsSpace()
    {
        checkParseExtensionString(extensionString(" , "));
    }

    /**
     * Tests that the set with extensions cannot be modified.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testGetSupportedExtensionsModify()
    {
        FSScannerImpl scan = new FSScannerImpl(manager, extensionString());
        Set<String> exts = scan.getSupportedExtensions();
        exts.clear();
    }

    /**
     * Tests the default extensions.
     */
    @Test
    public void testGetSupportedExtensionDefault()
    {
        FSScannerImpl scan = new FSScannerImpl(manager);
        Set<String> exts = scan.getSupportedExtensions();
        assertEquals("Wrong number of extensions", 1, exts.size());
        assertTrue("Standard extension not found", exts.contains("mp3"));
    }

    /**
     * Helper method for testing whether the file filter of the scanner accepts
     * a file with the given extension.
     *
     * @param scan the scanner
     * @param fileExt the extension of the file in question
     * @param expResult the expected result of the filter
     */
    private void checkFilterExtension(FSScannerImpl scan, String fileExt,
            boolean expResult) throws FileSystemException
    {
        FileSelectInfo info = EasyMock.createMock(FileSelectInfo.class);
        FileObject fo = EasyMock.createMock(FileObject.class);
        FileName name = EasyMock.createMock(FileName.class);
        EasyMock.expect(info.getFile()).andReturn(fo);
        EasyMock.expect(fo.getType()).andReturn(FileType.FILE);
        EasyMock.expect(fo.getName()).andReturn(name);
        EasyMock.expect(name.getExtension()).andReturn(fileExt);
        EasyMock.replay(info, fo, name);
        assertEquals("Wrong filter result", expResult,
                scan.getFilter().accept(info));
        EasyMock.verify(info, fo, name);
    }

    /**
     * Tests whether the file filter supports all extensions that were specified
     * ignoring case.
     */
    @Test
    public void testGetFilterSupportedExtensions() throws FileSystemException
    {
        FSScannerImpl scan = new FSScannerImpl(manager, extensionString());
        for (String ext : EXTENSIONS)
        {
            checkFilterExtension(scan, ext, true);
            checkFilterExtension(scan, ext.toUpperCase(Locale.ENGLISH), true);
        }
    }

    /**
     * Tests whether the filter can handle a file without an extension.
     */
    @Test
    public void testGetFilterNoExtension() throws FileSystemException
    {
        FSScannerImpl scan = new FSScannerImpl(manager, extensionString());
        checkFilterExtension(scan, "", false);
    }

    /**
     * Tests whether the filter can deal with exceptions.
     */
    @Test
    public void testGetFilterException() throws FileSystemException
    {
        FileSelectInfo info = EasyMock.createMock(FileSelectInfo.class);
        FileObject fo = EasyMock.createMock(FileObject.class);
        EasyMock.expect(info.getFile()).andReturn(fo);
        EasyMock.expect(fo.getType()).andThrow(
                new FileSystemException("TestException"));
        EasyMock.replay(info, fo);
        FSScannerImpl scan = new FSScannerImpl(manager, extensionString());
        assertFalse("Wrong result", scan.getFilter().accept(info));
        EasyMock.verify(info, fo);
    }

    /**
     * Creates a directory structure with some audio files that corresponds to
     * the constant definitions.
     *
     * @return the root directory of the file structure
     */
    private File setUpMusicDir()
    {
        testFileURIs = new HashSet<String>();
        File musicDir = tempFolder.newFolder("TestMusic");
        for (int inter = 0; inter < INTERPRETS.length; inter++)
        {
            File interpretDir = new File(musicDir, INTERPRETS[inter]);
            interpretDir.mkdir();
            for (int album = 0; album < ALBUMS[inter].length; album++)
            {
                File albumDir = new File(interpretDir, ALBUMS[inter][album]);
                albumDir.mkdir();
                for (int track = 1; track <= TRACKS[inter][album]; track++)
                {
                    createFile(albumDir, fileName(track));
                }
            }
        }
        return musicDir;
    }

    /**
     * Creates the name of an album file based on the track number.
     *
     * @param track
     * @return
     */
    private String fileName(int track)
    {
        String trackNo = String.valueOf(track);
        if (trackNo.length() < 2)
        {
            trackNo = "0" + trackNo;
        }
        return trackNo + TRACK_FILE;
    }

    /**
     * Helper method for creating a file. This will be a dummy file with the
     * specified name.
     *
     * @param dir the directory
     * @param name the name of the file
     */
    private void createFile(File dir, String name)
    {
        File f = new File(dir, name);
        PrintWriter out = null;
        try
        {
            out = new PrintWriter(new FileWriter(f));
            out.println(name);
            out.println(FILE_CONTENT);
        }
        catch (IOException ioex)
        {
            ioex.printStackTrace();
            fail("Could not create test file " + f);
        }
        finally
        {
            if (out != null)
            {
                out.close();
            }
        }
        testFileURIs.add(f.toURI().toString());
    }

    /**
     * Checks the content of the specified file.
     *
     * @param uri the URI to the test file
     * @throws IOException if an error occurs
     */
    private void checkFile(String uri) throws IOException
    {
        FileObject fo = manager.resolveFile(uri);
        assertTrue("File does not exist: " + uri, fo.exists());
        BufferedReader in =
                new BufferedReader(new InputStreamReader(fo.getContent()
                        .getInputStream()));
        try
        {
            String line = in.readLine();
            assertTrue("Wrong name: " + line, uri.endsWith(line));
            assertEquals("Wrong content of file", FILE_CONTENT, in.readLine());
        }
        finally
        {
            in.close();
        }
    }

    /**
     * Tests whether the scanner can scan a file structure.
     */
    @Test
    public void testScan() throws IOException
    {
        File musicDir = setUpMusicDir();
        FSScannerImpl scan = new FSScannerImpl(manager);
        scan.setRootURI(musicDir.toURI().toString());
        Collection<String> files = scan.scan();
        assertEquals("Wrong number of files", testFileURIs.size(), files.size());
        for (String uri : files)
        {
            checkFile(uri);
        }
    }

    /**
     * Tries to call scan() without setting a root directory.
     */
    @Test(expected = IllegalStateException.class)
    public void testScanNoRootDir() throws IOException
    {
        new FSScannerImpl(manager).scan();
    }

    /**
     * Tries to scan a non existing directory.
     */
    @Test(expected = IOException.class)
    public void testScanUnexistingRoot() throws IOException
    {
        FSScannerImpl scan = new FSScannerImpl(manager);
        scan.setRootURI("a non existing directory!");
        scan.scan();
    }
}
