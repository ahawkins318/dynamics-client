// Copyright (c) 2017 The Trapelo Group LLC
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package dynamics
package cli
import cats.effect.IO

import client.DynamicsClient

/** Context for running commands. */
trait Context {
  implicit val e: scala.concurrent.ExecutionContext
  implicit val sch: fs2.Scheduler
}

/** Dynamics context. */
trait DynamicsContext extends Context {

  /** Set your own LCID. Default is US English. */
  val LCID: Int = 1033

  implicit val dynclient: DynamicsClient

  /** Execute to close. */
  def close(): IO[Unit]

  /** The overall configuration for all plugins/modules. */
  def appConfig: AppConfig
}

object DynamicsContext {

  import scala.concurrent.ExecutionContext
  import fs2._
  import dynamics.http._
  import dynamics.client._

  /**
    * Create a default DynamicsContext. Middleware for request retry and ADAL
    * are automatically addded. Allowed policies are ''backoff'' and any other
    * policy string is translated into a ''pause'' policy.
    */
  def default(config: AppConfig)(implicit e: ExecutionContext): DynamicsContext =
    new DynamicsContext {
      import dynamics.client._
      implicit val e   = scala.concurrent.ExecutionContext.Implicits.global
      implicit val sch = Scheduler.default

      val fetchOpts = NodeFetchClientOptions(timeoutInMillis = config.common.requestTimeOutInMillis.getOrElse(0))

      val retryPolicyMiddleware = config.common.retryPolicy match {
        case "backoff" => RetryClient.backoff(config.common.numRetries, config.common.pauseBetween/*, config.common.debug*/)
        case _ => RetryClient.pause(config.common.numRetries, config.common.pauseBetween/*, config.common.debug*/)
      }
      val middleware =
        retryPolicyMiddleware andThen ADAL(config.common.connectInfo)

      val httpclient: Client =
        middleware(NodeFetchClient.newClient(config.common.connectInfo, config.common.debug, opts = fetchOpts))

      implicit val dynclient: DynamicsClient =
        DynamicsClient(httpclient, config.common.connectInfo, config.common.debug)

      def close() = httpclient.dispose

      val appConfig = config
    }
}
