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

package de.oliver_heger.linedj.platform.archiveclient

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

/**
  * Test class for [[MonitorSupport]].
  */
class MonitorSupportSpec extends ScalaTestWithActorTestKit, AnyFlatSpecLike, Matchers, MockitoSugar:
  "MonitorSupport" should "add a new change listener" in :
    val listener = mock[ArchiveStateMonitor.ArchiveChangeListener[String]]
    val helper = new SupportTestHelper

    helper.support.addChangeListener(listener)

    helper.expectMonitorCommand(ArchiveStateMonitor.ArchiveListenerCommand.AddChangeListener(listener))

  it should "remove a change listener" in :
    val listener = mock[ArchiveStateMonitor.ArchiveChangeListener[String]]
    val helper = new SupportTestHelper

    helper.support.removeChangeListener(listener)

    helper.expectMonitorCommand(ArchiveStateMonitor.ArchiveListenerCommand.RemoveChangeListener(listener))

  it should "handle a changes expected hint" in :
    val helper = new SupportTestHelper

    helper.support.expectChanges()

    helper.expectMonitorCommand(ArchiveStateMonitor.ArchiveListenerCommand.ChangesExpected())

  it should "handle an undefined monitor actor reference" in :
    val listener = mock[ArchiveStateMonitor.ArchiveChangeListener[String]]
    val support = new MonitorSupport[String]:
      override protected def optMonitorActor: Option[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[String]]] =
        None

    // It can only be tested that no exception is thrown.
    support.addChangeListener(listener)
    support.removeChangeListener(listener)
    support.expectChanges()

  /**
    * A test helper class managing a test instance and its dependencies.
    */
  private class SupportTestHelper:
    /** The probe for the monitor actor. */
    private val monitorProbe = testKit.createTestProbe[ArchiveStateMonitor.ArchiveListenerCommand[String]]()

    /** The instance to be tested. */
    val support: MonitorSupport[String] = new MonitorSupport[String]:
      override protected def optMonitorActor: Option[ActorRef[ArchiveStateMonitor.ArchiveListenerCommand[String]]] =
        Some(monitorProbe.ref)

    /**
      * Expects the given command to be received by the monitor actor delegate.
      *
      * @param command the expected command
      * @return this test helper
      */
    def expectMonitorCommand(command: ArchiveStateMonitor.ArchiveListenerCommand[String]): SupportTestHelper =
      monitorProbe.expectMessage(command)
      this