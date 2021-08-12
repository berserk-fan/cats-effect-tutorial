package tutorial

import cats.effect.kernel.Async
import cats.effect.{ExitCode, IO, IOApp, Ref, Sync}
import cats.effect.std.Console
import cats.syntax.all.*

import scala.concurrent.duration.*

object ProducerConsumer extends IOApp {
  val printPeriod = 10000

  def producer[F[_] : Async : Console](id: Int, queue: ConcurrentQueue[F,Int], counter: Ref[F, Int]): F[Unit] = {
    for {
      i <- counter.getAndUpdate(_ + 1)
      _ <- queue.offer(i)
      _ <- if (i % printPeriod == 0) Console[F].println(s"$id Produced $i items") else Sync[F].unit
      _ <- producer(id, queue, counter)
    } yield ()
  }

  def consumer[F[_] : Async : Console](id: Int, queue: ConcurrentQueue[F,Int]): F[Unit] = {
    for {
      i <- queue.take
      _ <- if (i % printPeriod == 0) Console[F].println(s"$id Consumer $i value") else Sync[F].unit
      _ <- consumer(id, queue)
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    queue <- ConcurrentQueue.empty[IO,Int](100)
    counterRef <- Ref.of[IO, Int](0)
    producers = List.range(1, 11).map(producer(_, queue, counterRef)) // 10 producers
    consumers = List.range(1, 11).map(consumer(_, queue))
    res <- (consumers ++ producers).parSequence.as(ExitCode.Success)
      .timeout(5.seconds)
      .handleErrorWith { err => Console[IO].println(s"Caught error ${err.getMessage}").as(ExitCode.Error) }
  } yield res
}
