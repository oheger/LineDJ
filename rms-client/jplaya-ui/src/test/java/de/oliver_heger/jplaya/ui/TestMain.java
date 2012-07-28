package de.oliver_heger.jplaya.ui;

import static org.junit.Assert.assertSame;

import java.util.concurrent.atomic.AtomicReference;

import net.sf.jguiraffe.di.BeanContext;
import net.sf.jguiraffe.gui.app.ApplicationContext;

import org.easymock.EasyMock;
import org.junit.Test;

/**
 * Test class for {@code Main}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestMain
{
    /**
     * Tests whether the reference to the audio player client can be queried.
     */
    @Test
    public void testGetAudioPlayerClientRef()
    {
        final ApplicationContext appCtx =
                EasyMock.createMock(ApplicationContext.class);
        BeanContext bctx = EasyMock.createMock(BeanContext.class);
        EasyMock.expect(appCtx.getBeanContext()).andReturn(bctx).anyTimes();
        AtomicReference<AudioPlayerClient> ref =
                new AtomicReference<AudioPlayerClient>();
        EasyMock.expect(bctx.getBean(Main.BEAN_PLAYER_CLIENT_REF)).andReturn(
                ref);
        EasyMock.replay(appCtx, bctx);
        Main main = new Main()
        {
            @Override
            public ApplicationContext getApplicationContext()
            {
                return appCtx;
            };
        };
        assertSame("Wrong reference", ref, main.getAudioPlayerClientRef());
        EasyMock.verify(appCtx, bctx);
    }
}
