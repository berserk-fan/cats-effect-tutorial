package tutorial

import cats.effect.implicits.monadCancelOps_
import cats.effect.kernel.Async
import cats.effect.{Deferred, ExitCode, IO, IOApp, Ref, Sync}
import cats.effect.std.Console
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.collection.immutable.Queue

object ProducerConsumer extends IOApp {
  val printPeriod = 10000

  case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F, A]], offerers: Queue[(A, Deferred[F, Unit])])

  def producer[F[_] : Async : Console](id: Int, state: Ref[F, State[F, Int]], counter: Ref[F, Int]): F[Unit] = {
    def offer(i: Int): F[Unit] = {
      Deferred[F, Unit].flatMap { offerer =>
        Async[F].uncancelable { poll =>
          state.modify {
            case State(queue, capacity, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(queue, capacity, rest, offerers) -> taker.complete(i).void
            case State(queue, capacity, takers, offerers) if queue.size < capacity =>
              State(queue.enqueue(i), capacity, takers, offerers) -> Async[F].unit
            case State(queue, capacity, takers, offerers) =>
              val removeOfferer = state.update(s => s.copy(offerers = s.offerers.filter(_._2 != offerer)))
              State(queue, capacity, takers, offerers.enqueue(i -> offerer)) -> poll(offerer.get).onCancel(removeOfferer)
          }.flatten
        }
      }
    }

    for {
      i <- counter.getAndUpdate(_ + 1)
      _ <- offer(i)
      _ <- if (i % printPeriod == 0) Console[F].println(s"$id Produced $i items") else Sync[F].unit
      _ <- producer(id, state, counter)
    } yield ()
  }

  def consumer[F[_] : Async : Console](id: Int, state: Ref[F, State[F, Int]]): F[Unit] = {
    val take: F[Int] = Deferred[F, Int].flatMap { taker =>
      Async[F].uncancelable { poll =>
        state.modify {
          case State(queue, capacity, takers, offerers) if queue.isEmpty && offerers.nonEmpty =>
            val ((i, offerer), rest) = offerers.dequeue
            State(queue, capacity, takers, rest) -> offerer.complete(()).as(i)
          case State(queue, capacity, takers, offerers) if queue.nonEmpty =>
            val (i, rest) = queue.dequeue
            State(rest, capacity, takers, offerers) -> Async[F].pure(i)
          case State(queue, capacity, takers, offerers) =>
            val removeTaker = state.update(s => s.copy(takers = takers.filter(_ ne taker)))
            State(queue, capacity, takers.enqueue(taker), offerers) -> poll(taker.get).onCancel(removeTaker)
        }.flatten
      }
    }

    for {
      i <- take
      _ <- if (i % printPeriod == 0) Console[F].println(s"$id Consumer $i value") else Sync[F].unit
      _ <- consumer(id, state)
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = for {
    stateRef <- Ref.of[IO, State[IO, Int]](State(Queue[Int](), 100, Queue[Deferred[IO, Int]](), Queue[(Int, Deferred[IO, Unit])]()))
    counterRef <- Ref.of[IO, Int](0)
    producers = List.range(1, 11).map(producer(_, stateRef, counterRef)) // 10 producers
    consumers = List.range(1, 11).map(consumer(_, stateRef))
    res <- (consumers ++ producers).parSequence.as(ExitCode.Success)
      .timeout(5.seconds)
      .handleErrorWith { err => Console[IO].println(s"Caught error ${err.getMessage}").as(ExitCode.Error) }
  } yield res
}
