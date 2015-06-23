package play.api.libs.ws.akka.support

import java.util.concurrent.{ConcurrentLinkedDeque, ConcurrentLinkedQueue}
import java.util.concurrent.atomic.AtomicReference
import java.util.function.UnaryOperator
import scala.concurrent.{ExecutionContext, Promise, Future}

/**
 *
 */
class ProducerConsumer[A] {

  private val elements = new ConcurrentLinkedDeque[A]()

  /**
   * Consumers will listen to the future of this promise so that they get "woken up" whenever this
   * collection will be filled somehow. In doing so, no operation needs to block.
   */
  private val notEmpty: AtomicReference[Option[Promise[Unit]]] = new AtomicReference(None)

  // -------------------------------------------- Public methods

  def offer(element: A): Unit = offerWith(_.offer(element))
  def offerFirst(element: A): Unit = offerWith(_.offerFirst(element))
  def offerLast(element: A): Unit = offerWith(_.offerLast(element))

  @inline private def offerWith(body: ConcurrentLinkedDeque[A] => Boolean): Boolean = {
    val result = body(elements)
    notEmpty.getAndSet(None).foreach(_.success(()))
    result
  }

  /**
   * Note that the way this is implemented, we don't offer any kind of order ..
   *
   * #1 offer
   * #1 take -> receives #1
   * #2 take
   * #3 take
   * #2 offer -> might cause #3 take to receive this
   * #4 take
   * #3 offer -> might cause #4 take to receive this
   * #4 offer -> might cause #2 take to receive this
   *
   * This only guarantees that each take will correspond to one and only one offer.
   */
  def take(implicit ec: ExecutionContext): Future[A] = {
    val result = elements.poll()
    if (result != null) {
      Future.successful(result)
    } else {
      val promise = notEmpty.updateAndGet(unary({
        case Some(previous) => Some(previous)
        case None           => Some(Promise[Unit]())
      }))

      promise.get.future.flatMap({ _ =>
        take
      })
    }
  }

  private def unary[T](body: T => T): UnaryOperator[T] = new UnaryOperator[T] {
    override def apply(t: T): T = body(t)
  }

}
