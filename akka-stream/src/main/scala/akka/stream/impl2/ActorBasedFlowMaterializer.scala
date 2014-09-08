/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl2

import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.Await
import org.reactivestreams.{ Processor, Publisher, Subscriber }
import akka.actor._
import akka.pattern.ask
import akka.stream.{ MaterializerSettings, Transformer }
import akka.stream.impl.{ ActorProcessor, ActorPublisher, ExposedPublisher, TransformProcessorImpl }
import akka.stream.scaladsl2._
import akka.stream.impl.MergeImpl

/**
 * INTERNAL API
 */
private[akka] object Ast {
  sealed trait AstNode {
    def name: String
  }

  case class Transform(name: String, mkTransformer: () ⇒ Transformer[Any, Any]) extends AstNode

  trait MergeNode extends AstNode {
    override def name = "merge"
  }

}

/**
 * INTERNAL API
 */
case class ActorBasedFlowMaterializer(override val settings: MaterializerSettings,
                                      supervisor: ActorRef,
                                      flowNameCounter: AtomicLong,
                                      namePrefix: String)
  extends FlowMaterializer(settings) {

  import akka.stream.impl2.Ast._

  def withNamePrefix(name: String): FlowMaterializer = this.copy(namePrefix = name)

  private def nextFlowNameCount(): Long = flowNameCounter.incrementAndGet()

  private def createFlowName(): String = s"$namePrefix-${nextFlowNameCount()}"

  @tailrec private def processorChain(topSubscriber: Subscriber[_], ops: immutable.Seq[AstNode],
                                      flowName: String, n: Int): Subscriber[_] = {
    ops match {
      case op :: tail ⇒
        val opProcessor: Processor[Any, Any] = processorForNode(op, flowName, n)
        opProcessor.subscribe(topSubscriber.asInstanceOf[Subscriber[Any]])
        processorChain(opProcessor, tail, flowName, n - 1)
      case _ ⇒ topSubscriber
    }
  }

  // Ops come in reverse order
  override def materialize[In, Out](source: Source[In], sink: Sink[Out], ops: List[Ast.AstNode]): MaterializedFlow = {
    val flowName = createFlowName()

    def attachSink(pub: Publisher[Out]) = sink match {
      case s: SimpleSink[Out]     ⇒ s.attach(pub, this, flowName)
      case s: SinkWithKey[Out, _] ⇒ s.attach(pub, this, flowName)
      case _                      ⇒ throw new MaterializationException("unknown Sink type " + sink.getClass)
    }
    def attachSource(sub: Subscriber[In]) = source match {
      case s: SimpleSource[In]     ⇒ s.attach(sub, this, flowName)
      case s: SourceWithKey[In, _] ⇒ s.attach(sub, this, flowName)
      case _                       ⇒ throw new MaterializationException("unknown Source type " + sink.getClass)
    }
    def createSink() = sink.asInstanceOf[Sink[In]] match {
      case s: SimpleSink[In]     ⇒ s.create(this, flowName) -> (())
      case s: SinkWithKey[In, _] ⇒ s.create(this, flowName)
      case _                     ⇒ throw new MaterializationException("unknown Sink type " + sink.getClass)
    }
    def createSource() = source.asInstanceOf[Source[Out]] match {
      case s: SimpleSource[Out]     ⇒ s.create(this, flowName) -> (())
      case s: SourceWithKey[Out, _] ⇒ s.create(this, flowName)
      case _                        ⇒ throw new MaterializationException("unknown Source type " + sink.getClass)
    }
    def isActive(s: AnyRef) = s match {
      case source: SimpleSource[_]     ⇒ source.isActive
      case source: SourceWithKey[_, _] ⇒ source.isActive
      case sink: SimpleSink[_]         ⇒ sink.isActive
      case sink: SinkWithKey[_, _]     ⇒ sink.isActive
      case _: Source[_]                ⇒ throw new MaterializationException("unknown Source type " + sink.getClass)
      case _: Sink[_]                  ⇒ throw new MaterializationException("unknown Sink type " + sink.getClass)
    }

    val (sourceValue, sinkValue) =
      if (ops.isEmpty) {
        if (isActive(sink)) {
          val (sub, value) = createSink()
          (attachSource(sub), value)
        } else if (isActive(source)) {
          val (pub, value) = createSource()
          (value, attachSink(pub))
        } else {
          val id: Processor[In, Out] = processorForNode(identityTransform, flowName, 1).asInstanceOf[Processor[In, Out]]
          (attachSource(id), attachSink(id))
        }
      } else {
        val opsSize = ops.size
        val last = processorForNode(ops.head, flowName, opsSize).asInstanceOf[Processor[Any, Out]]
        val first = processorChain(last, ops.tail, flowName, opsSize - 1).asInstanceOf[Processor[In, Any]]
        (attachSource(first), attachSink(last))
      }
    new MaterializedFlow(source, sourceValue, sink, sinkValue)
  }

  private val identityTransform = Transform("identity", () ⇒
    new Transformer[Any, Any] {
      override def onNext(element: Any) = List(element)
    })

  override def materializeProcessor[In, Out](op: Ast.AstNode): Processor[In, Out] = {
    // FIXME the naming of of FlowGraph processors might need to be revisited to make sense
    val flowName = createFlowName()
    processorForNode(op, flowName, 0).asInstanceOf[Processor[In, Out]]
  }

  private def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] = {
    val impl = actorOf(ActorProcessorFactory.props(settings, op), s"$flowName-$n-${op.name}")
    ActorProcessorFactory(impl)
  }

  def actorOf(props: Props, name: String): ActorRef = supervisor match {
    case ref: LocalActorRef ⇒
      ref.underlying.attachChild(props, name, systemService = false)
    case ref: RepointableActorRef ⇒
      if (ref.isStarted)
        ref.underlying.asInstanceOf[ActorCell].attachChild(props, name, systemService = false)
      else {
        implicit val timeout = ref.system.settings.CreationTimeout
        val f = (supervisor ? StreamSupervisor.Materialize(props, name)).mapTo[ActorRef]
        Await.result(f, timeout.duration)
      }
    case _ ⇒
      throw new IllegalStateException(s"Stream supervisor must be a local actor, was [${supervisor.getClass.getName}]")
  }

}

