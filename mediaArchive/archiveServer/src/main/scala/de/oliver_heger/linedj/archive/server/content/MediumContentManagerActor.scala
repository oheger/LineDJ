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

package de.oliver_heger.linedj.archive.server.content

import de.oliver_heger.linedj.archive.server.content.MediumContentManagerActor.*
import de.oliver_heger.linedj.archive.server.model.{ArchiveCommands, ArchiveModel}
import de.oliver_heger.linedj.shared.archive.metadata.MediaMetadata
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Scheduler}

import java.util.Locale
import scala.concurrent.{ExecutionContext, Future}

/**
  * An internally used helper actor implementation to manage data extracted 
  * from a collection of [[MediaMetadata]] objects.
  *
  * A medium can contain songs from different artists with different albums.
  * Users may want to navigate through these songs based on different criteria,
  * for instance browse the songs of a specific artist or the songs contained
  * on a specific album, or view the albums of a specific artist. For each of
  * such views, a specific content manager actor instance can be created. It is
  * configured with functions to extract the desired data from song metadata.
  * It then groups the data based on extracted keys like artist IDs or album
  * IDs. While doing the grouping, it handles corner cases like undefined keys
  * (for instance missing information in a song) gracefully. It is also
  * possible to manipulate the grouping by providing a custom [[GroupingFunc]].
  * This allows grouping the data based on custom criteria.
  *
  * Queries can already be served while reading the archive's content is still
  * in progress. When new song information arrives, the mapping becomes invalid
  * and needs to be reset. It is then re-constructed when the next query comes
  * in.
  */
