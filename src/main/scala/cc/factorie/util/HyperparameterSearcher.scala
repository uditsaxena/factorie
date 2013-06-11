package cc.factorie.util

import scala.util.{Try, Random}
import scala.concurrent._
import akka.actor._
import cc.factorie.Proportions

/**
 * User: apassos
 * Date: 6/9/13
 * Time: 7:20 AM
 */

trait ParameterSampler[T] {
  def sample(rng: scala.util.Random): T
  def buckets: Array[(T,Double,Double,Int)]
  def valueToBucket(v: T): Int
  def accumulate(value: T, d: Double) {
    val v = math.max(0, math.min(valueToBucket(value), buckets.length-1))
    val (_, sum, sumSq, count) = buckets(v)
    buckets(v) = (value, sum+d, sumSq+d*d, count+1)
  }
}

// Samples uniformly one of the values in the sequence.
class SampleFromSeq[T](seq: Seq[T]) extends ParameterSampler[T] {
  val buckets = seq.map(s => (s,0.0,0.0,0)).toArray
  def valueToBucket(v: T) = buckets.indexOf(v)
  def sample(rng: Random) = seq(rng.nextInt(seq.length))
}

// Samples non-uniformly one of the values in the sequence.
class SampleFromProportions[T](seq: Seq[T], prop: Proportions) extends ParameterSampler[T] {
  val buckets = seq.map(s => (s,0.0,0.0,0)).toArray
  def valueToBucket(v: T) = buckets.indexOf(v)
  def sample(rng: Random) = seq(prop.sampleIndex(rng))
}

// Samples uniformly a Double in the range
class UniformDoubleSampler(lower: Double, upper: Double, numBuckets: Int = 10) extends ParameterSampler[Double] {
  val dif = upper - lower
  val buckets = (0 to numBuckets).map(i => (0.0, 0.0, 0.0, 0)).toArray
  def valueToBucket(d: Double) = (numBuckets*(d - lower)/dif).toInt
  def sample(rng: Random) = rng.nextDouble()*dif + lower
}

// Samples Doubles in the range such that their logarithm is uniform.
// Useful for learning rates, variances, alphas, and other things which
// vary in order of magnitude.
class LogUniformDoubleSampler(lower: Double, upper: Double, numBuckets: Int = 10) extends ParameterSampler[Double] {
  val inner = new UniformDoubleSampler(math.log(lower), math.log(upper), numBuckets)
  def valueToBucket(v: Double) = inner.valueToBucket(math.log(v))
  val buckets = (0 to numBuckets).map(i => (0.0, 0.0, 0.0, 0)).toArray
  def sample(rng: Random) = math.exp(inner.sample(rng))
}

/**
 * A container for a hyperparameter which will be optimized.
 * @param option The CmdOption for the parameter
 * @param sampler A sampler which can return values for the parameter
 */
case class HyperParameter[T](option: CmdOption[T], sampler: ParameterSampler[T]) {
  def set(rng: Random) { option.setValue(sampler.sample(rng)) }
  def accumulate(objective: Double) { sampler.accumulate(option.value, objective) }
  def report() {
    println("Parameter " + option.name + " mean   stddev  count")
    for ((value, sum, sumSq, count) <- sampler.buckets) {
      val mean = sum/count
      val stdDev = math.sqrt(sumSq/count - mean*mean)
      value match {
        case v: Double => println(f"${v.toDouble}%2.15f  $mean%2.4f  $stdDev%2.4f  ($count)")
        case _ => println(f"${value.toString}%20s $mean%2.2f $stdDev%2.2f  ($count)")
      }

    }
    println()
  }
}

/**
 *
 * @param cmds The options to be passed to the slaves
 * @param parameters The hyperparameters we want to tune, assumed to be a subset of cmds
 * @param executor A function which will train the model and return its quality
 * @param numTrials How many executors should start
 * @param numToFinish How many executors we wait for (to account for stragglers)
 * @param secondsToSleep How many seconds do we sleep for between polling the executors.
 */
