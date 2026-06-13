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

import de.oliver_heger.linedj.server.discovery.ServerDiscovery
import de.oliver_heger.linedj.shared.config.ConfigExtensions.toDuration
import org.apache.commons.configuration2.ImmutableHierarchicalConfiguration

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

private object ArchiveClientConfig:
  /**
    * The key of the subsection in the platform configuration under which the
    * properties for the media archive are located.
    */
  private final val ArchiveConfigSection = "platform.mediaArchive"

  /**
    * The key of the subsection in the archive configuration under which the
    * properties for the discovery are located.
    */
  private final val DiscoveryConfigSection = "discovery"

  /**
    * The key of the subsection in the archive configuration that contains the
    * properties for monitoring changes in the archive's content.
    */
  private final val ContentMonitoringSection = "monitor.content"

  /**
    * The name of the mandatory configuration property for the multicast
    * address to use for the archive discovery operation.
    */
  private final val PropDiscoveryAddress = "multicastAddress"

  /**
    * The name of the mandatory configuration property for the port to use for
    * the archive discovery operation.
    */
  private final val PropDiscoveryPort = "port"

  /**
    * The name of the mandatory configuration property for the request code for
    * the archive discovery operation.
    */
  private final val PropDiscoveryCode = "requestCode"

  /**
    * The name of the optional configuration property defining the timeout for
    * a single request during the archive discovery operation.
    */
  private final val PropDiscoveryTimeout = "timeout"

  /**
    * The name of the optional configuration property defining the minimum
    * backoff for a repeated operation.
    */
  private final val PropMinBackoff = "minBackoff"

  /**
    * The name of the optional configuration property defining the maximum
    * backoff for a repeated operation.
    */
  private final val PropMaxBackoff = "maxBackoff"

  /**
    * The name of the optional configuration property defining the increment
    * factor for a repeated operation.
    */
  private final val PropIncFactor = "factor"

  /**
    * The name of the optional configuration property defining a timeout for
    * requests to the archive server.
    */
  private final val PropArchiveTimeout = "requestTimeout"

  /**
    * The default timeout for sending requests to the archive server. This is
    * used if no explicit timeout is set in the client configuration.
    */
  final val DefaultArchiveTimeout = 30.seconds

  /**
    * A [[BackoffConfig]] defining default values for properties. These are
    * used for missing values in the platform configuration.
    */
  final val DefaultBackoffConfig = BackoffConfig(
    minBackoff = 5.seconds,
    maxBackoff = 3.minutes,
    factor = 1.25
  )

  /**
    * Tries to create an [[ArchiveClientConfig]] from the properties of the
    * current platform configuration. If mandatory properties are undefined,
    * result is _None_.
    *
    * @param config the platform configuration
    * @return an [[Option]] with the configuration for this component
    */
  def apply(config: ImmutableHierarchicalConfiguration): Option[ArchiveClientConfig] =
    for
      clientConfig <- subSection(config, ArchiveConfigSection)
      discoveryConfig <- subSection(clientConfig, DiscoveryConfigSection)
      address <- Option(discoveryConfig.getString(PropDiscoveryAddress))
      port <- extractDiscoveryPort(discoveryConfig)
      code <- Option(discoveryConfig.getString(PropDiscoveryCode))
    yield
      ArchiveClientConfig(
        discoveryParams = ServerDiscovery.DiscoveryParams(
          multicastAddress = address,
          port = port,
          requestCode = code,
          timeout = extractDurationProperty(discoveryConfig, PropDiscoveryTimeout, ServerDiscovery.DefaultTimeout),
          minBackoff = extractDurationProperty(
            discoveryConfig,
            PropMinBackoff,
            ServerDiscovery.DefaultMinBackoff
          ),
          maxBackoff = extractDurationProperty(
            discoveryConfig,
            PropMaxBackoff,
            ServerDiscovery.DefaultMaxBackoff
          )
        ),
        archiveTimeout = extractDurationProperty(clientConfig, PropArchiveTimeout, DefaultArchiveTimeout),
        optContentMonitorBackoff = extractBackoffConfig(clientConfig, ContentMonitoringSection)
      )

  /**
    * Tries to get a subsection from the given configuration under a specific
    * key.
    *
    * @param config the configuration
    * @param key    the key
    * @return an [[Option]] with the subconfiguration at this key
    */
  private def subSection(config: ImmutableHierarchicalConfiguration, key: String):
  Option[ImmutableHierarchicalConfiguration] = Try(config.immutableConfigurationAt(key)).toOption

  /**
    * Extracts the port parameter for the discovery from the given
    * configuration. Result is _None_ if the parameter is missing.
    *
    * @param config the configuration
    * @return an [[Option]] with the port for the discovery
    */
  private def extractDiscoveryPort(config: ImmutableHierarchicalConfiguration): Option[Int] =
    if config.containsKey(PropDiscoveryPort) then
      Some(config.getInt(PropDiscoveryPort))
    else
      None

  /**
    * Extracts a [[BackoffConfig]] at the given path if this path exists. The
    * existence of the path determines whether a defined or undefined option is
    * returned. For missing properties, this function sets default values.
    *
    * @param config the configuration
    * @param path   the subpath for the backoff configuration to extract
    * @return an [[Option]] with the extracted configuration
    */
  private def extractBackoffConfig(config: ImmutableHierarchicalConfiguration, path: String): Option[BackoffConfig] =
    subSection(config, path).map: backoffConfig =>
      BackoffConfig(
        minBackoff = extractDurationProperty(backoffConfig, PropMinBackoff, DefaultBackoffConfig.minBackoff),
        maxBackoff = extractDurationProperty(backoffConfig, PropMaxBackoff, DefaultBackoffConfig.maxBackoff),
        factor = backoffConfig.getDouble(PropIncFactor, DefaultBackoffConfig.factor)
      )

  /**
    * Extracts an optional duration property from the given configuration.
    *
    * @param config  the configuration
    * @param key     the key of the property
    * @param default the default value for this property
    * @return the extracted property value
    */
  private def extractDurationProperty(config: ImmutableHierarchicalConfiguration,
                                      key: String,
                                      default: FiniteDuration): FiniteDuration =
    Option(config.getString(key)).flatMap(_.toDuration.toOption).getOrElse(default)
