/*
 * Copyright 2015-2026 The Developers Team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.oliver_heger.linedj.platform.audio2.impl

import de.oliver_heger.linedj.player.engine.{AsyncAudioStreamFactory, AudioStreamFactory}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, BeforeAndAfterAll}

import scala.concurrent.Future

/**
  * Test class for [[AudioStreamFactoryManager]].
  */
class AudioStreamFactoryManagerSpec(testSystem: ActorSystem) extends TestKit(testSystem), AsyncFlatSpecLike,
  BeforeAndAfterAll, Matchers, Eventually:
  def this() = this(ActorSystem("AudioStreamFactoryManagerSpec"))

  override protected def afterAll(): Unit =
    TestKit.shutdownActorSystem(system)
    super.afterAll()

  /**
    * Executes a test with a test manager instance. This function ensures that
    * the manager is shut down after the test. Since shutdown is an
    * asynchronous process, the start of the next test case may fail due to a 
    * non-unique actor name. Therefore, the test is wrapped in `eventually`.
    *
    * @param t the test function
    * @return the result of the test
    */
  private def managerTest(t: AudioStreamFactoryManager => Future[Assertion]): Future[Assertion] =
    eventually:
      val manager = new AudioStreamFactoryManager
      t(manager).andThen:
        case _ => manager.shutdown()

  "An AudioStreamFactoryManager" should "handle factories added after the management actor was created" in :
    managerTest: manager =>
      val factory1 = new AudioStreamFactoryTestImpl(".mp3", 256)
      val factory2 = new AudioStreamFactoryTestImpl(".ogg", 512)
      val managedFactory = manager.createManagedFactory(implicitly)

      manager.addFactory(factory1)
      manager.addFactory(factory2)
      for
        res1 <- managedFactory.playbackDataForAsync("test.mp3")
        res2 <- managedFactory.playbackDataForAsync("test.ogg")
      yield
        res1.streamFactoryLimit should be(256)
        res2.streamFactoryLimit should be(512)

  it should "handle factories added before the management actor was created" in :
    managerTest: manager =>
      val factory1 = new AudioStreamFactoryTestImpl(".mp3", 256)
      val factory2 = new AudioStreamFactoryTestImpl(".ogg", 512)
      manager.addFactory(factory1)
      manager.addFactory(factory2)
      val managedFactory = manager.createManagedFactory(implicitly)

      for
        res1 <- managedFactory.playbackDataForAsync("test.mp3")
        res2 <- managedFactory.playbackDataForAsync("test.ogg")
      yield
        res1.streamFactoryLimit should be(256)
        res2.streamFactoryLimit should be(512)

  it should "handle factories added before and after the management actor was created" in :
    managerTest: manager =>
      val factory1 = new AudioStreamFactoryTestImpl(".mp3", 256)
      val factory2 = new AudioStreamFactoryTestImpl(".ogg", 512)
      manager.addFactory(factory1)
      val managedFactory = manager.createManagedFactory(implicitly)
      manager.addFactory(factory2)

      for
        res1 <- managedFactory.playbackDataForAsync("test.mp3")
        res2 <- managedFactory.playbackDataForAsync("test.ogg")
      yield
        res1.streamFactoryLimit should be(256)
        res2.streamFactoryLimit should be(512)

  it should "add the default stream factory" in :
    managerTest: manager =>
      val managedFactory = manager.createManagedFactory(implicitly)
      managedFactory.playbackDataForAsync("someTest.wav") map : res =>
        res.streamFactoryLimit should be(AudioStreamFactory.DefaultAudioBufferSize * 2)

  it should "remove a factory after the management actor has been created" in :
    val factory = new AudioStreamFactoryTestImpl(".mp3", 100)
    managerTest: manager =>
      manager.addFactory(factory)
      val managedFactory = manager.createManagedFactory(implicitly)

      manager.removeFactory(factory)
      managedFactory.playbackDataForAsync("test.mp3") map : res =>
        res.streamFactoryLimit should be(AudioStreamFactory.DefaultAudioBufferSize * 2)

  it should "remove a factory before the management actor has been created" in :
    val factory = new AudioStreamFactoryTestImpl(".mp3", 100)
    managerTest: manager =>
      manager.addFactory(factory)
      manager.removeFactory(factory)
      val managedFactory = manager.createManagedFactory(implicitly)

      managedFactory.playbackDataForAsync("test.mp3") map : res =>
        res.streamFactoryLimit should be(AudioStreamFactory.DefaultAudioBufferSize * 2)

  it should "handle an exception thrown by a stream factory" in :
    val exception = new IllegalArgumentException("Test exception: Invalid audio stream.")
    val exceptionFactory = new AsyncAudioStreamFactory:
      override def playbackDataForAsync(uri: String): Future[AudioStreamFactory.AudioStreamPlaybackData] =
        Future.failed(exception)
    managerTest: manager =>
      manager.addFactory(exceptionFactory)
      val managedFactory = manager.createManagedFactory(implicitly)

      recoverToExceptionIf[IllegalArgumentException]:
        managedFactory.playbackDataForAsync("some.url")
      .map: actualException =>
        actualException should be(exception)

  it should "handle a shutdown before the actor has been created" in :
    managerTest: manager =>
      manager.shutdown()
      manager.addFactory(new AudioStreamFactoryTestImpl(".mp3", 100))

      val managedFactory = manager.createManagedFactory(implicitly)
      managedFactory.playbackDataForAsync("test.mp3") map : res =>
        res.streamFactoryLimit should be(100)

  it should "support another life phase after a shutdown" in :
    val factory1 = new AudioStreamFactoryTestImpl(".mp3", 1)
    val factory2 = new AudioStreamFactoryTestImpl(".mp3", 2)
    managerTest: manager =>
      manager.addFactory(factory1)
      manager.createManagedFactory(implicitly)
      manager.shutdown()
      Thread.sleep(100) // Give the actor time to stop itself.

      manager.addFactory(factory2)
      val managedFactory = manager.createManagedFactory(implicitly)
      managedFactory.playbackDataForAsync("test.mp3") map : res =>
        res.streamFactoryLimit should be(2)
end AudioStreamFactoryManagerSpec

/**
  * A stub implementation of the factory trait. It returns deterministic 
  * results for specific URIs.
  *
  * @param pattern the URI pattern accepted by this factory
  * @param limit   the limit to return by this factory
  */
private class AudioStreamFactoryTestImpl(pattern: String, limit: Int) extends AsyncAudioStreamFactory:
  override def playbackDataForAsync(uri: String): Future[AudioStreamFactory.AudioStreamPlaybackData] =
    if uri.endsWith(pattern) then
      Future.successful(AudioStreamFactory.AudioStreamPlaybackData(null, limit))
    else
      Future.failed(AsyncAudioStreamFactory.UnsupportedUriException(uri))  
      