package it.unibo.alchemist.model.implementations.reactions
import it.unibo.ProgramFactory
import it.unibo.alchemist.model.implementations.actions.{
  DistributedFrpIncarnation,
  SendToNeighbor,
  ThrottleNeighborhoodMessages
}
import it.unibo.alchemist.model.implementations.PimpAlchemist.*
import it.unibo.alchemist.model.implementations.timedistributions.{DiracComb, ExponentialTime, Trigger}
import it.unibo.alchemist.model.implementations.times.DoubleTime
import it.unibo.alchemist.model.interfaces.{Context, Environment, Position, Time, TimeDistribution}

import _root_.scala.jdk.CollectionConverters.IterableHasAsScala
import it.unibo.alchemist.model.implementations.molecules.SimpleMolecule
import it.unibo.distributed.frp.Molecules
import org.apache.commons.math3.random.RandomGenerator
import it.unibo.adapter.DistributionPrototype
import it.unibo.alchemist.model.implementations.actions
import it.unibo.alchemist.model.implementations.actions.DistributedFrpIncarnation.{Export, FrpContext}

import java.util.List as JList
class InitFrpGlobalReaction[P <: Position[P]](
    val environment: Environment[Any, P],
    val randomGenerator: RandomGenerator,
    val distribution: TimeDistribution[Any],
    val programFactory: String,
    val prototype: DistributionPrototype,
    val mode: String = "throttle"
) extends AbstractGlobalReaction[P]:

  def this(
      environment: Environment[Any, P],
      randomGenerator: RandomGenerator,
      distribution: TimeDistribution[Any],
      programFactory: String,
      prototype: DistributionPrototype
  ) = this(environment, randomGenerator, distribution, programFactory, prototype, "throttle")
  private var fired = false
  private val factory =
    Class.forName(programFactory).getDeclaredConstructor().newInstance().asInstanceOf[ProgramFactory]
  private lazy val globalIncarnation = new DistributedFrpIncarnation[P](environment, randomGenerator)

  override def initializationComplete(time: Time, environment: Environment[Any, _]): Unit =
    getTimeDistribution.update(Time.INFINITY, true, 0.0, environment)
  override def execute(): Unit =
    val program = factory.create(globalIncarnation)
    val contexts = environment.getNodes.asScala.toList.map(node => globalIncarnation.context(node.getId))
    for context <- contexts do
      program
        .run(Seq.empty)(using context)
        .listen { v =>
          val nextTime = if (fired) environment.getSimulation.getTime else prototype.randomInit(randomGenerator)
          mode.on("throttle").perform(throttling(context, nextTime))
          mode.on("reactive").perform(reactive(context, nextTime, v))
          context.storeTicks()
          context.node.setConcentration(Molecules.LastComputationTime, environment.getSimulation.getTime.toDouble)
          context.node.setConcentration(Molecules.Root, v.root)
          context.node.setConcentration(Molecules.Export, v)
        }
    fired = true
    distribution.update(Time.INFINITY, true, getRate, environment) // as it removes the current reaction

  private def throttling(context: FrpContext, nextTime: Time): Unit =
    val neighbor = environment.getNeighborhood(context.node).getNeighbors.size()
    if (!fired) {
      val fire = prototype.adapt[Any](nextTime, randomGenerator)
      fire.update(nextTime, true, fire.getRate, environment)
      val event = new Event(context.node, fire)
      event.setActions(JList.of(ThrottleNeighborhoodMessages(context.node, environment)))
      environment.getSimulation.reactionAdded(event)
    } else {
      context.node.updateConcentration[Double](Molecules.MessagesSent, _ + neighbor)
    }

  private def reactive(context: FrpContext, nextTime: Time, exportMessage: Export[Any]): Unit =
    /*if (context.node.getConcentration(Molecules.Export) != exportMessage) { // avoid to send the same message twice
      val neighborhood = context.node :: environment.getNeighborhood(context.node).asScala.toList
      neighborhood.foreach { to =>
        val fire = prototype.adapt[Any](nextTime, randomGenerator)
        fire.update(nextTime, true, fire.getRate, environment)
        val trigger = Trigger[Any](fire.getNextOccurence)
        trigger.update(fire.getNextOccurence, true, 0.0, environment)
        val event = new Event(to, trigger)
        event.setActions(JList.of(actions.SendToNeighbor(to, environment, (context.node, exportMessage))))
        to.addReaction(event)
        environment.getSimulation.reactionAdded(event)
      }
    }*/
    val neighbor = environment.getNeighborhood(context.node).getNeighbors.size()
    val fire = prototype.adapt[Any](nextTime, randomGenerator)
    fire.update(nextTime, true, fire.getRate, environment)
    val trigger = Trigger[Any](fire.getNextOccurence)
    trigger.update(fire.getNextOccurence, true, 0.0, environment)
    val event = new Event(context.node, trigger)
    event.setActions(JList.of(actions.BroadcastToNeighborhood(context.node, environment, exportMessage)))
    // remove the message before sending, otherwise it will create to many reactions
    context.node.getReactions.asScala.toList.foreach { reaction =>
      context.node.removeReaction(reaction)
      environment.getSimulation.reactionRemoved(reaction)
    }
    context.node.updateConcentration[Double](Molecules.MessagesSent, _ + neighbor)
    context.node.addReaction(event)
    environment.getSimulation.reactionAdded(event)

  extension (s: String) def on(value: String) = ModeContinuation(value == s)

  protected class ModeContinuation(condition: Boolean) {
    def perform(logic: => Unit) = if (condition) logic
  }
