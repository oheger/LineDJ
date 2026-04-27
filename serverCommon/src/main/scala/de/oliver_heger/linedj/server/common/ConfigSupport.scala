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

package de.oliver_heger.linedj.server.common

import de.oliver_heger.linedj.server.common.ServerController.given
import de.oliver_heger.linedj.utils.SystemPropertyAccess
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder
import org.apache.commons.configuration2.builder.fluent.Parameters
import org.apache.commons.configuration2.io.{ClasspathLocationStrategy, CombinedLocationStrategy, HomeDirectoryLocationStrategy, ProvidedURLLocationStrategy}
import org.apache.commons.configuration2.{ImmutableHierarchicalConfiguration, XMLConfiguration}
import org.apache.logging.log4j.LogManager

import scala.concurrent.Future
import scala.util.Try

object ConfigSupport:
  /**
    * The name of a system property that specifies the name of the
    * configuration file to be used. If this property is not defined, the
    * default file name specified by a concrete [[ConfigSupport]]
    * implementation is used.
    */
  final val PropConfigFileName = "configFile"

  /** The logger. */
  private val log = LogManager.getLogger(classOf[ConfigSupport])

  /**
    * Alias for a function that can extract a concrete server configuration
    * from a passed in [[ImmutableHierarchicalConfiguration]] instance. The
    * function returns a [[Try]], since the configuration may be invalid.
    */
  type ConfigLoader[CONF] = ImmutableHierarchicalConfiguration => Try[CONF]

  /**
    * A data class representing the context for this trait. The trait reads the
    * configured configuration file and extracts the server-related
    * configuration. Concrete subclasses then obtain their own configuration
    * settings and can create a custom context.
    *
    * @param serverConfig the configuration for the server itself
    * @param config       the configuration for a subclass
    * @param context      the context for a subclass
    * @tparam CONF    the type of the custom configuration
    * @tparam CONTEXT the type of the custom context
    */
  final case class ConfigSupportContext[CONF, CONTEXT](serverConfig: ServerConfig,
                                                       config: CONF,
                                                       context: CONTEXT)
end ConfigSupport

/**
  * A trait adding support for reading a configuration file and extracting a 
  * specific configuration object from this data. The parameters of the server
  * are also extracted from a separate configuration section. The name of the
  * configuration file load is defined by a concrete subclass. Optionally, it
  * is possible to override the file name via a system property.
  *
  * This trait overrides the [[ServerController.Context]] type as a type that
  * stores both a configuration object and a custom context. The type of the
  * custom context can be defined by a subclass. This trait then manages both
  * the custom configuration and the context.
  */
trait ConfigSupport extends ServerController:
  this: SystemPropertyAccess =>

  import ConfigSupport.*

  /**
    * The type of the concrete configuration for a derived class. This becomes
    * one of the generic type parameter of the [[ConfigSupportContext]] object
    * for this instance.
    */
  type CustomConfig

  /**
    * The type of the custom context used by a concrete controller
    * implementation. Derived classes can use this to store additional
    * information or service objects. This type becomes the other generic type
    * parameter of the [[ConfigSupportContext]] object for this instance.
    */
  type CustomContext

  /**
    * The context type used by this trait. It consists of the server-related
    * configuration and the data to be managed on behalf of a concrete
    * subclass.
    */
  override type Context = ConfigSupportContext[CustomConfig, CustomContext]

  /**
    * Returns the default name of the configuration file for this server
    * application. The controller loads a file with this name (in the user's
    * home directory), unless an alternative name is specified in the
    * ''PropConfigFileName'' property.
    *
    * @return the default name of the configuration file
    */
  def defaultConfigFileName: String

  /**
    * Returns the object to extract the archive config from the global
    * application configuration. When creating the server context, this loader
    * is used to obtain the custom configuration.
    *
    * @return the loader for the custom configuration
    */
  def configLoader: ConfigLoader[CustomConfig]

  /**
    * Creates the object for the custom context. This function is invoked when
    * creating the (base) context. It must be overridden by subclasses to 
    * create additional objects required by a concrete server implementation. 
    * For this purpose, the context object is passed in that is initialized
    * except for the custom context.
    *
    * @param context  the base context without any custom data
    * @param services the object with server services
    * @return the custom context for this server instance
    */
  def createCustomContext(context: ConfigSupportContext[CustomConfig, Unit])
                         (using services: ServerController.ServerServices): Future[CustomContext]

  /**
    * @inheritdoc This implementation determines the path to the configuration
    *             file and loads it. It extracts the [[ServerConfig]] and the
    *             custom configuration out of it. Then it creates the custom
    *             context and constructs a [[ConfigSupportContext]] object to
    *             store all this information.
    */
  override def createContext(using services: ServerController.ServerServices): Future[Context] =
    val configFileName = getSystemProperty(PropConfigFileName).getOrElse(defaultConfigFileName)
    log.info("Loading configuration file from '{}'.", configFileName)

    for
      config <- Future.fromTry(loadConfiguration(configFileName))
      serverConfig <- Future.fromTry(ServerConfig(config))
      customConfig <- Future.fromTry(configLoader(config))
      baseCtx = ConfigSupportContext(serverConfig, customConfig, ())
      custom <- createCustomContext(baseCtx)
    yield
      ConfigSupportContext(serverConfig, customConfig, custom)

  /**
    * @inheritdoc This implementation creates server parameters based on the
    *             [[ServerConfig]] parsed from the configuration file. This 
    *             includes parameters for a [[ServerLocator]].
    */
  override def serverParameters(context: Context)
                               (using services: ServerController.ServerServices):
  Future[ServerController.ServerParameters] =
    super.serverParameters(context) map : params =>
      val modifiedBindingParameters = params.bindingParameters.copy(bindPort = context.serverConfig.httpPort)
      val locatorParams = context.serverConfig.optDiscoveryConfig.map: discoveryConfig =>
        ServerLocator.LocatorParams(
          multicastAddress = discoveryConfig.multicastAddress,
          port = discoveryConfig.port,
          requestCode = discoveryConfig.command,
          responseTemplate = locatorResponseTemplate(context)
        )
      ServerController.ServerParameters(modifiedBindingParameters, locatorParams)

  /**
    * Generates the response template for the server locator. This base
    * implementation generates a default template based on the server
    * configuration.
    *
    * @param context the current server context
    * @return the template for the server locator
    */
  protected def locatorResponseTemplate(context: Context): String =
    s"http://${ServerLocator.PlaceHolderAddress}:${context.serverConfig.httpPort}"

  /**
    * Reads the configuration file with the given name. This implementation
    * expects that the configuration options are defined in an XML file.
    *
    * @param configFileName the name of the configuration file
    * @return a [[Try]] with the parsed configuration object
    */
  protected def loadConfiguration(configFileName: String): Try[ImmutableHierarchicalConfiguration] = Try:
    import scala.jdk.CollectionConverters.*
    val params = new Parameters
    val locationStrategies = List(
      new ProvidedURLLocationStrategy,
      new ClasspathLocationStrategy,
      new HomeDirectoryLocationStrategy
    )
    val builder = new FileBasedConfigurationBuilder(classOf[XMLConfiguration])
      .configure(
        params.xml()
          .setFileName(configFileName)
          .setLocationStrategy(new CombinedLocationStrategy(locationStrategies.asJava))
      )
    builder.getConfiguration
