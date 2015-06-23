/*
 * Copyright (c) 2014 Azavea.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.vector

import spire.syntax.cfor._
import scala.collection.mutable
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.analysis.{DifferentiableMultivariateVectorFunction, MultivariateMatrixFunction}
import org.apache.commons.math3.optimization.general.LevenbergMarquardtOptimizer
import org.apache.commons.math3.optimization.PointVectorValuePair

abstract sealed class ModelType

case object Linear extends ModelType
case object Gaussian extends ModelType
case object Circular extends ModelType
case object Spherical extends ModelType
case object Exponential extends ModelType
case object Wave extends ModelType

/**
  Empirical semivariogram
*/
object Semivariogram {
  case class Bucket(start:Double,end:Double) {
    private val points = mutable.Set[(PointFeature[Int],PointFeature[Int])]()

    def add(x:PointFeature[Int],y:PointFeature[Int]) = points += ((x,y))

    def contains(x:Double) =
      if(start==end) x == start
      else (start <= x) && (x < end)

    def midpoint = (start + end) / 2.0
    def isEmpty = points.isEmpty
    def semivariance = {
      val sumOfSquares = 
        points.foldLeft(0.0){ case(acc,(x,y)) =>
          acc + math.pow((x.data-y.data),2)
        }
      (sumOfSquares / points.size) / 2
    }
  }

  /** Produces unique pairs of points */
  def makePairs[T](elements:List[T]):List[(T,T)] = {
    def f(elements:List[T],acc:List[(T,T)]):List[(T,T)] =
      elements match {
        case head :: List() =>
          acc
        case head :: tail =>
          f(tail,tail.map((head,_)) ++ acc)
        case _ => acc
      }
    f(elements,List[(T,T)]())
  }

  def explicitGaussian(r: Double, s: Double, a: Double): Double => Double = {
    h: Double => {
      if (h == 0) 0
      else
        a + (s - a) * (1 - math.exp(- math.pow(h, 2) / math.pow(r, 2)))
    }
    /*                    | 0                             . h = 0
     *  gamma(h; r,s,a) = |
     *                    | a + (s-a) {1 - e^(-h^2/r^2)}  , h > 0
     */
  }

  def explicitCircular(r: Double, s: Double, a: Double): Double => Double = {
    h: Double => {
      if (h == 0) 0
      else if (h > r)
        s
      else
        a + (s - a) * (1 - (2 / math.Pi) * math.acos(h/r) + math.sqrt(1 - math.pow(h, 2) / math.pow(r, 2)))
    }
    /*                    | 0                                                           , h = 0
     *                    |
     *                    |             |                                   _________ |
     *                    |             |      2                | h |      /    h^2   |
     *  gamme(h; r,s,a) = | a + (s-a) * |1 - ----- * cos_inverse|---| +   /1 - -----  | , 0 < h <= r
     *                    |             |      pi               | r |   \/      r^2   |
     *                    |             |                                             |
     *                    |
     *                    | s                                                           , h > r
     */
  }

  def explicitSpherical(r: Double, s: Double, a: Double): Double => Double = {
    h: Double => {
      if (h == 0) 0
      else if (h > r) {
        a + (s - a) * ((3 * h / (2 * r)) - (math.pow(h, 3) / (2 * math.pow(r, 3)) ))
      }
      else  s
    }
    /*                    | 0                           . h = 0
     *                    |           | 3h      h^3   |
     *  gamma(h; r,s,a) = | a + (s-a) |---- - ------- | , 0 < h <= r
     *                    |           | 2r     2r^3   |
     *                    | s                           , h > r
     */
  }

  def explicitExponential(r: Double, s: Double, a: Double): Double => Double = {
    h: Double => {
      if (h == 0) 0
      else
        a + (s - a) * (1 - math.exp(- 3 * h / r))
    }
    /*                    | 0                           . h = 0
     *  gamma(h; r,s,a) = |
     *                    | a + (s-a) {1 - e^(-3h/r)}   , h > 0
     */
  }

