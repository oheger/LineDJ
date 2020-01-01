/*
 * Copyright 2015-2019 The Developers Team.
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

package de.oliver_heger.linedj.archive.group

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import de.oliver_heger.linedj.archive.config.MediaArchiveConfig
import de.oliver_heger.linedj.archive.media.MediaManagerActor
import de.oliver_heger.linedj.archive.metadata.MetaDataManagerActor
import de.oliver_heger.linedj.archive.metadata.persistence.PersistentMetaDataManagerActor
import de.oliver_heger.linedj.utils.ChildActorFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, Matchers}
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for ''ArchiveActorFactory''.
  */
class ArchiveActorFactorySpec(testSystem: ActorSystem) extends TestKit(testSystem) with FlatSpecLike
  with BeforeAndAfterAll with Matchers with MockitoSugar {
  def this() = this(ActorSystem("ArchiveActorFactorySpec"))

  override protected def afterAll(): Unit = {
    TestKit shutdownActorSystem system
    super.afterAll()
  }

  "An ArchiveActorFactory" should "create all actors for a media archive" in {
    val helper = new FactoryTestHelper

    helper.createArchive()
  }

  /**
    * A test helper class managing a test factory instance and its
    * dependencies.
    */
  private class FactoryTestHelper {
    /** Mock for the archive configuration. */
    private val archiveConfig = mock[MediaArchiveConfig]

    /** Test probe for the media union actor. */
    private val probeMediaUnionActor = TestProbe()

    /** Test probe for the meta data union actor. */
    private val probeMetaDataUnionActor = TestProbe()

    /** Test probe for the new media manager actor. */
    private val probeMediaManager = TestProbe()

    /** Test probe for the new meta data manager actor. */
    private val probeMetaDataManager = TestProbe()

    /** Test probe for the new persistence manager actor. */
    private val probePersistenceManager = TestProbe()

    /** Test probe for the group manager actor. */
    private val probeGroupManager = TestProbe()

    /** The map with information about the child actors to be created. */
    private var childActorsMap = createChildActorsMap()

    /** The test factory instance. */
    private val factory = createFactory()

    /**
      * Invokes the test factory to create the actors of the test archive. The
      * result is checked.
      *
      * @return this test helper
      */
    def createArchive(): FactoryTestHelper = {
      factory.createArchiveActors(probeMediaUnionActor.ref, probeMetaDataUnionActor.ref,
        probeGroupManager.ref, archiveConfig) should be(probeMediaManager.ref)
      childActorsMap.isEmpty shouldBe true
      this
    }

    /**
      * Creates a map that allows assigning test actor references to the
      * expected creation properties.
      *
      * @return the map to control the child actor creation
      */
    private def createChildActorsMap(): Map[Class[_], (Props => Boolean, ActorRef)] = {
      val propsPersistentManager = PersistentMetaDataManagerActor(archiveConfig, probeMetaDataUnionActor.ref)
      val propsMediaManager = MediaManagerActor(archiveConfig, probeMetaDataManager.ref,
        probeMediaUnionActor.ref, probeGroupManager.ref)
      val propsMetaDataManager = MetaDataManagerActor(archiveConfig, probePersistenceManager.ref,
        probeMetaDataUnionActor.ref)
      Map(propsMediaManager.actorClass() -> (_ == propsMediaManager, probeMediaManager.ref),
        propsMetaDataManager.actorClass() -> (_ == propsMetaDataManager, probeMetaDataManager.ref),
        propsPersistentManager.actorClass() -> (checkPersistenceManagerProps(propsPersistentManager),
          probePersistenceManager.ref))
    }

    /**
      * Checks whether the correct Props for creating the persistent manager
      * actor have been provided. Here a simple equals check is not possible
      * because a scanner object is part of the arguments.
      *
      * @param expected the expected Props
      * @param actual   the actual Props
      * @return a flag whether the actual Props are okay
      */
    private def checkPersistenceManagerProps(expected: Props)(actual: Props): Boolean =
      actual.args.size == expected.args.size &&
        actual.args.slice(0, 2) == expected.args.slice(0, 2)

    /**
      * Creates the test factory instance. Installs a child actor factory that
      * allows verifying that the correct child actors are created.
      *
      * @return the test archive actor factory
      */
    private def createFactory(): ArchiveActorFactory = {
      val testFactory = TestActorRef[Actor with ArchiveActorFactory](Props(
        new ArchiveActorFactory with ChildActorFactory {
          override def createChildActor(p: Props): ActorRef = {
            val actorData = childActorsMap(p.actorClass())
            childActorsMap -= p.actorClass()
            actorData._1(p) shouldBe true
            actorData._2
          }

          override def receive: Receive = Actor.emptyBehavior
        }
      ))
      testFactory.underlyingActor
    }
  }

}