private object MediumContentManagerActor:
  /**
    * Alias for a function to extract a key from a given [[MediaMetadata]]
    * object. This key is used for grouping the data, for instance, all songs
    * from an artist.
    */
  type KeyExtractor = MediaMetadata => Option[String]

  /**
    * Alias for a function to extract the data to be managed from a
    * [[MediaMetadata]] object. The function is passed the key that has been
    * computed based on the result of the [[KeyExtractor]] function and the
    * current metadata. From this information, it can produce a result data
    * object.
    */
  type DataExtractor[DATA] = (String, MediaMetadata) => DATA

  /**
    * Alias for a function that computes the key used for grouping the data
    * items to be managed. The function is passed the element IDs derived from
    * the result of the [[KeyExtractor]]. The resulting string value becomes
    * the basis for grouping; these values then also need to be specified to
    * the ''apply()'' function to fetch the corresponding items.
    */
  type GroupingFunc = String => String

  /**
    * Alias for a transformation function that is invoked on a mapping
    * constructed from the [[KeyExtractor]] and [[DataExtractor]] functions. In
    * some use cases, these functions are not sufficient to create the desired
    * outcome; especially, if the creation of target data requires complex and
    * maybe asynchronous operations. An example would be the construction of
    * another entity type for which IDs have to be queried from an ID manager
    * actor. To handle such cases, an actor instance can be configured with a
    * [[DataExtractor]] function that produces temporary data, which is then
    * mapped to the final results using the transformation function. The
    * transformation function is called with the whole mapping that has been
    * constructed so far. So, it can apply possible optimizations (such as
    * grouping the existing data) to produce the final result.
    *
    * @tparam SRC the source (temporary) type of data
    * @tparam DST the destination type of data
    */
  type DataTransformer[SRC, DST] = Map[String, List[SRC]] => Future[Map[String, List[DST]]]

  /**
    * Constant for a name that is going to be used for items (song titles,
    * artist or album names) for which no information is available.
    */
  private val UndefinedName = ""

  /**
    * Constant for the track number to use if a song's metadata does not any.
    */
  private val UndefinedTrackNumber = 0

  /**
    * A default [[DataExtractor]] function that just returns the passed in
    * metadata. This serves the frequent use case that all information about a
    * media file is required.
    */
  final val MetadataExtractor: DataExtractor[MediaMetadata] = (_, data) => data

  /**
    * The key that is used by the [[AggregateGroupingFunc]]. When passing this 
    * key to an actor that is configured with the [[AggregateGroupingFunc]],
    * all encountered entities are returned.
    */
  final val AggregateGroupingKey = ""

  /**
    * A special [[GroupingFunc]] that allows constructing an aggregate over all
    * entities constructed by the [[DataExtractor]] function. This function 
    * simply maps all keys to the same group whose name is determined by the
    * [[AggregateGroupingKey]] constant.
    */
  final val AggregateGroupingFunc: GroupingFunc = _ => AggregateGroupingKey

  /**
    * Definition of an [[Ordering]] on [[MediaMetadata]]. This implementation
    * allows to sort metadata using meaningful sort criteria.
    */
  given metadataOrdering: Ordering[MediaMetadata] = Ordering[(String, Int, String)].on(
    metadata => (
      metadata.album.map(_.toLowerCase(Locale.ROOT)).getOrElse(UndefinedName),
      metadata.trackNumber.getOrElse(UndefinedTrackNumber),
      metadata.title.map(_.toLowerCase(Locale.ROOT)).getOrElse(UndefinedName)
    )
  )

  /**
    * Definition of an [[Ordering]] on [[ArchiveModel.ArtistInfo]]. Objects are
    * sorted by the artist name (ignoring case).
    */
  given artistInfoOrdering: Ordering[ArchiveModel.ArtistInfo] = Ordering.by(_.artistName.toLowerCase(Locale.ROOT))

  /**
    * Definition of an [[Ordering]] on [[ArchiveModel.AlbumInfo]]. Objects are
    * sorted by the album name (ignoring case).
    */
  given albumInfoOrdering: Ordering[ArchiveModel.AlbumInfo] = Ordering.by(_.albumName.toLowerCase(Locale.ROOT))

  /**
    * Enumeration defining the commands supported by this actor implementation.
    *
    * @tparam DATA the type of the data managed by an instance
    */
  enum MediumContentManagerCommand[DATA]:
    /**
      * Queries the data mapped to the key with the given ID. If no up-to-date
      * mapping is available, it is constructed now. If the ID does not match an
      * existing key, the data in the result is ''None''.
      *
      * @param id      the ID of the key for which to retrieve data
      * @param request the original request (this is required to create a 
      *                complete response)
      * @param replyTo the actor to receive the response
      */
    case GetDataFor(id: String,
                    request: ArchiveCommands.ReadMediumContentCommand,
                    replyTo: ActorRef[ArchiveCommands.GetMediumDataResponse[DATA]])

    /**
      * Notifies this actor that the content of the associated medium has been
      * updated. The updated data is passed in. This means that a mapping that
      * might already have been created becomes invalid and needs to be
      * reconstructed.
      *
      * @param data the updated list with songs on the managed medium
      */
    case UpdateData(data: Iterable[MediaMetadata])

    /**
      * An internal command this actor sends to itself when the asynchronous
      * construction of the data mapping is complete. The mapping is then
      * cached.
      *
      * @param mapping the updated mapping
      * @param seqNo   the sequence number this update is for
      */
    case DataMappingUpdated(mapping: Map[String, List[DATA]],
                            seqNo: Int)
  end MediumContentManagerCommand

  /**
    * A data class to hold the current state of an actor instance.
    *
    * @param metadata       the current list of song metadata on the managed
    *                       medium
    * @param dataMapping    the data view if already computed
    * @param pendingClients a list with requests that are currently processed
    * @param seqNo          a sequence number to detect stale updates
    * @tparam DATA the type of data managed by this instance
    */
  private case class MediumContentState[DATA](metadata: Iterable[MediaMetadata],
                                              dataMapping: Option[Map[String, List[DATA]]],
                                              pendingClients: List[MediumContentManagerCommand.GetDataFor[DATA]],
                                              seqNo: Int)

  /**
    * Returns the [[Behavior]] of a new actor instance to manage a specific 
    * view on the data of a medium as defined by the given parameters.
    *
    * @param keyExtractor  the function to extract keys; the keys are the basis
    *                      for grouping the data
    * @param dataExtractor the function to extract data
    * @param idManager     the actor to obtain IDs for extracted keys
    * @param groupingFunc  the function to control grouping
    * @param ord           a [[Ordering]] for sorting the data
    * @tparam DATA the type of the data managed by this instance
    * @return the [[Behavior]] of the new actor instance
    */
  def newInstance[DATA](keyExtractor: KeyExtractor,
                        dataExtractor: DataExtractor[DATA],
                        idManager: ActorRef[IdManagerActor.QueryIdCommand],
                        groupingFunc: GroupingFunc = identity)
                       (using ord: Ordering[DATA]): Behavior[MediumContentManagerCommand[DATA]] =
    val dummyTransformer: DataTransformer[DATA, DATA] = Future.successful
    newTransformingInstance(
      keyExtractor,
      dataExtractor,
      dummyTransformer,
      idManager,
      groupingFunc
    )

  /**
    * Returns the [[Behavior]] of a new actor instance to manage a specific
    * view on the data of a medium that requires an additional transformation
    * function.
    *
    * @param keyExtractor  the function to extract keys; the keys are the basis
    *                      for grouping the data
    * @param dataExtractor the function to extract data
    * @param transformer   the transformation function
    * @param idManager     the actor to obtain IDs for extracted keys
    * @param groupingFunc  the function to control grouping
    * @param ord           a [[Ordering]] for sorting the data
    * @tparam TEMP the type of temporary data returned by the data extractor
    * @tparam DATA the type of the data managed by this instance
    * @return the [[Behavior]] of the new actor instance
    */
  def newTransformingInstance[TEMP, DATA](keyExtractor: KeyExtractor,
                                          dataExtractor: DataExtractor[TEMP],
                                          transformer: DataTransformer[TEMP, DATA],
                                          idManager: ActorRef[IdManagerActor.QueryIdCommand],
                                          groupingFunc: GroupingFunc = identity)
                                         (using ord: Ordering[DATA]): Behavior[MediumContentManagerCommand[DATA]] =
    Behaviors.setup[MediumContentManagerCommand[DATA]]: context =>
      given Scheduler = context.system.scheduler

      given ExecutionContext = context.system.executionContext

      def handleCommand(state: MediumContentState[DATA]): Behavior[MediumContentManagerCommand[DATA]] =
        Behaviors.receiveMessage:
          case MediumContentManagerCommand.UpdateData(data) =>
            val nextState = state.copy[DATA](metadata = data, dataMapping = None, seqNo = state.seqNo + 1)
            if state.pendingClients.nonEmpty then
              triggerMappingConstruction(nextState)
            handleCommand(nextState)

          case getData: MediumContentManagerCommand.GetDataFor[DATA] =>
            state.dataMapping match
              case Some(mapping) =>
                getData.replyTo ! ArchiveCommands.GetMediumDataResponse(getData.request, mapping.get(getData.id))
                Behaviors.same

              case None =>
                if state.pendingClients.isEmpty then
                  triggerMappingConstruction(state)
                val nextState = state.copy(pendingClients = getData :: state.pendingClients)
                handleCommand(nextState)

          case MediumContentManagerCommand.DataMappingUpdated(mapping, seqNo) =>
            val nextState = if seqNo == state.seqNo then
              state.pendingClients.foreach: getData =>
                getData.replyTo ! ArchiveCommands.GetMediumDataResponse(getData.request, mapping.get(getData.id))
              state.copy(dataMapping = Some(mapping), pendingClients = Nil)
            else
              state // Ignore a state updated mapping.
            handleCommand(nextState)

      /**
        * Returns a [[Future]] with an updated mapping from the given song
        * metadata.
        *
        * @param data the current song metadata on the managed medium
        * @return a [[Future]] with the data mapping
        */
      def constructMapping(data: Iterable[MediaMetadata]): Future[Map[String, List[DATA]]] =
        for
          temp <- constructTempMapping(data)
          mapping <- transformer(temp)
        yield sortMapping(mapping)

      /**
        * Constructs the temporary mapping for the given song metadata based on
        * the configured key and data extractor functions.
        *
        * @param data the current song metadata on the managed medium
        * @return a [[Future]] with the temporary data mapping
        */
      def constructTempMapping(data: Iterable[MediaMetadata]): Future[Map[String, List[TEMP]]] =
        val metadataNames = data.map(d => d -> keyExtractor(d)).toMap
        idManager.getIds(data.map(keyExtractor)).map: idsResponse =>
          val metadataIDs = data.toList.map(m => m -> idsResponse.ids(metadataNames(m))).toMap
          val groupedMetadata = data.toList.groupBy(m => groupingFunc(metadataIDs(m)))
          groupedMetadata.map: (id, list) =>
            (id, list.map(md => dataExtractor(metadataIDs(md), md)).distinct)

      /**
        * Sorts the given mapping based on the implicit ordering configured for
        * this actor instance.
        *
        * @param mapping the mapping
        * @return the sorted mapping
        */
      def sortMapping(mapping: Map[String, List[DATA]]): Map[String, List[DATA]] =
        mapping.map: (key, items) =>
          (key, items.sorted)

      /**
        * Triggers an asynchronous construction of the data mapping based on
        * the current state. When this is done, the asynchronous task sends a
        * message to this actor, so that the new mapping can be added to the
        * state, and pending requests can be served.
        *
        * @param state the current state of this actor
        */
      def triggerMappingConstruction(state: MediumContentState[DATA]): Unit =
        context.log.info("Constructing medium view.")
        constructMapping(state.metadata).foreach: mapping =>
          context.self ! MediumContentManagerCommand.DataMappingUpdated(mapping, state.seqNo)

      handleCommand(MediumContentState(List.empty, None, Nil, 0))
