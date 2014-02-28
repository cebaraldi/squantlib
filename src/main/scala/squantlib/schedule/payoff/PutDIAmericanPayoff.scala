package squantlib.schedule.payoff

import scala.collection.JavaConversions._
import org.codehaus.jackson.map.ObjectMapper
import squantlib.util.DisplayUtils._
import squantlib.util.JsonUtils._
import java.util.{Map => JavaMap}
import squantlib.database.DB
import squantlib.util.Date
import squantlib.schedule.CalculationPeriod
import squantlib.util.FixingInformation
import scala.reflect.ClassTag

/**
 * Interprets JSON formula specification for sum of linear formulas with discrete range.
 * JSON format:
 *  {type:"putdiamerican", variable:[String], trigger:[Double], strike:[Double], refstart:Date, refend:Date, description:String}, 
 * No strike is considered as no low boundary
 */
case class PutDIAmericanPayoff(
    putVariables:List[String], 
    trigger:List[Double], 
    strike:List[Double], 
    refstart:Date, 
    refend:Date, 
    knockedIn:Boolean, 
    amount:Double = 1.0, 
    description:String = null,
    inputString:String = null)(implicit val fixingInfo:FixingInformation) extends Payoff {
  
  override val variables = putVariables.toSet
  
  val strikeMap:Map[String, Double] = (putVariables zip strike) (collection.breakOut)
   
  val triggerMap:Map[String, Double] = (putVariables zip trigger) (collection.breakOut)
  
  override val isPriceable:Boolean = 
    !trigger.exists(v => v.isNaN || v.isInfinity) && 
    !strike.exists(v => v.isNaN || v.isInfinity) && 
    refstart != null && 
    refend != null &&
    (refstart le refend)
  
  var mcPeriod6m = 30
  var mcPeriod1y = 90
  var mcPeriodbefore = 180
  
  override def eventDates(period:CalculationPeriod):List[Date] = {
    if (!isPriceable) {return List(period.endDate)}
    val basemod = refend.serialNumber % mcPeriod6m
    val start = refstart.serialNumber
    val end = refend.serialNumber
    val dates:List[Date] = (for (i <- (start to end) 
        if (i >= end - 180 && i % mcPeriod6m == basemod)
        || (i >= end - 360 && i % mcPeriod1y == basemod)
        || (i % mcPeriodbefore == basemod)) yield Date(i)) (collection.breakOut)
    if (dates.head == refstart) dates else refstart :: dates
  }
  
  trait FixingInterpreter[T] {
    def isKnockIn(fixings:T):Boolean // Method to be implemented
    def price(fixings:T, isKnockedIn:Boolean):Double // Method to be implemented
    
    def isKnockIn(fixings:List[T]):Boolean = fixings.exists(isKnockIn(_))
    def price(fixings:T):Double = price(fixings, isKnockIn(fixings))
    def price(fixings:List[T]):Double = price(fixings.last, isKnockIn(fixings))
  }
  
  implicit object MapInterpreter extends FixingInterpreter[Map[String, Double]] {
    override def isKnockIn(fixings:Map[String, Double]):Boolean = 
      variables.exists(p => fixings.get(p) match { case Some(v) => v <= triggerMap(p) case None => false})
    
    override def price(fixings:Map[String, Double], isKnockedIn:Boolean):Double = {
      if ((variables subsetOf fixings.keySet) && variables.forall(v => !fixings(v).isNaN && !fixings(v).isInfinity) && isPriceable) {
        if (isKnockedIn) amount * math.min(1.00, variables.map(v => fixings(v) / strikeMap(v)).min)
        else amount
      } else Double.NaN
  }}
  
  implicit object DoubleInterpreter extends FixingInterpreter[Double] {
    override def isKnockIn(fixing:Double):Boolean = fixing <= trigger.head
    override def price(fixing:Double, isKnockedIn:Boolean):Double = 
      if (fixing.isNaN || fixing.isInfinity || variables.size != 1 || !isPriceable) Double.NaN
      else if (isKnockedIn) amount * math.min(1.0, fixing / strike.head)
      else amount
    }
  
  def priceSingle[A:FixingInterpreter](fixings:A):Double = implicitly[FixingInterpreter[A]] price fixings
  
  def priceList[A:FixingInterpreter](fixings:List[A]):Double = implicitly[FixingInterpreter[A]] price fixings
  
  override def priceImpl(fixings:List[Map[String, Double]]):Double = priceList(fixings)

  override def priceImpl(fixings:Map[String, Double]):Double = priceSingle(fixings)
  
  override def priceImpl[T:ClassTag](fixings:List[Double]):Double = priceList(fixings)
  
  override def priceImpl(fixing:Double):Double = priceSingle(fixing)
  
  override def priceImpl = Double.NaN
  
  override def toString =
    amount.asPercent + " [" + trigger.map(_.asDouble).mkString(",") + "](Amer) " + amount.asPercent + " x Min([" + variables.mkString(",") + "] / [" + strike.map(_.asDouble).mkString(",") + "])" 
  
  override def jsonString = {
    
    val infoMap:JavaMap[String, Any] = Map(
        "type" -> "putdiamerican", 
        "variable" -> putVariables.toArray, 
        "trigger" -> trigger.toArray, 
        "strike" -> strike.toArray,
        "refstart" -> (if (refstart == null) null else refstart.toString),
        "refend" -> (if (refend == null) null else refend.toString),
        "description" -> description)
    
    (new ObjectMapper).writeValueAsString(infoMap)    
  }  
  
}

object PutDIAmericanPayoff {
  
  def apply(formula:String)(implicit fixingInfo:FixingInformation):PutDIAmericanPayoff = {
    
    val fixed = fixingInfo.update(formula)
    val variable:List[String] = formula.parseJsonStringList("variable").map(_.orNull)
    val trigger:List[Double] = fixed.parseJsonDoubleList("trigger").map(_.getOrElse(Double.NaN))
    val strike:List[Double] = fixed.parseJsonDoubleList("strike").map(_.getOrElse(Double.NaN))
    val amount:Double = fixed.parseJsonDouble("amount").getOrElse(1.0)
    val refstart:Date = formula.parseJsonDate("refstart").orNull
    val refend:Date = formula.parseJsonDate("refend").orNull
    val description:String = formula.parseJsonString("description").orNull
    
    val knockedIn:Boolean = 
      if (refstart == null || refend == null) false
      else (variable zip trigger).exists{case (v, trig) => DB.getHistorical(v, refstart, refend) match {
        case h if h.isEmpty => false
        case h => h.exists{case (_, x) => x <= trig}
      }}
    
    PutDIAmericanPayoff(variable, trigger, strike, refstart, refend, knockedIn, amount, description, formula)
  }
  
}