class HyperParameterSearcher(cmds: CmdOptions,
                             parameters: Seq[HyperParameter[_]],
                             executor: Array[String] => Future[Double],
                             numTrials: Int,
                             numToFinish: Int,
                             secondsToSleep: Int = 60) {
  private def sampledParameters(rng: Random): Array[String] = {
    parameters.foreach(_.set(rng))
    cmds.values.map(_.unParse).toArray
  }

  // the contract is that optimize will also set the appropriate values in cmds
  def optimize(rng: Random = new Random(0)): Array[String] = {
    val settings = (0 until numTrials).map(i => sampledParameters(rng))
    println("Starting hyperparameter optimization")
    val futures = settings.map(s => (s,executor(s)))
    var finished = false
    while (!finished) {
      Thread.sleep(secondsToSleep*1000)
      val values = futures.filter(_._2.isCompleted).map(_._2.value.get.getOrElse(Double.NegativeInfinity))
      val finiteValues = values.filterNot(_.isInfinite)
      finished = values.length >= numToFinish
      if (values.length > 0)
        println(s"Finished jobs: ${values.length} failed jobs: ${values.count(_.isInfinite)}  best value: ${values.max} mean value: ${finiteValues.sum/finiteValues.length}")
      else
        println(s"Finished jobs: ${values.length} failed jobs: ${values.count(_.isInfinite)}")
    }
    for ((setting,future) <- futures; if future.isCompleted; if future.value.get.isSuccess) {
      cmds.parse(setting)
      for (p <- parameters) p.accumulate(future.value.get.get)
    }
    for (p <- parameters) p.report()
    val bestConfig = futures.filter(_._2.isCompleted).maxBy(_._2.value.get.get)._1
    cmds.parse(bestConfig)
    parameters.map(_.option.unParse).toArray
  }
}

// I created this because the return type of a reflection invocation is Object
// and I don't want to assume things about how the scala doubles are boxed.
case class BoxedDouble(d: Double)

/**
 * Main class which implements training of a model.
 * Can't assume anything about the environment.
 * Users should implement evaluateParameters.
 */
trait HyperparameterMain {
  def evaluateParameters(args: Array[String]): Double
  final def main(args: Array[String]) { evaluateParameters(args) }
  final def actualMain(args: Array[String]) = BoxedDouble(evaluateParameters(args))
}

/**
 * Base class for executors which run their own JVMs.
 */
trait Executor {
  def serializeArgs(args: Array[String]) = args.map(s => s.substring(2,s.length)).mkString("::")
  val classpath =
    ClassLoader.getSystemClassLoader.asInstanceOf[java.net.URLClassLoader].getURLs.map(_.getFile).mkString(":")
  def execute(args: Array[String]): Future[Double]
}

/**
 * A general executor for job queues.
 * @param memory How many gigabytes of RAM to use.
 * @param className The class which will be run.
 * @param prefix A path on which to store the files.
 */
abstract class JobQueueExecutor(memory: Int, className: String, prefix: String) extends Executor {
  /**
   * Runs a job in the queue
   * @param script the file name of the shell script to be run
   * @param logFile the file on which to write the output
   * @return an ID for the job
   */
  def runJob(script: String, logFile: String)

