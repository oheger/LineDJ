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
import de.oliver_heger.linedj.archive.server.model.ArchiveModel
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
    val probe = testKit.createTestProbe[ArchiveContentActor.GetMediaResponse]()
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    contentActor ! ArchiveContentCommand.GetMedia(probe.ref)

    probe.expectMessage(ArchiveContentActor.GetMediaResponse(Nil))

  it should "return overview information of the managed media" in :
    val media = (1 to 8) map createMedium
    val expectedOverviews = media.map(_.overview)
    val contentActor = testKit.spawn(ArchiveContentActor.behavior())

    media.foreach: medium =>
      contentActor ! ArchiveContentCommand.AddMedium(medium)

    val probe = testKit.createTestProbe[ArchiveContentActor.GetMediaResponse]()
    contentActor ! ArchiveContentCommand.GetMedia(probe.ref)
    val response = probe.expectMessageType[ArchiveContentActor.GetMediaResponse]
    response.media should contain theSameElementsAs expectedOverviews
