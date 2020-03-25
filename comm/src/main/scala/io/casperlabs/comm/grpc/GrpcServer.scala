package io.casperlabs.comm.grpc

import cats.implicits._
import cats.effect._
import io.grpc.{Server, ServerInterceptor, ServerServiceDefinition}
import io.grpc.netty.NettyServerBuilder
import io.netty.handler.ssl.SslContext
import monix.execution.Scheduler
import java.util.concurrent.{TimeUnit, TimeoutException}
import scala.concurrent.duration._

object GrpcServer {
  type ServiceBinder[F[_]] = Scheduler => F[ServerServiceDefinition]

  /** Start a gRPC server resource with multiple services listening on a common port. */
  def apply[F[_]: Sync](
      port: Int,
      services: List[ServiceBinder[F]],
      interceptors: List[ServerInterceptor] = Nil,
      sslContext: Option[SslContext] = None,
      maxMessageSize: Option[Int] = None,
      shutdownTimeout: FiniteDuration = 10.seconds
  )(
      implicit scheduler: Scheduler
  ): Resource[F, Server] =
    Resource.make(
      services.traverse(_(scheduler)).map { boundService =>
        val builder = NettyServerBuilder
          .forPort(port)
          .executor(scheduler)

        sslContext.foreach(builder.sslContext(_))
        boundService.foreach(builder.addService(_))
        interceptors.foreach(builder.intercept(_))
        maxMessageSize.foreach(builder.maxMessageSize(_))

        builder.build.start
      }
    )(
      server =>
        Sync[F].delay {
          // Calling `awaitTermination(<timeout>)` and then `.shutdownNow()` if this didn't work immediately,
          // otherwise shutting down docker containers take quite a long time.
          if (!server.shutdown().awaitTermination(shutdownTimeout.toSeconds, TimeUnit.SECONDS))
            server.shutdownNow()
        }
    )
}
