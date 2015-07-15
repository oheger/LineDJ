package de.oliver_heger.jplaya.ui;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.easymock.EasyMock;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

import scala.actors.Actor;
import de.oliver_heger.splaya.AudioPlayer;
import de.oliver_heger.splaya.AudioPlayerFactory;

/**
 * Test class for {@code PlayerAppComponent}.
 *
 * @author Oliver Heger
 * @version $Id: $
 */
public class TestPlayerAppComponent
{
    /** The component to be tested. */
    private PlayerAppComponent comp;

    @Before
    public void setUp() throws Exception
    {
        comp = new PlayerAppComponent();
    }

    /**
     * Tests whether an audio player instance can be obtained from a factory
     * service and is correctly passed to the application.
     */
    @Test
    public void testAudioPlayerInitialization()
    {
        AudioPlayerFactory factory =
                EasyMock.createMock(AudioPlayerFactory.class);
        AudioPlayer player = EasyMock.createMock(AudioPlayer.class);
        Main app = EasyMock.createMock(Main.class);
        AudioPlayerClient client = EasyMock.createMock(AudioPlayerClient.class);
        BundleContext bctx = EasyMock.createMock(BundleContext.class);
        Actor listener1 = EasyMock.createMock(Actor.class);
        Actor listener2 = EasyMock.createMock(Actor.class);
        AtomicReference<AudioPlayerClient> clientRef =
                new AtomicReference<AudioPlayerClient>();
        EasyMock.expect(factory.createAudioPlayer()).andReturn(player);
        EasyMock.expect(app.getAudioPlayerClientRef()).andReturn(clientRef);
        client.bindAudioPlayer(player);
        app.setExitHandler(EasyMock.anyObject(Runnable.class));
        EasyMock.expect(app.fetchAudioPlayerListenerActors()).andReturn(
                Arrays.asList(listener1, listener2));
        player.addActorListener(listener1);
        player.addActorListener(listener2);
        EasyMock.replay(factory, player, app, client, bctx, listener1,
                listener2);
        clientRef.set(client);
        comp.bindAudioPlayerFactory(factory);
        comp.initApplication(app, bctx);
        EasyMock.verify(factory, player, app, client, bctx, listener1,
                listener2);
    }

    /**
     * Tests whether the exit handler can successfully shutdown the application.
     */
    @Test
    public void testExitHandlerSuccess() throws BundleException
    {
        BundleContext bctx = EasyMock.createMock(BundleContext.class);
        Bundle bundle = EasyMock.createMock(Bundle.class);
        EasyMock.expect(bctx.getBundle(0)).andReturn(bundle);
        bundle.stop();
        EasyMock.replay(bctx, bundle);
        Runnable exitHandler = comp.createExitHandler(bctx);
        exitHandler.run();
        EasyMock.verify(bctx, bundle);
    }

    /**
     * Tests the exit handler if stopping the system bundle causes an exception.
     */
    @Test
    public void testExitHandlerEx() throws BundleException
    {
        BundleContext bctx = EasyMock.createMock(BundleContext.class);
        Bundle bundle = EasyMock.createMock(Bundle.class);
        EasyMock.expect(bctx.getBundle(0)).andReturn(bundle);
        bundle.stop();
        EasyMock.expectLastCall().andThrow(
                new BundleException("Testexception!"));
        EasyMock.replay(bctx, bundle);
        Runnable exitHandler = comp.createExitHandler(bctx);
        exitHandler.run();
        EasyMock.verify(bctx, bundle);
    }

    /**
     * Tests whether the audio factory can be unbound. We can only test that the
     * factory is not touched.
     */
    @Test
    public void testUnbindAudioFactory()
    {
        AudioPlayerFactory factory =
                EasyMock.createMock(AudioPlayerFactory.class);
        EasyMock.replay(factory);
        comp.unbindAudioPlayerFactory(factory);
    }
}
