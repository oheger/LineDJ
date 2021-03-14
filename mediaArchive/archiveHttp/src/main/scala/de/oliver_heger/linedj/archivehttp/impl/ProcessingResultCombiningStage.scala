/*
 * Copyright 2015-2021 The Developers Team.
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

package de.oliver_heger.linedj.archivehttp.impl

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import de.oliver_heger.linedj.shared.archive.media.MediumID

/**
  * A specialized flow stage used during processing of the content document of
  * an HTTP archive.
  *
  * From the content document partial results for medium descriptions and meta
  * data are produced. This is done in parallel, then the partial results have
  * to be combined to generate the final [[MediumProcessingResult]] objects. In
  * the original approach the partial results were directly combined. However,
  * as it came out, the flow stage that sends HTTP requests to the archive can
  * change the order of stream elements. So the results produced by the stream
  * were partly scrambled.
  *
  * This stage should fix this. It receives tuples with partial result objects.
  * If they refer to the same medium, they are combined to a final result
  * object and passed downstream. Otherwise, they are stored in maps and
  * combined with later partial results when they receive.
  *
  * Note that the temporary memory needed to recombine partial results that
  * are out of order should be limited; the default should be that the
  * elements are correctly ordered, so that they can be combined directly.
  * In other cases, only single elements have to be stored for a short while
  * until their counterpart arrives.
  */
class ProcessingResultCombiningStage extends GraphStage[
  FlowShape[(MediumInfoResponseProcessingResult, MetaDataResponseProcessingResult),
    MediumProcessingResult]] {
  val in: Inlet[(MediumInfoResponseProcessingResult, MetaDataResponseProcessingResult)] =
    Inlet[(MediumInfoResponseProcessingResult, MetaDataResponseProcessingResult)](
      "ProcessingResultCombiningStage.in")
  val out: Outlet[MediumProcessingResult] =
    Outlet[MediumProcessingResult]("ProcessingResultCombiningStage.out")

  override val shape: FlowShape[(MediumInfoResponseProcessingResult,
    MetaDataResponseProcessingResult), MediumProcessingResult] = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      // map with description results to be combined
      var info = Map.empty[MediumID, MediumInfoResponseProcessingResult]

      // map with meta data results to be combined
      var meta = Map.empty[MediumID, MetaDataResponseProcessingResult]

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val (resInfo, resMeta) = grab(in)
          if (resInfo.mediumInfo.mediumID == resMeta.mediumID)
            push(out, combine(resInfo, resMeta))
          else {
            val (tempInfo1, tempMeta1, optRes1) =
              updateMapping(info, meta, resInfo.mediumInfo.mediumID, resInfo)
            val (tempMeta2, tempInfo2, optRes2) =
              updateMapping(tempMeta1, tempInfo1, resMeta.mediumID, resMeta)
            info = tempInfo2
            meta = tempMeta2
            if (optRes1.isDefined || optRes2.isDefined) {
              val results = List(optRes1 map (m => combine(resInfo, m)),
                optRes2 map (i => combine(i, resMeta))).flatten
              emitMultiple(out, results)
            }
            else pull(in)
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pull(in)
        }
      })
    }

  /**
    * Combines partial result objects to a final result.
    *
    * @param resInfo the medium info result
    * @param resMeta the meta data result
    * @return the final result
    */
  private def combine(resInfo: MediumInfoResponseProcessingResult,
                      resMeta: MetaDataResponseProcessingResult): MediumProcessingResult =
    MediumProcessingResult(resInfo.mediumInfo, resMeta.metaData, resMeta.seqNo)

  /**
    * Updates the maps with results to be combined when a new partial result
    * arrives.
    *
    * @param map1 the map for which a new result arrived
    * @param map2 the map with results to be combined
    * @param mid  the medium ID
    * @param data the new result
    * @tparam V1 type of the first map
    * @tparam V2 type of the second map
    * @return the updated maps and an optional result to be combined
    */
  private def updateMapping[V1, V2](map1: Map[MediumID, V1], map2: Map[MediumID, V2],
                                    mid: MediumID, data: V1):
  (Map[MediumID, V1], Map[MediumID, V2], Option[V2]) =
    map2 get mid match {
      case res@Some(_) =>
        (map1, map2 - mid, res)
      case None =>
        (map1 + (mid -> data), map2, None)
    }
}
