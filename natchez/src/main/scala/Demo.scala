// Copyright (c) 2019-2020 by Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package example

import cats._
import cats.data.Kleisli
import cats.effect._
import cats.syntax.all._
import natchez._
import scala.util.Random
import scala.concurrent.duration._
import java.net.URI

object Main extends IOApp {

  // Intentionally slow parallel quicksort, to demonstrate branching. If we run too quickly it seems
  // to break Jaeger with "skipping clock skew adjustment" so let's pause a bit each time.
  def qsort[F[_]: Monad: Parallel: Trace: Timer, A: Order](as: List[A]): F[List[A]] =
    Trace[F].span(as.mkString(",")) {
      Timer[F].sleep(10.milli) *> {
          as match {
          case Nil    => Monad[F].pure(Nil)
          case h :: t =>
            val (a, b) = t.partition(_ <= h)
            (qsort[F, A](a), qsort[F, A](b)).parMapN(_ ++ List(h) ++ _)
        }
      }
    }

  def runF[F[_]: Sync: Trace: Parallel: Timer]: F[Unit] =
    Trace[F].span("Sort some stuff!") {
      for {
        as <- Sync[F].delay(List.fill(10)(Random.nextInt(1000)))
        _  <- qsort[F, Int](as)
        u  <- Trace[F].traceUri
        _  <- u.traverse(uri => Sync[F].delay(println(s"View this trace at $uri")))
        _  <- Sync[F].delay(println("Done."))
      } yield ()
    }

  // For Honeycomb you would say
  // def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] =
  //   Honeycomb.entryPoint[F]("natchez-example") { ob =>
  //     Sync[F].delay {
  //       ob.setWriteKey("<your API key here>")
  //         .setDataset("<your dataset>")
  //         .build
  //     }
  //   }

  // The following would be the minimal entrypoint setup for Lighstep. Note that
  // by default examples project uses lighstep HTTP binding. To change that,
  // edit the project dependencies.
  // def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] =
  //   Lightstep.entryPoint[F] { ob =>
  //     Sync[F].delay {
  //       val options = ob
  //         .withAccessToken("<your access token>")
  //         .withComponentName("<your app's name>")
  //         .withCollectorHost("<your collector host>")
  //         .withCollectorProtocol("<your collector protocol>")
  //         .withCollectorPort(<your collector port>)
  //         .build()
  //
  //       new JRETracer(options)
  //     }
  //   }

//   DataDog
//   def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] = {
//     import java.util.Properties
//     import natchez.datadog.DDTracer
//     DDTracer.entryPoint[F](
//       buildFunc =
//         builder =>
//           Sync[F].delay(
//             builder.withProperties(
//               new Properties() {
//                 put("writer.type", "LoggingWriter")
//               }
//             ).serviceName("natchez-sample").build()
//           ),
//       uriPrefix = Some(new URI("https://app.datadoghq.com")) // https://app.datadoghq.eu for Europe
//     )
//   }

  // Jaeger
  def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] = {
    import natchez.jaeger.Jaeger
    import io.jaegertracing.Configuration.SamplerConfiguration
    import io.jaegertracing.Configuration.ReporterConfiguration
    Jaeger.entryPoint[F](
      system    = "natchez-example",
      uriPrefix = Some(new URI("http://localhost:16686")),
    ) { c =>
      Sync[F].delay {
        c.withSampler(SamplerConfiguration.fromEnv)
         .withReporter(ReporterConfiguration.fromEnv)
         .getTracer
      }
    }
  }

  // Log
  // def entryPoint[F[_]: Sync]: Resource[F, EntryPoint[F]] = {
  //   import natchez.log.Log
  //   import io.chrisdavenport.log4cats.Logger
  //   import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
  //   implicit val log: Logger[F] = Slf4jLogger.getLogger[F]
  //   Log.entryPoint[F]("foo").pure[Resource[F, *]]
  // }

  // Log with Odin
  // def entryPoint[F[_]: Sync: Timer]: Resource[F, EntryPoint[F]] = {
  //   import natchez.logodin.Log
  //   import io.odin.Logger
  //   import io.odin.consoleLogger
  //   import io.odin.{Level, formatter}
  //   implicit val log: Logger[F] = consoleLogger[F](
  //     formatter = formatter.Formatter.colorful,
  //     minLevel = Level.Info
  //   )
  //   Log.entryPoint[F]("foo").pure[Resource[F, *]]
  // }

  def run(args: List[String]): IO[ExitCode] = {
    entryPoint[IO].use { ep =>
      ep.root("this is the root span").use { span =>
        runF[Kleisli[IO, Span[IO], *]].run(span)
      } *> IO.sleep(1.second) // Turns out Tracer.close() in Jaeger doesn't block. Annoying. Maybe fix in there?
    } as ExitCode.Success
  }

}