/**
 * INTERNAL API
 */
private[akka] object FlowNameCounter extends ExtensionId[FlowNameCounter] with ExtensionIdProvider {
  override def get(system: ActorSystem): FlowNameCounter = super.get(system)
  override def lookup = FlowNameCounter
  override def createExtension(system: ExtendedActorSystem): FlowNameCounter = new FlowNameCounter
}

/**
 * INTERNAL API
 */
private[akka] class FlowNameCounter extends Extension {
  val counter = new AtomicLong(0)
}

/**
 * INTERNAL API
 */
private[akka] object StreamSupervisor {
  def props(settings: MaterializerSettings): Props = Props(new StreamSupervisor(settings))

  case class Materialize(props: Props, name: String)
}

private[akka] class StreamSupervisor(settings: MaterializerSettings) extends Actor {
  import StreamSupervisor._

  def receive = {
    case Materialize(props, name) ⇒
      val impl = context.actorOf(props, name)
      sender() ! impl
  }
}

/**
 * INTERNAL API
 */
private[akka] object ActorProcessorFactory {

  import Ast._
  def props(settings: MaterializerSettings, op: AstNode): Props =
    (op match {
      case t: Transform ⇒ Props(new TransformProcessorImpl(settings, t.mkTransformer()))
      //      case m: MergeNode ⇒ Props(new MergeImpl(settings, m.other))

    }).withDispatcher(settings.dispatcher)

  def apply[I, O](impl: ActorRef): ActorProcessor[I, O] = {
    val p = new ActorProcessor[I, O](impl)
    impl ! ExposedPublisher(p.asInstanceOf[ActorPublisher[Any]])
    p
  }
}
