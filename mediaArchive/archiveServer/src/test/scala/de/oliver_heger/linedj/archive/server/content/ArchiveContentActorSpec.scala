/*
 * Copyright 2015-2025 The Developers Team.
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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.content.ArchiveContentActor.ArchiveContentCommand
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.Checksums
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

object ArchiveContentActorSpec:
  /**
    * Creates a test medium details object based in the given index.
    *
    * @param idx the index of the test medium
    * @return a test medium object for this index
    */
  private def createMedium(idx: Int): ArchiveModel.MediumDetails =
    ArchiveModel.MediumDetails(
      overview = ArchiveModel.MediumOverview(
        id = Checksums.MediumChecksum("id-" + idx),
        title = "Test medium " + idx
      ),
      description = "Description for test medium " + idx,
      orderMode = Some(ArchiveModel.OrderMode.fromOrdinal(idx % ArchiveModel.OrderMode.values.length))
    )
end ArchiveContentActorSpec

/**
  * Test class for [[ArchiveContentActor]].
  */
class ArchiveContentActorSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  import ArchiveContentActorSpec.*

  "ArchiveContentActor" should "return an empty list of media initially" in :
    val probe = testKit.createTestProbe[ArchiveCommands.GetMediaResponse]()
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedia(probe.ref)

    probe.expectMessage(ArchiveCommands.GetMediaResponse(Nil))

  it should "return overview information of the managed media" in :
    val media = (1 to 8) map createMedium
    val expectedOverviews = media.map(_.overview)
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    media.foreach: medium =>
      contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMedium(medium)

    val probe = testKit.createTestProbe[ArchiveCommands.GetMediaResponse]()
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedia(probe.ref)
    val response = probe.expectMessageType[ArchiveCommands.GetMediaResponse]
    response.media should contain theSameElementsAs expectedOverviews

  it should "return detail information about a specific medium" in :
    val medium = createMedium(1)
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())
    contentActor ! ArchiveCommands.UpdateArchiveContentCommand.AddMedium(medium)

    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumResponse]()
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedium(medium.id, probe.ref)
    val details = probe.expectMessageType[ArchiveCommands.GetMediumResponse]

    details should be(ArchiveCommands.GetMediumResponse(medium.id, Some(medium)))

  it should "handle a request for the details of a non-existing medium" in :
    val mediumID = Checksums.MediumChecksum("a-non-existing-medium-id")
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    val probe = testKit.createTestProbe[ArchiveCommands.GetMediumResponse]()
    contentActor ! ArchiveCommands.ReadArchiveContentCommand.GetMedium(mediumID, probe.ref)
    val details = probe.expectMessageType[ArchiveCommands.GetMediumResponse]

    details should be(ArchiveCommands.GetMediumResponse(mediumID, None))