end ArchiveClientConfig

/**
  * A data class holding the configuration settings for a repeated action with
  * an increasing delay. Such configurations are needed to monitor certain
  * properties of the archive.
  *
  * @param minBackoff the minimum backoff
  * @param maxBackoff the maximum backoff
  * @param factor     the increment factor between two actions
  */
private case class BackoffConfig(minBackoff: FiniteDuration,
                                 maxBackoff: FiniteDuration,
                                 factor: Double)

/**
  * A data class to store all the configuration settings supported by the
  * archive client component. The properties reflect the part of the platform
  * configuration consumed by this component.
  *
  * The archive client can monitor some properties of the archive server to
  * react on certain status changes. This is done by polling the corresponding
  * properties repeatedly with a backoff delay. (Typically, there are bulk
  * changes when the server is updated; after such an update, it is stable
  * again, so that the rate of checks can be decreased.) The configuration of
  * such polling actions is specified using a [[BackoffConfig]]. They are
  * optional. If the platform configuration does not contain the corresponding
  * key for an action, it is disabled. If the key is available, the
  * configuration class reads the properties in this section and uses default
  * values for missing ones. So, an available section key enables this
  * mechanism even if the section is empty.
  *
  * @param discoveryParams          the parameters for the discovery operation
  * @param archiveTimeout           the timeout for requests to the archive
  * @param optContentMonitorBackoff the optional config for monitoring changes
  *                                 in the archive's content
  */
private case class ArchiveClientConfig(discoveryParams: ServerDiscovery.DiscoveryParams,
                                       archiveTimeout: FiniteDuration,
                                       optContentMonitorBackoff: Option[BackoffConfig])