  var id = 0
  def execute(args: Array[String]) = {
    id += 1
    val thisId = id
    val as = serializeArgs(args)
    import scala.concurrent.ExecutionContext.Implicits.global
    future {
      import sys.process._
      val thisPrefix = s"$prefix-job-$thisId"
      val outFile = thisPrefix+"-out"
      new java.io.File(thisPrefix).getParentFile.mkdirs()
      val jvmCommand = s"java -Xmx${memory}g -classpath '$classpath' cc.factorie.util.QSubExecutor --className=$className  '--classArgs=$as' --outFile=$outFile"

      val cmdFile = thisPrefix+"-cmd.sh"
      (("echo " + jvmCommand) #> new java.io.File(cmdFile)).!
      blocking { try { runJob(cmdFile, thisPrefix+"-log.txt") } catch { case c: RuntimeException => () } }
      var done = false
      var tries = 0
      while (!done && tries < 10) {
        tries += 1
        if (new java.io.File(outFile).exists() && io.Source.fromFile(outFile).getLines().toSeq.size > 0) done = true
        else
        blocking { Thread.sleep(1000) }
      }
      if (new java.io.File(outFile).exists && io.Source.fromFile(outFile).getLines().toSeq.size > 0)
        io.Source.fromFile(outFile).getLines().toSeq.head.toDouble
      else {
        println("Job " + thisId + " failed. See log file " + thisPrefix+"-log.txt for more information.")
        Double.NegativeInfinity
      }
    }
  }
}

/**
 * An executor that uses qsub as it is set up in UMass.
 * @param memory How many gigabytes of RAM to use.
 * @param className The class which will be run.
 * @param prefix A path on which to store the files.
 */
class QSubExecutor(memory: Int, className: String, prefix: String) extends JobQueueExecutor(memory, className, prefix) {
  import sys.process._
  def runJob(script: String, logFile: String) { s"qsub -sync y -l mem_token=${memory}G -cwd -j y -o $logFile -S /bin/sh $script".!! }
}

/**
 * I wish I could make this object private. It is the main function
 * actually running in the slaves started by QSubActorExecutor.
 */
object QSubExecutor {
  object opts extends CmdOptions {
    val className = new CmdOption("className", "", "STRING", "Class to run")
    val classArgs = new CmdOption("classArgs", "", "STRING", "Arguments to pass it")
    val outFile = new CmdOption("outFile", "", "STRING", "File on which to write the final double")
  }
  def main(args: Array[String]) {
    opts.parse(args)
    val cls = Class.forName(opts.className.value)
    val mainMethod = cls.getMethods.filter(_.getName == "actualMain").head
    val argsArray = opts.classArgs.value.split("::").map("--" + _).toArray
    println("Using args \n" + argsArray.mkString("\n"))
    val result = mainMethod.invoke(null, argsArray).asInstanceOf[BoxedDouble].d
    println("---- END OF JOB -----")
    println("Result was: " + result)
    import sys.process._
    (("echo " + result.toString) #> new java.io.File(opts.outFile.value)).!
    println(s"Done, file ${opts.outFile.value} written")
  }
}

/**
 * An Executor which runs jobs from a pool of machines via ssh. It assumes
 * that private keys are properly set up.
 *
 * Each machine will cd into the specified directory and start a JVM which
 * will run the function evaluateParameters in the class whose name is passed.
 *
 * @param user The username
 * @param machines The list of machines on which to ssh
 * @param directory The directory to "cd" in each machine
 * @param logPrefix The prefix of the place on which to store logs
 * @param className The class whose main function the slaves will run. Needs to be
 *                  an instance of HyperparameterMain
 * @param memory How much memory to give each slave JVM, in gigabytes
 * @param timeoutMinutes The timeout for the SSH commands
 */
class SSHActorExecutor(user: String,
                       machines: Seq[String],
                       directory: String,
                       logPrefix: String,
                       className: String,
                       memory: Int,
                       timeoutMinutes: Int) extends Executor {
  import com.typesafe.config.ConfigFactory
  val customConf = ConfigFactory.parseString("my-balanced-dispatcher { type = BalancingDispatcher }")
  val system = ActorSystem("ssh", customConf)
  val actors = (0 until machines.length).map(i =>
    system.actorOf(Props(new SSHActor(machines(i), i)).withDispatcher("my-balanced-dispatcher"), "actor-"+i))
  case class ExecuteJob(args: String, jobId: Int)
  import akka.pattern.ask
  var job = 0
  def execute(args: Array[String]): Future[Double] = {
    job += 1
    import scala.concurrent.duration._
    actors.head.ask(ExecuteJob(serializeArgs(args), job))(timeoutMinutes.minutes) mapTo manifest[Double] fallbackTo Future.successful(Double.NegativeInfinity)
  }
  def shutdown() { system.shutdown() }

  class SSHActor(machine: String, i: Int) extends Actor {
    def receive = {
      case ExecuteJob(args, j) =>
        val logFile = logPrefix+"-job-"+j+".log"
        new java.io.File(logFile).getParentFile.mkdirs()
        val jvmCommand = s"java -Xmx${memory}g -classpath '$classpath' cc.factorie.util.SSHExecutor --className=$className  '--classArgs=$args'"
        val inSSh = s"cd $directory; $jvmCommand"
        val userMachine = user + "@" + machine
        val sshCommand = s"ssh $userMachine  $inSSh 2> $logFile.stderr"
        import sys.process._
        (sshCommand #> new java.io.File(logFile)).!
        val double = s"tail -n 1 $logFile".!!
        sender ! double.toDouble
    }
  }
}

/**
 * Slave job created by the SSHActorExecutor
 */
object SSHExecutor {
  object opts extends CmdOptions {
    val className = new CmdOption("className", "", "STRING", "Class to run")
    val classArgs = new CmdOption("classArgs", "", "STRING", "Arguments to pass it")
  }
  def main(args: Array[String]) {
    opts.parse(args)
    val cls = Class.forName(opts.className.value)
    val mainMethod = cls.getMethods.filter(_.getName == "actualMain").head
    val argsArray = opts.classArgs.value.split("::").map("--" + _).toArray
    println("Using args \n" + argsArray.mkString("\n"))
    val result = mainMethod.invoke(null, argsArray).asInstanceOf[BoxedDouble].d
    println("----- END OF JOB -----")
    println(result)
  }
}