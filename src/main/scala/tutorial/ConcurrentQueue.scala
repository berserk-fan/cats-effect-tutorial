package tutorial

import cats.effect.implicits.monadCancelOps_
import cats.effect.{Async, Deferred, Ref}
import cats.implicits.{toFlatMapOps, toFunctorOps}

import scala.collection.immutable.Queue

case class State[F[_], A](queue: Queue[A], capacity: Int, takers: Queue[Deferred[F, A]], offerers: Queue[(A, Deferred[F, Unit])])

class ConcurrentQueue[F[_] : Async, A] private (private val state: Ref[F, State[F, A]]) {
  def offer(a: A): F[Unit] = {
    Deferred[F, Unit].flatMap { offerer =>
      Async[F].uncancelable { poll =>
        state.modify {
          case State(queue, capacity, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, capacity, rest, offerers) -> taker.complete(a).void
          case State(queue, capacity, takers, offerers) if queue.size < capacity =>
            State(queue.enqueue(a), capacity, takers, offerers) -> Async[F].unit
          case State(queue, capacity, takers, offerers) =>
            val removeOfferer = state.update(s => s.copy(offerers = s.offerers.filter(_._2 != offerer)))
            State(queue, capacity, takers, offerers.enqueue(a -> offerer)) -> poll(offerer.get).onCancel(removeOfferer)
        }.flatten
      }
    }
  }

  def take: F[A] = Deferred[F, A].flatMap { taker =>
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
}

object ConcurrentQueue {
  def empty[F[_]: Async, A](capacity: Int): F[ConcurrentQueue[F,A]] = {
    val state: State[F,A] = State(Queue(), capacity, Queue(), Queue())
    Ref.of(state).map(new ConcurrentQueue[F,A](_))
  }
}