  def explicitWave(w: Double, s: Double, a: Double): Double => Double = {
    h: Double => {
      if (h == 0) 0
      else
        a + (s - a) * (1 - w * math.sin(h / w) / h)
    }
    /*                    | 0                             . h = 0
     *                    |
     *  gamma(h; w,s,a) = |           |       sin(h/w)  |
     *                    | a + (s-a) |1 - w ---------- | , h > 0
     *                    |           |         h       |
     */
  }

  def apply(pts:Seq[PointFeature[Int]],radius:Option[Int]=None,lag:Int=0,model:ModelType):Function1[Double,Double] = {

    def distance(p1: Point, p2: Point) = math.abs(math.sqrt(math.pow(p1.x - p2.x,2) + math.pow(p1.y - p2.y,2)))

    // every pair of points and their distance from each other
    val distancePairs:Seq[(Double,(PointFeature[Int],PointFeature[Int]))] =
      radius match {
        case Some(dmax) =>
          makePairs(pts.toList)
            .map{ case(a,b) => (distance(a.geom, b.geom), (a,b)) }
            .filter { case (distance,_) => distance <= dmax }
            .toSeq
        case None =>
            makePairs(pts.toList)
              .map{ case(a,b) => (distance(a.geom, b.geom), (a,b)) }
              .toSeq
      }

    val buckets:Seq[Bucket] =
      if(lag == 0) {
        distancePairs
          .map{ case(d,_) => d }
          .distinct
          .map { d => Bucket(d,d) }
      } else {
        // the maximum distance between two points in the field
        val dmax = distancePairs.map{ case(d,_) => d }.max
        // the lower limit of the largest bucket
        val lowerLimit = (Math.floor(dmax/lag).toInt * lag) + 1
        List.range(0,lowerLimit,lag).zip(List.range(lag,lowerLimit+lag,lag))
          .map{ case(start,end) => Bucket(start,end) }
      }

    // populate the buckets
    for( (d,(x,y)) <- distancePairs ) {
      buckets.find(b => b.contains(d)) match {
        case Some(b) => b.add(x,y)
        case None => sys.error(s"Points $x and $y don't fit any bucket")
      }
    }

    // use midpoint of buckets for distance
    val empiricalSemivariogram:Seq[(Double,Double)] =
      // empty buckets are first removed
      buckets.filter ( b => !b.isEmpty)
        .map { b => (b.midpoint,b.semivariance) }

    model match {
      case Linear =>
        println("Hello")
        // Construct slope and intercept
        val regression = new SimpleRegression
        for((x,y) <- empiricalSemivariogram) { regression.addData(x,y) }
        val slope = regression.getSlope
        val intercept = regression.getIntercept
        x => slope*x + intercept

      //Least Squares minimization
      case Gaussian =>
        class GaussianProblem extends DifferentiableMultivariateVectorFunction with Serializable {
          var x: Array[Double] = Array()
          var y: Array[Double] = Array()

          def addPoint(x: Double, y: Double) = {
            this.x = this.x :+ x
            this.y = this.y :+ y
          }

          def calculateTarget(): Array[Double] = {
            val target: Array[Double] = Array.ofDim[Double](y.length)
            cfor(0)(_ < y.length, _ + 1) { i =>
              target(i) = y(i)
            }
            target
          }

          def jacobianConstruct(variables: Array[Double]): Array[Array[Double]] = {
            val jacobianRet: Array[Array[Double]] = Array.ofDim[Double](x.length, 3)
            cfor(0)(_ < jacobianRet.length, _ + 1) { i =>
              if (x(i)==0) {
                jacobianRet(i)(0) = 0
                jacobianRet(i)(1) = 0
                jacobianRet(i)(2) = 0
              }
              else {
                jacobianRet(i)(0) = (variables(2) - variables(1)) * (2 * math.pow(x(i), 2) / math.pow(variables(0), 3)) * math.exp(-1 * math.pow(x(i), 2) / math.pow(variables(0), 2))
                jacobianRet(i)(1) = 1 - math.exp(-1 * math.pow(x(i), 2) / math.pow(variables(0), 2))
                jacobianRet(i)(2) = 1 - jacobianRet(i)(1)
              }
            }
            jacobianRet
          }

          def value(variables: Array[Double]): Array[Double] = {
            val values: Array[Double] = Array.ofDim[Double](x.length)
            cfor(0)(_ < values.length, _ + 1) { i =>
              values(i) = explicitGaussian(variables(0), variables(1), variables(2))(x(i))
            }
            values
          }

          def jacobian: MultivariateMatrixFunction = {
            new MultivariateMatrixFunction {
              override def value(doubles: Array[Double]): Array[Array[Double]] = jacobianConstruct(doubles)
            }
          }
        }
        val problem: GaussianProblem = new GaussianProblem
        for((x,y) <- empiricalSemivariogram) { problem.addPoint(x,y) }
        val optimizer: LevenbergMarquardtOptimizer = new LevenbergMarquardtOptimizer()
        val weights: Array[Double] = Array.fill[Double](empiricalSemivariogram.length)(1)
        val initialSolution: Array[Double] = Array[Double](1, 1, 1)
        val optimum: PointVectorValuePair = optimizer.optimize(100, problem, problem.calculateTarget(), weights, initialSolution)
        val optimalValues: Array[Double] = optimum.getPoint
        println("r: " + optimalValues(0))
        println("s: " + optimalValues(1))
        println("a: " + optimalValues(2))
        explicitGaussian(optimalValues(0), optimalValues(1), optimalValues(2))

      case Exponential =>
        class ExponentialProblem extends DifferentiableMultivariateVectorFunction with Serializable {
          var x: Array[Double] = Array()
          var y: Array[Double] = Array()

          def addPoint(x: Double, y: Double) = {
            this.x = this.x :+ x
            this.y = this.y :+ y
          }

          def calculateTarget(): Array[Double] = {
            val target: Array[Double] = Array.ofDim[Double](y.length)
            cfor(0)(_ < y.length, _ + 1) { i =>
              target(i) = y(i)
            }
            target
          }

          def jacobianConstruct(variables: Array[Double]): Array[Array[Double]] = {
            val jacobianRet: Array[Array[Double]] = Array.ofDim[Double](x.length, 3)
            cfor(0)(_ < jacobianRet.length, _ + 1) { i =>
              if (x(i)==0) {
                jacobianRet(i)(0) = 0
                jacobianRet(i)(1) = 0
                jacobianRet(i)(2) = 0
              }
              else {
                jacobianRet(i)(0) = (variables(2) - variables(1)) * (3 * x(i) / math.pow(variables(0), 2)) * math.exp(-3 * x(i) / variables(0))
                jacobianRet(i)(1) = 1 - math.exp(-3 * x(i) / variables(0))
                jacobianRet(i)(2) = 1 - jacobianRet(i)(1)
              }
            }
            jacobianRet
          }

          def value(variables: Array[Double]): Array[Double] = {
            val values: Array[Double] = Array.ofDim[Double](x.length)
            cfor(0)(_ < values.length, _ + 1) { i =>
              values(i) = explicitExponential(variables(0), variables(1), variables(2))(x(i))
            }
            values
          }

          def jacobian: MultivariateMatrixFunction = {
            new MultivariateMatrixFunction {
              override def value(doubles: Array[Double]): Array[Array[Double]] = jacobianConstruct(doubles)
            }
          }
        }
        val problem: ExponentialProblem = new ExponentialProblem
        for((x,y) <- empiricalSemivariogram) { problem.addPoint(x,y) }
        val optimizer: LevenbergMarquardtOptimizer = new LevenbergMarquardtOptimizer()
        val weights: Array[Double] = Array.fill[Double](empiricalSemivariogram.length)(1)
        val initialSolution: Array[Double] = Array[Double](1, 1, 1)
        val optimum: PointVectorValuePair = optimizer.optimize(100, problem, problem.calculateTarget(), weights, initialSolution)
        val optimalValues: Array[Double] = optimum.getPoint
        println("r: " + optimalValues(0))
        println("s: " + optimalValues(1))
        println("a: " + optimalValues(2))
        explicitExponential(optimalValues(0), optimalValues(1), optimalValues(2))

      case Circular =>
        class CircularProblem extends DifferentiableMultivariateVectorFunction with Serializable {
          var x: Array[Double] = Array()
          var y: Array[Double] = Array()

          def addPoint(x: Double, y: Double) = {
            this.x = this.x :+ x
            this.y = this.y :+ y
          }

          def calculateTarget(): Array[Double] = {
            val target: Array[Double] = Array.ofDim[Double](y.length)
            cfor(0)(_ < y.length, _ + 1) { i =>
              target(i) = y(i)
            }
            target
          }

          def jacobianConstruct(variables: Array[Double]): Array[Array[Double]] = {
            val jacobianRet: Array[Array[Double]] = Array.ofDim[Double](x.length, 3)
            cfor(0)(_ < jacobianRet.length, _ + 1) { i =>
              if (x(i)==0) {
                jacobianRet(i)(0) = 0
                jacobianRet(i)(1) = 0
                jacobianRet(i)(2) = 0
              }
              else {
                jacobianRet(i)(0) = (-2 * variables(1) * x(i)) / (math.Pi * math.pow(variables(0), 2)) + (variables(1) * math.pow(x(i), 2)) / (math.pow(variables(0), 2) * math.sqrt(1 - math.pow(x(i) / variables(0), 2)))
                jacobianRet(i)(1) = 1 - (2 / math.Pi) * math.acos(x(i) / variables(0)) + math.sqrt(1 - math.pow(x(i) / variables(0), 2))
                jacobianRet(i)(2) = 1 - jacobianRet(i)(1)
              }
            }
            jacobianRet
          }

          def value(variables: Array[Double]): Array[Double] = {
            val values: Array[Double] = Array.ofDim[Double](x.length)
            cfor(0)(_ < values.length, _ + 1) { i =>
              values(i) = explicitCircular(variables(0), variables(1), variables(2))(x(i))
            }
            values
          }

          def jacobian: MultivariateMatrixFunction = {
            new MultivariateMatrixFunction {
              override def value(doubles: Array[Double]): Array[Array[Double]] = jacobianConstruct(doubles)
            }
          }
        }
        val problem: CircularProblem = new CircularProblem
        for((x,y) <- empiricalSemivariogram) { problem.addPoint(x,y) }
        val optimizer: LevenbergMarquardtOptimizer = new LevenbergMarquardtOptimizer()
        val weights: Array[Double] = Array.fill[Double](empiricalSemivariogram.length)(1)
        val initialSolution: Array[Double] = Array[Double](1, 1, 1)
        val optimum: PointVectorValuePair = optimizer.optimize(100, problem, problem.calculateTarget(), weights, initialSolution)
        val optimalValues: Array[Double] = optimum.getPoint
        println("r: " + optimalValues(0))
        println("s: " + optimalValues(1))
        println("a: " + optimalValues(2))
        explicitCircular(optimalValues(0), optimalValues(1), optimalValues(2))

      case Spherical =>
        class SphericalProblem extends DifferentiableMultivariateVectorFunction with Serializable {
          var x: Array[Double] = Array()
          var y: Array[Double] = Array()

          def addPoint(x: Double, y: Double) = {
            this.x = this.x :+ x
            this.y = this.y :+ y
          }

          def calculateTarget(): Array[Double] = {
            val target: Array[Double] = Array.ofDim[Double](y.length)
            cfor(0)(_ < y.length, _ + 1) { i =>
              target(i) = y(i)
            }
            target
          }

          def jacobianConstruct(variables: Array[Double]): Array[Array[Double]] = {
            val jacobianRet: Array[Array[Double]] = Array.ofDim[Double](x.length, 3)
            cfor(0)(_ < jacobianRet.length, _ + 1) { i =>
              if (x(i)==0) {
                jacobianRet(i)(0) = 0
                jacobianRet(i)(1) = 0
                jacobianRet(i)(2) = 0
              }
              else if (x(i)>0 && x(i)<=variables(0)) {
                jacobianRet(i)(0) = (variables(1) - variables(2)) * ((-3*x(i))/(2*math.pow(variables(0),2)) + (3 * math.pow(x(i),3)/(2 * math.pow(variables(0),4))))
                jacobianRet(i)(1) = ((3 * x(i))/(2 * variables(0))) - (math.pow(x(i),3)/(2 * math.pow(variables(0),3)))
                jacobianRet(i)(2) = 1 - jacobianRet(i)(1)
              }
              else {
                jacobianRet(i)(0) = 0
                jacobianRet(i)(1) = 1
                jacobianRet(i)(2) = 0
              }
            }
            jacobianRet
          }

          def value(variables: Array[Double]): Array[Double] = {
            val values: Array[Double] = Array.ofDim[Double](x.length)
            cfor(0)(_ < values.length, _ + 1) { i =>
              values(i) = explicitSpherical(variables(0), variables(1), variables(2))(x(i))
            }
            values
          }

          def jacobian: MultivariateMatrixFunction = {
            new MultivariateMatrixFunction {
              override def value(doubles: Array[Double]): Array[Array[Double]] = jacobianConstruct(doubles)
            }
          }
        }
        val problem: SphericalProblem = new SphericalProblem
        for((x,y) <- empiricalSemivariogram) { problem.addPoint(x,y) }
        val optimizer: LevenbergMarquardtOptimizer = new LevenbergMarquardtOptimizer()
        val weights: Array[Double] = Array.fill[Double](empiricalSemivariogram.length)(1)
        val initialSolution: Array[Double] = Array[Double](1, 1, 1)
        val optimum: PointVectorValuePair = optimizer.optimize(100, problem, problem.calculateTarget(), weights, initialSolution)
        val optimalValues: Array[Double] = optimum.getPoint
        println("r: " + optimalValues(0))
        println("s: " + optimalValues(1))
        println("a: " + optimalValues(2))
        explicitSpherical(optimalValues(0), optimalValues(1), optimalValues(2))

      case Wave =>
        class WaveProblem extends DifferentiableMultivariateVectorFunction with Serializable {
          var x: Array[Double] = Array()
          var y: Array[Double] = Array()

          def addPoint(x: Double, y: Double) = {
            this.x = this.x :+ x
            this.y = this.y :+ y
          }

          def calculateTarget(): Array[Double] = {
            val target: Array[Double] = Array.ofDim[Double](y.length)
            cfor(0)(_ < y.length, _ + 1) { i =>
              target(i) = y(i)
            }
            target
          }

          def jacobianConstruct(variables: Array[Double]): Array[Array[Double]] = {
            val jacobianRet: Array[Array[Double]] = Array.ofDim[Double](x.length, 3)
            cfor(0)(_ < jacobianRet.length, _ + 1) { i =>
              if (x(i)==0) {
                jacobianRet(i)(0) = 0
                jacobianRet(i)(1) = 0
                jacobianRet(i)(2) = 0
              }
              else {
                jacobianRet(i)(0) = -1 * (variables(1) - variables(2)) * ((-1/x(i)) * (math.sin(x(i)/variables(0)) + (-1 / variables(0)) * math.cos(x(i)/variables(0))))
                jacobianRet(i)(1) = 1 - variables(0) * math.sin(x(i) / variables(0))
                jacobianRet(i)(2) = 1 - jacobianRet(i)(1)
              }
            }
            jacobianRet
          }

          def value(variables: Array[Double]): Array[Double] = {
            val values: Array[Double] = Array.ofDim[Double](x.length)
            cfor(0)(_ < values.length, _ + 1) { i =>
              values(i) = explicitWave(variables(0), variables(1), variables(2))(x(i))
            }
            values
          }

          def jacobian: MultivariateMatrixFunction = {
            new MultivariateMatrixFunction {
              override def value(doubles: Array[Double]): Array[Array[Double]] = jacobianConstruct(doubles)
            }
          }
        }
        val problem: WaveProblem = new WaveProblem
        for((x,y) <- empiricalSemivariogram) { problem.addPoint(x,y) }
        val optimizer: LevenbergMarquardtOptimizer = new LevenbergMarquardtOptimizer()
        val weights: Array[Double] = Array.fill[Double](empiricalSemivariogram.length)(1)
        val initialSolution: Array[Double] = Array[Double](1, 1, 1)
        val optimum: PointVectorValuePair = optimizer.optimize(100, problem, problem.calculateTarget(), weights, initialSolution)
        val optimalValues: Array[Double] = optimum.getPoint
        println("w: " + optimalValues(0))
        println("s: " + optimalValues(1))
        println("a: " + optimalValues(2))
        explicitWave(optimalValues(0), optimalValues(1), optimalValues(2))
      }
    }
  }
